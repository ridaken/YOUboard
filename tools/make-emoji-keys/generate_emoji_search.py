#!/usr/bin/env python3
"""Generate the bundled English emoji search index from pinned Unicode data."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ElementTree
from collections import defaultdict
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve()
TOOL_ROOT = SCRIPT_PATH.parent
REPO_ROOT = SCRIPT_PATH.parents[2]
EMOJI_ASSET_ROOT = REPO_ROOT / "app" / "src" / "main" / "assets" / "emoji"
SEARCH_ASSET_ROOT = REPO_ROOT / "app" / "src" / "main" / "assets" / "emoji_search"
CLDR_ROOT = TOOL_ROOT / "src" / "main" / "resources" / "emoji" / "cldr" / "48"
UCD_EMOJI_TEST = (
    TOOL_ROOT
    / "src"
    / "main"
    / "resources"
    / "emoji"
    / "ucd"
    / "17.0"
    / "emoji-test.txt"
)
OUTPUT_PATH = SEARCH_ASSET_ROOT / "search-en.json"
LICENSE_PATH = SEARCH_ASSET_ROOT / "LICENSE-CLDR.txt"

CATEGORIES = (
    "SMILEYS_AND_EMOTION",
    "PEOPLE_AND_BODY",
    "ANIMALS_AND_NATURE",
    "FOOD_AND_DRINK",
    "TRAVEL_AND_PLACES",
    "ACTIVITIES",
    "OBJECTS",
    "SYMBOLS",
    "FLAGS",
)

# Hashes use UTF-8 source bytes with CRLF normalized to LF so verification works
# with Git configurations that check text files out using platform line endings.
PINNED_SOURCE_HASHES = {
    CLDR_ROOT / "LICENSE": "780ed0d1e595f6bb3e7cad757ea147315596b96e75d6a271ae95e882185c8aa7",
    CLDR_ROOT / "common" / "annotations" / "en.xml": "8511aadd046fdba2f0ffe590266ced8bbf48175ad139b2675d85d7141057b235",
    CLDR_ROOT / "common" / "annotationsDerived" / "en.xml": "a00ea336effd538248af6c3c705a8562133e5bdef354a94d18b206324e1f790b",
    UCD_EMOJI_TEST: "1d8a944f88d7952f7ef7c5167fef3c67995bcae24543949710231b03a201acda",
}

VARIATION_SELECTORS = str.maketrans("", "", "\ufe0e\ufe0f")
SMILE_FORMS = ("smile", "smiles", "smiling")
SMILE_PATTERN = re.compile(r"(?<![A-Za-z])(?:smile|smiles|smiling)(?![A-Za-z])", re.I)


def normalized_emoji(emoji: str) -> str:
    return emoji.translate(VARIATION_SELECTORS)


def canonical_bytes(path: Path) -> bytes:
    return path.read_bytes().replace(b"\r\n", b"\n")


def verify_sources() -> None:
    for path, expected_hash in PINNED_SOURCE_HASHES.items():
        if not path.is_file():
            raise ValueError(f"Missing pinned source: {path}")
        actual_hash = hashlib.sha256(canonical_bytes(path)).hexdigest()
        if actual_hash != expected_hash:
            raise ValueError(
                f"Pinned source hash mismatch for {path}: "
                f"expected {expected_hash}, got {actual_hash}"
            )


def add_unique(values: list[str], seen: set[str], value: str) -> None:
    if value and value not in seen:
        values.append(value)
        seen.add(value)


def parse_cldr() -> tuple[dict[str, str], dict[str, list[str]]]:
    names: dict[str, str] = {}
    keywords: dict[str, list[str]] = defaultdict(list)
    keyword_sets: dict[str, set[str]] = defaultdict(set)
    sources = (
        CLDR_ROOT / "common" / "annotations" / "en.xml",
        CLDR_ROOT / "common" / "annotationsDerived" / "en.xml",
    )

    for source in sources:
        root = ElementTree.parse(source).getroot()
        for annotation in root.findall("./annotations/annotation"):
            key = normalized_emoji(annotation.attrib["cp"])
            value = "".join(annotation.itertext()).strip()
            if annotation.get("type") == "tts":
                previous = names.get(key)
                if previous is not None and previous != value:
                    raise ValueError(
                        f"Conflicting CLDR tts names for {annotation.attrib['cp']!r}: "
                        f"{previous!r} and {value!r}"
                    )
                names[key] = value
            else:
                for keyword in re.split(r"\s*\|\s*", value):
                    add_unique(keywords[key], keyword_sets[key], keyword)

    return names, dict(keywords)


def parse_ucd_names() -> dict[str, str]:
    names: dict[str, str] = {}
    for line_number, line in enumerate(
        UCD_EMOJI_TEST.read_text(encoding="utf-8-sig").splitlines(), start=1
    ):
        if not line or line.startswith("#"):
            continue
        try:
            code_points, remainder = line.split(";", 1)
            comment = remainder.split("#", 1)[1].strip()
            _, _, name = comment.split(maxsplit=2)
            emoji = "".join(chr(int(code_point, 16)) for code_point in code_points.split())
        except (IndexError, ValueError) as error:
            raise ValueError(f"Malformed UCD line {line_number}: {line!r}") from error

        key = normalized_emoji(emoji)
        previous = names.get(key)
        if previous is not None and previous != name:
            raise ValueError(
                f"Conflicting UCD names on line {line_number}: {previous!r} and {name!r}"
            )
        names[key] = name
    return names


def inventory() -> list[tuple[str, str, str]]:
    entries: list[tuple[str, str, str]] = []
    for category in CATEGORIES:
        category_path = EMOJI_ASSET_ROOT / f"{category}.txt"
        for line in category_path.read_text(encoding="utf-8-sig").splitlines():
            emojis = line.split()
            if not emojis:
                continue
            base = emojis[0]
            entries.extend((emoji, base, category) for emoji in emojis)
    return entries


def expanded_keywords(name: str, source_keywords: list[str]) -> list[str]:
    result = list(source_keywords)
    seen = set(result)
    if any(SMILE_PATTERN.search(value) for value in (name, *source_keywords)):
        for form in SMILE_FORMS:
            add_unique(result, seen, form)
    return result


def generate_records() -> list[dict[str, object]]:
    cldr_names, cldr_keywords = parse_cldr()
    ucd_names = parse_ucd_names()
    inventory_entries = inventory()
    records: list[dict[str, object]] = []
    missing_names: list[str] = []

    for emoji, base, category in inventory_entries:
        key = normalized_emoji(emoji)
        name = cldr_names.get(key) or ucd_names.get(key)
        if name is None:
            missing_names.append(emoji)
            continue
        records.append(
            {
                "emoji": emoji,
                "name": name,
                "keywords": expanded_keywords(name, cldr_keywords.get(key, [])),
                "base": base,
                "category": category,
            }
        )

    if missing_names:
        rendered = ", ".join(repr(emoji) for emoji in missing_names)
        raise ValueError(f"Missing CLDR and UCD names for {len(missing_names)} emoji: {rendered}")
    if len(records) != len(inventory_entries):
        raise AssertionError("Generated record count does not match inventory")

    actual_inventory = [record["emoji"] for record in records]
    expected_inventory = [emoji for emoji, _, _ in inventory_entries]
    if actual_inventory != expected_inventory:
        raise AssertionError("Generated record order does not match inventory")
    return records


def encoded_output(records: list[dict[str, object]]) -> bytes:
    compact_json = json.dumps(records, ensure_ascii=False, separators=(",", ":"))
    return (compact_json + "\n").encode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify that the bundled JSON and license are current without modifying them",
    )
    args = parser.parse_args()

    verify_sources()
    records = generate_records()
    output = encoded_output(records)
    license_bytes = canonical_bytes(CLDR_ROOT / "LICENSE")
    outputs = {OUTPUT_PATH: output, LICENSE_PATH: license_bytes}

    if args.check:
        stale_paths = [
            path for path, expected in outputs.items()
            if not path.is_file() or canonical_bytes(path) != expected
        ]
        if stale_paths:
            for path in stale_paths:
                print(
                    f"{path} is missing or stale; run {SCRIPT_PATH.name} to regenerate it",
                    file=sys.stderr,
                )
            return 1
        print(f"Checked {OUTPUT_PATH}: {len(records)} records, {len(output)} bytes")
        return 0

    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    print(f"Wrote {OUTPUT_PATH}: {len(records)} records, {len(output)} bytes")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1) from error
