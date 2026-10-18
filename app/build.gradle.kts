plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.lookingglass.alice"
    compileSdk = 34
    defaultConfig {
        applicationId = providers.gradleProperty("validationAppId").getOrElse("com.lookingglass.alice")
        minSdk = 26
        targetSdk = 34
        versionCode = 320090
        versionName = "0.32.0.88-dualscreen"
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildTypes { release { isMinifyEnabled = false } }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
