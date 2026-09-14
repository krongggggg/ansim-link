import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Register org.ansim.link with Dynamic Map enabled in the NAVER Cloud console.
// Supply -PnaverMapKeyId=..., NAVER_MAP_KEY_ID, or naverMapKeyId in local.properties.
val localMapProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val naverMapKeyId = providers.gradleProperty("naverMapKeyId")
    .orElse(providers.environmentVariable("NAVER_MAP_KEY_ID"))
    .getOrElse(localMapProperties.getProperty("naverMapKeyId", "")).trim()

android {
    namespace = "org.ansim.link"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.ansim.link"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.4.1"
        manifestPlaceholders["naverMapKeyId"] = naverMapKeyId
        buildConfigField("boolean", "NAVER_MAP_CONFIGURED", naverMapKeyId.isNotEmpty().toString())
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.naver.maps:map-sdk:3.24.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
