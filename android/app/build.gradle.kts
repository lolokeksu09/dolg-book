import java.net.URI

plugins {
    id("com.android.application") version "8.10.1"
    id("org.jetbrains.kotlin.android") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

// Opt-in isolated licensed staging; ordinary debug/release remain compatible with
// current public RC. Production enforcement needs a separate reviewed key rollout.
val licensePin = providers.gradleProperty("raschetLicensePublicKey").orNull
val licenseOrigin = providers.gradleProperty("raschetLicenseOrigin").orNull
require((licensePin == null) == (licenseOrigin == null)) { "Both license staging properties are required" }
if (licensePin != null) {
    require(licensePin.matches(Regex("[0-9a-f]{64}"))) { "Expected raw Ed25519 public key hex" }
    val origin = URI(licenseOrigin!!)
    require(origin.scheme == "http" && origin.host == "127.0.0.1" &&
        origin.port in 1..65535 && origin.rawUserInfo == null && origin.rawQuery == null &&
        origin.rawFragment == null && origin.rawPath.isNullOrEmpty()) { "Staging must use explicit loopback HTTP" }
}

android {
    namespace = "dev.ventry.raschet"
    compileSdk = 35
    buildFeatures { buildConfig = true; compose = true }
    defaultConfig {
        buildConfigField("boolean", "RUST_PREVIEW", "false")
        buildConfigField("boolean", "LICENSE_REQUIRED", "false")
        buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"\"")
        buildConfigField("String", "LICENSE_ORIGIN", "\"\"")
        applicationId = "dev.ventry.raschet"
        minSdk = 26
        targetSdk = 35
        versionCode = 35
        versionName = "0.30.0-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["licenseNetworkConfig"] = "@xml/license_network"
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = if (licensePin == null) ".nativev031" else ".licensedstaging"
            if (licensePin != null) {
                buildConfigField("boolean", "LICENSE_REQUIRED", "true")
                buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"$licensePin\"")
                buildConfigField("String", "LICENSE_ORIGIN", "\"$licenseOrigin\"")
                manifestPlaceholders["licenseNetworkConfig"] = "@xml/license_staging_network"
            }
        }
        getByName("release") {
            // Release corrections must use the packaged Rust JNI engine.
            buildConfigField("boolean", "RUST_PREVIEW", "true")
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

// Every release includes Rust. Debug remains Kotlin-only unless explicitly selected,
// keeping host unit tests independent of Android ELF libraries.
val rustPreview = providers.gradleProperty("raschetRustPreview").orNull == "true"
val nativeRoot = layout.buildDirectory.dir("generated/rustPreview")
val nativeLibs = nativeRoot.map { it.dir("jniLibs") }
val nativeAssets = nativeRoot.map { it.dir("testAssets") }
android.sourceSets.getByName("release").jniLibs.srcDir(nativeLibs)
val verifyRustLibraries = tasks.register("verifyRustLibraries") {
    doLast {
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
            val so = nativeLibs.get().file("$abi/libraschet_jni.so").asFile
            check(so.isFile && so.length() > 0) { "Missing Rust library for $abi; run scripts/build-rust-android.sh" }
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyRustLibraries) }
if (rustPreview) {
    android.buildTypes.getByName("debug").buildConfigField("boolean", "RUST_PREVIEW", "true")
    android.sourceSets.getByName("debug").jniLibs.srcDir(nativeLibs)
    android.sourceSets.getByName("androidTest").java.srcDir("src/nativePreviewAndroidTest/java")
    android.sourceSets.getByName("androidTest").assets.srcDir(nativeAssets)
    val verifyRustTestAssets = tasks.register("verifyRustTestAssets") {
        dependsOn(verifyRustLibraries)
        doLast {
            check(nativeAssets.get().file("correction.tsv").asFile.isFile) { "Missing Rust test fixture" }
        }
    }
    tasks.matching { it.name == "preDebugBuild" || it.name == "preDebugAndroidTestBuild" }
        .configureEach { dependsOn(verifyRustTestAssets) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.activity:activity:1.10.1")
    // Lightweight Ed25519 verification works below the platform EdDSA API level.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.87")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
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
