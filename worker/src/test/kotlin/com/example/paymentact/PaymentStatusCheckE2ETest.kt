package com.example.paymentact

import com.example.paymentact.model.CheckStatusQueryResponse
import com.example.paymentact.model.CheckStatusStartResponse
import com.example.paymentact.model.WorkflowStatus
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.client.expectBody
import org.awaitility.kotlin.await
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.temporal.test-server.enabled=true"]
)
@AutoConfigureRestTestClient
class PaymentStatusCheckE2ETest {

    companion object {
        private val wireMock = WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort()).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("external-services.elasticsearch.url") { wireMock.baseUrl() + "/elasticsearch" }
            registry.add("external-services.idb-facade.url") { wireMock.baseUrl() + "/idb-facade" }
            registry.add("external-services.pgi-gateway.url") { wireMock.baseUrl() + "/pgi-gateway" }
        }
    }

    @Autowired
    lateinit var client: RestTestClient

    @Test
    fun `should process payments through all gateways successfully`() {
        stubElasticsearch("PAY-001", "gateway-A")
        stubElasticsearch("PAY-002", "gateway-A")
        stubElasticsearch("PAY-003", "gateway-B")

        wireMock.stubFor(
            post(urlPathEqualTo("/idb-facade/api/v1/payments/notify"))
                .willReturn(aResponse().withStatus(200))
        )

        wireMock.stubFor(
            post(urlPathEqualTo("/pgi-gateway/api/v1/payments/PAY-001/check-status"))
                .willReturn(aResponse().withStatus(200))
        )
        wireMock.stubFor(
            post(urlPathEqualTo("/pgi-gateway/api/v1/payments/PAY-002/check-status"))
                .willReturn(aResponse().withStatus(200))
        )
        wireMock.stubFor(
            post(urlPathEqualTo("/pgi-gateway/api/v1/payments/PAY-003/check-status"))
                .willReturn(aResponse().withStatus(200))
        )

        val startResponse = client.post()
            .uri("/payments/check-status")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"paymentIds": ["PAY-001", "PAY-002", "PAY-003"]}""")
            .exchange()
            .expectStatus().isAccepted()
            .expectBody<CheckStatusStartResponse>()
            .returnResult()
            .responseBody!!

        val workflowId = startResponse.workflowId
        assertNotNull(workflowId)

        val result = pollUntilCompleted(workflowId)

        assertEquals(WorkflowStatus.COMPLETED, result.status)
        assertNotNull(result.result)

        val successful = result.result.successful
        assertTrue(successful.containsKey("gateway-A"))
        assertTrue(successful.containsKey("gateway-B"))
        assertTrue(successful["gateway-A"]!!.containsAll(listOf("PAY-001", "PAY-002")))
        assertTrue(successful["gateway-B"]!!.contains("PAY-003"))

        assertTrue(result.result.failed.isEmpty())
        assertTrue(result.result.gatewayLookupFailed.isEmpty())
    }

    private fun stubElasticsearch(paymentId: String, gatewayName: String) {
        wireMock.stubFor(
            get(urlPathEqualTo("/elasticsearch/payments/_doc/$paymentId"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                                "_id": "$paymentId",
                                "_source": {
                                    "paymentId": "$paymentId",
                                    "gatewayName": "$gatewayName"
                                }
                            }
                        """.trimIndent()
                        )
                )
        )
    }

    private fun pollUntilCompleted(workflowId: String): CheckStatusQueryResponse {
        lateinit var result: CheckStatusQueryResponse
        await.atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted {
                result = client.get()
                    .uri("/payments/check-status/$workflowId")
                    .exchange()
                    .expectBody<CheckStatusQueryResponse>()
                    .returnResult()
                    .responseBody!!
                assertTrue(result.status == WorkflowStatus.COMPLETED || result.status == WorkflowStatus.FAILED)
            }
        return result
    }
}