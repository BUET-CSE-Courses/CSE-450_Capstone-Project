package com.example.capstone.extractor

/**
 * Loads OpenCV for a JVM unit test.
 *
 * The Android AAR ships arm64 and x86 Android `.so` files and nothing a desktop JVM can load,
 * so it is excluded from the unit-test classpath in this module's `build.gradle.kts`. The
 * openpnp build stands in: the identical `org.opencv.*` API - checked signature by signature
 * against 4.11 for every call this module makes - with `opencv_java490.dll` and its Linux and
 * macOS equivalents bundled, extracted and loaded by `loadLocally()`.
 *
 * That is the reason [OpenCvNative] exists as its own quarantined object and is referenced by
 * nothing here: `org.opencv.android.OpenCVLoader` is not on this classpath.
 */
internal object OpenCvTestNative {

    private val loaded: Boolean by lazy {
        nu.pattern.OpenCV.loadLocally()
        true
    }

    fun load() {
        check(loaded)
    }
}
