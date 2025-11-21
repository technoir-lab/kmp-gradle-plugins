package io.technoirlab.vfsoverlay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VfsOverlayGeneratorTest {
    private val generator = VfsOverlayGenerator()

    @TempDir
    private lateinit var tempDir: File

    @Test
    fun `generate overlay with single mapping`() {
        val outputFile = File(tempDir, "overlay.json")
        val fromDir = File("/path/to/source")
        val toDir = File("/path/to/target")

        generator.generate(outputFile, mapOf(fromDir to toDir))

        assertThat(outputFile)
            .hasContent(
                //language=JSON
                """
                {
                    "version": 0,
                    "roots": [
                        {
                            "name": "/path/to/source",
                            "type": "directory-remap",
                            "external-contents": "/path/to/target"
                        }
                    ]
                }
                """.trimIndent()
            )
    }

    @Test
    fun `generate overlay with multiple mappings`() {
        val outputFile = File(tempDir, "overlay.json")
        val mappings = mapOf(
            File("/first/source") to File("/first/target"),
            File("/second/source") to File("/second/target"),
            File("/third/source") to File("/third/target")
        )

        generator.generate(outputFile, mappings)

        assertThat(outputFile).hasContent(
            //language=JSON
            """
            {
                "version": 0,
                "roots": [
                    {
                        "name": "/first/source",
                        "type": "directory-remap",
                        "external-contents": "/first/target"
                    },
                    {
                        "name": "/second/source",
                        "type": "directory-remap",
                        "external-contents": "/second/target"
                    },
                    {
                        "name": "/third/source",
                        "type": "directory-remap",
                        "external-contents": "/third/target"
                    }
                ]
            }
            """.trimIndent()
        )
    }

    @Test
    fun `generate overlay with no mappings`() {
        val outputFile = File(tempDir, "overlay.json")
        val mappings = emptyMap<File, File>()

        generator.generate(outputFile, mappings)

        assertThat(outputFile)
            .hasContent(
                //language=JSON
                """
                {
                    "version": 0,
                    "roots": []
                }
                """.trimIndent()
            )
    }
}
