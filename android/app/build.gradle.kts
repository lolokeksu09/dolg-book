plugins {
    id("com.android.application") version "8.10.1"
    id("org.jetbrains.kotlin.android") version "2.2.21"
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android.sourceSets.getByName("test").resources.srcDir(rootProject.projectDir.parentFile.resolve("tests/contracts"))

// Opt-in debug-only native preview. Ordinary APKs and signed releases stay Kotlin-only.
val rustPreview = providers.gradleProperty("raschetRustPreview").orNull == "true"
if (rustPreview) {
    val nativeRoot = layout.buildDirectory.dir("generated/rustPreview")
    val nativeLibs = nativeRoot.map { it.dir("jniLibs") }
    val nativeAssets = nativeRoot.map { it.dir("testAssets") }
    android.sourceSets.getByName("debug").jniLibs.srcDir(nativeLibs)
    android.sourceSets.getByName("androidTest").java.srcDir("src/nativePreviewAndroidTest/java")
    android.sourceSets.getByName("androidTest").assets.srcDir(nativeAssets)
    val verifyRustPreview = tasks.register("verifyRustPreviewLibraries") {
        doLast {
            for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
                val so = nativeLibs.get().file("$abi/libraschet_jni.so").asFile
                check(so.isFile && so.length() > 0) { "Missing Rust preview library for $abi; run scripts/build-rust-android.sh" }
            }
            check(nativeAssets.get().file("correction.tsv").asFile.isFile) { "Missing Rust preview test fixture" }
        }
    }
    tasks.matching { it.name == "preDebugBuild" || it.name == "preDebugAndroidTestBuild" }
        .configureEach { dependsOn(verifyRustPreview) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
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
