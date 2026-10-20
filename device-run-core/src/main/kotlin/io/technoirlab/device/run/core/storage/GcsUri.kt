package io.technoirlab.device.run.core.storage

import io.technoirlab.device.run.core.ValidationErrors

/**
 * A Cloud Storage object or directory, written as `gs://<bucket>/<object name>`.
 *
 * @property bucket the name of the bucket.
 * @property objectName the name of the object, or the path of the directory without a trailing `/`.
 */
data class GcsUri(
    val bucket: String,
    val objectName: String,
) {
    override fun toString(): String = "$SCHEME$bucket/$objectName"

    /**
     * Returns the object or directory at the relative [path] in this directory, separated from the directory path by one `/`.
     */
    internal operator fun div(path: String): GcsUri = copy(objectName = "${objectName.removeSuffix("/")}/$path")

    internal companion object {
        const val SCHEME = "gs://"

        /**
         * Parses `gs://<bucket>/<object name>`.
         *
         * Object names ending in `/` are rejected because they denote directory placeholders rather than files.
         *
         * @throws IllegalArgumentException when [text] does not name a Cloud Storage object.
         */
        fun parse(text: String): GcsUri {
            val path = text.removePrefix(SCHEME)
            val bucket = path.substringBefore('/')
            val objectName = path.substringAfter('/', missingDelimiterValue = "")
            require(text.startsWith(SCHEME) && objectName.isNotEmpty()) { "'$text' must have the form gs://<bucket>/<object>" }
            require(isValidBucketName(bucket)) { "'$text' must name a valid Cloud Storage bucket" }
            require(!objectName.endsWith('/')) { "'$text' must name an object, not a directory" }
            return GcsUri(bucket, objectName)
        }

        /**
         * Parses `gs://<bucket>/<object name>` like [parse], or returns null when [text] does not name a Cloud Storage object.
         */
        fun parseOrNull(text: String): GcsUri? = try {
            parse(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Returns whether [name] follows the Cloud Storage bucket naming rules that matter for building object URIs.
 */
internal fun isValidBucketName(name: String): Boolean = BUCKET_NAME.matches(name)

/**
 * Validates the name of the bucket that stores the inputs and outputs of sessions, and returns it, or an empty name when it is
 * missing.
 */
internal fun ValidationErrors.validateBucket(bucket: String?): String {
    when {
        bucket.isNullOrBlank() -> add(BUCKET, "is required; set it to the name of an existing Cloud Storage bucket")
        bucket.startsWith(GcsUri.SCHEME) -> add(BUCKET, "must be a bucket name without the gs:// scheme, but was '$bucket'")
        !isValidBucketName(bucket) -> add(BUCKET, "'$bucket' is not a valid Cloud Storage bucket name")
    }
    return bucket.orEmpty()
}

private const val BUCKET = "deviceRun.storage.bucket"
private val BUCKET_NAME = Regex("[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]")
