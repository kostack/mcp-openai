package io.github.kostack.mcp_openai

object RealtimeEvents {
  const val TOKEN_PRE_CREATE = "kostack_mcp.token.pre_create"
  const val CONNECT = "kostack_mcp.connect"
  const val DISCONNECT = "kostack_mcp.disconnect"
  const val SESSION_START = "kostack_mcp.session.start"

  const val CONVERSATION_ITEM_DONE = "kostack_mcp.conversation.item.done"
  const val CONVERSATION_ITEM_INPUT_AUDIO_TRANSCRIPTION_COMPLETED =
    "kostack_mcp.conversation.item.input_audio_transcription.completed"

  const val RESPONSE_OUTPUT_ITEM_DONE = "kostack_mcp.response.output.item.done"
  const val RESPONSE_DONE = "kostack_mcp.response.done"
  const val RESPONSE_ERROR = "kostack_mcp.response.error"

  const val SESSION_UPDATED = "kostack_mcp.session.updated"
}
