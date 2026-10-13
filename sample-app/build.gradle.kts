plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zeotap.sdk.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zeotap.sdk.sample"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = providers.gradleProperty("VERSION_NAME").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // The instrumentation / Maestro flows point the app at an ingest
            // on 10.0.2.2 (host) or 127.0.0.1 (in-process MockWebServer).
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            manifestPlaceholders["usesCleartextTraffic"] = "false"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":sdk"))
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.mockwebserver)
}
