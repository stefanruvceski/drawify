import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// versionCode = GitHub Actions run number, so every CI build is newer than the last one.
val buildNumber = (System.getenv("APP_BUILD_NUMBER") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// Release signing comes from the environment (GitHub secrets in CI). The keystore is never committed.
val keystorePath: String? = System.getenv("DRAWIFY_KEYSTORE_PATH")

android {
    namespace = "com.drawify.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drawify.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"stefanruvceski/drawify\"")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("DRAWIFY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DRAWIFY_KEY_ALIAS") ?: "drawify"
                keyPassword = System.getenv("DRAWIFY_KEY_PASSWORD") ?: System.getenv("DRAWIFY_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the secrets (PR builds) fall back to the debug key: installable for testing, never released.
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "true")
        }
        debug {
            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val cameraX = "1.4.2"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the Android stub throws).
    testImplementation("org.json:json:20240303")
}
