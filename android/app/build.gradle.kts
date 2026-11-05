plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version numbers come from the build (GitHub Actions run number); defaults for local builds.
val cadranVersionCode = (project.findProperty("cadranVersionCode") as String?)?.toIntOrNull() ?: 1
val cadranVersionName = (project.findProperty("cadranVersionName") as String?) ?: "1.0.0"
val releaseKeystore = rootProject.file("release.keystore")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")

android {
    namespace = "fr.micolomike.cadran"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.micolomike.cadran"
        minSdk = 26
        targetSdk = 35
        versionCode = cadranVersionCode
        versionName = cadranVersionName
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore.exists() && keystorePassword != null) {
                storeFile = releaseKeystore
                storePassword = keystorePassword
                keyAlias = "cadran"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore.exists() && keystorePassword != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
