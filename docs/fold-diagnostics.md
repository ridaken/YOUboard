# Fold layout diagnostics

Automatic split uses a committed posture rather than individual hardware callbacks.
Hinge readings take priority over supported window folds, then the legacy OEM setting.
An empty legacy setting cannot close a wide display. Missing observations retain the
last committed posture on the same display; a new display starts unknown.

Posture changes settle for 150 ms. The hinge closes at 35 degrees or less and opens
at 45 degrees or more, retaining its previous classification in between. Automatic
split enters after 150 ms with both widths at least 620 dp and exits immediately
below 600 dp. Floating and one-handed modes still pause automatic split. Explicit
Split/Standard choices and folded profiles keep their existing meaning.

Keyboard geometry has a shared cache/application signature. Hardware evidence that
does not change that signature must not replace the keyboard or cancel a touch.
Actual geometry changes invalidate obsolete coordinates once and preserve composing
text, selection, and the current keyboard panel/state.

## Capturing an incident

After an incident, open **Settings → About → Save log** and note its approximate
time, whether the phone was open, its orientation, and whether multi-window,
floating, or one-handed mode was active. History survives restarting YOUBoard and
rebooting. **Clear diagnostic history** removes the stored history and in-memory
diagnostic tail; new events continue to be recorded.

Diagnostic history is local, enabled without Debug mode, and expires after seven
days or sooner if it reaches 16 MiB. Segments rotate daily or at 512 KiB. It lives
in device-protected, backup-excluded private storage. The history does not contain
typed text, key codes, clipboard text, suggestions, editor labels, or application
identifiers. The separate general debug log included in a manually requested
export retains its existing behavior and may contain text when Debug mode is used.
Nothing uploads automatically.

Each JSON Lines record has schema version 1, UTC/monotonic timestamps, a process
session identifier, a sequence number, an observer generation, and a typed reason.
Use `SOURCE_OBSERVATION` and `POSTURE_PENDING` to follow evidence and candidates,
`POSTURE_COMMITTED`/`ELIGIBILITY_CHANGED` for accepted changes, and
`GEOMETRY_APPLIED`/`TOUCH_CANCELLED` for actual keyboard changes. Geometry events
include the configured mode and before/after signatures. Sensor activity is
aggregated into one-second summaries. `QUEUE_OVERFLOW` reports dropped events;
storage failures retain a diagnostic tail and an export notice.
`STORAGE_RECOVERED` records the failure type and lost-record count when writing
becomes available again. Exception messages are never saved in diagnostic history.

Critical changes flush/sync on a background worker. Abrupt termination can lose
events that have not reached that worker. Export is ordered with pending writes.
No diagnostic filesystem work or waiting is performed by the typing thread.

## Validation

Use Java 21 for Android 36 Robolectric tests (Android Studio's bundled runtime is
suitable). Run `./gradlew testRunTestsUnitTest`, `./gradlew lintRelease`, and
`./gradlew assembleRelease`. CI uses Java 21 and also runs for test-only changes.

For physical acceptance on the reported Galaxy, perform at least 20 open/close
cycles and 20 minutes of typing across both orientations and multi-window sizes.
Check symbol/shift state, composing text, cursor selection, emoji/clipboard panels,
floating/one-handed modes, and manual profiles. Verify no unsolicited oscillation,
then restart and export history to confirm every actual transition is explained.
