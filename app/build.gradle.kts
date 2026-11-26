plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.alma.connect"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.alma.connect"
        minSdk = 26; targetSdk = 35
        val vc = (project.findProperty("verCode") as String?)?.toInt() ?: 4
        versionCode = vc; versionName = "0.5.$vc"
    }
    val ks = System.getenv("KEYSTORE_PATH")
    if (ks != null) signingConfigs.create("rel") {
        storeFile = file(ks); storeType = "pkcs12"
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
    }
    buildTypes {
        release { if (ks != null) signingConfig = signingConfigs.getByName("rel") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}
