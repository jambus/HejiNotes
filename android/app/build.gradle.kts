import java.net.URLDecoder

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
        versionCode = 17
        versionName = "0.6.5"
        val oneDriveClientId = (project.findProperty("HEJI_ONEDRIVE_CLIENT_ID") as String?)
            ?: System.getenv("HEJI_ONEDRIVE_CLIENT_ID")
            ?: ""
        val oneDriveRawHash = (project.findProperty("HEJI_ONEDRIVE_SIGNATURE_HASH") as String?)
            ?: System.getenv("HEJI_ONEDRIVE_SIGNATURE_HASH")
            ?: ""
        val oneDriveSignatureHash = try {
            if (oneDriveRawHash.contains('%')) URLDecoder.decode(oneDriveRawHash, "UTF-8") else oneDriveRawHash
        } catch (_: Exception) {
            oneDriveRawHash
        }
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"${oneDriveClientId.replace("\"", "\\\"")}\"")
        buildConfigField("String", "ONEDRIVE_SIGNATURE_HASH", "\"${oneDriveSignatureHash.replace("\"", "\\\"")}\"")
        manifestPlaceholders["onedriveSignatureHash"] = oneDriveSignatureHash.ifBlank { "not-configured" }
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
    implementation("com.microsoft.identity.client:msal:5.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
