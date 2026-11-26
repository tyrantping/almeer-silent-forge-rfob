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
        buildConfigField("String", "REPO", "\"${project.findProperty("ghRepo") ?: "tyrantping/almeer-silent-forge-1kkp"}\"")
        versionCode = vc; versionName = "0.6.$vc"
    }
    signingConfigs.create("rel") {
        storeFile = file("alma-release.p12"); storeType = "pkcs12"
        storePassword = "dc43678f0f9cbbe0f212cd6637c83e29"; keyAlias = "alma"; keyPassword = "dc43678f0f9cbbe0f212cd6637c83e29"
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("rel") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
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
