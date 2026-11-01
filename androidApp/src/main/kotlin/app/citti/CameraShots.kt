package app.citti

import java.io.File

/**
 * Deletes the camera shots left in `cacheDir/camera/`, where Insert image's camera app writes (see `file_paths.xml`).
 * A shot is removed once uploaded; one survives only when the process died in between. Run at start and at sign-out.
 */
fun clearCameraShots(cacheDir: File) {
    File(cacheDir, "camera").deleteRecursively()
}
