# UI interaction update plan

Scope: implement the confirmed control/settings split, weekly calendar and locally saved course plans, and locally saved profile/test records. Keep workout execution demonstrative and keep the existing real metronome working. Preserve the supplied launcher icon changes.

## Files

- `MainActivity.kt`: navigation, metronome control and settings, entry points.
- `PlanningStore.kt`: locally persisted course templates, weekday assignments, profile, weight and fitness tests; JSON export and clear.
- `PlanningScreens.kt`: calendar, course editor, profile and test screens.
- `DemoScreens.kt`: use a selected saved strength template to launch the existing demonstration.
- `PlanningStoreTest.kt`: round-trip and recurrence behavior.

## Tasks

1. Add data models and persistence. Test weekly recurrence, course reuse, and profile/test round trips. Keep demo results separate from real saved plans.
2. Split metronome control and settings. Verify live BPM/volume changes and unchanged session lifecycle.
3. Replace the course tab with a Monday–Sunday calendar, saved template editor, and selected-day cards. Running cards open the real control screen with a one-time suggested BPM; strength cards open the labeled demo.
4. Add profile, weight history, test history, edit/delete, JSON export and confirmed clear. Verify invalid input is rejected.
5. Build debug APK, run unit tests, inspect navigation and the final diff.
