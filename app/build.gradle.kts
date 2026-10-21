import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


// Voice or text answers to spoken questions (see gradle.properties); -PmuseVoiceReplies=true.
val museVoiceReplies = (findProperty("museVoiceReplies") as String?)?.toBoolean() ?: false

android {
    namespace = "dev.tvlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tvlauncher"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "MUSE_VOICE_REPLIES", "$museVoiceReplies")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sideloaded personal build: sign with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
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

dependencies {
    // No runtime dependencies on purpose (no AndroidX/AppCompat): startup and
    // memory stay minimal. Tests only.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
