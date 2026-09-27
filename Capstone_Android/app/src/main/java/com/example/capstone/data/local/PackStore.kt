package com.example.capstone.data.local

import java.io.File

/**
 * On-disk cache of assignment packs and their images, one folder per paper:
 *
 * ```
 * <root>/<question_id>/pack.json
 * <root>/<question_id>/images/model-answer/<image_id>
 * <root>/<question_id>/images/question/<image_id>
 * ```
 *
 * `pack.json` is the server's bytes as received. The pack holds the answer
 * key, so the app roots this in `noBackupFilesDir`: it never goes to a cloud
 * backup. Files are written to a temp name and renamed, so a crash
 * mid-download never leaves a half pack that parses.
 */
class PackStore(private val root: File) {

    fun readPack(questionId: String): String? =
        packFile(questionId).takeIf { it.isFile }?.readText()

    fun savedAt(questionId: String): Long? =
        packFile(questionId).takeIf { it.isFile }?.lastModified()

    fun writePack(questionId: String, json: String) {
        atomicWrite(packFile(questionId), json.toByteArray(Charsets.UTF_8))
    }

    fun hasImage(questionId: String, kind: String, imageId: String): Boolean =
        imageFile(questionId, kind, imageId).isFile

    fun readImage(questionId: String, kind: String, imageId: String): ByteArray? =
        imageFile(questionId, kind, imageId).takeIf { it.isFile }?.readBytes()

    fun writeImage(questionId: String, kind: String, imageId: String, bytes: ByteArray) {
        atomicWrite(imageFile(questionId, kind, imageId), bytes)
    }

    private fun dir(questionId: String) = File(root, safe(questionId))

    private fun packFile(questionId: String) = File(dir(questionId), "pack.json")

    private fun imageFile(questionId: String, kind: String, imageId: String) =
        File(File(File(dir(questionId), "images"), safe(kind)), safe(imageId))

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not save ${target.name}" }
        }
    }

    companion object {
        private val SAFE = Regex("[A-Za-z0-9_-]{1,128}")

        /**
         * Ids come from the server and become path segments. Anything that is
         * not a plain id (a "..", a slash) is refused rather than cleaned up.
         */
        fun safe(segment: String): String {
            require(SAFE.matches(segment)) { "not a plain id: \"$segment\"" }
            return segment
        }
    }
}
