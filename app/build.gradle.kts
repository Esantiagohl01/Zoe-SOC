plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zoesoc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zoesoc"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4"
    }

    // Llave fija para que cada versión nueva se instale encima de la anterior
    // (GitHub crearía una llave distinta en cada compilación). Solo para uso personal.
    signingConfigs {
        create("fixed") {
            storeFile = file("zoesoc.jks")
            storePassword = "zoesoc123"
            keyAlias = "zoesoc"
            keyPassword = "zoesoc123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
        abortOnError = false
    }
}

// Sin dependencias externas: solo APIs de Android.
