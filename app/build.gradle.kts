plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.devtools.ksp)
}

android {
    namespace = "com.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.rigscript"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Fixes "must uninstall before installing the new APK every time":
    // CI (GitHub Actions) runs on a fresh, stateless VM every build with no
    // pre-existing ~/.android/debug.keystore. Left unconfigured, AGP's
    // default behavior is to auto-generate a BRAND NEW random debug
    // keystore whenever that file is missing -- so every single CI build
    // was signed with a DIFFERENT key, and Android correctly refuses to
    // install an update signed with a different certificate than what's
    // already installed. app/debug.keystore is committed to the repo
    // specifically so every future build (on any machine, including any
    // future CI runner) signs with the SAME key. This is a debug-only key
    // never intended for Play Store distribution, so committing it carries
    // none of the risk a release-signing key committed to a repo would.
    signingConfigs {
        getByName("debug") {
            storeFile     = file("debug.keystore")
            storePassword = "android"
            keyAlias      = "androiddebugkey"
            keyPassword   = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions { jvmTarget = "11" }

    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
