package io.technoirlab.device.run.core.client

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import io.technoirlab.device.run.core.DeviceRunException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.util.Date

class ExecutionProjectTest {
    private val serviceAccount = ServiceAccountCredentials.newBuilder()
        .setClientEmail("runner@credentials-project.iam.gserviceaccount.com")
        .setPrivateKey(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private)
        .setProjectId("credentials-project")
        .build()

    @Test
    fun `prefers the quota project to the project of the credentials`() {
        val credentials = serviceAccount.createWithQuotaProject("quota-project")

        val projectId = credentials.executionProjectId()

        assertThat(projectId).isEqualTo("quota-project")
    }

    @Test
    fun `falls back to the project of the credentials`() {
        val projectId = serviceAccount.executionProjectId()

        assertThat(projectId).isEqualTo("credentials-project")
    }

    @Test
    fun `explains how to configure a project when the credentials name none`() {
        val credentials = GoogleCredentials.create(AccessToken("test-token", Date(Long.MAX_VALUE))).createWithQuotaProject(" ")

        assertThatThrownBy { credentials.executionProjectId() }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageContaining("gcloud auth application-default set-quota-project PROJECT_ID")
            .hasMessageContaining("GOOGLE_CLOUD_QUOTA_PROJECT")
    }
}
