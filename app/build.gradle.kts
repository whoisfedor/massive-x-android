plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    useLibrary("android.test.runner", false)
    useLibrary("android.test.base", false)
    namespace = "com.massivex.android"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.massivex.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.5.0"
        manifestPlaceholders["appLabel"] = "@string/app_name"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    sourceSets.getByName("androidTest").assets.srcDir("../massa-core/src/test/resources")
    buildFeatures { compose = true }
    // Keep every language available to the in-app picker without a Play download.
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug { applicationIdSuffix = ".dev"; manifestPlaceholders["appLabel"] = "@string/dev_app_name" }
        release { isMinifyEnabled = false }
    }
    lint { abortOnError = true; checkReleaseBuilds = true }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}
dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation(project(":massa-core"))
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
