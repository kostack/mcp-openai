package io.github.kostack.mcp_openai.service

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.autoconfiguration.McpProperties
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest
import io.github.kostack.mcp_openai.registry.SidebandSessionRegistry
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.client.WebSocketClient
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class RealtimeSidebandServiceTest {
  private val sidebandRegistry = mockk<SidebandSessionRegistry>(relaxed = true)
  private val sessionRegistry = mockk<WebSocketSessionRegistry>(relaxed = true)
  private val realtimeEventHandler = mockk<RealtimeEventHandler>(relaxed = true)
  private val suspendDispatcher = mockk<SuspendDispatcher>(relaxed = true)
  private val objectMapper = mockk<ObjectMapper>(relaxed = true)
  private val sidebandWebSocketClient = mockk<WebSocketClient>()
  private val properties =
    McpProperties(
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
  fun `disconnect cancels sideband job`() =
    runTest {
      val request =
        SidebandDisconnectRequest(
          callId = "call-123",
          namespace = "crm",
          channel = "web"
        )

      service().disconnect(request)

      coVerify(exactly = 0) { suspendDispatcher.publishSequential(any(), any()) }
      verify(exactly = 1) { sidebandRegistry.cancel(request.callId) }
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
      client = sidebandWebSocketClient
    )
}
