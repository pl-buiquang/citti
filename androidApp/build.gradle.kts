plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.citti"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.citti"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.coroutines)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.kotlin.test.junit)
}
