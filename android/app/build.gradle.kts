plugins {
    id("com.android.application") version "8.9.2"
}

val origin = providers.gradleProperty("raschetOrigin").orElse("https://raschet.example.test").get()
require(Regex("""https://[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)+""").matches(origin)) {
    "raschetOrigin must be an HTTPS origin without a path or trailing slash"
}

// Copy only public runtime assets. Never package project-source.zip, docs or tests.
val syncOfflineBook by tasks.registering(Copy::class) {
    from(rootProject.file("../dist"))
    include("index.html", "style.css", "app.js", "ledger.js")
    into(layout.buildDirectory.dir("generated/offlineBookAssets"))
}

android {
    namespace = "dev.ventry.raschet"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.ventry.raschet"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        resValue("string", "book_url", "$origin/book/")
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/offlineBookAssets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.named("preBuild") { dependsOn(syncOfflineBook) }

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
