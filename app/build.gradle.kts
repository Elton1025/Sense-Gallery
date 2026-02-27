plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.sensegallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.sensegallery"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "tflite"
        noCompress += "onnx"
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)

    // Lifecycle / ViewModel
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)

    // Room
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)

    // Navigation
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    // Glide
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)

    // TensorFlow Lite (kept for backward compatibility)
    implementation(libs.tflite)
    implementation(libs.tflite.support)

    // MediaPipe Vision (face detection fallback)
    implementation(libs.mediapipe.vision) {
        exclude(group = "org.tensorflow", module = "tensorflow-lite")
    }

    // ONNX Runtime — runs ArcFace R50 + RetinaFace models
    implementation(libs.onnxruntime.android)

    // WorkManager
    implementation(libs.workmanager)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}