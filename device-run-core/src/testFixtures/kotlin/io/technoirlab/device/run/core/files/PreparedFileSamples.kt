package io.technoirlab.device.run.core.files

import io.technoirlab.device.run.core.storage.GcsUri

/**
 * A Cloud Storage object pushed to the device as `/sdcard/test-output/probe-input.txt`.
 */
val PROBE_PUSHED_FILE = PreparedFile(
    name = "probe",
    uri = GcsUri("example-bucket", "fixtures/probe-input.txt"),
    checksum = "md5:Z9ylmxPJRgMSk3sbLg4LOg==",
    destinationPath = "/sdcard/test-output/probe-input.txt",
)
