package app.citti.e2e

/**
 * Marks the flows that also run against the minified build (R8 on): `./gradlew e2e -Pe2e.minified` runs only these.
 * The plain `e2e` run includes them with the rest.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Smoke
