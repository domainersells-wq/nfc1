plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hni.nfcidreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hni.nfcidreader"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // قراءة شريحة الوثائق البيومترية (ICAO 9303)
    implementation("org.jmrtd:jmrtd:0.7.40")
    implementation("net.sf.scuba:scuba-sc-android:0.0.23")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // فك صورة الوجه بصيغة JPEG2000
    implementation("com.gemalto.jp2:jp2-android:1.0.3")
}
