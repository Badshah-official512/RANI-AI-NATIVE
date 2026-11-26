import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// ---- Release signing: read from keystore.properties (local) or environment (CI). Never committed. ----
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? = ksProps.getProperty(propKey) ?: System.getenv(envKey)
val releaseStoreFile = signingValue("storeFile", "RANI_KEYSTORE_FILE")
val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).exists() &&
    signingValue("storePassword", "RANI_KEYSTORE_PASSWORD") != null &&
    signingValue("keyAlias", "RANI_KEY_ALIAS") != null &&
    signingValue("keyPassword", "RANI_KEY_PASSWORD") != null

android {
    namespace = "com.badshah222.rani.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.badshah222.rani.ai"   // must match Firebase (google-services.json)
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.4.0"
        // Supabase values come from CI secrets (see docs/SETUP-SECRETS.md). findProperty
        // with a placeholder fallback keeps debug builds green before secrets exist.
        buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("SUPABASE_URL") ?: "https://placeholder.supabase.co"}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${project.findProperty("SUPABASE_PUBLISHABLE_KEY") ?: "placeholder-anon-key"}\"")
        buildConfigField("String", "FUNCTIONS_REGION", "\"${project.findProperty("FIREBASE_FUNCTIONS_REGION") ?: "asia-south1"}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "RANI_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RANI_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RANI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ""; isDebuggable = true }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without signing secrets this yields an UNSIGNED release APK/AAB (clearly named by Gradle).
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Java-WebSocket — the Live voice socket engine (v1.2.3). OkHttp's WebSocket
    // could not open a socket on the boss's phone (silent 15 s hang, three
    // versions); this raw-socket client sends a plain HTTP/1.1 upgrade.
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Firebase (versions managed by the BoM)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-functions")
    // Google Sign-In via Credential Manager
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // CameraX — Camera View screen (live front-camera preview + stills for boss recognition)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // ML Kit — on-device face detection for boss recognition / Face Lock (bundled model, works offline)
    implementation("com.google.mlkit:face-detection:16.1.6")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
