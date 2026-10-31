plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "app.citti.ui"
        compileSdk = 37
        minSdk = 31
        withHostTest {}
        androidResources.enable = true
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(project(":core"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.compose.resources)
            implementation(libs.kotlinx.serialization.json)
            api(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
        androidMain.dependencies {
            implementation(libs.compose.ui)
            // Insert image: the camera and Photo Picker result contracts, and FileProvider (androidx.core).
            implementation(libs.androidx.activity.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

compose.resources {
    packageOfResClass = "app.citti.ui.resources"
}
