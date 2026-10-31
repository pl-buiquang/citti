import com.android.build.api.dsl.ManagedVirtualDevice
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

        // E2E (androidTest): see androidApp/src/androidTest and `./gradlew e2e`.
        testInstrumentationRunner = "app.citti.e2e.E2eRunner"
        // Orchestrator runs each test in its own process on wiped app data (prefs, DB, Keystore-backed blob).
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // Failure screenshots and request logs (E2eRule) go to the test storage, pulled into
        // build/outputs/managed_device_android_test_additional_output/.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        // `./gradlew e2e -Pe2e.class=app.citti.e2e.flows.SearchTest` runs one flow (or `Class#method`).
        (findProperty("e2e.class") as String?)?.let { testInstrumentationRunnerArguments["class"] = it }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
        managedDevices {
            allDevices {
                create<ManagedVirtualDevice>("api36") {
                    device = "Pixel 6"
                    apiLevel = 36
                    systemImageSource = "aosp-atd"
                    require64Bit = true
                }
            }
        }
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
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.androidx.glance.appwidget.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)

    // The fake server and the fixture Vault: androidTest only, never in the app.
    androidTestImplementation(project(":core-testing"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlin.test.junit)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
}

// Thread.sleep is forbidden in the E2E suite: wait on the sync state or the fake's request log instead.
tasks.register("e2eNoSleep") {
    val sources = fileTree("src/androidTest") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val offenders = sources.files.filter { "Thread.sleep" in it.readText() }
        check(offenders.isEmpty()) { "Thread.sleep in the E2E suite: ${offenders.joinToString()}" }
    }
}
