package io.github.kostack.mcp_openai

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.autoconfiguration.McpOpenAiAutoConfiguration
import io.github.kostack.mcp_openai.autoconfiguration.McpProperties
import io.github.kostack.mcp_openai.dto.RealtimeCallRequest
import io.github.kostack.mcp_openai.dto.RealtimeCallResponse
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest
import io.github.kostack.mcp_openai.event.RealtimeConnectEvent
import io.github.kostack.mcp_openai.event.RealtimeDisconnectEvent
import io.github.kostack.mcp_openai.event.RealtimePreConnectEvent
import io.github.kostack.mcp_openai.service.OpenAiHttpService
import io.github.kostack.mcp_openai.service.RealtimeSidebandService
import io.github.kostack.mcp_openai.tool.ToolDispatcher
import io.mockk.Called
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RealtimeSidebandHandlerTest {
  private val sideband = mockk<RealtimeSidebandService>()
  private val http = mockk<OpenAiHttpService>()
  private val events = mockk<SuspendDispatcher>()
  private val tools = mockk<ToolDispatcher>()
  private val request = RealtimeCallRequest("offer", "crm", "chat-1", "en", true)
  private val call = RealtimeCallResponse("answer", "call-1")

  @Test
  fun `call applies listener instructions and publishes connect before attaching sideband`() =
    runTest {
      val connection = slot<SidebandConnectRequest>()
      val connectEvent = slot<RealtimeConnectEvent>()
      coEvery { events.publishSequential(RealtimeEvents.PRE_CONNECT, any()) } coAnswers {
        val event = secondArg<RealtimePreConnectEvent>()
        assertEquals("crm", event.request.namespace)
        assertEquals("chat-1", event.request.channel)
        assertEquals("en", event.request.language)
        event.instructions = "Custom instructions"
      }
      every { tools.getDefinitions("crm") } returns emptyList()
      coEvery { http.createCall("offer", "Custom instructions", "en", emptyList()) } returns call
      coEvery { events.publishSequential(RealtimeEvents.CONNECT, capture(connectEvent)) } just Runs
      every { sideband.connect(capture(connection)) } just Runs

      val response = handler(McpProperties(enableAudio = true)).createCall(request)

      assertEquals(200, response.statusCode().value())
      assertEquals(
        SidebandConnectRequest("call-1", namespace = "crm", channel = "chat-1", language = "en", audioEnabled = true),
        connection.captured
      )
      assertSame(connectEvent.captured.request, connection.captured)
      coVerifyOrder {
        events.publishSequential(RealtimeEvents.PRE_CONNECT, any())
        tools.getDefinitions("crm")
        http.createCall("offer", "Custom instructions", "en", emptyList())
        events.publishSequential(RealtimeEvents.CONNECT, any())
        sideband.connect(any())
      }
    }

  @Test
  fun `audio uses listener override subject to global setting`() =
    runTest {
      for (globalAudio in listOf(false, true)) {
        for (requestedAudio in listOf(false, true)) {
          for (listenerAudio in listOf(false, true)) {
            coEvery { events.publishSequential(RealtimeEvents.PRE_CONNECT, any()) } coAnswers {
              val settings = secondArg<RealtimePreConnectEvent>().request
              assertEquals(globalAudio && requestedAudio, settings.audioEnabled)
              settings.audioEnabled = listenerAudio
            }
            every { tools.getDefinitions("crm") } returns emptyList()
            coEvery { http.createCall(any(), any(), any(), any()) } returns call
            coEvery { events.publishSequential(RealtimeEvents.CONNECT, any()) } just Runs
            val connection = slot<SidebandConnectRequest>()
            every { sideband.connect(capture(connection)) } just Runs

            handler(McpProperties(enableAudio = globalAudio)).createCall(request.copy(audioEnabled = requestedAudio))

            assertEquals(globalAudio && listenerAudio, connection.captured.audioEnabled)
          }
        }
      }
    }

  @Test
  fun `pre connect failure prevents creating call`() =
    runTest {
      val failure = IllegalStateException("listener failed")
      coEvery { events.publishSequential(RealtimeEvents.PRE_CONNECT, any()) } throws failure

      assertSame(failure, assertFailsWith<IllegalStateException> { handler().createCall(request) })

      verify { http wasNot Called }
      verify { sideband wasNot Called }
      verify { tools wasNot Called }
    }

  @Test
  fun `call creation failure does not publish connect or attach sideband`() =
    runTest {
      val failure = IllegalStateException("call failed")
      coEvery { events.publishSequential(RealtimeEvents.PRE_CONNECT, any()) } just Runs
      every { tools.getDefinitions("crm") } returns emptyList()
      coEvery { http.createCall(any(), any(), any(), any()) } throws failure

      assertSame(failure, assertFailsWith<IllegalStateException> { handler().createCall(request) })

      coVerify(exactly = 0) { events.publishSequential(RealtimeEvents.CONNECT, any()) }
      verify { sideband wasNot Called }
    }

  @Test
  fun `disconnect publishes event before disconnecting and returns no content`() =
    runTest {
      val request = SidebandDisconnectRequest("call-1", "crm", "chat-1")
      val event = slot<RealtimeDisconnectEvent>()
      coEvery { events.publishSequential(RealtimeEvents.DISCONNECT, capture(event)) } just Runs
      coEvery { sideband.disconnect(request) } just Runs

      val response = handler().disconnect(request)

      assertEquals(204, response.statusCode().value())
      assertSame(request, event.captured.request)
      coVerifyOrder {
        events.publishSequential(RealtimeEvents.DISCONNECT, any())
        sideband.disconnect(request)
      }
    }

  @Test
  fun `legacy token and connect routes are no longer available`() {
    val properties = McpProperties()
    val client =
      WebTestClient
        .bindToRouterFunction(
          McpOpenAiAutoConfiguration().realtimeSidebandRoutes(handler(properties), properties)
        ).build()

    for (path in listOf("token", "connect")) {
      client
        .post()
        .uri("${properties.sidebandPrefix}/$path")
        .exchange()
        .expectStatus()
        .isNotFound
    }
    verify { http wasNot Called }
    verify { sideband wasNot Called }
  }

  private fun handler(properties: McpProperties = McpProperties()) =
    RealtimeSidebandHandler(sideband, http, events, tools, properties)
}
