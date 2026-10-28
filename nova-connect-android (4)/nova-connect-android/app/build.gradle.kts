import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vp = Properties().apply { file("../version.properties").inputStream().use { load(it) } }

android {
    namespace = "app.nova.connect"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.nova.connect"
        minSdk = 26
        targetSdk = 33
        versionCode = vp.getProperty("VERSION_CODE").trim().toInt()
        versionName = vp.getProperty("VERSION_NAME").trim()
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        create("release") {
            val p = System.getenv("NOVA_KS_PATH")
            if (p != null) {
                storeFile = file(p)
                storePassword = System.getenv("NOVA_KS_PASS")
                keyAlias = System.getenv("NOVA_KS_ALIAS")
                keyPassword = System.getenv("NOVA_KS_PASS")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libxray.so" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
