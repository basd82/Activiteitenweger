plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "net.dikkenberg.activiteitenweger"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.dikkenberg.activiteitenweger"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(dependencies.project(":shared"))
    implementation(libs.androidx.activity.compose)
}
