// Root build for the SignalSmith Android SDK (increment 242).
// Modules: :sdk (the library, com.zeotap.sdk:android-sdk) and :sample-app
// (the instrumentation / Maestro host app). See sdk/android/README.md.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.bcv) apply false
    alias(libs.plugins.vanniktech.publish) apply false
}
