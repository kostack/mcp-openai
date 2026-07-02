package io.github.kostack.mcp_openai.listener

import io.github.kostack.event_dispatcher.SuspendListener
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.event.RealtimeConnectEvent
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import io.github.kostack.mcp_openai.service.ConversationStore
import io.github.kostack.mcp_openai.utils.RealtimeUtils
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.milliseconds

@Component
class RealtimeSessionListener(
  private val sessionRegistry: WebSocketSessionRegistry,
  private val conversationStore: ConversationStore
) {
  @SuspendListener(RealtimeEvents.SESSION_START)
  suspend fun onSessionStart(event: RealtimeConnectEvent) {
    if (conversationStore.history(event.request.channel).isNotEmpty()) return

    withTimeoutOrNull(3_000.milliseconds) {
      val message = "My nickname is Nik, greet me."
      sessionRegistry.sendJson(
        event.request.callId,
        RealtimeUtils.createResponse(event.request.modality, message)
      )
      conversationStore.append(event.request.channel, mapOf("role" to "user", "content" to message))
    }
  }
}
