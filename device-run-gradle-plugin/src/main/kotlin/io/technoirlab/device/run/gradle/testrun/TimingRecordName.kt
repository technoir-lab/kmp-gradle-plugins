package io.technoirlab.device.run.gradle.testrun

import java.security.MessageDigest

/**
 * Returns a stable timing record name for the tests of one build, project, variant, and catalog device.
 *
 * The name joins the sanitized identity, starting with a letter or digit, and appends a hash of the exact values, so that
 * identities that sanitize alike, such as the projects `:feature:login` and `:feature-login`, keep separate records.
 */
internal fun defaultTimingRecordName(buildName: String, projectPath: String, variantName: String, deviceId: String): String {
    val identity = listOf(buildName, projectPath, variantName, deviceId)
    val readableName = identity.map { it.replace(UNSAFE_CHARACTERS, "-").trim('-') }
        .filter { it.isNotEmpty() }
        .joinToString("-")
        .trimStart { !it.isLetterOrDigit() }
        .take(MAX_READABLE_LENGTH)
        .trimEnd('-')
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.joinToString("\u0000").encodeToByteArray())
    val hash = digest.toHexString().take(HASH_LENGTH)
    return if (readableName.isEmpty()) hash else "$readableName-$hash"
}

private const val MAX_READABLE_LENGTH = 150
private const val HASH_LENGTH = 12
private val UNSAFE_CHARACTERS = Regex("[^A-Za-z0-9._-]+")
