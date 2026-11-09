# FocusLock

FocusLock is a native Android focus and productivity application. The Android Studio project and application source are in [`android/`](android/).

## Repository layout

- `android/` — Android source, Gradle wrapper, tests, and engineering documentation.
- `docs/` — privacy, release, and Google Play preparation documents.
- `assets/branding/` — public project branding assets.

## Build

Requirements: a compatible JDK, Android SDK Platform 36, and Android Build Tools. On Windows PowerShell:

```powershell
cd android
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

## Security and release

Do not commit keystores, signing passwords, API tokens, local SDK configuration, private device identifiers, raw test evidence, or signed release binaries. Release signing must use secure local configuration or CI secrets.

The privacy policy and Google Play declarations in `docs/` are drafts, not legal advice or Play approval. Complete the release checklist and review the exact artifact before public distribution.

## Engineering notes

Review `android/README.md` and the current release-candidate audit before changing permissions, Accessibility behavior, Device Owner behavior, data deletion, or recovery logic.

## License

No open-source license has been selected for this repository yet. Public visibility on GitHub does not itself grant permission to reuse, modify, or redistribute the code. A license should be chosen by the project owner before inviting external contributions.

