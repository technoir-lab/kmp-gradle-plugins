package io.technoirlab.device.run.gradle.api

import org.gradle.api.Named
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * A file pushed to the device before the tests run, from exactly one source: a Cloud Storage object or a local file.
 */
@DeviceRunDsl
interface PushedFile : Named {
    /**
     * An existing Cloud Storage object, such as `gs://my-bucket/fixtures/data.json`.
     */
    val gcsUri: Property<String>

    /**
     * A local file, which is uploaded to the bucket. A file produced by a task makes the upload depend on that task.
     */
    val localFile: RegularFileProperty

    /**
     * The required absolute path of the file on the device, such as `/sdcard/data.json`.
     */
    val destinationPath: Property<String>
}
