# Extra keep rules for the e2eMinified build type only (the minified E2E smoke). The test APK builds the
# app through these seams, so R8 must neither remove nor rename them. Release does not use this file.
-keep class app.citti.AppConfig { *; }
-keep class app.citti.AppContainer { *; }
-keep class app.citti.CittiApp { *; }

# Libraries the test APK shares with the app: it does not bundle its own copy of a class the app has, so
# R8 must not strip or rename what the tests and the fake server call. App code stays fully shrunk.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class io.ktor.** { *; }
-keep class androidx.** { *; }
-keep class app.citti.core.** { *; }
-dontwarn java.lang.management.**
-dontwarn javax.lang.model.**
