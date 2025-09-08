plugins {
    alias(libs.plugins.android.application)
}

    android {
        namespace = "com.example.uvctestcamera"
        compileSdk = 35

        defaultConfig {
            applicationId = "com.example.uvctestcamera"
            minSdk = 21
            targetSdk = 35
            versionCode = 1
            versionName = "1.0"

            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        buildTypes {
            release {
                isMinifyEnabled = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        buildFeatures {
            viewBinding = true
            dataBinding = true
        }

    }

    dependencies {
        implementation(libs.appcompat)
        implementation(libs.material)
        implementation(libs.activity)
        implementation(libs.constraintlayout)

        // ✅ Make sure these .aar files exist in app/libs/
        implementation(files("libs/libuvccamera-release.aar"))
        implementation(files("libs/usbCameraCommon-release.aar"))
        implementation(files("libs/libuvccommon.aar"))
        implementation(files("libs/common-2.12.4.aar"))
        implementation(files("libs/opencv-release.aar"))

        implementation(libs.mlkit.face.detection)
        implementation(libs.tensorflow.lite)
        implementation(libs.vision.common)

        implementation(libs.paho.mqtt)
        implementation(libs.paho.android)
        implementation(libs.protolite.well.known.types)
        implementation(libs.annotation.jvm)

        testImplementation(libs.junit)
        androidTestImplementation(libs.ext.junit)
        androidTestImplementation(libs.espresso.core)

        implementation(libs.jackson.databind)
        implementation(libs.jackson.core)
        implementation(libs.jackson.annotations)
    }

