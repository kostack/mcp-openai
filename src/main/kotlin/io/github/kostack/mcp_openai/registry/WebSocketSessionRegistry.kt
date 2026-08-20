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

  fun get(callId: String): WebSocketSession? = sessions[callId]

  fun isOpen(callId: String): Boolean {
    val session = get(callId) ?: return false

    if (!session.isOpen) {
      remove(callId, session)
      return false
    }

    return true
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
    val session = get(callId) ?: return false
    if (!isOpen(callId)) {
      log.debug("Skipped websocket send because session is closed callId={}", callId)
      return false
    }

    val message = objectMapper.writeValueAsString(json)
    return try {
      session.send(Mono.just(session.textMessage(message))).awaitFirstOrNull()
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      remove(callId, session)
      log.debug("Skipped websocket send after session closed callId={}, error={}", callId, e.message)
      false
    }
  }

  suspend fun sendPing(callId: String): Boolean {
    val session =
      get(callId) ?: return false

    if (!isOpen(callId)) {
      return false
    }

    return try {
      val ping =
        session.pingMessage { bufferFactory ->
          bufferFactory.wrap(
            callId.toByteArray()
          )
        }

      session
        .send(Mono.just(ping))
        .awaitFirstOrNull()

      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      remove(callId, session)

      log.debug(
        "Websocket ping failed callId={}, error={}",
        callId,
        e.message
      )

      false
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(WebSocketSessionRegistry::class.java)
  }
}
