package io.github.kostack.mcp_openai.registry

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    }

  @Test
  fun `json messages share one ordered outbound stream`() =
    runTest {
      val registry = WebSocketSessionRegistry(objectMapper)
      val session = mockk<WebSocketSession>()
      val jsonMessage = mockk<WebSocketMessage>()
      val secondJsonMessage = mockk<WebSocketMessage>()
      val payload = mapOf("type" to "response.create")
      val secondPayload = mapOf("type" to "response.cancel")

      every { session.isOpen } returns true
      every { objectMapper.writeValueAsString(payload) } returns """{"type":"response.create"}"""
      every { session.textMessage("""{"type":"response.create"}""") } returns jsonMessage
      every { objectMapper.writeValueAsString(secondPayload) } returns """{"type":"response.cancel"}"""
      every { session.textMessage("""{"type":"response.cancel"}""") } returns secondJsonMessage

      val messages =
        registry
          .put("call-123", session)
          .take(2)
          .collectList()
          .toFuture()

      assertTrue(registry.sendJson("call-123", payload))
      assertTrue(registry.sendJson("call-123", secondPayload))

      kotlin.test.assertEquals(listOf(jsonMessage, secondJsonMessage), messages.get())
      verify(exactly = 0) { session.send(any()) }
    }
}
