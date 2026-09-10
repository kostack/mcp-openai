package io.github.kostack.mcp_openai.service

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.autoconfiguration.McpProperties
import io.github.kostack.mcp_openai.dto.RealtimeEvent
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest
import io.github.kostack.mcp_openai.registry.SidebandHeartbeatRegistry
import io.github.kostack.mcp_openai.registry.SidebandSessionRegistry
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.WebSocketClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import tools.jackson.databind.ObjectMapper
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class RealtimeSidebandServiceTest {
  private val openAiHttpService = mockk<OpenAiHttpService>(relaxed = true)
  private val sidebandRegistry = mockk<SidebandSessionRegistry>(relaxed = true)
  private val heartbeatRegistry = mockk<SidebandHeartbeatRegistry>(relaxed = true)
  private val sessionRegistry = mockk<WebSocketSessionRegistry>(relaxed = true)
  private val realtimeEventHandler = mockk<RealtimeEventHandler>(relaxed = true)
  private val suspendDispatcher = mockk<SuspendDispatcher>(relaxed = true)
  private val objectMapper = mockk<ObjectMapper>(relaxed = true)
  private val sidebandWebSocketClient = mockk<WebSocketClient>()
  private val properties =
    McpProperties(
      apiKey = "server-api-key",
      sidebandUrl = "wss://realtime.example.test/v1/realtime"
    )

  @Test
  fun `connect registers sideband job and opens websocket with call id and bearer token`() =
    runBlocking {
      val jobSlot = slot<Job>()
      val uriSlot = slot<URI>()
      val headersSlot = slot<HttpHeaders>()
      val request =
        SidebandConnectRequest(
          callId = "call-123",
          clientSecret = "client-secret",
          namespace = "crm",
          channel = "web",
          language = "en"
        )

      every { sidebandRegistry.putIfAbsent(request.callId, capture(jobSlot)) } returns null
      mockWebSocketExecute(uriSlot, headersSlot)

      service().connect(request)

      verify(exactly = 1) { sidebandRegistry.putIfAbsent(request.callId, any()) }
      verify(timeout = 1_000) {
        sidebandWebSocketClient.execute(
          any<URI>(),
          any<HttpHeaders>(),
          any<WebSocketHandler>()
        )
      }

      assertEquals(
        URI.create("wss://realtime.example.test/v1/realtime?call_id=call-123"),
        uriSlot.captured
      )
      assertEquals("Bearer client-secret", headersSlot.captured.getFirst(HttpHeaders.AUTHORIZATION))

      jobSlot.captured.cancelAndJoin()
    }

  @Test
  fun `unified call sideband uses configured server api key`() =
    runBlocking {
      val jobSlot = slot<Job>()
      val headersSlot = slot<HttpHeaders>()
      every { sidebandRegistry.putIfAbsent("rtc_test", capture(jobSlot)) } returns null
      mockWebSocketExecute(headersSlot = headersSlot)
      service().connect(
        SidebandConnectRequest(callId = "rtc_test", namespace = "crm", channel = "web", language = "en")
      )
      verify(timeout = 1_000) {
        sidebandWebSocketClient.execute(any<URI>(), any<HttpHeaders>(), any<WebSocketHandler>())
      }
      assertEquals("Bearer server-api-key", headersSlot.captured.getFirst(HttpHeaders.AUTHORIZATION))
      jobSlot.captured.cancelAndJoin()
    }

  @Test
  fun `connect cancels newly created job when call id is already registered`() =
    runBlocking {
      val newJobSlot = slot<Job>()
      val existingJob = Job()
      val request =
        SidebandConnectRequest(
          callId = "call-123",
          clientSecret = "client-secret",
          namespace = "crm",
          channel = "web",
          language = "en"
        )

      every { sidebandRegistry.putIfAbsent(request.callId, capture(newJobSlot)) } returns existingJob
      mockWebSocketExecute()

      service().connect(request)

      verify(exactly = 1) { sidebandRegistry.putIfAbsent(request.callId, any()) }
      verify(exactly = 0) {
        sidebandWebSocketClient.execute(
          any<URI>(),
          any<HttpHeaders>(),
          any<WebSocketHandler>()
        )
      }
      newJobSlot.captured.cancelAndJoin()

      assertTrue(newJobSlot.captured.isCancelled)
      assertTrue(existingJob.isActive)

      existingJob.cancel()
    }

  @Test
  fun `connect encodes call id in sideband websocket uri`() =
    runBlocking {
      val jobSlot = slot<Job>()
      val uriSlot = slot<URI>()
      val request =
        SidebandConnectRequest(
          callId = "call 123&next=value",
          clientSecret = "client-secret",
          namespace = "crm",
          channel = "web",
          language = "en"
        )

      every { sidebandRegistry.putIfAbsent(request.callId, capture(jobSlot)) } returns null
      mockWebSocketExecute(uriSlot = uriSlot)

      service().connect(request)

      verify(timeout = 1_000) {
        sidebandWebSocketClient.execute(
          any<URI>(),
          any<HttpHeaders>(),
          any<WebSocketHandler>()
        )
      }

      assertEquals(
        URI.create("wss://realtime.example.test/v1/realtime?call_id=call%20123%26next%3Dvalue"),
        uriSlot.captured
      )

      jobSlot.captured.cancelAndJoin()
    }

  @Test
  fun `disconnect hangs up call and cancels sideband job`() =
    runTest {
      val request =
        SidebandDisconnectRequest(
          callId = "call-123",
          namespace = "crm",
          channel = "web"
        )

      service().disconnect(request)

      coVerify(exactly = 1) { openAiHttpService.disconnect(request.callId) }
      coVerify(exactly = 0) { suspendDispatcher.publishSequential(any(), any()) }
      verify(exactly = 1) { sidebandRegistry.cancel(request.callId) }
    }

  @Test
  fun `disconnect cancels sideband job when hangup fails`() =
    runTest {
      val request = SidebandDisconnectRequest(callId = "call-123", namespace = "crm", channel = "web")
      val failure = IllegalStateException("Hangup failed")
      coEvery { openAiHttpService.disconnect(request.callId) } throws failure

      val thrown = assertFailsWith<IllegalStateException> { service().disconnect(request) }

      assertEquals(failure, thrown)
      verify(exactly = 1) { sidebandRegistry.cancel(request.callId) }
    }

  @Test
  fun `pong is processed after tool call is dispatched`() =
    runBlocking {
      val jobSlot = slot<Job>()
      val session = mockk<WebSocketSession>()
      val functionCallMessage = mockk<WebSocketMessage>()
      val pongMessage = mockk<WebSocketMessage>()
      val toolStarted = Sinks.one<Unit>()
      val request =
        SidebandConnectRequest(
          callId = "call-123",
          clientSecret = "client-secret",
          namespace = "crm",
          channel = "web",
          language = "en"
        )
      val event =
        RealtimeEvent(
          type = "response.function_call_arguments.done",
          callId = "tool-call-1"
        )

      every { sidebandRegistry.putIfAbsent(request.callId, capture(jobSlot)) } returns null
      every { functionCallMessage.type } returns WebSocketMessage.Type.TEXT
      every { functionCallMessage.payloadAsText } returns "tool-call-event"
      every { pongMessage.type } returns WebSocketMessage.Type.PONG
      every { objectMapper.readValue("tool-call-event", RealtimeEvent::class.java) } returns event
      every { sessionRegistry.put(request.callId, session) } returns Flux.never()
      every { session.send(any()) } returns Mono.never()
      every { session.receive() } returns
        Flux.concat(
          Mono.just(functionCallMessage),
          toolStarted.asMono().thenReturn(pongMessage),
          Mono.never()
        )
      coEvery { realtimeEventHandler.handleInbound(event, request) } coAnswers {
        toolStarted.tryEmitValue(Unit)
      }
      every {
        sidebandWebSocketClient.execute(
          any<URI>(),
          any<HttpHeaders>(),
          any<WebSocketHandler>()
        )
      } answers {
        thirdArg<WebSocketHandler>().handle(session)
      }

      service().connect(request)

      verify(timeout = 1_000, exactly = 1) { heartbeatRegistry.pong(request.callId) }
      verify(exactly = 1) { session.send(any()) }
      coVerify(exactly = 1) { realtimeEventHandler.handleInbound(event, request) }

      jobSlot.captured.cancelAndJoin()
      verify(exactly = 1) { realtimeEventHandler.cancel(request.callId) }
    }

  @Test
  fun `connect does not log sideband failure when connection closes before send`(output: CapturedOutput) =
    runBlocking {
      val jobSlot = slot<Job>()
      val request =
        SidebandConnectRequest(
          callId = "call-123",
          clientSecret = "client-secret",
          namespace = "crm",
          channel = "web",
          language = "en"
        )

      every { sidebandRegistry.putIfAbsent(request.callId, capture(jobSlot)) } returns null
      mockWebSocketExecuteFailure(
        RuntimeException("Connection has been closed BEFORE send operation")
      )

      service().connect(request)
      jobSlot.captured.join()

      assertFalse(output.all.contains("Sideband failed callId=call-123"))
    }

  @Test
  fun `remote close hangs up call and removes sideband job`() =
    runBlocking {
      val jobSlot = slot<Job>()
      every { sidebandRegistry.putIfAbsent("call-123", capture(jobSlot)) } returns null
      every {
        sidebandWebSocketClient.execute(any<URI>(), any<HttpHeaders>(), any<WebSocketHandler>())
      } returns Mono.empty()

      service().connect(
        SidebandConnectRequest(callId = "call-123", namespace = "crm", channel = "web", language = "en")
      )
      jobSlot.captured.join()

      coVerify(exactly = 1) { openAiHttpService.disconnect("call-123") }
      verify(exactly = 1) { sidebandRegistry.remove("call-123", jobSlot.captured) }
    }

  @Test
  fun `connection failure cleans up even when hangup fails`() =
    runBlocking {
      val jobSlot = slot<Job>()
      every { sidebandRegistry.putIfAbsent("call-123", capture(jobSlot)) } returns null
      mockWebSocketExecuteFailure(IllegalStateException("Connection dropped"))
      coEvery { openAiHttpService.disconnect("call-123") } throws IllegalStateException("Hangup failed")

      service().connect(
        SidebandConnectRequest(callId = "call-123", namespace = "crm", channel = "web", language = "en")
      )
      jobSlot.captured.join()

      coVerify(exactly = 1) { openAiHttpService.disconnect("call-123") }
      verify(exactly = 1) { realtimeEventHandler.cancel("call-123") }
      verify(exactly = 1) { sidebandRegistry.remove("call-123", jobSlot.captured) }
    }

  @Test
  fun `explicit disconnect hangs up connected call only once`() =
    runBlocking {
      val jobSlot = slot<Job>()
      every { sidebandRegistry.putIfAbsent("call-123", capture(jobSlot)) } returns null
      every { sidebandRegistry.get("call-123") } answers { jobSlot.captured }
      every { sidebandRegistry.cancel("call-123") } answers { jobSlot.captured.cancel() }
      mockWebSocketExecute()
      val service = service()
      service.connect(SidebandConnectRequest(callId = "call-123", namespace = "crm", channel = "web", language = "en"))
      verify(timeout = 1_000) {
        sidebandWebSocketClient.execute(any<URI>(), any<HttpHeaders>(), any<WebSocketHandler>())
      }

      service.disconnect(SidebandDisconnectRequest(callId = "call-123", namespace = "crm", channel = "web"))
      jobSlot.captured.join()

      coVerify(exactly = 1) { openAiHttpService.disconnect("call-123") }
    }

  @Test
  fun `destroy waits for hangup and cleanup of all calls even when one hangup fails`() {
    val jobs = mutableListOf<Job>()
    every { sidebandRegistry.putIfAbsent(any(), capture(jobs)) } returns null
    mockWebSocketExecute()
    coEvery { openAiHttpService.disconnect("call-1") } throws IllegalStateException("Hangup failed")
    var hangupCompleted = false
    coEvery { openAiHttpService.disconnect("call-2") } coAnswers {
      delay(50)
      hangupCompleted = true
    }
    val service = service()
    for (callId in listOf("call-1", "call-2")) {
      service.connect(SidebandConnectRequest(callId = callId, namespace = "crm", channel = "web", language = "en"))
    }
    verify(timeout = 1_000, exactly = 2) {
      sidebandWebSocketClient.execute(any<URI>(), any<HttpHeaders>(), any<WebSocketHandler>())
    }

    service.destroy()
    service.destroy()

    assertTrue(hangupCompleted)
    assertTrue(jobs.all { it.isCompleted })
    for (callId in listOf("call-1", "call-2")) {
      coVerify(exactly = 1) { openAiHttpService.disconnect(callId) }
      verify(exactly = 1) { realtimeEventHandler.cancel(callId) }
      verify(exactly = 1) { sidebandRegistry.remove(callId, any()) }
    }
  }

  private fun mockWebSocketExecute(
    uriSlot: io.mockk.CapturingSlot<URI> = slot(),
    headersSlot: io.mockk.CapturingSlot<HttpHeaders> = slot()
  ) {
    every {
      sidebandWebSocketClient.execute(
        capture(uriSlot),
        capture(headersSlot),
        any<WebSocketHandler>()
      )
    } returns Mono.never()
  }

  private fun mockWebSocketExecuteFailure(error: Throwable) {
    every {
      sidebandWebSocketClient.execute(
        any<URI>(),
        any<HttpHeaders>(),
        any<WebSocketHandler>()
      )
    } returns Mono.error(error)
  }

  private fun service(): RealtimeSidebandService =
    RealtimeSidebandService(
      mcpProperties = properties,
      objectMapper = objectMapper,
      sidebandRegistry = sidebandRegistry,
      sessionRegistry = sessionRegistry,
      realtimeEventHandler = realtimeEventHandler,
      suspendDispatcher = suspendDispatcher,
      client = sidebandWebSocketClient,
      heartbeatRegistry = heartbeatRegistry,
      openAiHttpService = openAiHttpService
    )
}
