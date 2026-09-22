plugins {
    id("com.android.application")
}

android {
    namespace = "dev.vehiclespeed.gnssprobe"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.vehiclespeed.gnssprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.9.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
