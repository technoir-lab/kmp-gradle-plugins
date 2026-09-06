package io.technoirlab.kotlin.native.utils

import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class KotlinNativeLayoutTest {
    private val project = ProjectBuilder.builder().build()
    private val providers = StubProviderFactory(project.providers)
    private val nativeLayout = KotlinNativeLayout(providers, project.layout)

    @Test
    fun `Gradle property takes precedence over environment and user home`() {
        providers.gradleProperties["konan.data.dir"] = "property-data"
        providers.environmentVariables["KONAN_DATA_DIR"] = "environment-data"
        providers.systemProperties["user.home"] = "home"

        val directory = nativeLayout.dependenciesDirectory.get()

        assertThat(directory).isEqualTo(project.layout.projectDirectory.dir("property-data/dependencies"))
    }

    @Test
    fun `environment takes precedence over user home`() {
        providers.environmentVariables["KONAN_DATA_DIR"] = "environment-data"
        providers.systemProperties["user.home"] = "home"

        val directory = nativeLayout.dependenciesDirectory.get()

        assertThat(directory).isEqualTo(project.layout.projectDirectory.dir("environment-data/dependencies"))
    }

    @Test
    fun `defaults to konan directory under user home`() {
        providers.systemProperties["user.home"] = project.layout.projectDirectory.dir("home").asFile.path

        val dataDirectory = nativeLayout.dataDirectory.get()
        val dependenciesDirectory = nativeLayout.dependenciesDirectory.get()

        assertThat(dataDirectory).isEqualTo(project.layout.projectDirectory.dir("home/.konan"))
        assertThat(dependenciesDirectory).isEqualTo(project.layout.projectDirectory.dir("home/.konan/dependencies"))
    }

    @Test
    fun `resolves relative overrides against the consuming project`() {
        providers.gradleProperties["konan.data.dir"] = "local-data"

        val directory = nativeLayout.dataDirectory.get()

        assertThat(directory).isEqualTo(project.layout.projectDirectory.dir("local-data"))
        assertThat(directory.asFile).doesNotExist()
    }

    @Test
    fun `preserves absolute overrides outside the project`() {
        val expectedDirectory = project.layout.projectDirectory.dir("../shared-data")
        providers.gradleProperties["konan.data.dir"] = expectedDirectory.asFile.path

        val directory = nativeLayout.dataDirectory.get()

        assertThat(directory).isEqualTo(expectedDirectory)
    }

    @Test
    fun `reads changed configuration lazily`() {
        providers.gradleProperties["konan.data.dir"] = "first-data"
        val dependenciesDirectory = nativeLayout.dependenciesDirectory
        providers.gradleProperties["konan.data.dir"] = "second-data"

        val directory = dependenciesDirectory.get()

        assertThat(directory).isEqualTo(project.layout.projectDirectory.dir("second-data/dependencies"))
    }

    private class StubProviderFactory(
        private val delegate: ProviderFactory,
    ) : ProviderFactory by delegate {
        val gradleProperties = mutableMapOf<String, String>()
        val environmentVariables = mutableMapOf<String, String>()
        val systemProperties = mutableMapOf<String, String>()

        override fun gradleProperty(propertyName: String): Provider<String> = delegate.provider { gradleProperties[propertyName] }

        override fun environmentVariable(variableName: String): Provider<String> = delegate.provider { environmentVariables[variableName] }

        override fun systemProperty(propertyName: String): Provider<String> = delegate.provider { systemProperties[propertyName] }
    }
}
