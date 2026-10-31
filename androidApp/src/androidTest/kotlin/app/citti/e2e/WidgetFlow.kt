package app.citti.e2e

/**
 * Marks a flow that needs a launcher that can pin widgets. The ATD image has none, so these run only with
 * `./gradlew e2e -Pe2e.widgets`, on the `api36Google` managed device; the plain `e2e` run leaves them out.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WidgetFlow
