package io.technoirlab.device.run.gradle

import org.gradle.api.problems.Problem

/**
 * A problem recorded by [RecordingProblems].
 *
 * @property id the group and name of the problem ID, as Gradle prints them: `<group>:<name>`.
 */
@Suppress("UnstableApiUsage")
internal data class RecordedProblem(
    val id: String,
    val contextualLabel: String?,
    val solutions: List<String> = emptyList(),
) : Problem
