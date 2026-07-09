package io.github.kostack.mcp_openai.autoconfiguration

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.RealtimeSidebandHandler
import io.github.kostack.mcp_openai.listener.ConversationListener
import io.github.kostack.mcp_openai.registry.SidebandSessionRegistry
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import io.github.kostack.mcp_openai.service.ConversationStore
import io.github.kostack.mcp_openai.service.OpenAiHttpService
import io.github.kostack.mcp_openai.service.RealtimeEventHandler
import io.github.kostack.mcp_openai.service.RealtimeSidebandService
import io.github.kostack.mcp_openai.tool.ToolDispatcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.reactive.socket.client.WebSocketClient
import tools.jackson.databind.ObjectMapper

class McpOpenAiAutoConfigurationTest {
  private val contextRunner =
    ApplicationContextRunner()
      .withConfiguration(
        AutoConfigurations.of(
          McpOpenAiAutoConfiguration::class.java
        )
      ).withUserConfiguration(RequiredUserConfiguration::class.java)

  @Test
  fun `auto configuration registers package beans without component scanning package`() {
    contextRunner.run { context ->
      assertThat(context).hasSingleBean(McpProperties::class.java)
      assertThat(context).hasBean("realtimeSidebandWebSocketClient")
      assertThat(context).hasSingleBean(WebSocketClient::class.java)
      assertThat(context).hasSingleBean(SidebandSessionRegistry::class.java)
      assertThat(context).hasSingleBean(WebSocketSessionRegistry::class.java)
      assertThat(context).hasSingleBean(ConversationStore::class.java)
      assertThat(context).hasSingleBean(ToolDispatcher::class.java)
      assertThat(context).hasSingleBean(OpenAiHttpService::class.java)
      assertThat(context).hasSingleBean(RealtimeEventHandler::class.java)
      assertThat(context).hasSingleBean(RealtimeSidebandService::class.java)
      assertThat(context).hasSingleBean(RealtimeSidebandHandler::class.java)
      assertThat(context).hasSingleBean(ConversationListener::class.java)
      assertThat(context).hasBean("realtimeSidebandRoutes")

      val sidebandClient = context.getBean("realtimeSidebandWebSocketClient", ReactorNettyWebSocketClient::class.java)
      assertThat(sidebandClient.websocketClientSpec.maxFramePayloadLength()).isEqualTo(1024 * 1024)
    }
  }

  @Configuration(proxyBeanMethods = false)
  private class RequiredUserConfiguration {
    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper()

    @Bean
    fun suspendDispatcher(): SuspendDispatcher = SuspendDispatcher(emptyList())
  }
}
