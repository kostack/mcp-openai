package io.github.kostack.mcp_openai.controller

import freemarker.template.Configuration
import io.github.kostack.mcp_openai.service.ConversationStore
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.io.StringWriter

@RestController
class FrontendController(
  private val configuration: Configuration,
  private val conversationStore: ConversationStore
) {
  @GetMapping("/frontend-app/history", produces = [MediaType.APPLICATION_JSON_VALUE])
  suspend fun history(
    @RequestParam("channel") channel: String
  ): List<Map<String, Any>> = conversationStore.history(channel)

  @GetMapping("/frontend-app")
  fun getAction(): Mono<ResponseEntity<String>> =
    Mono
      .fromCallable {
        val html =
          StringWriter().use { writer ->
            configuration.getTemplate("rtc.ftl").process(mapOf("token" to "your-token"), writer)
            writer.toString()
          }

        ResponseEntity
          .ok()
          .contentType(MediaType.TEXT_HTML)
          .body(html)
      }.subscribeOn(Schedulers.boundedElastic())
}
