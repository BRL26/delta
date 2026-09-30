import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
    alias(libs.plugins.ksp)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
    id("com.google.firebase.crashlytics")

}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

// Load version properties
val versionProps = Properties()
val versionPropsFile = rootProject.file("version.properties")
if (versionPropsFile.exists()) {
    versionProps.load(FileInputStream(versionPropsFile))
}

android {
    namespace = "com.blurr.voice"
    compileSdk = 35

    // Common API keys and configuration - extracted to avoid duplication
    val apiKeys = localProperties.getProperty("GEMINI_API_KEYS") ?: ""
    val tavilyApiKeys = localProperties.getProperty("TAVILY_API") ?: ""
    val mem0ApiKey = localProperties.getProperty("MEM0_API") ?: ""
    val googleTtsApiKey = localProperties.getProperty("GOOGLE_TTS_API_KEY") ?: ""
    val googlecloudGatewayURL = localProperties.getProperty("GCLOUD_GATEWAY_URL") ?: ""
    val googlecloudProxyURL = localProperties.getProperty("GCLOUD_PROXY_URL") ?: ""
    val googlecloudProxyURLKey = localProperties.getProperty("GCLOUD_PROXY_URL_KEY") ?: ""
    val revenueCatSDK = localProperties.getProperty("REVENUE_CAT_PUBLIC_URL") ?: ""
    val revenueCatApiKey = localProperties.getProperty("REVENUECAT_API_KEY") ?: ""

    val debugSha1 = "D0:A1:49:03:FD:B5:37:DF:B5:36:51:B1:66:AE:70:11:E2:59:08:33"

    defaultConfig {
        // Forked build. Changed from "com.blurr.voice" so this variant can be
        // installed alongside the upstream app. NOTE: `namespace` above is
        // deliberately left as com.blurr.voice - every component in the
        // manifest is declared relatively (.MainActivity, .triggers.ui.X) and
        // all sources live under com/blurr/voice/. Changing the namespace would
        // require relocating the entire source tree for no benefit.
        applicationId = "com.brl.blurrmbtg"
        minSdk = 24
        targetSdk = 35
        versionCode = versionProps.getProperty("VERSION_CODE", "13").toInt()
        versionName = versionProps.getProperty("VERSION_NAME", "1.0.13")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Common build config fields - applies to all build types
        buildConfigField("String", "GEMINI_API_KEYS", "\"$apiKeys\"")
        buildConfigField("String", "TAVILY_API", "\"$tavilyApiKeys\"")
        buildConfigField("String", "MEM0_API", "\"$mem0ApiKey\"")
        buildConfigField("boolean", "ENABLE_DIRECT_APP_OPENING", "true")
        buildConfigField("boolean", "SPEAK_INSTRUCTIONS", "true")
        buildConfigField("String", "GOOGLE_TTS_API_KEY", "\"$googleTtsApiKey\"")
        buildConfigField("String", "GCLOUD_GATEWAY_URL", "\"$googlecloudGatewayURL\"")
        buildConfigField("String", "GCLOUD_PROXY_URL", "\"$googlecloudProxyURL\"")
        buildConfigField("String", "GCLOUD_PROXY_URL_KEY", "\"$googlecloudProxyURLKey\"")
        buildConfigField("boolean", "ENABLE_LOGGING", "true")

    }

    // Release signing. The keystore and passwords come from local.properties -- it is
    // gitignored, so none of this ever reaches the repository. Set these to sign
    // release builds:
    //   DELTA_RELEASE_KEYSTORE=/path/to/delta-release.jks
    //   DELTA_RELEASE_KEY_ALIAS=delta                        (optional, falls back to "delta")
    //   DELTA_RELEASE_KEYSTORE_PASSWORD=...                  (required)
    //   DELTA_RELEASE_KEY_PASSWORD=...                       (optional, falls back to the
    //                                                         keystore password)
    // Without them, assembleRelease still works but silently produces an unsigned APK,
    // so a loud warning is printed. The key itself: ~/.android/delta-release.jks with
    // its password in ~/.android/delta-release.pass -- back both up, losing them makes
    // this app permanently un-updatable.
    val releaseKeystore = localProperties.getProperty("DELTA_RELEASE_KEYSTORE")
    val releaseKeystorePass = localProperties.getProperty("DELTA_RELEASE_KEYSTORE_PASSWORD")
    val hasReleaseSigning = !releaseKeystore.isNullOrBlank() && !releaseKeystorePass.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseKeystorePass!!
                keyAlias = localProperties.getProperty("DELTA_RELEASE_KEY_ALIAS", "delta")
                keyPassword = localProperties.getProperty(
                    "DELTA_RELEASE_KEY_PASSWORD",
                    releaseKeystorePass!!
                )
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "Release signing not configured: set DELTA_RELEASE_KEYSTORE and " +
                        "DELTA_RELEASE_KEYSTORE_PASSWORD in local.properties to sign " +
                        "release builds (otherwise the APK comes out unsigned)."
                )
            }
            firebaseCrashlytics {
                nativeSymbolUploadEnabled = true
            }
            // Deliberately off for this release. R8 goes far beyond shrinking an APK: it
            // renames every class, and this app leans on reflection in ways that only fail
            // at runtime -- Firebase, the service loader behind the action dispatchers, and
            // the composable-driven session UI. A release APK is the one artefact that gets
            // handed to other people, and a release build cannot be instrumented here, so
            // the failure mode is an APK that installs cleanly and then breaks when used.
            // An oversized APK is a much better outcome than a subtly broken one.
            //
            // Turn both back on once there is a device test pass against a release build to
            // catch what shrinking breaks.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Debug-specific field only
            buildConfigField("String", "SHA1_FINGERPRINT", "\"$debugSha1\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Pure-logic helpers log on their failure paths, and a stubbed
            // android.util.Log throws instead of returning a default, which
            // turns a passing assertion into an error about the logging.
            isReturnDefaultValues = true
        }
    }
}
val libsuVersion = "6.0.0"

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.appcompat)
    implementation(libs.generativeai)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation("com.google.android.material:material:1.11.0") // or latest

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.16")
    implementation("com.squareup.moshi:moshi:1.15.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // https://mvnrepository.com/artifact/androidx.test.uiautomator/uiautomator
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")

    implementation("com.google.firebase:firebase-analytics")

    // Room database dependencies
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // Import the Firebase BoM
    implementation(platform(libs.firebase.bom))

    implementation(libs.firebase.config)


    // Add the dependency for the Firebase Authentication library
    implementation(libs.firebase.auth)

    // Add the dependency for the Google Play services library
    implementation(libs.play.services.auth)

    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics-ndk")
    implementation("com.google.firebase:firebase-functions")
    implementation(libs.firebase.firestore)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.android.billingclient:billing-ktx:7.0.0")
}

// Task to increment version for release builds
tasks.register("incrementVersion") {
    doLast {
        val versionFile = rootProject.file("version.properties")
        val props = Properties()
        props.load(FileInputStream(versionFile))

        val currentVersionCode = props.getProperty("VERSION_CODE").toInt()
        val currentVersionName = props.getProperty("VERSION_NAME")

        // Increment version code
        val newVersionCode = currentVersionCode + 1

        // Increment patch version in semantic versioning (x.y.z -> x.y.z+1)
        val versionParts = currentVersionName.split(".")
        val newPatchVersion = if (versionParts.size >= 3) {
            versionParts[2].toInt() + 1
        } else {
            1
        }
        val newVersionName = if (versionParts.size >= 2) {
            "${versionParts[0]}.${versionParts[1]}.$newPatchVersion"
        } else {
            "1.0.$newPatchVersion"
        }

        // Update properties
        props.setProperty("VERSION_CODE", newVersionCode.toString())
        props.setProperty("VERSION_NAME", newVersionName)

        // Save back to file with comments
        val output = FileOutputStream(versionFile)
        output.use { fileOutput ->
            fileOutput.write("# Version configuration for Blurr Android App\n".toByteArray())
            fileOutput.write("# This file is automatically updated during release builds\n".toByteArray())
            fileOutput.write("# Do not modify manually - use Gradle tasks to update versions\n\n".toByteArray())
            fileOutput.write("# Current version code (integer - increments by 1 each release)\n".toByteArray())
            fileOutput.write("VERSION_CODE=$newVersionCode\n\n".toByteArray())
            fileOutput.write("# Current version name (semantic version - increments patch number each release)\n".toByteArray())
            fileOutput.write("VERSION_NAME=$newVersionName".toByteArray())
        }

        println("Version incremented to: versionCode=$newVersionCode, versionName=$newVersionName")
    }
}

// Make release builds automatically increment version
tasks.whenTaskAdded {
    if (name == "assembleRelease" || name == "bundleRelease") {
        dependsOn("incrementVersion")
    }
}