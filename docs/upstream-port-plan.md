# Selective HeliBoard ports

## Delivery

PR #12 (placeholder-only popup crash) was merged with a merge commit. The remaining twelve ports are delivered in one PR from `codex/remaining-heliboard-ports`, with a separate commit per port. Merge that PR using a merge commit, never squash or rebase. Run the existing release workflow after merge.

## Compatibility contract

Preserve YOUboard identity, JNI bindings, offline operation, adaptive touch, correction feedback, Always replace semantics, dictionary-free English emoji search, fold hysteresis, geometry signatures, lifecycle recovery and private diagnostics. New conservative history learning is opt-in, default off. Word swipe deletion remains Letters by default and preserves existing Off choices. No dependency/SDK upgrades or unrelated upstream refactors.

## Remaining ports, in order

1. Floating handle reachability (27d9d3cb).
2. Clipboard preview and dismiss target (0535564d, cfef63b9).
3. Alphabet shift bookkeeping (19ce4168).
4. Capitalized swipe suggestion deduplication (a37fb339).
5. Dictionary catalog compatibility (d18a13f9).
6. Settings default callbacks (623f9885).
7. Emoji recents controls (b28307db, ea327f73, 438f4967); retain existing capacity and privacy.
8. Secondary-locale emoji dictionaries (b6b62e83); retain bundled English search.
9. Local combined/gzip wordlist import (25ccf871, 6e631a64, 45c49702); transactional background conversion.
10. Dictionary source refresh (4034b46b); dictionary-only observation, no fold observer moves.
11. Conservative history learning (3afa6bb1, c768bf5e, 3e6f48f3, 45230ec4); disabled by default, delayed additions cancelled on correction/removal/clear/closure.
12. Word swipe deletion and DELETE_WORD (c04a1ee9, 45933187); isolated word boundaries and version-independent preference migration.

## Verification

Use Java 21. Run testRunTestsUnitTest, lintRelease and assembleRelease. Preserve existing tests and add behavior regressions. Lint currently reports 68 MissingTranslation errors in unchanged resources; do not suppress them. Monitor all PR checks for the final revision. Device acceptance covers floating touch bounds, emoji activity restoration and the documented fold exercise (20 open/close cycles and 20 minutes of typing). Do not claim device checks without performing them.
