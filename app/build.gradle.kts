import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "jp.personal.mindmap"
    compileSdk = 35
    defaultConfig {
        applicationId = "jp.personal.mindmap.preview.v09"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.9.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
