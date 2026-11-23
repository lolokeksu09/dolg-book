plugins {
    id("com.android.application") version "8.9.2"
}

android {
    namespace = "dev.ventry.raschet"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.ventry.raschet"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.2"
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".nativev031"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
