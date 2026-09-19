plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.jambus.heji"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jambus.heji"
        minSdk = 26
        targetSdk = 28
        versionCode = 11
        versionName = "0.5.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    testImplementation("junit:junit:4.13.2")
}
