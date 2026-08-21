package io.github.kostack.mcp_openai.registry

import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap

class WebSocketSessionRegistry(
  private val objectMapper: ObjectMapper
) {
  private val sessions = ConcurrentHashMap<String, Connection>()

  fun put(
    callId: String,
    session: WebSocketSession
  ): Flux<WebSocketMessage> {
    val connection = Connection(session)
    sessions.put(callId, connection)?.complete()
    return connection.outbound.asFlux()
  }

  fun get(callId: String): WebSocketSession? = sessions[callId]?.session

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
    val connection = sessions[callId] ?: return
    if (connection.session !== session) return

    if (sessions.remove(callId, connection)) {
      connection.complete()
    }
  }

  fun sendJson(
    callId: String,
    json: Any
  ): Boolean {
    val connection = sessions[callId] ?: return false
    val session = connection.session
    if (!session.isOpen) {
      remove(callId, session)
      log.debug("Skipped websocket send because session is closed callId={}", callId)
      return false
    }

    return try {
      val jsonMessage = objectMapper.writeValueAsString(json)
      val emitted = connection.emit(session.textMessage(jsonMessage))
      if (!emitted) {
        remove(callId, session)
        log.debug("Websocket JSON enqueue failed callId={}", callId)
      }
      emitted
    } catch (e: Exception) {
      remove(callId, session)
      log.debug("Websocket JSON enqueue failed callId={}, error={}", callId, e.message)
      false
    }
  }

  fun sendPing(callId: String): Boolean {
    val connection = sessions[callId] ?: return false
    val session = connection.session

    if (!session.isOpen) {
      remove(callId, session)
      return false
    }

    return try {
      val ping =
        session.pingMessage { bufferFactory ->
          bufferFactory.wrap(
            callId.toByteArray()
          )
        }

      val emitted = connection.emit(ping)
      if (!emitted) {
        remove(callId, session)
        log.debug("Websocket ping enqueue failed callId={}", callId)
      }
      emitted
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

  private class Connection(
    val session: WebSocketSession
  ) {
    val outbound: Sinks.Many<WebSocketMessage> =
      Sinks.many().unicast().onBackpressureBuffer()

    @Synchronized
    fun emit(message: WebSocketMessage): Boolean {
      val emitted = outbound.tryEmitNext(message) == Sinks.EmitResult.OK
      if (!emitted) {
        DataBufferUtils.release(message.payload)
      }
      return emitted
    }

    @Synchronized
    fun complete() {
      outbound.tryEmitComplete()
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(WebSocketSessionRegistry::class.java)
  }
}
