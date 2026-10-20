package io.technoirlab.device.run.core.testrun.apk

import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.storage.InputUploader
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Uploads the packages of a test run: the test APK and the tested application selected for the device.
 */
internal class TestPackagePreparer(
    private val inputUploader: InputUploader,
) {
    /**
     * Uploads [testApk] and [testedApks] to [bucket].
     *
     * [testedApks] form one package: the files of one application that install together, such as its split APKs, selected for
     * the device from alternatives such as the APKs of different ABIs. Without tested APKs, as for library tests, the test APK
     * instruments itself and no target application is installed.
     */
    fun prepare(bucket: String, testApk: Path, testedApks: List<Path>): TestPackages {
        checkApks(testApk, testedApks)
        val testApkUri = inputUploader.upload(bucket, testApk).uri
        return TestPackages(testApkUri, testedApks.map { inputUploader.upload(bucket, it).uri })
    }

    private fun checkApks(testApk: Path, testedApks: List<Path>) {
        val apks = listOf(testApk) + testedApks
        val missingFiles = apks.filterNot { it.isRegularFile() }
        if (missingFiles.isNotEmpty()) {
            throw DeviceRunException("The APKs to test do not exist: ${missingFiles.joinToString()}.")
        }
        val otherFiles = apks.filterNot { it.extension.equals(APK_EXTENSION, ignoreCase = true) }
        if (otherFiles.isNotEmpty()) {
            throw DeviceRunException("The packages to test must be APK files, but include ${otherFiles.joinToString()}.")
        }
        if (testedApks.size > MAX_PACKAGE_FILES) {
            throw DeviceRunException(
                "The tested application has ${testedApks.size} APK files, more than the Device Run limit of $MAX_PACKAGE_FILES.",
            )
        }
    }

    private companion object {
        private const val APK_EXTENSION = "apk"
        private const val MAX_PACKAGE_FILES = 100
    }
}
