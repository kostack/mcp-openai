package io.github.kostack.mcp_openai.autoconfiguration

import io.github.kostack.event_dispatcher.SuspendDispatcher
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.RealtimeSidebandHandler
import io.github.kostack.mcp_openai.RealtimeSidebandRoutes
import io.github.kostack.mcp_openai.RealtimeSidebandRoutesImpl
import io.github.kostack.mcp_openai.dto.RealtimeCallRequest
import io.github.kostack.mcp_openai.dto.RealtimeCallResponse
import io.github.kostack.mcp_openai.dto.RealtimeEvent
import io.github.kostack.mcp_openai.dto.SidebandConnectRequest
import io.github.kostack.mcp_openai.dto.SidebandDisconnectRequest
import io.github.kostack.mcp_openai.dto.ToolDefinition
import io.github.kostack.mcp_openai.event.RealtimeHandlerEvent
import io.github.kostack.mcp_openai.listener.ConversationListener
import io.github.kostack.mcp_openai.registry.SidebandSessionRegistry
import io.github.kostack.mcp_openai.registry.SidebandSessionRegistryImpl
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistry
import io.github.kostack.mcp_openai.registry.WebSocketSessionRegistryImpl
import io.github.kostack.mcp_openai.service.ConversationStore
import io.github.kostack.mcp_openai.service.OpenAiHttpService
import io.github.kostack.mcp_openai.service.RealtimeEventHandler
import io.github.kostack.mcp_openai.service.RealtimeSidebandService
import io.github.kostack.mcp_openai.tool.ToolDispatcher
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import org.springframework.web.reactive.socket.client.WebSocketClient
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

  @Test
  fun `application services replace defaults regardless of bean names`() {
    contextRunner
      .withBean("customSidebandRegistry", SidebandSessionRegistry::class.java, { SidebandSessionRegistryImpl() })
      .withBean(
        "customWebSocketRegistry",
        WebSocketSessionRegistry::class.java,
        { WebSocketSessionRegistryImpl(ObjectMapper()) }
      ).withBean("customStore", ConversationStore::class.java, { mockk(relaxed = true) })
      .withBean("customToolDispatcher", ToolDispatcher::class.java, { mockk(relaxed = true) })
      .withBean("customHttpService", OpenAiHttpService::class.java, { mockk(relaxed = true) })
      .withBean("customEventHandler", RealtimeEventHandler::class.java, { mockk(relaxed = true) })
      .withBean("customSidebandService", RealtimeSidebandService::class.java, { mockk(relaxed = true) })
      .withBean("customSidebandHandler", RealtimeSidebandHandler::class.java, { mockk(relaxed = true) })
      .withBean("customConversationListener", ConversationListener::class.java, { mockk(relaxed = true) })
      .run { context ->
        assertThat(context).hasNotFailed()
        for (type in listOf(
          SidebandSessionRegistry::class.java,
          WebSocketSessionRegistry::class.java,
          ConversationStore::class.java,
          ToolDispatcher::class.java,
          OpenAiHttpService::class.java,
          RealtimeEventHandler::class.java,
          RealtimeSidebandService::class.java,
          RealtimeSidebandHandler::class.java,
          ConversationListener::class.java
        )) {
          assertThat(context.getBeansOfType(type)).hasSize(1)
        }
        for (name in listOf(
          "sidebandSessionRegistry",
          "webSocketSessionRegistry",
          "conversationStore",
          "toolDispatcher",
          "openAiHttpService",
          "realtimeEventHandler",
          "realtimeSidebandService",
          "realtimeSidebandHandler",
          "conversationListener"
        )) {
          assertThat(context).doesNotHaveBean(name)
        }
        assertThat(context).hasBean("realtimeSidebandRoutes")
      }
  }

  @Test
  fun `application websocket client replaces default regardless of bean name`() {
    val client = mockk<WebSocketClient>(relaxed = true)
    contextRunner
      .withBean("customWebSocketClient", WebSocketClient::class.java, { client })
      .run { context ->
        assertThat(context).hasNotFailed()
        assertThat(context).hasSingleBean(WebSocketClient::class.java)
        assertThat(context).doesNotHaveBean("realtimeSidebandWebSocketClient")
        assertThat(context.getBean(WebSocketClient::class.java)).isSameAs(client)
      }
  }

  @Test
  fun `named sideband client can be replaced by application`() {
    val client = mockk<WebSocketClient>(relaxed = true)
    contextRunner
      .withBean("realtimeSidebandWebSocketClient", WebSocketClient::class.java, { client })
      .run { context ->
        assertThat(context).hasNotFailed()
        assertThat(context).hasSingleBean(WebSocketClient::class.java)
        assertThat(context.getBean(WebSocketClient::class.java)).isSameAs(client)
      }
  }

  @Test
  fun `unrelated routes coexist with MCP routes`() {
    val routes = RouterFunctions.route().GET("/custom") { ServerResponse.ok().build() }.build()
    contextRunner
      .withBean("customRoutes", RouterFunction::class.java, { routes })
      .run { context ->
        assertThat(context).hasNotFailed()
        assertThat(context).hasBean("customRoutes").hasBean("realtimeSidebandRoutes")
      }
  }

  @Test
  fun `MCP routes can be replaced by type regardless of bean name`() {
    val routes =
      RealtimeSidebandRoutesImpl(
        RouterFunctions.route().GET("/custom-mcp") { ServerResponse.ok().build() }.build()
      )
    contextRunner
      .withBean("customMcpRoutes", RealtimeSidebandRoutes::class.java, { routes })
      .run { context ->
        assertThat(context).hasNotFailed()
        assertThat(context).hasSingleBean(RouterFunction::class.java)
        assertThat(context).doesNotHaveBean("realtimeSidebandRoutes")
        assertThat(context.getBean(RealtimeSidebandRoutes::class.java)).isSameAs(routes)
        WebTestClient
          .bindToRouterFunction(routes)
          .build()
          .get()
          .uri("/custom-mcp")
          .exchange()
          .expectStatus()
          .isOk
      }
  }

  @Test
  fun `custom service interfaces are injected into default handler and routes`() {
    val connected = mutableListOf<SidebandConnectRequest>()
    val disconnected = mutableListOf<SidebandDisconnectRequest>()
    val http =
      object : OpenAiHttpService {
        override suspend fun createCall(
          sdp: String,
          instructions: String,
          language: String,
          definitions: List<ToolDefinition>
        ) = RealtimeCallResponse("custom-answer", "custom-call")

        override suspend fun disconnect(callId: String) = Unit
      }
    val sideband =
      object : RealtimeSidebandService {
        override fun connect(request: SidebandConnectRequest) {
          connected.add(request)
        }

        override suspend fun disconnect(request: SidebandDisconnectRequest) {
          disconnected.add(request)
        }
      }
    contextRunner
      .withBean("customHttp", OpenAiHttpService::class.java, { http })
      .withBean("customSideband", RealtimeSidebandService::class.java, { sideband })
      .run { context ->
        assertThat(context).hasNotFailed()
        assertThat(context).doesNotHaveBean("openAiHttpService").doesNotHaveBean("realtimeSidebandService")
        val client = WebTestClient.bindToRouterFunction(context.getBean(RealtimeSidebandRoutes::class.java)).build()
        client
          .post()
          .uri("/api/realtime/calls")
          .bodyValue(RealtimeCallRequest("offer", "crm", "chat", "en", false))
          .exchange()
          .expectStatus()
          .isOk
          .expectBody()
          .jsonPath("$.callId")
          .isEqualTo("custom-call")
        assertThat(connected.map { it.callId }).containsExactly("custom-call")
        client
          .post()
          .uri("/api/realtime/disconnect")
          .bodyValue(SidebandDisconnectRequest("custom-call", "crm", "chat"))
          .exchange()
          .expectStatus()
          .isNoContent
        assertThat(disconnected.map { it.callId }).containsExactly("custom-call")
      }
  }

  @Test
  fun `interface declared listener bean is discovered by event dispatcher auto configuration`() {
    ApplicationContextRunner()
      .withConfiguration(
        AutoConfigurations.of(
          McpOpenAiAutoConfiguration::class.java,
          Class.forName("io.github.kostack.event_dispatcher.SuspendDispatcherConfiguration")
        )
      ).withBean(ObjectMapper::class.java, { ObjectMapper() })
      .run { context ->
        assertThat(context).hasNotFailed()
        runBlocking {
          context.getBean(SuspendDispatcher::class.java).publishSequential(
            RealtimeEvents.REALTIME_EVENT,
            RealtimeHandlerEvent(
              RealtimeEvent(
                type = "conversation.item.input_audio_transcription.completed",
                itemId = "item-1",
                transcript = "hello"
              ),
              SidebandConnectRequest("call-1", "crm", "chat", "en")
            )
          )
          assertThat(context.getBean(ConversationStore::class.java).history("chat"))
            .containsExactly(mapOf("id" to "item-1", "role" to "user", "content" to "hello"))
        }
      }
  }

  @Test
  fun `interface declared event handler retains shutdown cleanup`() {
    val started = CountDownLatch(1)
    val cancelled = CountDownLatch(1)
    val tools = mockk<ToolDispatcher>()
    coEvery { tools.execute(any(), any()) } coAnswers {
      started.countDown()
      try {
        awaitCancellation()
      } finally {
        cancelled.countDown()
      }
    }
    contextRunner.withBean("customTools", ToolDispatcher::class.java, { tools }).run { context ->
      assertThat(context).hasNotFailed()
      runBlocking {
        context.getBean(RealtimeEventHandler::class.java).handleInbound(
          RealtimeEvent(type = "response.function_call_arguments.done", name = "waiting", callId = "tool-1"),
          SidebandConnectRequest("call-1", "crm", "chat", "en")
        )
      }
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
    }
    assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue()
  }

  @Configuration(proxyBeanMethods = false)
  private class RequiredUserConfiguration {
    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper()

    @Bean
    fun suspendDispatcher(): SuspendDispatcher = SuspendDispatcher(emptyList())
  }
}
