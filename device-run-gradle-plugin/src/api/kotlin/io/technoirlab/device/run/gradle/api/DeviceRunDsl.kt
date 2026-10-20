package io.technoirlab.device.run.gradle.api

/**
 * Marks the Device Run configuration DSL.
 */
@DslMarker
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
internal annotation class DeviceRunDsl
