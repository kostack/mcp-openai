package io.github.kostack.mcp_openai.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class RealtimeEvent(
  val type: String,
  val item: RealtimeItem? = null,
  val response: RealtimeResponse? = null,
  val error: RealtimeError? = null,
  @JsonProperty("item_id")
  val itemId: String? = null,
  val name: String? = null,
  val arguments: String? = null,
  @JsonProperty("call_id")
  val callId: String? = null,
  val transcript: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RealtimeItem(
  val id: String? = null,
  val type: String? = null,
  val role: String? = null,
  val content: List<RealtimeContent>? = null,
  val name: String? = null,
  val arguments: String? = null,
  @JsonProperty("call_id")
  val callId: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RealtimeContent(
  val type: String? = null,
  val text: String? = null,
  val transcript: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RealtimeResponse(
  val output: List<RealtimeItem> = emptyList(),
  val usage: RealtimeUsage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RealtimeError(
  val message: String? = null,
  val type: String? = null,
  val code: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RealtimeUsage(
  @JsonProperty("input_tokens")
  val inputTokens: Long = 0,
  @JsonProperty("output_tokens")
  val outputTokens: Long = 0,
  @JsonProperty("total_tokens")
  val totalTokens: Long = 0
)
