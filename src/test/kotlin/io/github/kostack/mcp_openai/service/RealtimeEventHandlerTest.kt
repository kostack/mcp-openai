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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tools.jackson.databind.ObjectMapper
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeEventHandlerTest {
  private val handlers = mutableListOf<RealtimeEventHandler>()

  @AfterTest
  fun tearDown() {
    handlers.forEach { it.destroy() }
  }

  private val objectMapper = mockk<ObjectMapper>()
  private val toolDispatcher = mockk<ToolDispatcher>()
  private val websocketSessionRegistry = mockk<WebSocketSessionRegistry>()
  private val suspendDispatcher = mockk<SuspendDispatcher>()

  @Test
  fun `handle inbound returns while function call continues in tool scope`() =
    runTest {
      val event =
        RealtimeEvent(
          type = "response.function_call_arguments.done",
          name = "lookup_account",
          callId = "tool-call-1"
        )
      val request = request()
      val toolStarted = CompletableDeferred<Unit>()
      val toolCancelled = CompletableDeferred<Unit>()

      coEvery { toolDispatcher.execute("lookup_account", any()) } coAnswers {
        toolStarted.complete(Unit)
        try {
          awaitCancellation()
        } finally {
          toolCancelled.complete(Unit)
        }
      }

      val handler = handler()
      handler.handleInbound(event, request)

      runCurrent()
      toolStarted.await()
      coVerify(exactly = 1) { toolDispatcher.execute("lookup_account", any()) }
      coVerify(exactly = 0) { websocketSessionRegistry.sendJson(any(), any()) }

      handler.cancel(request.callId)
      runCurrent()
      toolCancelled.await()
    }

  @Test
  fun `handle publishes session updated event with request and namespace`() =
    runTest {
      val event = RealtimeEvent(type = "session.updated")
      val request = request()
      val eventSlot = slot<RealtimeHandlerEvent>()

      coEvery {
        suspendDispatcher.publishSequential(RealtimeEvents.REALTIME_EVENT, capture(eventSlot))
      } just Runs

      handler().handleInbound(event = event, request = request)
      runCurrent()

      assertSame(event, eventSlot.captured.realtimeEvent)
      assertSame(request, eventSlot.captured.request)
      assertEquals("crm", eventSlot.captured.request.namespace)
      coVerify(exactly = 1) {
        suspendDispatcher.publishSequential(RealtimeEvents.REALTIME_EVENT, any())
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
          RealtimeEvents.REALTIME_EVENT,
          capture(handlerEventSlot)
        )
      } just Runs

      handler().handleInbound(event = event, request = request)
      runCurrent()

      assertSame(event, handlerEventSlot.captured.realtimeEvent)
      assertSame(request, handlerEventSlot.captured.request)
      assertEquals("crm", handlerEventSlot.captured.request.namespace)
      coVerify(exactly = 1) {
        suspendDispatcher.publishSequential(RealtimeEvents.REALTIME_EVENT, any())
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
      val toolResult = ToolResult(result = mapOf("status" to "found"))
      val serializedToolResult = """{"result":{"status":"found"},"mode":"REALTIME"}"""
      val contextSlot = slot<ToolContext>()
      val sentMessages = mutableListOf<Any>()
      val expectedMessages: List<Any> =
        listOf(
          RealtimeUtils.conversationFunctionOutput("tool-call-1", serializedToolResult),
          RealtimeUtils.createResponse("text")
        )

      coEvery { toolDispatcher.execute("lookup_account", capture(contextSlot)) } returns toolResult
      every { objectMapper.writeValueAsString(toolResult) } returns serializedToolResult
      coEvery { websocketSessionRegistry.sendJson(request.callId, capture(sentMessages)) } returns true

      handler().handleInbound(event = event, request = request)
      runCurrent()

      assertEquals(
        ToolContext(
          namespace = "crm",
          channel = "web",
          sessionId = "call-123",
          toolCallId = "tool-call-1",
          rawRequest = arguments
        ),
        contextSlot.captured
      )
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
        suspendDispatcher.publishSequential(RealtimeEvents.REALTIME_EVENT, capture(eventSlot))
      } just Runs

      handler().handleInbound(event = event, request = request)
      runCurrent()

      assertSame(event, eventSlot.captured.realtimeEvent)
      assertSame(request, eventSlot.captured.request)
      assertEquals("crm", eventSlot.captured.request.namespace)
      coVerify(exactly = 1) {
        suspendDispatcher.publishSequential(RealtimeEvents.REALTIME_EVENT, any())
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
      val toolResult = ToolResult(result = mapOf("status" to "found"))
      val serializedToolResult = """{"result":{"status":"found"},"mode":"REALTIME"}"""
      val sentMessages = mutableListOf<Any>()

      request.audioEnabled = false
      coEvery { toolDispatcher.execute("lookup_account", any()) } returns toolResult
      every { objectMapper.writeValueAsString(toolResult) } returns serializedToolResult
      coEvery { websocketSessionRegistry.sendJson(request.callId, capture(sentMessages)) } returns true

      handler().handleInbound(event = event, request = request)
      runCurrent()

      assertEquals(RealtimeUtils.createResponse("text"), sentMessages[1])
    }

  @Test
  fun `handle skips response create when function output cannot be sent`() =
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
      val toolResult = ToolResult(result = mapOf("status" to "found"))
      val serializedToolResult = """{"result":{"status":"found"},"mode":"REALTIME"}"""
      val expectedOutput =
        RealtimeUtils.conversationFunctionOutput("tool-call-1", serializedToolResult)

      coEvery { toolDispatcher.execute("lookup_account", any()) } returns toolResult
      every { objectMapper.writeValueAsString(toolResult) } returns serializedToolResult
      coEvery { websocketSessionRegistry.sendJson(request.callId, expectedOutput) } returns false

      handler().handleInbound(event = event, request = request)
      runCurrent()

      coVerify(exactly = 1) { websocketSessionRegistry.sendJson(request.callId, expectedOutput) }
      coVerify(exactly = 0) {
        websocketSessionRegistry.sendJson(request.callId, RealtimeUtils.createResponse("text"))
      }
    }

  @Test
  fun `handle publishes error event`() =
    runTest {
      val event = RealtimeEvent(type = "error")
      val request = request()
      val eventSlot = slot<RealtimeHandlerEvent>()

      coEvery {
        suspendDispatcher.publishSequential(RealtimeEvents.REALTIME_EVENT, capture(eventSlot))
      } just Runs

      handler().handleInbound(event = event, request = request)
      runCurrent()

      assertSame(event, eventSlot.captured.realtimeEvent)
      assertSame(request, eventSlot.captured.request)
      assertEquals("crm", eventSlot.captured.request.namespace)
      coVerify(exactly = 1) {
        suspendDispatcher.publishSequential(RealtimeEvents.REALTIME_EVENT, any())
      }
    }

  @Test
  fun `direct results acknowledge delivery with result without requesting response`() =
    runTest {
      val contextSlot = slot<ToolContext>()
      val sentMessages = mutableListOf<Any>()
      val acknowledgementPayload =
        mapOf("status" to "delivered_to_client", "result" to "Direct result")
      val serializedAcknowledgement =
        """{"status":"delivered_to_client","result":"Direct result"}"""
      val acknowledgement =
        RealtimeUtils.conversationFunctionOutput(
          "tool-call-1",
          serializedAcknowledgement
        )
      every { objectMapper.writeValueAsString(acknowledgementPayload) } returns serializedAcknowledgement
      every { websocketSessionRegistry.sendJson("call-123", capture(sentMessages)) } returns true
      for (audioEnabled in listOf(true, false)) {
        val result = ToolResult("Direct result", ToolResult.ToolResultMode.DIRECT)
        coEvery { toolDispatcher.execute("lookup_account", capture(contextSlot)) } returns result
        val handler = handler()
        try {
          handler.handleInbound(
            RealtimeEvent(
              type = "response.function_call_arguments.done",
              name = "lookup_account",
              callId = "tool-call-1"
            ),
            request(audioEnabled)
          )
          runCurrent()
        } finally {
          handler.destroy()
        }
        assertEquals(
          ToolContext("crm", "web", "call-123", "tool-call-1", "{}"),
          contextSlot.captured
        )
      }
      coVerify(exactly = 2) { toolDispatcher.execute("lookup_account", any()) }
      assertEquals(List<Any>(2) { acknowledgement }, sentMessages)
      verify(exactly = 2) { objectMapper.writeValueAsString(acknowledgementPayload) }
      verify(exactly = 2) { websocketSessionRegistry.sendJson("call-123", acknowledgement) }
      verify { suspendDispatcher wasNot Called }
    }

  @Test
  fun `all non tool events publish on the shared topic`() =
    runTest {
      val events =
        listOf(
          "session.updated",
          "response.created",
          "response.done",
          "conversation.item.done",
          "conversation.item.input_audio_transcription.completed",
          "response.output_item.done",
          "error",
          "custom.event"
        ).map { RealtimeEvent(type = it) }
      val published = mutableListOf<RealtimeHandlerEvent>()
      val request = request()
      coEvery {
        suspendDispatcher.publishSequential(RealtimeEvents.REALTIME_EVENT, capture(published))
      } just Runs
      val handler = handler()

      events.forEach { handler.handleInbound(it, request) }

      assertEquals(events, published.map { it.realtimeEvent })
      published.forEach { assertSame(request, it.request) }
      coVerify(exactly = 0) { toolDispatcher.execute(any(), any()) }
    }

  @Test
  fun `malformed tool calls do not execute or publish`() =
    runTest {
      val handler = handler()
      handler.handleInbound(
        RealtimeEvent(type = "response.function_call_arguments.done", name = "lookup_account"),
        request()
      )
      handler.handleInbound(
        RealtimeEvent(type = "response.function_call_arguments.done", callId = "tool-call-1"),
        request()
      )
      runCurrent()

      verify { toolDispatcher wasNot Called }
      verify { websocketSessionRegistry wasNot Called }
      verify { suspendDispatcher wasNot Called }
    }

  @Test
  fun `cancelling one session leaves other sessions running and destroy cancels all`() =
    runTest {
      val started = mutableListOf<String>()
      val cancelled = mutableListOf<String>()
      coEvery { toolDispatcher.execute(any(), any()) } coAnswers {
        val context = secondArg<ToolContext>()
        started.add(context.sessionId)
        try {
          awaitCancellation()
        } finally {
          cancelled.add(context.sessionId)
        }
      }
      val handler = handler()
      val event =
        RealtimeEvent(type = "response.function_call_arguments.done", name = "lookup_account", callId = "tool-call-1")
      handler.handleInbound(event, request())
      handler.handleInbound(event, request().copy(callId = "call-456"))
      runCurrent()
      assertEquals(listOf("call-123", "call-456"), started)

      handler.cancel("call-123")
      runCurrent()
      assertEquals(listOf("call-123"), cancelled)

      handler.destroy()
      runCurrent()
      assertEquals(listOf("call-123", "call-456"), cancelled)
      verify { websocketSessionRegistry wasNot Called }
    }

  @Test
  fun `tool failure does not cancel sibling calls`() =
    runTest {
      val handler = handler()
      coEvery { toolDispatcher.execute("failing", any()) } throws IllegalStateException("failed")
      coEvery { toolDispatcher.execute("waiting", any()) } coAnswers { awaitCancellation() }
      handler.handleInbound(
        RealtimeEvent(type = "response.function_call_arguments.done", name = "failing", callId = "tool-1"),
        request()
      )
      handler.handleInbound(
        RealtimeEvent(type = "response.function_call_arguments.done", name = "waiting", callId = "tool-2"),
        request()
      )
      runCurrent()

      coVerify(exactly = 1) { toolDispatcher.execute("waiting", any()) }
      verify { websocketSessionRegistry wasNot Called }
    }

  private fun TestScope.handler(): RealtimeEventHandler =
    RealtimeEventHandler(
      objectMapper = objectMapper,
      toolDispatcher = toolDispatcher,
      websocketSessionRegistry = websocketSessionRegistry,
      suspendDispatcher = suspendDispatcher,
      toolDispatcherContext = StandardTestDispatcher(testScheduler)
    ).also { handlers.add(it) }

  private fun request(audioEnabled: Boolean = false): SidebandConnectRequest =
    SidebandConnectRequest(
      callId = "call-123",
      namespace = "crm",
      channel = "web",
      language = "en",
      audioEnabled = audioEnabled
    )
}
