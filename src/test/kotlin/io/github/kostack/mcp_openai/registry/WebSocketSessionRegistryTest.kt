package io.github.kostack.mcp_openai.registry

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertFalse

class WebSocketSessionRegistryTest {
  private val objectMapper = mockk<ObjectMapper>()

  @Test
  fun `sendJson skips and removes closed session before serializing message`() =
    runTest {
      val registry = WebSocketSessionRegistry(objectMapper)
      val session = mockk<WebSocketSession>()
      val payload = mapOf("type" to "response.create")

      every { session.isOpen } returns false

      registry.put("call-123", session)
      assertFalse(registry.sendJson("call-123", payload))
      assertFalse(registry.sendJson("call-123", payload))

      verify(exactly = 1) { session.isOpen }
      verify(exactly = 0) { objectMapper.writeValueAsString(any()) }
      verify(exactly = 0) { session.send(any()) }
    }

  @Test
  fun `sendJson removes session and suppresses websocket send failure`() =
    runTest {
      val registry = WebSocketSessionRegistry(objectMapper)
      val session = mockk<WebSocketSession>()
      val message = mockk<WebSocketMessage>()
      val payload = mapOf("type" to "response.create")

      every { session.isOpen } returns true
      every { objectMapper.writeValueAsString(payload) } returns """{"type":"response.create"}"""
      every { session.textMessage("""{"type":"response.create"}""") } returns message
      every { session.send(any()) } returns Mono.error(IllegalStateException("closed before send"))

      registry.put("call-123", session)
      assertFalse(registry.sendJson("call-123", payload))
      assertFalse(registry.sendJson("call-123", payload))

      verify(exactly = 1) { objectMapper.writeValueAsString(payload) }
      verify(exactly = 1) { session.send(any()) }
    }
}
