# Convertmax Android SDK

Kotlin Android library starter for the native Convertmax mobile event SDK. Open this directory in Android Studio; the `:convertmax` library and `:samples:android` Compose app are in the same Gradle project.

## Sample app

1. Open this repository root in Android Studio (File → Open).
2. Wait for Gradle sync, then run the `samples.android` run configuration on an emulator or device.
3. Grant consent, then Identify / Track / Screen / Reset. Events are in-memory only until the durable queue ships; the write key is public and ingestion-only.

```bash
./gradlew :samples:android:assembleDebug
./gradlew test
```

This SDK does not manage Google Play billing or verified revenue.

Do not publish until the durable queue, per-message `202` response handling, gzip limits, lifecycle scheduling, privacy/data-safety documentation and contract parity tests are implemented.
