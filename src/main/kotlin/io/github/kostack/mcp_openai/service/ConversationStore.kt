package io.github.kostack.mcp_openai.service

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class ConversationStore {
  private val sessions = ConcurrentHashMap<String, MutableList<Map<String, Any>>>()
  private val seenItems = ConcurrentHashMap<String, MutableSet<String>>()

  fun append(
    sessionId: String,
    item: Map<String, Any>
  ) {
    sessions.getOrPut(sessionId) { mutableListOf() }.add(item)
  }

  fun appendOnce(
    sessionId: String,
    itemId: String?,
    item: Map<String, Any>
  ) {
    if (itemId == null) {
      append(sessionId, item)
      return
    }

    val seen = seenItems.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }
    if (seen.add(itemId)) {
      append(sessionId, item)
    }
  }

  fun history(sessionId: String): List<Map<String, Any>> = sessions[sessionId] ?: emptyList()

  fun clear(sessionId: String) {
    sessions.remove(sessionId)
    seenItems.remove(sessionId)
  }
}
