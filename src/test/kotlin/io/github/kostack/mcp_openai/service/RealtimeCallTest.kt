package io.github.kostack.mcp_openai.service

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.RealtimeSidebandHandler
import io.github.kostack.mcp_openai.autoconfiguration.McpOpenAiAutoConfiguration
import io.github.kostack.mcp_openai.autoconfiguration.McpProperties
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.event.RealtimeTokenPreCreateEvent
import io.github.kostack.mcp_openai.tool.ToolDispatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.server.HttpServer
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealtimeCallTest {
  @Test
  fun `unified call posts multipart with server key and attaches sideband without exposing credentials`() {
    val authorization = AtomicReference<String>()
    val contentType = AtomicReference<String>()
    val multipart = AtomicReference<String>()
    val server =
      HttpServer
        .create()
        .host("127.0.0.1")
        .port(0)
        .handle { request, response ->
          authorization.set(request.requestHeaders().get("Authorization"))
          contentType.set(request.requestHeaders().get("Content-Type"))
          request.receive().aggregate().asString().flatMap { body ->
            multipart.set(body)
            response
              .status(201)
              .header("Location", "/v1/realtime/calls/rtc_test")
              .header("Content-Type", "application/sdp")
              .sendString(
                reactor.core.publisher.Mono
                  .just("v=0\r\nanswer\r\n")
              ).then()
          }
        }.bindNow()
    try {
      val properties =
        McpProperties(apiKey = "server-test-key", callsUrl = "http://127.0.0.1:${server.port()}/v1/realtime/calls")
      val sideband = mockk<RealtimeSidebandService>(relaxed = true)
      val dispatcher = mockk<SuspendDispatcher>(relaxed = true)
      val tools = mockk<ToolDispatcher>()
      every { tools.getDefinitions("crm") } returns emptyList()
      coEvery { dispatcher.publishSequential(RealtimeEvents.TOKEN_PRE_CREATE, any()) } coAnswers {
        secondArg<RealtimeTokenPreCreateEvent>().instructions = "Application instructions and history"
      }
      val handler =
        RealtimeSidebandHandler(
          sideband,
          OpenAiHttpService(WebClient.create(), properties),
          dispatcher,
          tools,
          properties
        )
      val client =
        WebTestClient
          .bindToRouterFunction(
            McpOpenAiAutoConfiguration().realtimeSidebandRoutes(handler, properties)
          ).build()
      val result =
        client
          .post()
          .uri("/api/realtime/calls")
          .bodyValue(
            mapOf(
              "sdp" to "v=0\r\noffer\r\n",
              "namespace" to "crm",
              "channel" to "chat-1",
              "language" to "en",
              "audioEnabled" to true
            )
          ).exchange()
          .expectStatus()
          .isOk
          .expectBody()
          .jsonPath("$.callId")
          .isEqualTo("rtc_test")
          .jsonPath("$.sdp")
          .isEqualTo("v=0\r\nanswer\r\n")
          .returnResult()
      assertEquals("Bearer server-test-key", authorization.get())
      assertTrue(contentType.get().startsWith("multipart/form-data;"))
      assertTrue(multipart.get().contains("v=0\r\noffer\r\n"))
      val sessionPart =
        multipart
          .get()
          .substringAfter(
            "name=\"session\""
          ).substringAfter("\r\n\r\n")
          .substringBefore("\r\n--")
      val session = ObjectMapper().readTree(sessionPart)
      assertEquals("realtime", session.path("type").asString())
      assertEquals(properties.model, session.path("model").asString())
      assertEquals("Application instructions and history", session.path("instructions").asString())
      assertFalse(session.has("session"))
      val connect = slot<SidebandConnectRequest>()
      verify(exactly = 1) { sideband.connect(capture(connect)) }
      assertEquals("rtc_test", connect.captured.callId)
      assertEquals("", connect.captured.clientSecret)
      assertEquals("chat-1", connect.captured.channel)
      assertFalse(connect.captured.audioEnabled)
      val json = String(result.responseBody!!)
      assertFalse(json.contains("server-test-key"))
      assertFalse(json.contains("clientSecret"))
    } finally {
      server.disposeNow()
    }
  }
}
