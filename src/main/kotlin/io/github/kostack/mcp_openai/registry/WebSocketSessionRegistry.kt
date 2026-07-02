package io.github.kostack.mcp_openai.registry

import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap

@Component
class WebSocketSessionRegistry(
  private val objectMapper: ObjectMapper
) {
  private val sessions = ConcurrentHashMap<String, WebSocketSession>()

  fun put(
    callId: String,
    session: WebSocketSession
  ) {
    sessions[callId] = session
  }

  fun remove(callId: String) {
    sessions.remove(callId)
  }

  fun remove(
    callId: String,
    session: WebSocketSession
  ) {
    sessions.remove(callId, session)
  }

  suspend fun sendJson(
    callId: String,
    json: Any
  ) {
    val message = objectMapper.writeValueAsString(json)
    val session = sessions[callId] ?: return
    session.send(Mono.just(session.textMessage(message))).awaitFirstOrNull()
  }
}
