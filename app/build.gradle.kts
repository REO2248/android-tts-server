plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.io.FileInputStream
import java.util.Properties

// Load keystore properties from root project file 'keystore.properties' if present.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

android {
    namespace = "net.re22.androidttsserver"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.re22.androidttsserver"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // Prefer reading signing credentials from an external keystore.properties file
            // (keystore.properties should be added to .gitignore). If absent, fall back
            // to the local debug keystore for convenience.
            val storeFilePath: String? = keystoreProperties.getProperty("storeFile")
            val storePasswordProp: String? = keystoreProperties.getProperty("storePassword")
            val keyAliasProp: String? = keystoreProperties.getProperty("keyAlias")
            val keyPasswordProp: String? = keystoreProperties.getProperty("keyPassword")

            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
            } else {
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            }
            storePassword = storePasswordProp ?: "android"
            keyAlias = keyAliasProp ?: "androiddebugkey"
            keyPassword = keyPasswordProp ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
}
