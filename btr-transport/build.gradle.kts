plugins {
    id("com.android.library")
}

// Port of Bilibili-thread-ripper's range downloader (src/range-core.js, cdn-resolver.js,
// idm-downloader.js at bbf4d3d). Kept out of :app so upstream updates only touch the hooks.
android {
    namespace = "com.android.purebilibili.btr"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    testOptions { unitTests.all { it.useJUnitPlatform() } }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:5.5.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    api("androidx.media3:media3-datasource:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
}
