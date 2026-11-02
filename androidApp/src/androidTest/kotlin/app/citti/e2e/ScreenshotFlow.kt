package app.citti.e2e

/**
 * Marks a flow that takes the README screenshots. ATD screenshots come out black, so these run only with
 * `./gradlew e2e -Pe2e.screenshots`, on the `api36Google` managed device; the plain `e2e` run leaves them out.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ScreenshotFlow
