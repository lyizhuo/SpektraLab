// android/app/build.gradle.kts -- the Compose application.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The D9 memory budget's bytes-per-pixel constant (plan D9, "用实测值替换本节与
// 实现里的常数 180"). It ships at 180 -- the plan's conservative value -- and
// the *measured* numbers so far are 113.7 B/px for a fresh export session and
// 190.7 B/px for an editing session that already holds live and preview
// negatives (android/DEVICE-BASELINE.md, host reference).
//
// Calibrating to a device is therefore a property, not a code change:
//
//   -Pspektralab.bytesPerPixel=140      (or the same line in gradle.properties)
//
// Raise it if a device refuses an export it could finish; lower it only with
// that device's own measured peak in hand, because too low means the OS kills
// the process instead of the export being refused.
val bytesPerPixel: String =
    (findProperty("spektralab.bytesPerPixel") as String?) ?: "180"

android {
    namespace = "com.spektralab.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.spektralab.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("double", "BYTES_PER_PIXEL", bytesPerPixel)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // android/testdata, for the instrumented tests that need a real photograph.
    sourceSets.getByName("androidTest") {
        assets.srcDir(file("../testdata"))
    }
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
