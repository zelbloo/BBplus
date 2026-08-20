plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.zel.bbplus"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.zel.bbplus"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.14"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/bbplus.keystore")
            storePassword = "bbplus-bbplus"
            keyAlias = "bbplus"
            keyPassword = "bbplus-bbplus"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
}
