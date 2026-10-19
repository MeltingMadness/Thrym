import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseKeystoreProperties = Properties()
val releaseKeystoreFile = rootProject.file("keystore.properties")
val hasReleaseSigning = releaseKeystoreFile.exists()

if (hasReleaseSigning) {
    releaseKeystoreFile.inputStream().use(releaseKeystoreProperties::load)
}

android {
    namespace = "com.thrym.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.thrym.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    sourceSets.getByName("androidTest").assets.directories.add("../core/database/schemas")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // UI languages: English (values/, default) and German (values-de/). Drops the other
        // translations of AndroidX/Material so the UI never mixes languages. Keep in sync with
        // res/xml/locales_config.xml.
        localeFilters += listOf("en", "de")
    }

    // Leave out the dependency metadata blob that AGP encrypts for Google Play: F-Droid rejects it
    // and it gets in the way of reproducible builds.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    // Project modules
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":data"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:workout"))
    implementation(project(":feature:exercises"))
    implementation(project(":feature:history"))
    implementation(project(":feature:plans"))
    implementation(project(":feature:statistics"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:progression"))
    implementation(project(":feature:onboarding"))

    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Activity & Lifecycle
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Room (still needed for ksp in app for schema export)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // Core
    implementation(libs.core.ktx)
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.serialization.json)
    implementation(libs.paging.compose)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.koin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(project(":shared"))

    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(project(":shared"))
}
