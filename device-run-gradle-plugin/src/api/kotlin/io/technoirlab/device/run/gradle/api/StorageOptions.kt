package io.technoirlab.device.run.gradle.api

import org.gradle.api.provider.Property

/**
 * The Cloud Storage bucket that Device Run sessions use.
 *
 * The bucket has fixed paths: the plugin uploads test inputs below `automation/inputs/`, and Device Run writes session outputs below
 * `automation/sessions/` and smart sharding timing records below `automation/smart-sharding/`. The plugin never creates the bucket
 * or changes its permissions.
 */
@DeviceRunDsl
interface StorageOptions {
    /**
     * The required name of an existing bucket, without the `gs://` scheme.
     *
     * The credentials of the build need to upload, list, and download objects, and the Device Run service agent of the project
     * needs to read the inputs, write the outputs, and read and write the timing records.
     */
    val bucket: Property<String>
}
