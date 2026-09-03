plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.appfusion.product"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appfusion.product"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha01"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.android)
}
