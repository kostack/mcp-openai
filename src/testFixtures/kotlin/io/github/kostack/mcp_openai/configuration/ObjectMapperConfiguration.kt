package io.github.kostack.mcp_openai.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
class ObjectMapperConfiguration {
  @Bean
  fun objectMapper(): ObjectMapper = ObjectMapper()
}
