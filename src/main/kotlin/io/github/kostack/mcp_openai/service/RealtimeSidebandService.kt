package io.github.kostack.mcp_openai.service

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.autoconfiguration.McpProperties
import io.github.kostack.mcp_openai.dto.RealtimeEvent
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest
import io.github.kostack.mcp_openai.event.RealtimeConnectEvent
import io.github.kostack.mcp_openai.registry.SidebandHeartbeatRegistry
import io.github.kostack.mcp_openai.registry.SidebandSessionRegistry
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.WebSocketClient
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class RealtimeSidebandService(
  private val mcpProperties: McpProperties,
  private val objectMapper: ObjectMapper,
  private val sidebandRegistry: SidebandSessionRegistry,
  private val sessionRegistry: WebSocketSessionRegistry,
  private val realtimeEventHandler: RealtimeEventHandler,
  private val suspendDispatcher: SuspendDispatcher,
  private val client: WebSocketClient,
  private val heartbeatRegistry: SidebandHeartbeatRegistry,
  private val openAiHttpService: OpenAiHttpService
) {
  private val supervisorJob = SupervisorJob()
  private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)

  private val hangupStarted = ConcurrentHashMap<Job, AtomicBoolean>()

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

    hangupStarted[job] = AtomicBoolean()
    job.invokeOnCompletion { hangupStarted.remove(job) }
    job.start()
  }

  private suspend fun runSideband(request: SidebandConnectRequest) {
    val callId = request.callId
    val job = currentCoroutineContext().job
    val clientSecret = request.clientSecret.ifEmpty { mcpProperties.apiKey }
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
          val outbound = sessionRegistry.put(callId, session)
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
                when (msg.type) {
                  WebSocketMessage.Type.TEXT -> {
                    val event =
                      objectMapper.readValue(
                        msg.payloadAsText,
                        RealtimeEvent::class.java
                      )
                    mono {
                      realtimeEventHandler.handleInbound(event, request)
                    }.then()
                  }

                  WebSocketMessage.Type.PONG -> {
                    heartbeatRegistry.pong(callId)
                    Mono.empty()
                  }

                  else -> {
                    Mono.empty()
                  }
                }
              }.doOnError { e ->
                if (e is CancellationException) {
                  log.info("Sideband receive cancelled callId={}", callId)
                } else {
                  log.error("Sideband error callId={}", callId, e)
                }
              }.doFinally {
                sessionRegistry.remove(callId, session)
              }.then()

          startSession.then(Mono.`when`(inbound, session.send(outbound)))
        }.awaitSingleOrNull()
    } catch (e: CancellationException) {
      log.info("Sideband cancelled callId={}", callId)
      throw e
    } catch (e: Exception) {
      if (e.isClosedConnectionBeforeSend()) {
        log.info("Sideband closed before send callId={}", callId)
      } else {
        log.error("Sideband failed callId={}, error={}", callId, e.message, e)
      }
    } finally {
      try {
        if (hangupStarted[job]?.compareAndSet(false, true) == true) {
          withContext(NonCancellable) {
            withTimeout(5_000.milliseconds) {
              openAiHttpService.disconnect(callId)
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.warn("Failed to hang up dropped sideband callId={}", callId, e)
      } finally {
        realtimeEventHandler.cancel(callId)
        log.info("Sideband closed callId={}", callId)
        websocketSession?.let { sessionRegistry.remove(callId, it) }
        sidebandRegistry.remove(callId, job)
      }
    }
  }

  suspend fun disconnect(request: SidebandDisconnectRequest) {
    try {
      val state = sidebandRegistry.get(request.callId)?.let { hangupStarted[it] }
      if (state == null || state.compareAndSet(false, true)) {
        openAiHttpService.disconnect(request.callId)
      }
    } finally {
      sidebandRegistry.cancel(request.callId)
    }
  }

  @PreDestroy
  fun destroy() {
    runBlocking {
      supervisorJob.cancelAndJoin()
    }
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

private fun Throwable.isClosedConnectionBeforeSend(): Boolean =
  generateSequence(this) { it.cause }.any { cause ->
    cause::class.qualifiedName == "reactor.netty.channel.AbortedException" ||
      cause.message?.contains("Connection has been closed BEFORE send operation") == true
  }
