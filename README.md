# Zeotap Android SDK

`com.zeotap.sdk:android-sdk` collects events from Android apps and sends them to Zeotap. It uses the
same event model, consent model, on-device identity hashing and wire format as the Zeotap browser
SDK. Shared conformance vectors, generated from the browser SDK and run by this repository's tests,
prove those parts match. It also adds what a mobile app needs:
- a durable offline queue
- flush on background
- device and app context
- app-lifecycle events

Kotlin, **minSdk 23**, no UI. Three runtime dependencies: Kotlin stdlib, kotlinx-coroutines and
kotlinx-serialization-json. MIT licensed.

Full documentation: <https://agentic-docs.zeotap.com/events/android-sdk>

## Install

The SDK is published on Maven Central.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.zeotap.sdk:android-sdk:<version>")
}
```

Releases are listed under this repository's tags (`v<version>`).

To use `collectAdvertisingId`, add `com.google.android.gms:play-services-ads-identifier` to your app.
The SDK never bundles that library and still works when it is missing.

## Quick start

Call `Zeotap.load` once, as early as you can (usually in `Application.onCreate`):

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val config = Configuration.Builder("https://events.zeotap.com")
            .consent(ConsentOptions.Builder().require(true).build())  // opt-in gating + IABTCF_* auto-detect
            .trackApplicationLifecycleEvents(true)
            .build()
        Zeotap.load(this, "YOUR_WRITE_KEY", config)
    }
}

Zeotap.setUserIdentities(mapOf("email" to "shopper@example.com"))  // hashed on the device
Zeotap.track("Order Completed", mapOf("order_id" to "o-1", "total" to 99.5))
Zeotap.screen("Product", category = "Shop")
Zeotap.identify("user-42", mapOf("plan" to "pro"))
Zeotap.reset()                                                      // logout
```

`apiHost` is required, because an app has no "same origin". Set it to Zeotap's ingest host or to a
first-party proxy on your own domain.

Raw email, phone and login values are normalised and hashed on the device, and never leave it.
Consent follows the browser SDK's model:
- manual input, or the IAB TCF-for-Apps `IABTCF_*` preferences a CMP SDK writes
- opt-in or opt-out gating
- decisions persisted across launches

The [documentation](https://agentic-docs.zeotap.com/events/android-sdk) covers every option,
consent, context fields, delivery guarantees and troubleshooting.

## Repository layout

```
sdk/            the library: com.zeotap.sdk (public API) + com.zeotap.sdk.internal.* (engine)
  api/sdk.api   public-API dump, checked on every build (binary-compatibility-validator)
sample-app/     host app exposing every SDK method as a button; carries the instrumented test suite
conformance/    golden vectors generated from the browser SDK, run by the unit tests (read-only here)
ci/             Dockerfile for a JDK 17 + Android SDK build image
```

## Building and testing

You need JDK 17 and an Android SDK with platform 35, or Docker:

```bash
# On a machine with JDK 17 + Android SDK
./gradlew :sdk:check :sdk:koverVerify      # detekt, Android lint, API check, unit + conformance tests, coverage

# Or entirely in Docker (amd64; runs under emulation on Apple silicon)
docker build --platform linux/amd64 -t zeotap-android-build ci
docker run --rm --platform linux/amd64 -v "$PWD:/work" -w /work zeotap-android-build \
  ./gradlew --no-daemon :sdk:check :sdk:koverVerify
```

The instrumented suite in `sample-app/src/androidTest` needs a device or emulator:
`./gradlew :sample-app:connectedDebugAndroidTest`.

### Conformance

`conformance/vectors/*.vectors.json` are generated from the Zeotap browser SDK, which is the
reference implementation. `:sdk:testDebugUnitTest` runs every vector that applies to Android. A
vector that fails is a bug. The few places where Android deliberately differs from the browser are
listed, with reasons, in [`conformance/README.md`](conformance/README.md). Do not edit vectors by
hand.

## How this repository is maintained

Zeotap develops the SDK in an internal repository, together with the browser, iOS and React Native
SDKs. That keeps one change, reviewed and tested once, in step across all four. On every release
the Android tree is exported here as one commit tagged `v<version>`. Before that commit is pushed,
a clean checkout of it has passed the full test suite above.

So pull requests can't be merged here directly. To report a bug or request a feature, open an
issue. Fixes are made upstream and arrive with the next release.

## License

[MIT](LICENSE) © Zeotap GmbH
