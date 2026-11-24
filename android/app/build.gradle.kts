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
        versionCode = 34
        versionName = "0.29.0-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// The preview/debug package stays isolated from the future signed release.
val signingStorePath = System.getenv("RASCHET_SIGNING_STORE_FILE")
val signingStorePassword = System.getenv("RASCHET_SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("RASCHET_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("RASCHET_SIGNING_KEY_PASSWORD")
if (listOf(signingStorePath, signingStorePassword, signingKeyAlias, signingKeyPassword)
        .all { !it.isNullOrBlank() }) {
    android.signingConfigs.create("raschetRelease") {
        storeFile = file(signingStorePath!!)
        storePassword = signingStorePassword!!
        keyAlias = signingKeyAlias!!
        keyPassword = signingKeyPassword!!
    }
    android.buildTypes.getByName("release").signingConfig =
        android.signingConfigs.getByName("raschetRelease")
}
