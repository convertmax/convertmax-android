# Convertmax Android SDK (0.2.0)

Kotlin Android library starter for the native Convertmax mobile event SDK. Open this directory in Android Studio; the `:convertmax` library and `:samples:android` Compose app are in the same Gradle project.

The release line is `0.2.0`; publish it from the consuming organization’s Maven repository configuration.

## Install

Add the `:convertmax` library and initialize it once:

```kotlin
val sdk = Convertmax.create(this, Configuration(writeKey = "YOUR_WRITE_KEY", appId = "com.example.app"))
sdk.setConsent(Consent.GRANTED)
sdk.identify("account-123")
sdk.track("signup", mapOf("plan" to "pro"))
```

The SDK persists consent, identity, anonymous ID, session ID and queued events. Denied consent clears pending events and rotates identity. `revenue(...)` records a behavioral `purchase_observed` event; it does not verify Google Play billing. Verified subscriptions remain a separate server integration.

## Sample app

1. Open this repository root in Android Studio (File → Open).
2. Wait for Gradle sync, then run the `samples.android` run configuration on an emulator or device.
3. Grant consent, then Identify / Track / Screen / Reset. Events are stored in SQLite and flushed as gzip `mobile-v1` batches when the app backgrounds. The write key is public and ingestion-only.

```bash
./gradlew :samples:android:assembleDebug
./gradlew test
```
