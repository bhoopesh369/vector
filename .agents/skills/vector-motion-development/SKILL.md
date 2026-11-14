---
name: vector-motion-development
description: Use when working in the Vector Motion repository on Waveshare watch firmware, the Android recorder, motion measurements, device checks, or project commits and pushes.
---

# Vector Motion development

Preserve acquired motion and existing recordings. Distinguish current source behavior, historical device evidence and what has been verified in this task.

## Start here

Read root `AGENTS.md`, inspect branch and staged/unstaged changes, then load only the relevant reference:

- [Recording architecture and sensor limits](references/recording.md): firmware, BLE, CSV, analytics and range/rate questions.
- [Laptop, verification and Git](references/workflow.md): builds, flashing, Android checks, authentication and push.

Repository paths are relative to the checkout root, `/Users/titan/vector` on this laptop. Recheck mutable device identifiers and installation state.

## Project choices

- Work and push on **main** by default. A feature branch requires an explicit user request. If work already exists elsewhere, inspect ancestry and preserve local changes; fast-forward main when possible. Do not force-push or reset away work.
- Author **and committer**: `Bhoopesh <bhoopesh459@gmail.com>`, using per-command or repo-local Git settings. No GitHub CLI or global Git configuration changes.
- New tools, libraries and caches stay inside the repo: `.tools`, `.vendor`, `.cache`, `.venv`, `build`. Existing system SDK/JDK tools may be used without changing them. Do not modify laptop-wide packages, SDKs, shell profiles or credential helpers.
- Preserve unrelated staged work. At handoff `.idea/git_toolbox_prj.xml` was independently staged; inspect status rather than assuming it remains. Exclude recordings, backups, tokens, signing keys and generated build/tool files from commits.
- Preserve watch archives and phone data. Update APKs with `adb install -r` using the existing signing key; uninstalling the product app deletes recordings. Do not format or restore watch storage as routine troubleshooting.

## Measurement contract

Current v0.6 phone recording uses the full acquired BLE stream when supported; only display charts are reduced. Legacy preview and standalone watch archive paths also exist. Read the recording reference before changing their handling. Older prototype sections in the docs do not describe every current path.

Hz sampling rate and dps angular-speed range are separate. Nominal ODR, saved average rate, reconstructed timestamps, transport loss and hardware acquisition gaps are different measurements. Zero sequence gaps does not establish continuous acquisition or uniform 1 ms spacing.

Calculate analytics from full-resolution saved data, independent of chart reduction. Acceleration magnitude includes gravity. Separate motion measurements from quality indicators. Do not infer unlabelled strokes or claim calories, heart rate, racket/shuttle speed, recovery or validated performance ratings. Running distance from wrist IMU integration is not implemented or established; phone GPS would be a separate feature.

## Finish

Run checks appropriate to changed components using the workflow reference. Inspect instrumentation text for `PASS`, not just exit status. Report untested physical/battery behavior. Commit intended paths with a conventional message and push main when requested. Verify the remote SHA; a local commit is not evidence of a successful push.
