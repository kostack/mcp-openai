package io.github.kostack.mcp_openai.registry

import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap

class WebSocketSessionRegistry(
  private val objectMapper: ObjectMapper,
  private val outboundBufferCapacity: Int = 256
) {
  private val sessions = ConcurrentHashMap<String, Connection>()

  init {
    require(outboundBufferCapacity > 0) { "Outbound buffer capacity must be positive" }
  }

  fun put(
    callId: String,
    session: WebSocketSession
  ): Flux<WebSocketMessage> {
    val connection = Connection(session, outboundBufferCapacity)
    sessions.put(callId, connection)?.complete()
    return connection.outbound.asFlux().doOnDiscard(WebSocketMessage::class.java) {
      DataBufferUtils.release(it.payload)
    }
  }

  fun get(callId: String): WebSocketSession? = sessions[callId]?.session

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

  private class Connection(
    val session: WebSocketSession,
    capacity: Int
  ) {
    val outbound: Sinks.Many<WebSocketMessage> =
      Sinks.many().unicast().onBackpressureBuffer(ArrayBlockingQueue(capacity))

    @Synchronized
    fun emit(message: WebSocketMessage): Boolean {
      val emitted = outbound.tryEmitNext(message) == Sinks.EmitResult.OK
      if (!emitted) {
        DataBufferUtils.release(message.payload)
        outbound.tryEmitError(IllegalStateException("WebSocket outbound buffer unavailable"))
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
