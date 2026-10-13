import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.bcv)
    alias(libs.plugins.vanniktech.publish)
}

val sdkVersion: String = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "com.zeotap.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        // Single source of truth for context.library.{name,version}. Never
        // hardcode these in source (the browser SDK did, and shipped "0.1.0"
        // on every release until increment 241).
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
        buildConfigField("String", "SDK_NAME", "\"@zeotap/android-sdk\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // Consumers shrink; an unminified AAR keeps stack traces readable
            // and needs no repackaging tricks (the package is unique).
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // The SDK renders no UI and declares no translations.
        disable += setOf("MissingTranslation", "GradleDependency", "NewerVersionAvailable")
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.annotation)
    // Advertising ID is optional at runtime: accessed reflectively so hosts
    // without Play Services never see a NoClassDefFoundError.
    compileOnly(libs.play.services.ads.identifier)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}

// ---------------------------------------------------------------------------
// Conformance vectors (sdk/conformance/vectors) — copied into the unit-test
// classpath so ConformanceTest can load them as resources. Copied, not
// symlinked, and into build/ so nothing under src/ is generated.
//
// In the monorepo the vectors sit beside this tree. The standalone public
// repository (scripts/sdk-export/export-android.sh) carries a copy at
// conformance/vectors and sets `zeotap.conformanceDir` to say so — a property
// rather than a "whichever exists" probe, so a tree missing its vectors fails
// in VectorLoader instead of quietly reading some other copy.
// ---------------------------------------------------------------------------
val conformanceVectorsDir = rootProject.layout.projectDirectory.dir(
    providers.gradleProperty("zeotap.conformanceDir").getOrElse("../conformance/vectors"),
)
val copyConformanceVectors by tasks.registering(Copy::class) {
    from(conformanceVectorsDir)
    include("*.json")
    into(layout.buildDirectory.dir("conformance/conformance"))
}
android.sourceSets.getByName("test").resources.srcDir(layout.buildDirectory.dir("conformance"))
tasks.matching { it.name.startsWith("process") && it.name.endsWith("UnitTestJavaRes") }
    .configureEach { dependsOn(copyConformanceVectors) }

// ---------------------------------------------------------------------------
// Quality gates
// ---------------------------------------------------------------------------
kover {
    reports {
        filters {
            excludes {
                classes("com.zeotap.sdk.BuildConfig")
                // Device/OS probes need a real device to exercise meaningfully.
                packages("com.zeotap.sdk.internal.context")
            }
        }
        verify {
            // Line coverage over everything except the excluded device probes.
            //
            // Measured at the time these bounds were set (increment 249):
            //   consent 100  consent.tcf 100  json 100  transport 100  storage 100
            //   queue 97.8   identity 97.1    util 93.5  lifecycle 90.9
            //   core 90.3    com.zeotap.sdk 84.2         OVERALL 93.7
            //
            // The overall bound is 90, not the old 85: at 85 the gate was
            // actually FAILING (84.1 %) and nobody saw it, because
            // `make sdk-android-test` never ran koverVerify while the Cloud Build
            // step did. That is fixed in the Makefile; the bound is raised here so
            // the number means something again.
            rule("overall") { minBound(90) }

            // A floor under EVERY package, not just the average.
            //
            // The overall rule alone cannot catch one package rotting while the
            // others carry it — which is exactly what had happened: the public
            // `com.zeotap.sdk` sat at 0 % (nothing ever instantiated the facade)
            // while the engine sat near 100 %.
            //
            // `groupBy = PACKAGE` applies the bound to each package
            // independently and names the offender when it fails. Note this is
            // the ONLY way to express a per-package bound in Kover 0.8: a
            // per-rule `filters { includes { packages(...) } }` is an explicit
            // error ("use custom report variants"), so the 95 % floor the
            // increment-242 follow-up imagined is not available without a report
            // variant per package.
            //
            // 80, not 95: the lowest package today is `com.zeotap.sdk` at 84.2.
            // A bound the code does not meet just breaks the build, and one it
            // scrapes past ratchets on noise. Raise it as the numbers rise.
            rule("every package") {
                groupBy = GroupingEntityType.PACKAGE
                minBound(80)
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("detekt.yml"))
    // Production code only — test tables/vectors are deliberately wide.
    source.setFrom(files("src/main/kotlin"))
}

apiValidation {
    // Internal packages are `internal` in Kotlin; nothing else to hide.
    nonPublicMarkers.add("androidx.annotation.RestrictTo")
}

// ---------------------------------------------------------------------------
// Publishing — com.zeotap.sdk:android-sdk. Coordinates/POM from gradle.properties.
// Maven Central (customer channel) needs Sonatype + GPG credentials; Artifact
// Registry (internal channel) needs a Google credential. Both configured in CI.
// ---------------------------------------------------------------------------
mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent) {
        signAllPublications()
    }
}

publishing {
    repositories {
        maven {
            name = "ArtifactRegistry"
            url = uri("https://europe-west1-maven.pkg.dev/zeotap-prod-microsvcs/android-sdk")
            credentials {
                username = "oauth2accesstoken"
                password = providers.environmentVariable("ARTIFACT_REGISTRY_TOKEN").orNull ?: ""
            }
        }
    }
}
