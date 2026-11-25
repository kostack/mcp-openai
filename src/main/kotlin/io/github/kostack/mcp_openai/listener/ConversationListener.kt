package io.github.kostack.mcp_openai.listener

import io.github.kostack.event_dispatcher.SuspendListener
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.event.RealtimeHandlerEvent

interface ConversationListener {
  @SuspendListener(RealtimeEvents.REALTIME_EVENT)
  suspend fun onFunctionOutputDone(event: RealtimeHandlerEvent)

  @SuspendListener(RealtimeEvents.REALTIME_EVENT)
  suspend fun onConversationItemDone(event: RealtimeHandlerEvent)

  @SuspendListener(RealtimeEvents.REALTIME_EVENT)
  suspend fun onInputAudioTranscriptionCompleted(event: RealtimeHandlerEvent)

  @SuspendListener(RealtimeEvents.REALTIME_EVENT)
  suspend fun onResponseDone(event: RealtimeHandlerEvent)

  @SuspendListener(RealtimeEvents.REALTIME_EVENT)
  suspend fun onResponseOutputItemDone(event: RealtimeHandlerEvent)
}
