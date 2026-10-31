# 天天向上 Android Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a runnable Android app with a real background metronome and clearly separated, clickable demo flows for the remaining features.

**Architecture:** A single Compose activity owns navigation and demo state. A foreground media playback service owns real metronome timing/audio; a local repository owns real session records. Demo flows never call the real repository.

**Tech Stack:** Kotlin, Android SDK, Jetpack Compose, Gradle, JUnit.

**Spec:** `docs/spec-v1.md`; design: `docs/superpowers/specs/2026-10-31-android-demo-design.md`.

## Global Constraints

- True functionality in v1 is the metronome, including background audio, notification controls, and local history.
- Cadence 160–220 inclusive; buttons change by 5, direct integer input can use any value in range; one click equals one step.
- The real metronome has no voice announcements. Later voice guidance is event triggered.
- All other flows carry a visible “演示模式” label, use separate data, and never claim actual workout results.
- Calls do not stop metronome elapsed time; playback resumes after the call. A process restart does not restart playback.

## Review Focus

- Invalid BPM input must not alter a running session.
- Paused time must not inflate a record's duration.
- Switching away from the app must not stop the foreground service.
- A demo workout must not create a real session record.
- Phone audio and other media may behave differently by device; surface actual audio state and verify on devices.

---

### Task 1: Project and metronome domain

**Files:** Gradle settings/build files, manifest, `app/src/main/java/com/daydayup/metronome/MetronomeSession.kt`, its unit test.

**Interfaces:** `MetronomeSession` accepts start/pause/resume/changeBpm/stop timestamps from a monotonic clock; exposes BPM, phase, elapsed duration, and rate changes. `Cadence.isValid(Int)` validates 160–220.

- [ ] Write JUnit tests for 160/220 boundaries, invalid input, pause exclusion, and changeBpm event timing.
- [ ] Run the test command and observe failure from missing implementation.
- [ ] Add the Android project configuration and minimal domain implementation.
- [ ] Run the domain tests and confirm pass.

### Task 2: Background playback and real records

**Files:** `MetronomeService.kt`, `ClickPlayer.kt`, `SessionStore.kt`, notification resources, service manifest registration, tests.

**Interfaces:** service commands `START`, `PAUSE`, `RESUME`, `SET_BPM`, `SET_VOLUME`, `SET_SOUND`, `STOP`; published state includes running phase, BPM, elapsed time, sound status. Store saves completed and interrupted records separately from demo state.

- [ ] Write tests for repository round trip, interrupted-session recovery, and service command state changes.
- [ ] Run tests and observe feature failures.
- [ ] Implement PCM click playback on monotonic schedule, foreground notification controls, persistence, and phone audio handling.
- [ ] Run tests and assemble debug APK.

### Task 3: Real metronome interface

**Files:** `MainActivity.kt`, Compose theme, home/metronome/history/settings screens, UI tests.

**Interfaces:** UI dispatches service commands, observes service state, reads real records from `SessionStore`.

- [ ] Write UI/domain tests for BPM entry, start/pause/stop, history presentation, deletion, and export intent.
- [ ] Run tests and observe failures.
- [ ] Implement accessible large controls, notification permission path, history, delete, export, and clear state labels.
- [ ] Run tests and assemble debug APK.

### Task 4: Isolated demo flows

**Files:** Compose course, workout, coach, report, device screens; `DemoState.kt`; tests.

**Interfaces:** all demo actions mutate `DemoState` only. A reset action restores demo examples. The real session repository is read only from demo views.

- [ ] Write tests for template edits, 5-second preparation/phase transition, group confirmation, coach recommendation approval, report labeling, and data isolation.
- [ ] Run tests and observe failures.
- [ ] Implement clickable end-to-end demo flow with persistent “演示模式” label and example-only report values.
- [ ] Run all tests, assemble debug APK, and inspect UI on an emulator or device if available.

## Final verification

Run `testDebugUnitTest`, `assembleDebug`, and available instrumented checks. Check each acceptance item in `docs/spec-v1.md` against code and, for audio integration, a real Android environment. State any device behavior that could not be verified.
