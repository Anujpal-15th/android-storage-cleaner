plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI sets these: a v1.2.3 tag becomes versionName 1.2.3; the run number keeps versionCode increasing.
val tag = System.getenv("GITHUB_REF_NAME")?.removePrefix("v")?.takeIf { it.firstOrNull()?.isDigit() == true }
val keystore = System.getenv("KEYSTORE_FILE")

android {
    namespace = "dev.storagecleaner"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.storagecleaner"
        minSdk = 26 // StorageStatsManager + java.nio.file
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionName = tag ?: "1.0.0-dev"
    }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storeType = "pkcs12"
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "storagecleaner"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // without the release key (local builds), sign with the debug key so the APK still installs
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
}
