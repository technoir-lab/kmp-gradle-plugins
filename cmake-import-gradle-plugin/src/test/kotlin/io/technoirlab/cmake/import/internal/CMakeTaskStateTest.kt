package io.technoirlab.cmake.import.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class CMakeTaskStateTest {
    private val stateManager = CMakeTaskStateManager()

    @TempDir
    private lateinit var temporaryDirectory: Path

    @Test
    fun `reset identity is independent of definition insertion order`() {
        val toolchain = temporaryDirectory.resolve("toolchain.cmake").apply { writeText("toolchain") }

        val first = resetIdentity(toolchain, definitions = linkedMapOf("B" to "2", "A" to "1"))
        val second = resetIdentity(toolchain, definitions = linkedMapOf("A" to "1", "B" to "2"))

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `reset identity captures configuration values paths and toolchain contents`() {
        val toolchain = temporaryDirectory.resolve("toolchain.cmake").apply { writeText("first") }
        val baseline = resetIdentity(toolchain)

        assertThat(
            listOf(
                resetIdentity(toolchain, generator = "Ninja"),
                resetIdentity(toolchain, buildType = "Debug"),
                resetIdentity(toolchain, definitions = mapOf("FOO" to "bar")),
                resetIdentity(toolchain, sourceDirectory = temporaryDirectory.resolve("other-source")),
                resetIdentity(toolchain, configureDirectory = temporaryDirectory.resolve("other-configure")),
                resetIdentity(temporaryDirectory.resolve("other-toolchain.cmake").apply { writeText("first") }),
                resetIdentity(toolchain.apply { writeText("second") }),
            ),
        ).allSatisfy { identity -> assertThat(identity).isNotEqualTo(baseline) }
    }

    @Test
    fun `generate state round trips without exposing configuration paths`() {
        val stateFile = temporaryDirectory.resolve("state/generate.state")
        val resetIdentity = resetIdentity(
            temporaryDirectory.resolve("toolchain.cmake").apply { writeText("toolchain") },
        )
        val state = stateManager.create(resetIdentity)

        stateManager.write(stateFile, state)

        assertThat(stateManager.readOrNull(stateFile)).isEqualTo(state)
        assertThat(stateFile)
            .content()
            .contains("\"version\": 1")
            .contains("\"resetIdentity\": \"$resetIdentity\"")
            .doesNotContain(temporaryDirectory.toString())
    }

    @Test
    fun `common state represents Build executions and changes for every successful execution`() {
        val first = stateManager.create()
        val second = stateManager.create()

        assertThat(first.version).isEqualTo(1)
        assertThat(first.resetIdentity).isNull()
        assertThat(first.executionId).isNotEqualTo(second.executionId)
    }

    @Test
    fun `invalid state is ignored`() {
        val stateFile = temporaryDirectory.resolve("generate.state").apply {
            writeText("""{"version":2,"resetIdentity":"value","executionId":"value"}""")
        }

        assertThat(stateManager.readOrNull(stateFile)).isNull()
    }

    private fun resetIdentity(
        toolchainFile: Path,
        generator: String? = null,
        buildType: String = "Release",
        definitions: Map<String, String> = emptyMap(),
        sourceDirectory: Path = temporaryDirectory.resolve("source").createDirectories(),
        configureDirectory: Path = temporaryDirectory.resolve("configure").createDirectories(),
    ): String = stateManager.calculateResetIdentity(
        generator = generator,
        buildType = buildType,
        definitions = definitions,
        sourceDirectory = sourceDirectory,
        configureDirectory = configureDirectory,
        toolchainFile = toolchainFile,
    )
}
