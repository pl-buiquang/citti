plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// Test doubles shared by the :core unit tests and the :androidApp E2E tests (FakeNotesServer).
// It does not depend on :core, so the fake stays independent of the app's API client.
// Never an `implementation` dependency of the app: it must not reach the release APK.
kotlin {
    android {
        namespace = "app.citti.core.testing"
        compileSdk = 37
        minSdk = 31
        withHostTest {}
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.mock)
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
