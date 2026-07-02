package io.github.kostack.mcp_openai.configuration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class WebClientConfiguration {
  @Bean
  fun mcpOpenAiClient(): WebClient = WebClient.create()
}
