package io.github.kostack.mcp_openai.service

interface ConversationStore {
  suspend fun append(
    sessionId: String,
    item: Map<String, Any>
  )

  suspend fun appendOnce(
    sessionId: String,
    itemId: String?,
    item: Map<String, Any>
  )

  suspend fun history(sessionId: String): List<Map<String, Any>>

  suspend fun clear(sessionId: String)
}
