package io.github.kostack.mcp_openai.registry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap

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
  ): Boolean {
    val session = sessions[callId] ?: return false
    if (!session.isOpen) {
      sessions.remove(callId, session)
      log.debug("Skipped websocket send because session is closed callId={}", callId)
      return false
    }

    val message = objectMapper.writeValueAsString(json)
    try {
      session.send(Mono.just(session.textMessage(message))).awaitFirstOrNull()
      return true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      sessions.remove(callId, session)
      log.debug("Skipped websocket send after session closed callId={}, error={}", callId, e.message)
      return false
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(WebSocketSessionRegistry::class.java)
  }
}
