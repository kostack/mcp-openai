package io.github.kostack.mcp_openai.service

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.dto.RealtimeEvent
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.ToolContext
import io.github.kostack.mcp_openai.dto.ToolResult
import io.github.kostack.mcp_openai.event.RealtimeHandlerEvent
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import io.github.kostack.mcp_openai.tool.ToolDispatcher
import io.github.kostack.mcp_openai.utils.RealtimeUtils
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap

class RealtimeEventHandler(
  private val objectMapper: ObjectMapper,
  private val toolDispatcher: ToolDispatcher,
  private val websocketSessionRegistry: WebSocketSessionRegistry,
  private val suspendDispatcher: SuspendDispatcher
) {
  private val supervisorJob = SupervisorJob()
  private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)
  private val toolCallScopes = ConcurrentHashMap<String, CoroutineScope>()

  suspend fun handleInbound(
    event: RealtimeEvent,
    request: SidebandConnectRequest
  ) {
    if (event.type != FUNCTION_CALL_ARGUMENTS_DONE) {
      handle(event, request)
      return
    }

    toolCallScope(request.callId).launch {
      try {
        handle(event, request)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.error(
          "Realtime tool call failed callId={}, toolCallId={}",
          request.callId,
          event.callId,
          e
        )
      }
    }
  }

  fun cancel(callId: String) {
    toolCallScopes.remove(callId)?.cancel()
  }

  @PreDestroy
  fun destroy() {
    supervisorJob.cancel()
    toolCallScopes.clear()
  }

  suspend fun handle(
    event: RealtimeEvent,
    request: SidebandConnectRequest
  ) {
    when (event.type) {
      "session.updated" -> {
        suspendDispatcher.publishSequential(
          RealtimeEvents.SESSION_UPDATED,
          RealtimeHandlerEvent(event, request)
        )
      }

      "response.created" -> {
        suspendDispatcher.publishSequential(
          RealtimeEvents.RESPONSE_CREATED,
          RealtimeHandlerEvent(event, request)
        )
      }

      "response.done" -> {
        suspendDispatcher.publishSequential(
          RealtimeEvents.RESPONSE_DONE,
          RealtimeHandlerEvent(event, request)
        )
      }

      "conversation.item.done" -> {
        suspendDispatcher.publishSequential(
          RealtimeEvents.CONVERSATION_ITEM_DONE,
          RealtimeHandlerEvent(event, request)
        )
      }

      "conversation.item.input_audio_transcription.completed" -> {
        suspendDispatcher.publishSequential(
          RealtimeEvents.CONVERSATION_ITEM_INPUT_AUDIO_TRANSCRIPTION_COMPLETED,
          RealtimeHandlerEvent(event, request)
        )
      }

      "response.output_item.done" -> {
        suspendDispatcher.publishSequential(
          RealtimeEvents.RESPONSE_OUTPUT_ITEM_DONE,
          RealtimeHandlerEvent(event, request)
        )
      }

      "response.function_call_arguments.done" -> {
        handleFunctionCallArgumentsDone(event, request)
      }

      "error" -> {
        log.warn("Realtime error: {}", event.error)
        suspendDispatcher.publishSequential(
          RealtimeEvents.RESPONSE_ERROR,
          RealtimeHandlerEvent(event, request)
        )
      }

      else -> {
        suspendDispatcher.publishSequential(
          RealtimeEvents.REALTIME_EVENT,
          RealtimeHandlerEvent(event, request)
        )
      }
    }
  }

  private suspend fun handleFunctionCallArgumentsDone(
    event: RealtimeEvent,
    request: SidebandConnectRequest
  ) {
    handleFunctionCall(event.name, event.callId, event.arguments, request)
  }

  private suspend fun handleFunctionCall(
    name: String?,
    callId: String?,
    arguments: String?,
    request: SidebandConnectRequest
  ) {
    val toolName = name ?: return
    val toolCallId = callId ?: return
    val rawArguments = arguments ?: "{}"

    log.info(
      "Handling function call: name={}, callId={}, namespace={}, channel={}, sessionCallId={} args={}",
      toolName,
      toolCallId,
      request.namespace,
      request.channel,
      request.callId,
      rawArguments
    )

    val context =
      ToolContext(
        namespace = request.namespace,
        channel = request.channel,
        sessionId = request.callId,
        toolCallId = toolCallId,
        rawRequest = rawArguments
      )
    val toolResult = toolDispatcher.execute(toolName, context)

    log.info(
      "Tool call result: name={}, callId={}, channel={}, sessionCallId={}, success={}",
      toolName,
      toolCallId,
      request.channel,
      request.callId,
      toolResult.success
    )

    if (toolResult.mode == ToolResult.ToolResultMode.DIRECT) {
      websocketSessionRegistry.sendJson(
        request.callId,
        RealtimeUtils.conversationFunctionOutput(
          toolCallId,
          """{"status":"delivered_to_client"}"""
        )
      )
      return
    }

    val outputSent =
      websocketSessionRegistry.sendJson(
        request.callId,
        RealtimeUtils.conversationFunctionOutput(
          toolCallId,
          objectMapper.writeValueAsString(toolResult)
        )
      )

    if (!outputSent) {
      log.debug(
        "Skipped response create because sideband session is closed callId={}, toolCallId={}",
        request.callId,
        toolCallId
      )
      return
    }

    websocketSessionRegistry.sendJson(
      request.callId,
      RealtimeUtils.createResponse(request.modality)
    )
  }

  private fun toolCallScope(callId: String): CoroutineScope =
    toolCallScopes.computeIfAbsent(callId) {
      CoroutineScope(scope.coroutineContext + SupervisorJob(supervisorJob))
    }

  companion object {
    private const val FUNCTION_CALL_ARGUMENTS_DONE = "response.function_call_arguments.done"
    private val log = LoggerFactory.getLogger(RealtimeEventHandler::class.java)
  }
}
