# Android build environment

The project uses the Gradle Wrapper checked into this repository. The currently validated configuration is:

- Gradle Wrapper: 9.2.0
- Android Gradle Plugin: 9.0.0
- Kotlin Compose plugin: 2.3.10
- KSP: 2.3.10
- compileSdk / targetSdk: 36
- minSdk: 29

Install a compatible JDK and Android SDK locally. Configure the SDK location in the machine-local `local.properties` file; this file is excluded from version control.

## Build and test (Windows PowerShell)

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug lintRelease --no-daemon --console=plain
```

For a release bundle, use the repository's release build configuration. Signing keys and passwords must be provided through secure local configuration or a CI secret store and must never be committed.

Toolchain upgrades should be made only after compatibility validation against the Android Gradle Plugin, Kotlin, KSP, Compose, compileSdk, and the full test suite.
