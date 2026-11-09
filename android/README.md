# FocusLock Android project

This directory contains the native Android application source and build configuration for FocusLock.

## Stack
- Kotlin and Android SDK
- Jetpack Compose and Material 3
- Room for local structured data
- DataStore for preferences
- Kotlin Coroutines and Flow
- Gradle Wrapper for reproducible project builds

## Android compatibility
- `minSdk`: 29
- `compileSdk`: 36
- `targetSdk`: 36

The validated local toolchain uses Gradle 9.2.0 and Android Gradle Plugin 9.0.0. Use the checked-in Gradle Wrapper rather than relying on a globally installed Gradle version.

## Build and test
From this directory, with a compatible JDK and Android SDK installed:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

## Architecture
Application code is organized by responsibility under `app/src/main/java/com/focuslock/app/`:
- `data/` — Room database, repositories, and settings persistence.
- `domain/` — session and policy logic.
- `presentation/` — Compose UI and view-model state.
- `services/` — Android services and receivers.
- `system/` — permissions, device-policy, recovery, and protection integrations.

Unit tests and Android instrumentation tests are under `app/src/test/` and `app/src/androidTest/`.

## Privacy and platform behavior
FocusLock is designed for local-first operation. Optional notification capture, call screening, Accessibility-based selected-app blocking, and managed-device protection have separate Android permission/role requirements and documented limitations. Review the current privacy draft and release-candidate audit in the repository-level `docs/` folder before distribution.

## Release status
A signed release candidate has been built and locally smoke-tested. This does not mean Google Play has approved the app. Refer to `../docs/RELEASE_CANDIDATE_AUDIT.md` and `../docs/RELEASE_CHECKLIST.md` for the current status and outstanding gates.

Signing credentials, local SDK paths, device identifiers, private test evidence, and release binaries must not be committed to this repository.
