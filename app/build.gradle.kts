plugins {
    id("com.android.application")
}

android {
    namespace = "com.hyperlite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hyperlite"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}

