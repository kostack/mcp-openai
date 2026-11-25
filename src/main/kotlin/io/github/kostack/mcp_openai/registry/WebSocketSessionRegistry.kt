package io.github.kostack.mcp_openai.registry

import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux

interface WebSocketSessionRegistry {
  fun put(
    callId: String,
    session: WebSocketSession
  ): Flux<WebSocketMessage>

  fun get(callId: String): WebSocketSession?

  fun remove(
    callId: String,
    session: WebSocketSession
  )

  fun sendJson(
    callId: String,
    json: Any
  ): Boolean
}
