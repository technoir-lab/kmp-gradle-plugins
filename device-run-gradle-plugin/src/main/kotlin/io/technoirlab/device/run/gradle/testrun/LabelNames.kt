package io.technoirlab.device.run.gradle.testrun

/**
 * The names of the values that identify the tests of a test task: the labels of their Device Run job and the properties of their
 * JUnit report, which AGP reads for its HTML test report.
 *
 * Jobs are labeled with [PROJECT], [VARIANT], and [DEVICE]; reports have the properties [DEVICE], [FLAVOR], and [PROJECT], where
 * [FLAVOR] holds the name of the tested variant like [VARIANT].
 */
internal object LabelNames {
    const val PROJECT = "project"
    const val VARIANT = "variant"
    const val FLAVOR = "flavor"
    const val DEVICE = "device"
}
