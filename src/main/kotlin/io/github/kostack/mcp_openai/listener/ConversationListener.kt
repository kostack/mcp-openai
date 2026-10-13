package io.github.kostack.mcp_openai.listener

import io.github.kostack.event_dispatcher.SuspendListener
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.dto.RealtimeItem
import io.github.kostack.mcp_openai.event.RealtimeHandlerEvent
import io.github.kostack.mcp_openai.service.ConversationStore
import kotlin.collections.firstOrNull
import kotlin.collections.orEmpty

class ConversationListener(
  private val conversationStore: ConversationStore
) {
  @SuspendListener(RealtimeEvents.REALTIME_EVENT)
  suspend fun onFunctionOutputDone(event: RealtimeHandlerEvent) {
    if (event.realtimeEvent.type != "conversation.item.done") return
    val item = event.realtimeEvent.item ?: return
    if (item.type != "function_call_output") return
    val itemId = item.id ?: return
    val callId = item.callId ?: return
    val output = item.output ?: return

    conversationStore.appendOnce(
      sessionId = event.request.channel,
      itemId = itemId,
      item =
        mapOf(
          "type" to "function_call_output",
          "call_id" to callId,
          "output" to output
        )
    )
  }

  @SuspendListener(RealtimeEvents.REALTIME_EVENT)
  suspend fun onConversationItemDone(event: RealtimeHandlerEvent) {
    val realtimeEvent = event.realtimeEvent
    if (realtimeEvent.type != "conversation.item.done") return
    val item = realtimeEvent.item
    if (item?.type != "message" || item.role != "user") return
    val text = extractTextFromItem(item, input = true) ?: return

    conversationStore.appendOnce(
      sessionId = event.request.channel,
      itemId = item.id,
      item = mapOf("role" to "user", "content" to text)
    )
  }

  @SuspendListener(RealtimeEvents.REALTIME_EVENT)
  suspend fun onInputAudioTranscriptionCompleted(event: RealtimeHandlerEvent) {
    val realtimeEvent = event.realtimeEvent
    if (realtimeEvent.type != "conversation.item.input_audio_transcription.completed") return
    val transcript = realtimeEvent.transcript?.takeIf { it.isNotBlank() } ?: return

    conversationStore.appendOnce(
      sessionId = event.request.channel,
      itemId = realtimeEvent.itemId,
      item = mapOf("role" to "user", "content" to transcript)
    )
  }

  @SuspendListener(RealtimeEvents.REALTIME_EVENT)
  suspend fun onResponseDone(event: RealtimeHandlerEvent) {
    val realtimeEvent = event.realtimeEvent
    if (realtimeEvent.type != "response.done") return
    val output = realtimeEvent.response?.output.orEmpty()

    output
      .filter { it.type == "message" && it.role == "assistant" }
      .forEach { appendAssistantItem(event, it) }
  }

  @SuspendListener(RealtimeEvents.REALTIME_EVENT)
  suspend fun onResponseOutputItemDone(event: RealtimeHandlerEvent) {
    if (event.realtimeEvent.type != "response.output_item.done") return
    val item = event.realtimeEvent.item ?: return
    if (item.type != "message" || item.role != "assistant") return

    appendAssistantItem(event, item)
  }

  private suspend fun appendAssistantItem(
    event: RealtimeHandlerEvent,
    item: RealtimeItem
  ) {
    val text = extractTextFromItem(item, input = false) ?: return

    conversationStore.appendOnce(
      sessionId = event.request.channel,
      itemId = item.id,
      item = mapOf("role" to "assistant", "content" to text)
    )
  }

  private fun extractTextFromItem(
    item: RealtimeItem,
    input: Boolean = false
  ): String? {
    val textTypes = if (input) setOf("input_text", "text") else setOf("output_text", "text")
    val audioTypes = if (input) setOf("input_audio", "audio") else setOf("output_audio", "audio")

    return item.content
      .orEmpty()
      .firstOrNull { it.type in textTypes }
      ?.text
      ?: item.content
        .orEmpty()
        .firstOrNull { it.type in audioTypes }
        ?.transcript
  }
}
