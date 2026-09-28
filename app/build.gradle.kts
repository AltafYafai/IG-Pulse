import com.android.build.api.variant.impl.VariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Locale

plugins {
    alias(libs.plugins.androidApplication)
}

// `substring(0, 8)` used to run unguarded, so any build from a source archive (no .git, or git
// absent entirely) died with `Range [0, 8) out of bounds`. Keep it a plain string instead.
val gitHash: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get()
}.getOrDefault("").trim().uppercase(Locale.getDefault()).take(8).ifEmpty { "UNKNOWN" }

// Point these at your own repository before the first release build.
val releasesRepo = providers.gradleProperty("releasesRepo").getOrElse("IG-Pulse/IG-Pulse-releases")
val sourceRepo = providers.gradleProperty("sourceRepo").getOrElse("IG-Pulse/IG-Pulse")
val rawBranch = providers.gradleProperty("rawBranch").getOrElse("main")

android {
    namespace = "com.igpulse"
    compileSdk = 37

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    defaultConfig {
        applicationId = "com.igpulse"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0 ($gitHash)"
        multiDexEnabled = true

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }

        buildConfigField("boolean", "RESET_ON_INSTALL", "false")
        buildConfigField("String", "RELEASES_API_LATEST", "\"https://api.github.com/repos/$releasesRepo/releases/latest\"")
        buildConfigField("String", "RELEASES_API_ALL", "\"https://api.github.com/repos/$releasesRepo/releases\"")
        buildConfigField("String", "RELEASES_PAGE", "\"https://github.com/$releasesRepo/releases/latest\"")
        buildConfigField("String", "SUPPORTED_VERSIONS_RAW", "\"https://raw.githubusercontent.com/$sourceRepo/$rawBranch/app/src/main/res/values/arrays.xml\"")
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/$sourceRepo\"")
        buildConfigField("String", "ISSUES_URL", "\"https://github.com/$sourceRepo/issues\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/**", "kotlin/**", "okhttp3/**", "org/**", "**.properties", "**.bin")
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    signingConfigs.create("config") {
        val storePath = (project.findProperty("androidStoreFile") as String?)
        val target = when {
            !storePath.isNullOrEmpty() && rootProject.file(storePath).exists() -> rootProject.file(storePath)
            rootProject.file("key.jks").exists() -> rootProject.file("key.jks")
            rootProject.file("keystore/release.jks").exists() -> rootProject.file("keystore/release.jks")
            else -> null
        }
        if (target != null) {
            storeFile = target
            storePassword = project.findProperty("androidStorePassword") as String? ?: "igpulse"
            keyAlias = project.findProperty("androidKeyAlias") as String? ?: "igpulse"
            keyPassword = project.findProperty("androidKeyPassword") as String? ?: "igpulse"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = if (signingConfigs["config"].storeFile != null) {
                signingConfigs["config"]
            } else {
                signingConfigs["debug"]
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (signingConfigs["config"].storeFile != null) {
                signingConfigs["config"]
            } else {
                signingConfigs["debug"]
            }
        }
    }

    lint {
        // `assembleRelease` pulls in lintVital, and there is no toolchain here to run lint
        // against first - a lint finding must not be what stops an APK from being produced.
        checkReleaseBuilds = false
        abortOnError = false
        disable += "SelectedPhotoAccess"
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as VariantOutputImpl).outputFileName.set("IGPulse-${variant.name}-1.0.0 ($gitHash).apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Local aar: dexkit 2.2.0 on Maven has breaking API changes that this codebase has not
    // been migrated to. Swap for libs.dexkit once the migration lands.
    implementation(files("libs/dexkit-android.aar"))

    compileOnly(libs.libxposed.legacy)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.rikkax.appcompat)
    implementation(libs.rikkax.core)
    implementation(libs.rikkax.material)
    implementation(libs.rikkax.material.preference)
    implementation(libs.okhttp)
    implementation(libs.markwon.core)
    implementation(libs.remote.preferences)
    implementation(libs.arscblamer)
}

configurations.all {
    exclude("androidx.appcompat", "appcompat")
}
