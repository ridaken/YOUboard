# make-emoji-keys

This module takes care of generating emoji data bundled with YOUBoard.
Basically data is generated in three steps:
1. Unicode emoji table provides code points and grouping plus ordering.
2. Local file lists every new emojis supported for every android version since 4.4.
3. Emoji sequences are merged with their base version and formatted into android resource file.

### Generate emoji-categories.xml

A gradle task called 'makeEmoji' builds and runs this tool to generate android resource file which. Path to YOUBoard's res directory is automatically set so the file is ready to be bundled at build.

### Generate the English emoji search data

Run the generator from the repository root. It uses only Python's standard library and the Unicode sources vendored in this module; routine generation does not access the network.

```shell
python tools/make-emoji-keys/generate_emoji_search.py
python tools/make-emoji-keys/generate_emoji_search.py --check
```

The output is the compact UTF-8 file `app/src/main/assets/emoji_search/search-en.json`. It is an ordered JSON array whose records have this schema:

```json
{"emoji":"👋🏽","name":"waving hand: medium skin tone","keywords":["bye","cya","g2g","greetings","gtg","hand","hello","hey","hi","later","medium skin tone","outtie","ttfn","ttyl","wave","yo","you"],"base":"👋","category":"PEOPLE_AND_BODY"}
```

The generator reads every whitespace-separated emoji from these category files, in this order: `SMILEYS_AND_EMOTION`, `PEOPLE_AND_BODY`, `ANIMALS_AND_NATURE`, `FOOD_AND_DRINK`, `TRAVEL_AND_PLACES`, `ACTIVITIES`, `OBJECTS`, `SYMBOLS`, and `FLAGS`. `EMOTICONS` is intentionally excluded. `base` is the first emoji on the same source line, while `emoji` preserves the inventory sequence exactly, including variation selectors and skin-tone variants.

Names come from CLDR `tts` annotations. Keywords are merged from CLDR's common and derived English annotations, which includes derived flag and profession data. CLDR lookup ignores U+FE0E and U+FE0F, but output sequences remain unchanged. Unicode Emoji 17.0 `emoji-test.txt` names are the fallback; generation fails if neither source has a name. Records with a smile word also receive the equivalent `smile`, `smiles`, and `smiling` keywords.

`--check` regenerates data in memory, verifies the bundled JSON and license, and exits nonzero if either is missing or stale. It does not modify files. The normal command also copies the pinned CLDR license to `app/src/main/assets/emoji_search/LICENSE-CLDR.txt`.

#### Pinned Unicode sources

CLDR 48 came from the immutable [Unicode CLDR 48 core archive](https://unicode.org/Public/cldr/48/core.zip). Its downloaded SHA-256 is `06c7c698d6fd8d67cefac15a0206b0109b00e0ef1636f86f84449fa959561f74`. Only the needed archive members are vendored. Hashes below are SHA-256 after CRLF is normalized to LF, matching the generator's cross-platform verification:

| Source | SHA-256 |
| --- | --- |
| `src/main/resources/emoji/cldr/48/common/annotations/en.xml` | `8511aadd046fdba2f0ffe590266ced8bbf48175ad139b2675d85d7141057b235` |
| `src/main/resources/emoji/cldr/48/common/annotationsDerived/en.xml` | `a00ea336effd538248af6c3c705a8562133e5bdef354a94d18b206324e1f790b` |
| `src/main/resources/emoji/cldr/48/LICENSE` | `780ed0d1e595f6bb3e7cad757ea147315596b96e75d6a271ae95e882185c8aa7` |
| `src/main/resources/emoji/ucd/17.0/emoji-test.txt` | `1d8a944f88d7952f7ef7c5167fef3c67995bcae24543949710231b03a201acda` |

Network access is needed only when updating the pinned data. Download a new Unicode release archive, verify its hash before extracting the required files, update `PINNED_SOURCE_HASHES` in the generator, regenerate, and run `--check`.

### Update to latest emoji version

* Get new emoji data from Unicode official repository located here: https://unicode.org/Public/emoji.
* Create a new directory in [/src/main/resources/emoji/ucd](/tools/make-emoji-keys/src/main/resources/emoji/ucd) and name it as a decimal number corresponding to Unicode's version.
* Update [android-emoji-support.txt](/tools/make-emoji-keys/src/main/resources/emoji/android-emoji-support.txt) with new emojis supported in latest Android versions.
* Build the jar necessary for emoji update (`./gradlew jar`)
* Either run `./gradlew tools:make-emoji-keys:makeEmoji` or start the `makeEmoji` task via the IDE
