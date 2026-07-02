package io.github.kostack.mcp_openai.listener

import io.github.kostack.event_dispatcher.SuspendListener
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.dto.RealtimeItem
import io.github.kostack.mcp_openai.event.RealtimeHandlerEvent
import io.github.kostack.mcp_openai.event.RealtimeTokenPreCreateEvent
import io.github.kostack.mcp_openai.service.ConversationStore
import org.springframework.stereotype.Component
import kotlin.collections.firstOrNull
import kotlin.collections.orEmpty

@Component
class ConversationListener(
  private val conversationStore: ConversationStore
) {
  @SuspendListener(RealtimeEvents.TOKEN_PRE_CREATE, priority = -100)
  suspend fun onTokenPreCreate(event: RealtimeTokenPreCreateEvent) {
    val history = conversationStore.history(event.request.channel)
    if (history.isNotEmpty()) {
      event.instructions =
        event.instructions.replace(":history_items", history.joinToString("\n"))
    }
  }

  @SuspendListener(RealtimeEvents.CONVERSATION_ITEM_DONE)
  suspend fun onConversationItemDone(event: RealtimeHandlerEvent) {
    val realtimeEvent = event.realtimeEvent
    val item = realtimeEvent.item
    if (item?.type != "message" || item.role != "user") return
    val text = extractTextFromItem(item, input = true) ?: return

    conversationStore.appendOnce(
      sessionId = event.request.channel,
      itemId = item.id,
      item = mapOf("role" to "user", "content" to text)
    )
  }

  @SuspendListener(RealtimeEvents.CONVERSATION_ITEM_INPUT_AUDIO_TRANSCRIPTION_COMPLETED)
  suspend fun onInputAudioTranscriptionCompleted(event: RealtimeHandlerEvent) {
    val realtimeEvent = event.realtimeEvent
    val transcript = realtimeEvent.transcript?.takeIf { it.isNotBlank() } ?: return

    conversationStore.appendOnce(
      sessionId = event.request.channel,
      itemId = realtimeEvent.itemId,
      item = mapOf("role" to "user", "content" to transcript)
    )
  }

  @SuspendListener(RealtimeEvents.RESPONSE_DONE)
  suspend fun onResponseDone(event: RealtimeHandlerEvent) {
    val realtimeEvent = event.realtimeEvent
    val output = realtimeEvent.response?.output.orEmpty()

    output
      .filter { it.type == "message" && it.role == "assistant" }
      .mapNotNull { extractTextFromItem(it, input = false) }
      .forEach { text ->
        conversationStore.append(
          event.request.channel,
          mapOf("role" to "assistant", "content" to text)
        )
      }
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

  companion object {
    private val log = org.slf4j.LoggerFactory.getLogger(ConversationListener::class.java)
  }
}
