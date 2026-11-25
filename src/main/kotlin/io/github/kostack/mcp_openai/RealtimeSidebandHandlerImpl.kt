package io.github.kostack.mcp_openai

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.autoconfiguration.McpProperties
import io.github.kostack.mcp_openai.dto.PreConnectRequest
import io.github.kostack.mcp_openai.dto.RealtimeCallRequest
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest
import io.github.kostack.mcp_openai.event.RealtimeConnectEvent
import io.github.kostack.mcp_openai.event.RealtimeDisconnectEvent
import io.github.kostack.mcp_openai.event.RealtimePreConnectEvent
import io.github.kostack.mcp_openai.service.OpenAiHttpService
import io.github.kostack.mcp_openai.service.RealtimeSidebandService
import io.github.kostack.mcp_openai.tool.ToolDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import kotlin.time.Duration.Companion.milliseconds

class RealtimeSidebandHandlerImpl(
  private val sidebandService: RealtimeSidebandService,
  private val openAiHttpService: OpenAiHttpService,
  private val suspendDispatcher: SuspendDispatcher,
  private val toolDispatcher: ToolDispatcher,
  private val properties: McpProperties
) : RealtimeSidebandHandler {
  override suspend fun createCall(request: RealtimeCallRequest): ServerResponse {
    val settings =
      PreConnectRequest(
        request.namespace,
        request.channel,
        request.language,
        request.audioEnabled && properties.enableAudio
      )
    val event = RealtimePreConnectEvent(settings)
    suspendDispatcher.publishSequential(RealtimeEvents.PRE_CONNECT, event)
    val call =
      openAiHttpService.createCall(
        request.sdp,
        event.instructions,
        settings.language,
        toolDispatcher.getDefinitions(settings.namespace)
      )

    val sidebandConnectionRequest =
      SidebandConnectRequest(
        callId = call.callId,
        namespace = settings.namespace,
        channel = settings.channel,
        language = settings.language,
        audioEnabled = properties.enableAudio && settings.audioEnabled
      )

    try {
      suspendDispatcher.publishSequential(
        RealtimeEvents.CONNECT,
        RealtimeConnectEvent(sidebandConnectionRequest)
      )
      sidebandService.connect(sidebandConnectionRequest)

      return ServerResponse.ok().bodyValueAndAwait(call)
    } catch (failure: Throwable) {
      cleanup(SidebandDisconnectRequest(call.callId, settings.namespace, settings.channel), failure)
      throw failure
    }
  }

  override suspend fun disconnect(request: SidebandDisconnectRequest): ServerResponse {
    try {
      suspendDispatcher.publishSequential(RealtimeEvents.DISCONNECT, RealtimeDisconnectEvent(request))
    } catch (failure: Throwable) {
      cleanup(request, failure)
      throw failure
    }
    withContext(NonCancellable) {
      try {
        withTimeout(5_000.milliseconds) { sidebandService.disconnect(request) }
      } catch (failure: TimeoutCancellationException) {
        log.warn("Timed out hanging up sideband callId={}", request.callId, failure)
      }
    }

    return ServerResponse
      .noContent()
      .buildAndAwait()
  }

  private suspend fun cleanup(
    request: SidebandDisconnectRequest,
    failure: Throwable
  ) {
    try {
      withContext(NonCancellable) {
        withTimeout(5_000.milliseconds) { sidebandService.disconnect(request) }
      }
    } catch (cleanupFailure: Throwable) {
      if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(RealtimeSidebandHandlerImpl::class.java)
  }
}
