# MCP Open AI

MCP Open AI is a Kotlin/Spring Boot package for connecting a Realtime
client to OpenAI Realtime sessions and dispatching Realtime function calls to
server-side Kotlin tools.

Use it when you want to:

- Create WebRTC calls on your backend using the OpenAI unified interface.
- Open a server-side sideband WebSocket for each Realtime call.
- Register Kotlin tools that OpenAI can call during a conversation.
- Return tool results back to the same Realtime conversation.
- Listen to Realtime lifecycle and conversation events in your application.

## Requirements

The current build uses:

- Java 25
- Kotlin 2.4.21
- Spring Boot 4.1.2 and Spring WebFlux
- Spring AI 2.0.0
- An OpenAI API key with Realtime API access

Enable the Kotlin Spring and serialization compiler plugins in your application.

## Installation

The library is published without its Spring, Spring AI, coroutine, serialization,
and event dispatcher dependencies. Add these to the consuming application.
The BOM versions below match this repository's build; omit the Spring Boot BOM
if your application already imports it.

```kotlin
dependencies {
  implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
  implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))

  implementation("io.github.kostack:mcp-openai:<version>")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.ai:spring-ai-model")
  implementation("io.github.kostack:event-dispatcher:1.3.0")
  implementation(kotlin("reflect"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
}
```

Kotlin reflection is required at runtime by the event dispatcher. The configuration
processor is already run for this library using kapt, and the resulting property
metadata is included in its JAR.

## Spring Boot Setup

Add the dependency and let Spring Boot auto-configuration register the package.
Your application does not need to scan `io.github.kostack.mcp_openai`.

```kotlin
package com.example.demo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DemoApplication

fun main(args: Array<String>) {
  runApplication<DemoApplication>(*args)
}
```

The package creates a default `WebClient` when your application does not
already provide one. Register your own `WebClient` bean only when you need
custom HTTP client settings.

```kotlin
package com.example.demo

import org.springframework.web.reactive.function.client.WebClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class HttpConfiguration {
  @Bean
  fun webClient(): WebClient = WebClient.create()
}
```

## Replacing Default Beans

Auto-configuration exposes the library beans through interfaces and uses
`@ConditionalOnMissingBean` to create defaults only when the corresponding
interface has no application bean. Register an implementation under any bean
name to replace the default.

| Interface | Default implementation |
| --- | --- |
| `OpenAiHttpService` | `OpenAiHttpServiceImpl` |
| `RealtimeSidebandService` | `RealtimeSidebandServiceImpl` |
| `RealtimeEventHandler` | `RealtimeEventHandlerImpl` |
| `RealtimeSidebandHandler` | `RealtimeSidebandHandlerImpl` |
| `ToolDispatcher` | `ToolDispatcherImpl` |
| `SidebandSessionRegistry` | `SidebandSessionRegistryImpl` |
| `WebSocketSessionRegistry` | `WebSocketSessionRegistryImpl` |
| `ConversationListener` | `ConversationListenerImpl` |
| `ConversationStore` | `ConversationStoreImpl` |
| `RealtimeSidebandRoutes` | `RealtimeSidebandRoutesImpl` |

For example, this application bean takes the place of the default HTTP service:

```kotlin
package com.example.demo

import io.github.kostack.mcp_openai.autoconfiguration.McpProperties
import io.github.kostack.mcp_openai.service.OpenAiHttpService
import io.github.kostack.mcp_openai.service.OpenAiHttpServiceImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration(proxyBeanMethods = false)
class McpConfiguration {
  @Bean
  fun applicationOpenAiHttpService(
    webClient: WebClient,
    properties: McpProperties
  ): OpenAiHttpService = OpenAiHttpServiceImpl(webClient, properties)
}
```

Return your own implementation of the interface to customize its behavior.
Inject interfaces in application code. Code that previously constructed a service,
registry, handler, or listener directly must now construct its `Impl` class.
`ConversationStore` and `ConversationStoreImpl` retain their existing API.

An application `WebSocketClient` bean also replaces the default client, regardless
of its name. If multiple beans implement an injected interface, select one with
`@Primary`. Application-provided WebSocket clients are responsible for their own
frame-size configuration.

`RealtimeSidebandRoutes` extends `RouterFunction<ServerResponse>`. It has its own
interface so unrelated application routes can coexist with the MCP routes.
To replace the MCP routes, expose a `RealtimeSidebandRoutes` bean; the default
`RealtimeSidebandRoutesImpl` can wrap a custom `coRouter`.

## Configuration

Configure the package with the `kostack-mcp` prefix.

```yaml
kostack-mcp:
  api-key: ${OPENAI_API_KEY}
  model: gpt-realtime-mini
  transcription-model: gpt-realtime-whisper
  sideband-max-frame-payload-length: 1048576
  enable-audio: false
```

Available properties:

| Property | Default | Description |
| --- | --- | --- |
| `api-key` | empty | Your server-side OpenAI API key. Do not expose this to browsers. |
| `calls-url` | `https://api.openai.com/v1/realtime/calls` | OpenAI unified call creation endpoint. |
| `sideband-url` | `wss://api.openai.com/v1/realtime` | OpenAI sideband WebSocket endpoint. |
| `sideband-prefix` | `/api/realtime` | Prefix for the built-in Realtime HTTP routes. |
| `sideband-max-frame-payload-length` | `1048576` | Maximum inbound sideband WebSocket frame payload length in bytes. Increase this if large Realtime events exceed the default. |
| `model` | `gpt-realtime-mini` | Realtime model used when creating a session. |
| `transcription-model` | `gpt-realtime-whisper` | Audio transcription model used for input audio. |
| `enable-audio` | `false` | Allows requests to enable audio. When false, the handler forces request audio off. |
| `outbound-buffer-capacity` | `256` | Maximum queued outbound messages per sideband connection. Overflow fails the outbound stream so the connection can be cleaned up. |
| `tool-max-concurrent-calls` | `16` | Maximum tool calls executing across all sessions. Excess calls receive a capacity error result without being queued. |
| `tool-timeout-millis` | `30000` | Deadline for each tool execution. Timeout returns an error result. Tools must cooperate with coroutine cancellation. |

All limits must be positive.

## Core Concepts

### Namespace

A namespace groups tools and routes. When a call is created for a namespace,
only tools from that namespace are sent to OpenAI. When a tool call comes back,
the dispatcher executes a tool with a matching namespace and name.

Use namespaces when one application has separate assistants, tenants, or feature
areas.

### Channel

A channel identifies a conversation history in `ConversationStore`. Use a stable
value for the same user conversation, such as a chat id, ticket id, or room id.

### Call ID

The backend obtains a call id from OpenAI, starts the matching sideband connection,
and returns the id with the SDP answer. Keep the id in the browser and send it
back when disconnecting the call.

## Define A Tool

Create a request data class for the tool arguments.

```kotlin
package com.example.demo.tools

import kotlinx.serialization.Serializable
import org.springframework.ai.tool.annotation.ToolParam

@Serializable
data class WeatherRequest(
  @ToolParam(description = "City name, for example Athens or Berlin")
  val city: String
)
```

Create a result type.

```kotlin
package com.example.demo.tools

data class WeatherResult(
  val city: String,
  val summary: String,
  val temperatureCelsius: Int
)
```

Implement a Spring component that extends `AbstractTool`.

```kotlin
package com.example.demo.tools

import io.github.kostack.mcp_openai.dto.ToolContext
import io.github.kostack.mcp_openai.dto.ToolDefinition
import io.github.kostack.mcp_openai.dto.ToolResult
import io.github.kostack.mcp_openai.tool.AbstractTool
import io.github.kostack.mcp_openai.utils.ToolSchemaUtils
import org.springframework.stereotype.Component

@Component
class WeatherTool : AbstractTool() {
  override val namespace: String = "support_assistant"
  override val toolName: String = "get_weather"
  override val description: String = "Gets the current weather for a city."

  override fun getDefinition(): ToolDefinition =
    ToolDefinition(
      namespace = namespace,
      name = toolName,
      description = description,
      parameters = ToolSchemaUtils.toParameters<WeatherRequest>()
    )

  override suspend fun execute(context: ToolContext): ToolResult {
    val request = context.getRequest<WeatherRequest>()

    val result =
      WeatherResult(
        city = request.city,
        summary = "Clear",
        temperatureCelsius = 27
      )

    return ToolResult(result = result)
  }
}
```

When OpenAI calls `get_weather`, the package decodes the JSON arguments into
`WeatherRequest` and runs `execute`. `ToolResult.mode` controls what happens next:

| Mode | Behavior |
| --- | --- |
| `REALTIME` (default) | Sends the result back to OpenAI and requests an assistant response using the session's current modality. |
| `DIRECT` | Sends OpenAI a delivery acknowledgement containing the result, without requesting an assistant response. Your tool/application handles client delivery, for example through SSE. |

For direct delivery, send the result through your application's SSE layer within
your tool implementation, then return:

```kotlin
return ToolResult(
  result = result,
  mode = ToolResult.ToolResultMode.DIRECT
)
```

Use `context.channel` and `context.sessionId` to route the SSE message and
`context.toolCallId` to correlate it with the function call. `DIRECT` does not
publish an application event or provide an SSE endpoint. It applies to both
successful and failed results. Exceptions handled by `ToolDispatcherImpl` produce a
failed result with the default `REALTIME` mode; return a failed `DIRECT` result
explicitly when your tool handles and delivers an error itself.

## Backend Routes

The package auto-configures a default Realtime router. The
default routes use `kostack-mcp.sideband-prefix`, which is `/api/realtime` by
default:

- `POST /api/realtime/calls` (recommended: creates the call and starts sideband attachment)
- `POST /api/realtime/disconnect`

The request body carries `namespace`, `channel`, and language/audio settings.
The handler uses `request.namespace` to select tool definitions. The default
call instructions are intentionally restrictive; set application instructions
in a `RealtimeEvents.PRE_CONNECT` listener.

### Create A Call (Unified Interface)

Send the browser's SDP offer and application settings to your backend:

```http
POST /api/realtime/calls
Content-Type: application/json

{
  "sdp": "v=0\r\n...",
  "namespace": "support_assistant",
  "channel": "conversation-123",
  "language": "en",
  "audioEnabled": false
}
```

The backend runs `PRE_CONNECT` listeners for instructions and history,
posts multipart `sdp` and `session` fields to OpenAI using `kostack-mcp.api-key`,
and starts the sideband with that same API key. The response contains only
`{"sdp": "...answer SDP...", "callId": "rtc_..."}`. Apply the answer with
`pc.setRemoteDescription()`. No separate `/token` or `/connect` request is needed.
Sideband attachment runs asynchronously; the HTTP response does not confirm its readiness.

### Disconnect A Call

```http
POST /api/realtime/disconnect
Content-Type: application/json

{
  "callId": "call_abc123",
  "namespace": "support_assistant",
  "channel": "conversation-123"
}
```

Successful response:

```http
204 No Content
```

The disconnect route publishes `DISCONNECT`, hangs up the OpenAI call, and cancels
the sideband job. Cleanup still runs if a disconnect listener fails. If a `CONNECT`
listener or sideband attachment setup throws after call creation, the handler
attempts cleanup and preserves the original error. Cleanup attempts have a
five-second deadline. A dropped or failed sideband also attempts hangup and
removes its session state.

## Browser Flow Example

The browser exchanges SDP through your backend; media and data use WebRTC directly
with OpenAI. The backend creates the call and sideband using the same server API key.
Apply your application's authentication headers to the request below.

```javascript
async function connectRealtime() {
  const namespace = "support_assistant";
  const channel = "conversation-123";
  const pc = new RTCPeerConnection();
  let dc = null;
  let callId = null;

  try {
    pc.addTransceiver("audio", { direction: "recvonly" });
    dc = pc.createDataChannel("oai-events");
    dc.onmessage = (event) => console.log(JSON.parse(event.data));

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    const response = await fetch("/api/realtime/calls", {
      signal: AbortSignal.timeout(15_000),
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        sdp: offer.sdp,
        namespace,
        channel,
        language: "en",
        audioEnabled: false
      })
    });
    if (!response.ok) throw new Error(await response.text());
    const call = await response.json();
    callId = call.callId;
    await pc.setRemoteDescription({ type: "answer", sdp: call.sdp });
    return { pc, dc, callId, namespace, channel };
  } catch (error) {
    try {
      await disconnectRealtime({ pc, dc, callId, namespace, channel });
    } catch (cleanupError) {
      console.error("Call cleanup failed:", cleanupError);
    }
    throw error;
  }
}
```

Send a text message through the Realtime data channel:

```javascript
function sendText(dc, text) {
  if (dc.readyState !== "open") throw new Error("Data channel is not open");
  dc.send(JSON.stringify({
    type: "conversation.item.create",
    item: {
      type: "message",
      role: "user",
      content: [
        {
          type: "input_text",
          text
        }
      ]
    }
  }));

  dc.send(JSON.stringify({
    type: "response.create",
    response: {
      output_modalities: ["text"]
    }
  }));
}
```

Disconnect when the session ends:

```javascript
async function disconnectRealtime({ pc, dc, callId, namespace, channel }) {
  // Release local resources before waiting for the server.
  dc?.close();
  pc.close();
  if (!callId) return;

  const response = await fetch("/api/realtime/disconnect", {
    signal: AbortSignal.timeout(5_000),
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ callId, namespace, channel })
  });
  if (!response.ok) throw new Error("Hangup failed: HTTP " + response.status);
}
```

Call `sendText(session.dc, text)` after the data channel opens and
`disconnectRealtime(session)` to close the object returned by `connectRealtime()`.
The example above uses text mode. The fixture template in
`src/testFixtures/resources/templates/rtc.ftl` additionally handles microphone
tracks, failed setup, repeated Connect clicks, and connection-close events.

## Listening To Events

The package publishes events through `io.github.kostack:event-dispatcher`.
Register suspend listeners with `@SuspendListener`.

```kotlin
package com.example.demo

import io.github.kostack.event_dispatcher.SuspendListener
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.event.RealtimeConnectEvent
import io.github.kostack.mcp_openai.event.RealtimeHandlerEvent
import io.github.kostack.mcp_openai.event.RealtimePreConnectEvent
import io.github.kostack.mcp_openai.service.ConversationStore
import org.springframework.stereotype.Component

@Component
class RealtimeListeners(
  private val conversationStore: ConversationStore
) {
  @SuspendListener(RealtimeEvents.PRE_CONNECT)
  suspend fun onPreConnect(event: RealtimePreConnectEvent) {
    event.instructions =
      """
      You are a helpful support assistant.
      Reply in the user's selected language.
      Conversation history:
      ${conversationStore.history(event.request.channel).joinToString("\n")}
      """.trimIndent()
  }

  @SuspendListener(RealtimeEvents.CONNECT)
  suspend fun onConnect(event: RealtimeConnectEvent) {
    println("Call created ${event.request.callId}; sideband attachment is starting")
  }

  @SuspendListener(RealtimeEvents.SESSION_START)
  suspend fun onSessionStart(event: RealtimeConnectEvent) {
    println("Sideband session started for ${event.request.callId}")
  }

  @SuspendListener(RealtimeEvents.REALTIME_EVENT)
  suspend fun onResponseDone(event: RealtimeHandlerEvent) {
    if (event.realtimeEvent.type != "response.done") return
    println("Response completed on ${event.request.channel}")
  }
}
```

Published event names:

| Event | When it is published |
| --- | --- |
| `kostack_mcp.pre_connect` | Before a call is created; listeners supply instructions. |
| `kostack_mcp.connect` | Before the sideband connection is started. |
| `kostack_mcp.disconnect` | Before an explicit disconnect attempts hangup and cancels the sideband. Remote closure does not publish this event. |
| `kostack_mcp.session.start` | After the sideband WebSocket session is available. |
| `kostack_mcp.realtime_event` | Inbound events other than function-call arguments completion; filter by `event.realtimeEvent.type`. |

## Conversation History

`ConversationStore` stores conversation history by channel in memory. The
default `ConversationListenerImpl` records user and assistant messages. To include
history in a new call, read `ConversationStore.history(channel)` in a
`PRE_CONNECT` listener and insert it into `RealtimePreConnectEvent.instructions`.
Placeholder replacement is the responsibility of your listener.

Example instruction:

```text
You are a support assistant.
Use this conversation history when answering:
:history_items
```

For production applications, treat this as an in-memory default. If you need
durable history across restarts, persist your own history and update
`RealtimePreConnectEvent.instructions` in a `PRE_CONNECT` listener.

## Audio

Set `audioEnabled` to `true` in call requests only when your
application supports microphone access and audio output. If `enable-audio` is
`false` in configuration, the handler forces request audio off.

Session creation currently always includes the following audio configuration;
`enable-audio` controls the requested modality for tool follow-up responses:

- Server VAD turn detection.
- Input transcription using `kostack-mcp.transcription-model`.
- Output voice set to `alloy`.

## Error Handling

If tool execution throws an exception, the default `ToolDispatcherImpl` returns:

```json
{
  "mode": "REALTIME",
  "result": "Error executing tool: tool_name, error: ..."
}
```

For expected business failures, return a normal `ToolResult` instead:

```kotlin
return ToolResult(
  result = mapOf("message" to "No matching account was found")
)
```

Capacity exhaustion and the configured tool deadline return `REALTIME` error
results containing `Tool execution capacity exceeded` and `Tool execution timed out`,
respectively. Cancellation caused by disconnecting a session cancels its tool
jobs. Tools must cooperate with coroutine cancellation for deadlines and cleanup
to take effect.

## Testing A Tool Directly

You can test tools without opening a Realtime session.

```kotlin
package com.example.demo.tools

import io.github.kostack.mcp_openai.dto.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class WeatherToolTest {
  @Test
  fun `returns weather`() = runTest {
    val tool = WeatherTool()

    val result =
      tool.execute(
        ToolContext(
          namespace = "support_assistant",
          channel = "test-channel",
          sessionId = "test-session",
          toolCallId = "test-tool-call",
          rawRequest = """{"city":"Athens"}"""
        )
      )

    assertNotNull(result.result)
  }
}
```

## Full Minimal Backend Example

```kotlin
package com.example.demo

import io.github.kostack.event_dispatcher.SuspendListener
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.event.RealtimePreConnectEvent
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Component

@SpringBootApplication
class DemoApplication

fun main(args: Array<String>) {
  runApplication<DemoApplication>(*args)
}

@Component
class DemoRealtimeListeners {
  @SuspendListener(RealtimeEvents.PRE_CONNECT)
  suspend fun onPreConnect(event: RealtimePreConnectEvent) {
    event.instructions = "You are a helpful assistant. Reply in English."
  }
}
```

## Development And Fixture Application

Run backend tests, formatting checks, and coverage verification:

```bash
./gradlew check
```

Run the fixture frontend lifecycle tests with Node.js:

```bash
node scripts/rtc.test.cjs
```

The test fixtures include `io.github.kostack.mcp_openai.TestApplicationKt` and a
browser demo at `/frontend-app`. Copy
`src/testFixtures/resources/application.yml.dist` to `application.yml` in the
same directory, set `API_KEY`, and run `TestApplicationKt` from your IDE using
the test-fixtures runtime classpath and Java 25. The fixtures are not published
with the library. No Spring Boot `bootRun` task is configured for this library.

The demo supports text and push-to-talk audio. It releases microphone, audio,
data-channel, and peer-connection resources on failure or disconnect, and prevents
overlapping connection attempts. Set its placeholder auth token when testing
against an application that requires authentication.

## Security Notes

- Keep `kostack-mcp.api-key` server-side.
- The unified call flow returns only SDP and a call ID to the browser.
- Protect your call and disconnect endpoints with your application auth.
- Validate that the current user is allowed to access the requested channel.
- Do not trust tool arguments just because they came from the model; validate
  them in your tool implementation.
