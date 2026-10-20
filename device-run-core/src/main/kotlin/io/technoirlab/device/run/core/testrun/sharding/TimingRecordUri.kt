package io.technoirlab.device.run.core.testrun.sharding

import io.technoirlab.device.run.core.storage.GcsUri

/**
 * Returns the smart sharding timing record named [recordName] in [bucket], `automation/smart-sharding/<record name>.yaml`.
 *
 * Device Run reads a record to plan shards and, when the job finishes, rewrites it with the new test durations added to the earlier
 * ones; it creates a missing record.
 */
internal fun timingRecordUri(bucket: String, recordName: String): GcsUri = GcsUri(bucket, "$RECORDS_DIRECTORY/$recordName.yaml")

private const val RECORDS_DIRECTORY = "automation/smart-sharding"
