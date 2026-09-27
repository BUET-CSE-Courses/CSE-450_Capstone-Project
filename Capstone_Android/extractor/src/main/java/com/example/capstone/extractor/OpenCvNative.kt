package com.example.capstone.extractor

import org.opencv.android.OpenCVLoader

/**
 * Loads the OpenCV native library on Android. Call once, before any [PageExtractor] work.
 *
 * This object is quarantined for a reason. `org.opencv.android.OpenCVLoader` exists **only in
 * the Android AAR**, and the AAR is deliberately absent from the unit-test classpath (see this
 * module's `build.gradle.kts`) because it ships no host-loadable native library. Nothing on a
 * test path may reference this object; JVM lazy class resolution then never looks for the
 * missing class, and the tests load the openpnp desktop build instead.
 *
 * Keep it that way: no helper, no convenience wrapper, and nothing else in this file.
 */
object OpenCvNative {

    @Volatile
    private var loaded = false

    /**
     * Idempotent. Throws [IllegalStateException] if the native library will not load, because
     * every subsequent OpenCV call would otherwise fail with an UnsatisfiedLinkError from
     * somewhere much less obvious.
     */
    @Synchronized
    fun load() {
        if (loaded) return
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
        loaded = true
    }
}
