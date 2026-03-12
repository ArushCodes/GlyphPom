plugins {
    alias(libs.plugins.android.application)
}

android {
    // Updated Namespace to match the new ID
    namespace = "com.closenheimer.glyphpom"

    compileSdk = 36 // Standardized for SDK 36

    defaultConfig {
        // Updated Application ID
        applicationId = "com.closenheimer.glyphpom"

        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Recommendation: Set this to true when you finally publish
            // to shrink the app size and protect your code.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Kept your Ketchum SDK for Nothing Glyph hardware
    implementation(files("libs/ketchum-sdk.jar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}