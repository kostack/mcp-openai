package io.github.kostack.mcp_openai.service

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.configuration.McpProperties
import io.github.kostack.mcp_openai.dto.RealtimeEvent
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest
import io.github.kostack.mcp_openai.event.RealtimeConnectEvent
import io.github.kostack.mcp_openai.registry.SidebandSessionRegistry
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.net.URI

@Service
class RealtimeSidebandService(
  private val mcpProperties: McpProperties,
  private val objectMapper: ObjectMapper,
  private val sidebandRegistry: SidebandSessionRegistry,
  private val sessionRegistry: WebSocketSessionRegistry,
  private val realtimeEventHandler: RealtimeEventHandler,
  private val suspendDispatcher: SuspendDispatcher
) {
  private val supervisorJob = SupervisorJob()
  private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)
  private val client = ReactorNettyWebSocketClient()

  fun connect(request: SidebandConnectRequest) {
    val callId = request.callId
    val job =
      scope.launch(start = CoroutineStart.LAZY) {
        runSideband(request)
      }

    val existing = sidebandRegistry.putIfAbsent(callId, job)

    if (existing != null) {
      log.info("Sideband already connected for callId={}", callId)
      job.cancel()
      return
    }

    job.start()
  }

  private suspend fun runSideband(request: SidebandConnectRequest) {
    val callId = request.callId
    val job = currentCoroutineContext().job
    val clientSecret = request.clientSecret
    val uri = sidebandUri(callId)
    val headers =
      HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $clientSecret")
      }
    var websocketSession: WebSocketSession? = null

    try {
      client
        .execute(uri, headers) { session ->
          websocketSession = session
          sessionRegistry.put(callId, session)
          log.info("Sideband connected callId={}", callId)

          val startSession =
            mono {
              suspendDispatcher.publishSequential(
                RealtimeEvents.SESSION_START,
                RealtimeConnectEvent(request)
              )
            }.then()

          val inbound =
            session
              .receive()
              .concatMap { msg ->
                val event =
                  objectMapper.readValue(
                    msg.payloadAsText,
                    RealtimeEvent::class.java
                  )
                mono {
                  realtimeEventHandler.handle(event, request)
                }.then()
              }.doOnError { e ->
                if (e is CancellationException) {
                  log.info("Sideband receive cancelled callId={}", callId)
                } else {
                  log.error("Sideband error callId={}", callId, e)
                }
              }.then()

          startSession.then(inbound)
        }.awaitSingleOrNull()
    } catch (e: CancellationException) {
      log.info("Sideband cancelled callId={}", callId)
      throw e
    } catch (e: Exception) {
      log.error("Sideband failed callId={}, error={}", callId, e.message, e)
    } finally {
      log.info("Sideband closed callId={}", callId)
      websocketSession?.let { sessionRegistry.remove(callId, it) }
      sidebandRegistry.remove(callId, job)
    }
  }

  suspend fun disconnect(request: SidebandDisconnectRequest) {
    sidebandRegistry.cancel(request.callId)
  }

  @PreDestroy
  fun destroy() {
    supervisorJob.cancel()
  }

  private fun sidebandUri(callId: String): URI =
    UriComponentsBuilder
      .fromUriString(mcpProperties.sidebandUrl)
      .queryParam("call_id", callId)
      .build()
      .encode()
      .toUri()

  companion object {
    private val log = LoggerFactory.getLogger(RealtimeSidebandService::class.java)
  }
}
