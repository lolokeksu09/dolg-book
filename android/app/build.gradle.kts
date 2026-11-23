plugins {
    id("com.android.application") version "8.9.2"
}

val origin = providers.gradleProperty("raschetOrigin").orElse("https://raschet.example.test").get()
require(Regex("""https://[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)+""").matches(origin)) {
    "raschetOrigin must be an HTTPS origin without a path or trailing slash"
}

android {
    namespace = "dev.ventry.raschet"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.ventry.raschet"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        resValue("string", "book_url", "$origin/book/")
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
