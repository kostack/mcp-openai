package io.github.kostack.mcp_openai

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.autoconfiguration.McpProperties
import io.github.kostack.mcp_openai.dto.RealtimeCallRequest
import io.github.kostack.mcp_openai.dto.RealtimeTokenResponse
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest
import io.github.kostack.mcp_openai.dto.TokenRequest
import io.github.kostack.mcp_openai.event.RealtimeConnectEvent
import io.github.kostack.mcp_openai.event.RealtimeDisconnectEvent
import io.github.kostack.mcp_openai.event.RealtimeTokenPreCreateEvent
import io.github.kostack.mcp_openai.service.OpenAiHttpService
import io.github.kostack.mcp_openai.service.RealtimeSidebandService
import io.github.kostack.mcp_openai.tool.ToolDispatcher
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait

class RealtimeSidebandHandler(
  private val sidebandService: RealtimeSidebandService,
  private val openAiHttpService: OpenAiHttpService,
  private val suspendDispatcher: SuspendDispatcher,
  private val toolDispatcher: ToolDispatcher,
  private val properties: McpProperties
) {
  suspend fun createCall(request: RealtimeCallRequest): ServerResponse {
    val settings =
      TokenRequest(
        request.namespace,
        request.channel,
        request.language,
        request.audioEnabled && properties.enableAudio
      )
    val event = RealtimeTokenPreCreateEvent(settings)
    suspendDispatcher.publishSequential(RealtimeEvents.TOKEN_PRE_CREATE, event)
    val call =
      openAiHttpService.createCall(
        request.sdp,
        event.instructions,
        settings.language,
        toolDispatcher.getDefinitions(settings.namespace)
      )
    connect(
      SidebandConnectRequest(
        callId = call.callId,
        namespace = settings.namespace,
        channel = settings.channel,
        language = settings.language,
        audioEnabled = settings.audioEnabled
      )
    )
    return ServerResponse.ok().bodyValueAndAwait(call)
  }

  // legacy
  suspend fun createToken(request: TokenRequest): ServerResponse {
    if (!properties.enableAudio) request.audioEnabled = false

    val event = RealtimeTokenPreCreateEvent(request)
    suspendDispatcher.publishSequential(
      RealtimeEvents.TOKEN_PRE_CREATE,
      event
    )

    val token: RealtimeTokenResponse =
      openAiHttpService.createEphemeralToken(
        event.instructions,
        request.language,
        toolDispatcher.getDefinitions(request.namespace)
      )

    return ServerResponse
      .ok()
      .bodyValueAndAwait(token)
  }

  // legacy
  suspend fun connect(request: SidebandConnectRequest): ServerResponse {
    if (!properties.enableAudio) request.audioEnabled = false
    suspendDispatcher.publishSequential(RealtimeEvents.CONNECT, RealtimeConnectEvent(request))
    sidebandService.connect(request)

    return ServerResponse
      .noContent()
      .buildAndAwait()
  }

  suspend fun disconnect(request: SidebandDisconnectRequest): ServerResponse {
    suspendDispatcher.publishSequential(RealtimeEvents.DISCONNECT, RealtimeDisconnectEvent(request))
    sidebandService.disconnect(request)

    return ServerResponse
      .noContent()
      .buildAndAwait()
  }
}
