plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.hotcorners"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.hotcorners"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.4.1"
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
