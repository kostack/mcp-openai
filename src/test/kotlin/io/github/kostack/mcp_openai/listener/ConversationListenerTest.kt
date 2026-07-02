package io.github.kostack.mcp_openai.listener

import io.github.kostack.mcp_openai.dto.RealtimeContent
import io.github.kostack.mcp_openai.dto.RealtimeEvent
import io.github.kostack.mcp_openai.dto.RealtimeItem
import io.github.kostack.mcp_openai.dto.RealtimeResponse
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.event.RealtimeHandlerEvent
import io.github.kostack.mcp_openai.service.ConversationStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationListenerTest {
  private val conversationStore = ConversationStore()
  private val listener = ConversationListener(conversationStore)

  @Test
  fun `conversation item done appends user text once`() =
    runTest {
      val event =
        event(
          RealtimeEvent(
            type = "conversation.item.done",
            item =
              RealtimeItem(
                id = "item-1",
                type = "message",
                role = "user",
                content = listOf(RealtimeContent(type = "input_text", text = "Hello"))
              )
          )
        )

      listener.onConversationItemDone(event)
      listener.onConversationItemDone(event)

      assertEquals(
        listOf(mapOf("role" to "user", "content" to "Hello")),
        conversationStore.history("web")
      )
    }

  @Test
  fun `conversation item done ignores non user messages`() =
    runTest {
      listener.onConversationItemDone(
        event(
          RealtimeEvent(
            type = "conversation.item.done",
            item =
              RealtimeItem(
                id = "item-1",
                type = "message",
                role = "assistant",
                content = listOf(RealtimeContent(type = "text", text = "Ignored"))
              )
          )
        )
      )

      assertEquals(emptyList(), conversationStore.history("web"))
    }

  @Test
  fun `input audio transcription completed appends non blank transcript once`() =
    runTest {
      val event =
        event(
          RealtimeEvent(
            type = "conversation.item.input_audio_transcription.completed",
            itemId = "audio-item-1",
            transcript = "Audio transcript"
          )
        )

      listener.onInputAudioTranscriptionCompleted(event)
      listener.onInputAudioTranscriptionCompleted(event)

      assertEquals(
        listOf(mapOf("role" to "user", "content" to "Audio transcript")),
        conversationStore.history("web")
      )
    }

  @Test
  fun `input audio transcription completed ignores blank transcript`() =
    runTest {
      listener.onInputAudioTranscriptionCompleted(
        event(
          RealtimeEvent(
            type = "conversation.item.input_audio_transcription.completed",
            itemId = "audio-item-1",
            transcript = " "
          )
        )
      )

      assertEquals(emptyList(), conversationStore.history("web"))
    }

  @Test
  fun `response done appends assistant text output`() =
    runTest {
      listener.onResponseDone(
        event(
          RealtimeEvent(
            type = "response.done",
            response =
              RealtimeResponse(
                output =
                  listOf(
                    RealtimeItem(
                      id = "assistant-item-1",
                      type = "message",
                      role = "assistant",
                      content = listOf(RealtimeContent(type = "output_text", text = "Answer"))
                    ),
                    RealtimeItem(
                      id = "user-item-1",
                      type = "message",
                      role = "user",
                      content = listOf(RealtimeContent(type = "input_text", text = "Ignored"))
                    )
                  )
              )
          )
        )
      )

      assertEquals(
        listOf(mapOf("role" to "assistant", "content" to "Answer")),
        conversationStore.history("web")
      )
    }

  @Test
  fun `response done falls back to assistant audio transcript`() =
    runTest {
      listener.onResponseDone(
        event(
          RealtimeEvent(
            type = "response.done",
            response =
              RealtimeResponse(
                output =
                  listOf(
                    RealtimeItem(
                      id = "assistant-item-1",
                      type = "message",
                      role = "assistant",
                      content =
                        listOf(
                          RealtimeContent(
                            type = "output_audio",
                            transcript = "Spoken answer"
                          )
                        )
                    )
                  )
              )
          )
        )
      )

      assertEquals(
        listOf(mapOf("role" to "assistant", "content" to "Spoken answer")),
        conversationStore.history("web")
      )
    }

  private fun event(realtimeEvent: RealtimeEvent): RealtimeHandlerEvent =
    RealtimeHandlerEvent(
      realtimeEvent = realtimeEvent,
      request =
        SidebandConnectRequest(
          callId = "call-123",
          clientSecret = "client-secret",
          namespace = "crm",
          channel = "web",
          language = "en"
        )
    )
}
