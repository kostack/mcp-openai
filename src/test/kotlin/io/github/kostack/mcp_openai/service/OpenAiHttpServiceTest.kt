package io.github.kostack.mcp_openai.service

import io.github.kostack.mcp_openai.autoconfiguration.McpProperties
import kotlinx.coroutines.test.runTest
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiHttpServiceTest {
  @Test
  fun `disconnect posts hangup with server key and accepts empty response`() =
    runTest {
      var requests = 0
      val client =
        WebClient
          .builder()
          .exchangeFunction { request ->
            requests++
            assertEquals(HttpMethod.POST, request.method())
            assertEquals("https://example.test/v1/realtime/calls/rtc%2Ftest/hangup", request.url().toString())
            assertEquals("Bearer server-key", request.headers().getFirst("Authorization"))
            Mono.just(ClientResponse.create(HttpStatus.OK).build())
          }.build()
      val service =
        OpenAiHttpService(
          client,
          McpProperties(apiKey = "server-key", callsUrl = "https://example.test/v1/realtime/calls/")
        )

      service.disconnect("rtc/test")

      assertEquals(1, requests)
    }

  @Test
  fun `disconnect propagates hangup failure`() =
    runTest {
      val client =
        WebClient
          .builder()
          .exchangeFunction {
            Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build())
          }.build()

      assertFailsWith<WebClientResponseException.NotFound> {
        OpenAiHttpService(client, McpProperties(apiKey = "server-key")).disconnect("rtc_missing")
      }
    }
}
