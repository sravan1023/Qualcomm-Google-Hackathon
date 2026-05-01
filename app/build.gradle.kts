plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sightsense"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sightsense"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
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



    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
    // .tflite and .litertlm must not be compressed
    noCompress += "tflite"
    noCompress += "litertlm"
}

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "/META-INF/{AL2.0,LGPL2.1}"
            )
        }
        // QNN delegate ships native .so libs — keep uncompressed for fast load
        jniLibs {
            useLegacyPackaging = false
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
            // ../models lets dropped AOT models (FFNet, detector, etc.) bundle into the APK
            // without manual copying; runtime asset path is the file's path *relative to* models/.
            assets.srcDirs("src/main/assets", "../models")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            java.srcDirs("src/androidTest/kotlin")
        }
    }
}

dependencies {
    // QNN delegate AAR — drop the file into app/libs/ when obtained from AI Hub sample / QAIRT SDK
    implementation(fileTree("libs") { include("*.aar") })

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // CameraX (perception/vision)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // LiteRT (infra/litert)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.support)
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    // Qualcomm QNN TFLite delegate
    // TODO: drop the AAR from AI Hub sample / QAIRT SDK into app/libs/ then enable:
    //   implementation(fileTree("libs") { include("*.aar") })
    // OR enable the libs.versions.toml entry once Maven coordinates are confirmed:
    //   implementation(libs.qnn.litert.delegate)

    // GPS (nav/gps — stretch S1)
    implementation(libs.play.services.location)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    debugImplementation(libs.androidx.ui.tooling)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

