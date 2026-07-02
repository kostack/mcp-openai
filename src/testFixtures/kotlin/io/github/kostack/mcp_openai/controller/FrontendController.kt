package io.github.kostack.mcp_openai.controller

import freemarker.template.Configuration
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.io.StringWriter

@RestController
class FrontendController(
  private val configuration: Configuration
) {
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
