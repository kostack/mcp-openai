package io.github.kostack.mcp_openai.service

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.dto.RealtimeEvent
import io.github.kostack.mcp_openai.dto.RealtimeItem
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.ToolContext
import io.github.kostack.mcp_openai.dto.ToolResult
import io.github.kostack.mcp_openai.event.RealtimeHandlerEvent
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import io.github.kostack.mcp_openai.tool.ToolDispatcher
import io.github.kostack.mcp_openai.utils.RealtimeUtils
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RealtimeEventHandlerTest {
  private val objectMapper = mockk<ObjectMapper>()
  private val toolDispatcher = mockk<ToolDispatcher>()
  private val websocketSessionRegistry = mockk<WebSocketSessionRegistry>()
  private val suspendDispatcher = mockk<SuspendDispatcher>()

  @Test
  fun `handle publishes session updated event with request and namespace`() =
    runTest {
      val event = RealtimeEvent(type = "session.updated")
      val request = request()
      val eventSlot = slot<RealtimeHandlerEvent>()

      coEvery {
        suspendDispatcher.publishSequential(RealtimeEvents.SESSION_UPDATED, capture(eventSlot))
      } just Runs

      handler().handle(event = event, request = request)

      assertSame(event, eventSlot.captured.realtimeEvent)
      assertSame(request, eventSlot.captured.request)
      assertEquals("crm", eventSlot.captured.request.namespace)
      coVerify(exactly = 1) {
        suspendDispatcher.publishSequential(RealtimeEvents.SESSION_UPDATED, any())
      }
    }

  @Test
  fun `handle publishes output item done event without executing function call`() =
    runTest {
      val arguments = """{"accountId":"acct-1"}"""
      val item =
        RealtimeItem(
          type = "function_call",
          name = "lookup_account",
          callId = "tool-call-1",
          arguments = arguments
        )
      val event = RealtimeEvent(type = "response.output_item.done", item = item)
      val request = request()
      val handlerEventSlot = slot<RealtimeHandlerEvent>()

      coEvery {
        suspendDispatcher.publishSequential(
          RealtimeEvents.RESPONSE_OUTPUT_ITEM_DONE,
          capture(handlerEventSlot)
        )
      } just Runs

      handler().handle(event = event, request = request)

      assertSame(event, handlerEventSlot.captured.realtimeEvent)
      assertSame(request, handlerEventSlot.captured.request)
      assertEquals("crm", handlerEventSlot.captured.request.namespace)
      coVerify(exactly = 1) {
        suspendDispatcher.publishSequential(RealtimeEvents.RESPONSE_OUTPUT_ITEM_DONE, any())
      }
      coVerify(exactly = 0) { toolDispatcher.execute(any(), any()) }
    }

  @Test
  fun `handle executes function call arguments done event`() =
    runTest {
      val arguments = """{"accountId":"acct-1"}"""
      val event =
        RealtimeEvent(
          type = "response.function_call_arguments.done",
          name = "lookup_account",
          callId = "tool-call-1",
          arguments = arguments
        )
      val request = request()
      val toolResult = ToolResult(success = true, result = mapOf("status" to "found"))
      val serializedToolResult = """{"success":true,"result":{"status":"found"}}"""
      val contextSlot = slot<ToolContext>()
      val sentMessages = mutableListOf<Any>()
      val expectedMessages: List<Any> =
        listOf(
          RealtimeUtils.conversationFunctionOutput("tool-call-1", serializedToolResult),
          RealtimeUtils.createResponse("text")
        )

      coEvery { toolDispatcher.execute("lookup_account", capture(contextSlot)) } returns toolResult
      every { objectMapper.writeValueAsString(toolResult) } returns serializedToolResult
      coEvery { websocketSessionRegistry.sendJson(request.callId, capture(sentMessages)) } just Runs

      handler().handle(event = event, request = request)

      assertEquals(ToolContext(namespace = "crm", channel = "web", rawRequest = arguments), contextSlot.captured)
      assertEquals(expectedMessages, sentMessages)
      coVerifyOrder {
        toolDispatcher.execute("lookup_account", any())
        websocketSessionRegistry.sendJson(request.callId, sentMessages[0])
        websocketSessionRegistry.sendJson(request.callId, sentMessages[1])
      }
    }

  @Test
  fun `handle publishes output item done event when item is missing`() =
    runTest {
      val event = RealtimeEvent(type = "response.output_item.done")
      val request = request()
      val eventSlot = slot<RealtimeHandlerEvent>()

      coEvery {
        suspendDispatcher.publishSequential(RealtimeEvents.RESPONSE_OUTPUT_ITEM_DONE, capture(eventSlot))
      } just Runs

      handler().handle(event = event, request = request)

      assertSame(event, eventSlot.captured.realtimeEvent)
      assertSame(request, eventSlot.captured.request)
      assertEquals("crm", eventSlot.captured.request.namespace)
      coVerify(exactly = 1) {
        suspendDispatcher.publishSequential(RealtimeEvents.RESPONSE_OUTPUT_ITEM_DONE, any())
      }
      coVerify(exactly = 0) { toolDispatcher.execute(any(), any()) }
    }

  @Test
  fun `handle uses current request modality when creating follow up response`() =
    runTest {
      val arguments = """{"accountId":"acct-1"}"""
      val event =
        RealtimeEvent(
          type = "response.function_call_arguments.done",
          name = "lookup_account",
          callId = "tool-call-1",
          arguments = arguments
        )
      val request = request(audioEnabled = true)
      val toolResult = ToolResult(success = true, result = mapOf("status" to "found"))
      val serializedToolResult = """{"success":true,"result":{"status":"found"}}"""
      val sentMessages = mutableListOf<Any>()

      request.audioEnabled = false
      coEvery { toolDispatcher.execute("lookup_account", any()) } returns toolResult
      every { objectMapper.writeValueAsString(toolResult) } returns serializedToolResult
      coEvery { websocketSessionRegistry.sendJson(request.callId, capture(sentMessages)) } just Runs

      handler().handle(event = event, request = request)

      assertEquals(RealtimeUtils.createResponse("text"), sentMessages[1])
    }

  @Test
  fun `handle publishes error event`() =
    runTest {
      val event = RealtimeEvent(type = "error")
      val request = request()
      val eventSlot = slot<RealtimeHandlerEvent>()

      coEvery {
        suspendDispatcher.publishSequential(RealtimeEvents.RESPONSE_ERROR, capture(eventSlot))
      } just Runs

      handler().handle(event = event, request = request)

      assertSame(event, eventSlot.captured.realtimeEvent)
      assertSame(request, eventSlot.captured.request)
      assertEquals("crm", eventSlot.captured.request.namespace)
      coVerify(exactly = 1) {
        suspendDispatcher.publishSequential(RealtimeEvents.RESPONSE_ERROR, any())
      }
    }

  private fun handler(): RealtimeEventHandler =
    RealtimeEventHandler(
      objectMapper = objectMapper,
      toolDispatcher = toolDispatcher,
      websocketSessionRegistry = websocketSessionRegistry,
      suspendDispatcher = suspendDispatcher
    )

  private fun request(audioEnabled: Boolean = false): SidebandConnectRequest =
    SidebandConnectRequest(
      callId = "call-123",
      clientSecret = "client-secret",
      namespace = "crm",
      channel = "web",
      language = "en",
      audioEnabled = audioEnabled
    )
}
