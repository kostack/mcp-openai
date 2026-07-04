package io.github.kostack.mcp_openai.service

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.dto.RealtimeEvent
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.ToolContext
import io.github.kostack.mcp_openai.event.RealtimeHandlerEvent
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import io.github.kostack.mcp_openai.tool.ToolDispatcher
import io.github.kostack.mcp_openai.utils.RealtimeUtils
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper

class RealtimeEventHandler(
  private val objectMapper: ObjectMapper,
  private val toolDispatcher: ToolDispatcher,
  private val websocketSessionRegistry: WebSocketSessionRegistry,
  private val suspendDispatcher: SuspendDispatcher
) {
  suspend fun handle(
    event: RealtimeEvent,
    request: SidebandConnectRequest
  ) {
    when (event.type) {
      "session.created",
      "response.created",
      "rate_limits.updated" -> {
        log.debug("Realtime event: {}", event.type)
      }

      "session.updated" -> {
        suspendDispatcher.publishSequential(
          RealtimeEvents.SESSION_UPDATED,
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
        log.debug("Realtime event ignored: {}", event.type)
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
      "Handling function call: name={}, callId={}, namespace={}, channel={}, args={}",
      toolName,
      toolCallId,
      request.namespace,
      request.channel,
      rawArguments
    )

    val context = ToolContext(request.namespace, request.channel, rawArguments)
    val toolResult = toolDispatcher.execute(toolName, context)

    log.info(
      "Tool call result: name={}, callId={}, channel={}, success={}",
      toolName,
      toolCallId,
      request.channel,
      toolResult.success
    )

    websocketSessionRegistry.sendJson(
      request.callId,
      RealtimeUtils.conversationFunctionOutput(
        toolCallId,
        objectMapper.writeValueAsString(toolResult)
      )
    )

    websocketSessionRegistry.sendJson(request.callId, RealtimeUtils.createResponse(request.modality))
  }

  companion object {
    private val log = LoggerFactory.getLogger(RealtimeEventHandler::class.java)
  }
}
