package io.technoirlab.device.run.gradle.api

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.ListProperty

/**
 * Files pushed to the device before the tests and paths pulled from it afterwards.
 */
@DeviceRunDsl
interface FileOptions {
    /**
     * Absolute paths of files and directories pulled from the device after the tests, at most 10.
     *
     * Pulled files are stored with the other session outputs below `device-run/` in the test results directory.
     */
    val pathsToPull: ListProperty<String>

    /**
     * Files pushed to the device before the tests run, at most 50.
     */
    val push: NamedDomainObjectContainer<PushedFile>
}
