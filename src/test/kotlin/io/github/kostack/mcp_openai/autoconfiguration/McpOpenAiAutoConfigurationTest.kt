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
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
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

  @Test
  fun `auto configuration binds all configured properties`() {
    contextRunner
      .withPropertyValues(
        "kostack-mcp.api-key=test-key",
        "kostack-mcp.sideband-url=wss://example.com/realtime",
        "kostack-mcp.sideband-prefix=/custom/realtime",
        "kostack-mcp.sideband-max-frame-payload-length=2097152",
        "kostack-mcp.model=test-model",
        "kostack-mcp.transcription-model=test-transcription-model",
        "kostack-mcp.enable-audio=true",
        "kostack-mcp.calls-url=https://example.com/realtime/calls"
      ).run { context ->
        assertThat(context).hasNotFailed()
        val properties = context.getBean(McpProperties::class.java)
        assertThat(properties.apiKey).isEqualTo("test-key")
        assertThat(properties.sidebandUrl).isEqualTo("wss://example.com/realtime")
        assertThat(properties.sidebandPrefix).isEqualTo("/custom/realtime")
        assertThat(properties.sidebandMaxFramePayloadLength).isEqualTo(2097152)
        assertThat(properties.model).isEqualTo("test-model")
        assertThat(properties.transcriptionModel).isEqualTo("test-transcription-model")
        assertThat(properties.enableAudio).isTrue()
        assertThat(properties.callsUrl).isEqualTo("https://example.com/realtime/calls")

        val sidebandClient = context.getBean("realtimeSidebandWebSocketClient", ReactorNettyWebSocketClient::class.java)
        assertThat(sidebandClient.websocketClientSpec.maxFramePayloadLength()).isEqualTo(2097152)
      }
  }

  @Test
  fun `properties support setter binding`() {
    val properties = McpProperties()
    val source =
      MapConfigurationPropertySource(
        mapOf(
          "kostack-mcp.enable-audio" to "true",
          "kostack-mcp.sideband-prefix" to "/custom/realtime",
          "kostack-mcp.sideband-max-frame-payload-length" to "2097152",
          "kostack-mcp.transcription-model" to "test-transcription-model"
        )
      )

    Binder(source).bind("kostack-mcp", Bindable.ofInstance(properties))

    assertThat(properties.enableAudio).isTrue()
    assertThat(properties.sidebandPrefix).isEqualTo("/custom/realtime")
    assertThat(properties.sidebandMaxFramePayloadLength).isEqualTo(2097152)
    assertThat(properties.transcriptionModel).isEqualTo("test-transcription-model")
  }

  @Configuration(proxyBeanMethods = false)
  private class RequiredUserConfiguration {
    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper()

    @Bean
    fun suspendDispatcher(): SuspendDispatcher = SuspendDispatcher(emptyList())
  }
}
