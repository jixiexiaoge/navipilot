package com.navipilot.amap2tesla.membership

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ProjectionMembershipTransportTest {
    @Test
    fun `membership transport does not follow redirects`() {
        val client = projectionMembershipHttpClient()

        assertThat(client.followRedirects).isFalse()
        assertThat(client.followSslRedirects).isFalse()
    }

    @Test
    fun `membership endpoint accepts HTTPS URLs`() {
        assertThat(projectionMembershipUrl("https://membership.example", "/api/v1/plans"))
            .isEqualTo("https://membership.example/api/v1/plans".toHttpUrl())
    }

    @Test
    fun `membership endpoint rejects cleartext URLs`() {
        assertThrows(IOException::class.java) {
            projectionMembershipUrl("http://membership.example", "/api/v1/plans")
        }
    }
}
