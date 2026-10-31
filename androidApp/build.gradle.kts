import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from `keystore.properties` (gitignored, see keystore.properties.example)
// or from CITTI_* environment variables (CI). Without either, the release build stays unsigned.
val keystoreProps =
    Properties().apply {
        rootProject
            .file("keystore.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use(::load)
    }

fun signing(
    key: String,
    env: String,
): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

// `-PversionName=X.Y.Z` (set by the release workflow from the tag). versionCode is derived so it
// always increases with the version, which Android requires for in-place updates.
val appVersionName = (findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode =
    appVersionName.split('.').map { it.toInt() }.let { (major, minor, patch) ->
        major * 10_000 + minor * 100 + patch
    }

android {
    namespace = "app.citti"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.citti"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    val releaseStoreFile = signing("storeFile", "CITTI_KEYSTORE_FILE")
    if (releaseStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signing("storePassword", "CITTI_KEYSTORE_PASSWORD")
                keyAlias = signing("keyAlias", "CITTI_KEY_ALIAS")
                keyPassword = signing("keyPassword", "CITTI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
