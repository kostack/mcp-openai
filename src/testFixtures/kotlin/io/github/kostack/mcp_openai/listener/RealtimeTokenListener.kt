package io.github.kostack.mcp_openai.listener

import io.github.kostack.event_dispatcher.SuspendListener
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.event.RealtimeTokenPreCreateEvent
import org.springframework.stereotype.Component

@Component
class RealtimeTokenListener {
  private val instructions: String =
    """
    You are a helpful assistant. I will ask you who lives in a certain country and 
    you will find the person and reply.
    Always reply in English. Context of the chat :history_items
    """.trimIndent()

  @SuspendListener(RealtimeEvents.TOKEN_PRE_CREATE)
  suspend fun onTokenPreCreate(event: RealtimeTokenPreCreateEvent) {
    event.instructions = instructions
  }
}
