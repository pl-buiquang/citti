plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.spotless)
}

// E2E suite on the managed emulator (not part of `check`). `-Pe2e.class=<class>[#method]` runs one flow.
// `-Pe2e.minified` runs only the @Smoke flows against the minified e2eMinified build.
// `-Pe2e.widgets` runs the @WidgetFlow classes (launcher widgets) on the google image instead of the rest on ATD.
// `-Pe2e.screenshots` runs the @ScreenshotFlow classes (the README screenshots) on the google image too: ATD screenshots are black.
tasks.register("e2e") {
    group = "verification"
    description = "Runs the E2E tests on the api36 managed device; -Pe2e.widgets and -Pe2e.screenshots run on api36Google."
    val google = findProperty("e2e.widgets") != null || findProperty("e2e.screenshots") != null
    val device = if (google) "api36Google" else "api36"
    val variant = if (findProperty("e2e.minified") != null) "E2eMinified" else "Debug"
    dependsOn(":androidApp:e2eNoSleep", ":androidApp:$device${variant}AndroidTest")
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/ui/theme/Notes*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}
