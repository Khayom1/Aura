import java.net.URL
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "systems.apexforge.aura"
    compileSdk = 35
    defaultConfig {
        applicationId = "systems.apexforge.aura"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/auraAssets"))
}

val handModel = layout.buildDirectory.file("generated/auraAssets/hand_landmarker.task")
val downloadHandModel by tasks.registering {
    outputs.file(handModel)
    doLast {
        val target = handModel.get().asFile
        if (!target.exists() || target.length() != 7_819_105L) {
            target.parentFile.mkdirs()
            val temp = File(target.parentFile, "hand_landmarker.task.download")
            val connection = URL("https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task").openConnection()
            connection.connectTimeout = 20_000
            connection.readTimeout = 90_000
            connection.getInputStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            check(temp.length() == 7_819_105L) {
                "Hand Landmarker model size mismatch; refusing to package an incomplete or unexpected model."
            }
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "Could not atomically place Hand Landmarker model." }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadHandModel) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mediapipe:tasks-vision:1.2.2")
    testImplementation("junit:junit:4.13.2")
}
