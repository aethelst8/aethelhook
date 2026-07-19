import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystoreProps = Properties().also { props ->
    val f = rootProject.file("keystore.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace = "com.aethelhook.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.aethelhook.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile     = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias      = keystoreProps["keyAlias"] as String
            keyPassword   = keystoreProps["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // OkHttp for LAN WebSocket connection (replaces FCM for <100ms LAN delivery)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR scanning for device pairing
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Fingerprint/PIN gate for revealing masked IP/token values
    implementation("androidx.biometric:biometric:1.1.0")
    // Encrypts the SharedPreferences file holding the device API token and pinned
    // cert fingerprint (Android Keystore-backed AES-GCM), instead of plain-text prefs.
    implementation("androidx.security:security-crypto:1.1.0")
    // Force a modern Fragment version - zxing-android-embedded's appcompat and
    // biometric 1.1.0 both transitively pull fragment ~1.2.x, which is too old for
    // FragmentActivity's ActivityResult APIs (MainActivity became a FragmentActivity
    // for BiometricPrompt) and silently drops permission-result callbacks.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}