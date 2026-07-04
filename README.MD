# MCP Open AI

MCP Open AI is a Kotlin/Spring Boot package for connecting a Realtime
client to OpenAI Realtime sessions and dispatching Realtime function calls to
server-side Kotlin tools.

Use it when you want to:

- Create short-lived OpenAI Realtime client secrets from your backend.
- Open a server-side sideband WebSocket for each Realtime call.
- Register Kotlin tools that OpenAI can call during a conversation.
- Return tool results back to the same Realtime conversation.
- Listen to Realtime lifecycle and conversation events in your application.

## Requirements

- Java 23
- Kotlin 2.3+
- Spring Boot 4.1+
- Spring WebFlux
- An OpenAI API key with Realtime API access

## Installation

Add the package to your application.

```kotlin
dependencies {
  implementation("io.github.kostack:mcp-openai:<version>")
}
```

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

## Configuration

Configure the package with the `kostack-mcp` prefix.

```yaml
kostack-mcp:
  api-key: ${OPENAI_API_KEY}
  model: gpt-realtime-mini
  transcription-model: gpt-realtime-whisper
  enable-audio: false
```

Available properties:

| Property | Default | Description |
| --- | --- | --- |
| `api-key` | empty | Your server-side OpenAI API key. Do not expose this to browsers. |
| `client-secrets-url` | `https://api.openai.com/v1/realtime/client_secrets` | OpenAI endpoint used to create ephemeral Realtime client secrets. |
| `sideband-url` | `wss://api.openai.com/v1/realtime` | OpenAI sideband WebSocket endpoint. |
| `sideband-prefix` | `/api/realtime` | Prefix for the built-in Realtime HTTP routes. |
| `model` | `gpt-realtime-mini` | Realtime model used when creating a session. |
| `transcription-model` | `gpt-realtime-whisper` | Audio transcription model used for input audio. |
| `enable-audio` | `false` | Application-level flag you can use when deciding whether to allow audio requests. |

## Core Concepts

### Namespace

A namespace groups tools and routes. When a token is created for a namespace,
only tools from that namespace are sent to OpenAI. When a tool call comes back,
the dispatcher executes a tool with a matching namespace and name.

Use namespaces when one application has separate assistants, tenants, or feature
areas.

### Channel

A channel identifies a conversation history in `ConversationStore`. Use a stable
value for the same user conversation, such as a chat id, ticket id, or room id.

### Call ID

The browser receives a call id from OpenAI when it creates the Realtime WebRTC
session. Send that call id to your backend so the package can open the matching
server-side sideband connection.

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

    return ToolResult(success = true, result = result)
  }
}
```

When OpenAI calls `get_weather`, the package decodes the JSON arguments into
`WeatherRequest`, runs `execute`, and sends the `ToolResult` back to OpenAI.

## Backend Routes

The package auto-configures a default `SidebandConfigurationRouter`. The
default routes use `kostack-mcp.sideband-prefix`, which is `/api/realtime` by
default:

- `POST /api/realtime/token`
- `POST /api/realtime/connect`
- `POST /api/realtime/disconnect`

The request body carries `namespace`, `channel`, and language/audio settings.
The handler uses `request.namespace` to select tool definitions. The default
token instructions are intentionally restrictive; set application instructions
in a `RealtimeEvents.TOKEN_PRE_CREATE` listener.

### Create A Token

Request:

```http
POST /api/realtime/token
Content-Type: application/json

{
  "namespace": "support_assistant",
  "channel": "conversation-123",
  "language": "en",
  "audioEnabled": false
}
```

Response:

```json
{
  "clientSecret": "ek_..."
}
```

Use the returned `clientSecret` only in the browser session that requested it.

### Connect The Sideband

After the browser creates the Realtime WebRTC call, send the OpenAI call id and
the client secret to your backend.

```http
POST /api/realtime/connect
Content-Type: application/json

{
  "callId": "call_abc123",
  "clientSecret": "ek_...",
  "namespace": "support_assistant",
  "channel": "conversation-123",
  "language": "en",
  "audioEnabled": false
}
```

Successful response:

```http
204 No Content
```

### Disconnect The Sideband

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

## Browser Flow Example

The browser is responsible for connecting to OpenAI Realtime with WebRTC. The
backend is responsible for creating the ephemeral client secret and sideband
connection.

```javascript
const channel = "conversation-123";
const namespace = "support_assistant";
const language = "en";

async function createToken() {
  const response = await fetch("/api/realtime/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      namespace,
      channel,
      language,
      audioEnabled: false
    })
  });

  return response.json();
}

function extractCallId(locationHeader) {
  const match = locationHeader.match(/\/v1\/realtime\/calls\/([^/?#]+)/);
  if (!match) throw new Error("Could not read Realtime call id");
  return match[1];
}

async function connectRealtime() {
  const { clientSecret } = await createToken();
  const pc = new RTCPeerConnection();
  const dc = pc.createDataChannel("oai-events");

  dc.onmessage = (event) => {
    const realtimeEvent = JSON.parse(event.data);
    console.log("Realtime event", realtimeEvent);
  };

  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);

  const sdpResponse = await fetch("https://api.openai.com/v1/realtime/calls", {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${clientSecret}`,
      "Content-Type": "application/sdp"
    },
    body: offer.sdp
  });

  const answer = await sdpResponse.text();
  await pc.setRemoteDescription({ type: "answer", sdp: answer });

  const callId = extractCallId(sdpResponse.headers.get("Location"));

  await fetch("/api/realtime/connect", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      callId,
      clientSecret,
      namespace,
      channel,
      language,
      audioEnabled: false
    })
  });

  return { pc, dc, callId };
}
```

Send a text message through the Realtime data channel:

```javascript
function sendText(dc, text) {
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
async function disconnectRealtime(pc, callId) {
  await fetch("/api/realtime/disconnect", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ callId, namespace, channel })
  });

  pc.close();
}
```

## Listening To Events

The package publishes events through `io.github.kostack:event-dispatcher`.
Register suspend listeners with `@SuspendListener`.

```kotlin
package com.example.demo

import io.github.kostack.event_dispatcher.SuspendListener
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.event.RealtimeConnectEvent
import io.github.kostack.mcp_openai.event.RealtimeHandlerEvent
import io.github.kostack.mcp_openai.event.RealtimeTokenPreCreateEvent
import org.springframework.stereotype.Component

@Component
class RealtimeListeners {
  @SuspendListener(RealtimeEvents.TOKEN_PRE_CREATE)
  suspend fun onTokenPreCreate(event: RealtimeTokenPreCreateEvent) {
    event.instructions =
      """
      You are a helpful support assistant.
      Reply in the user's selected language.
      Conversation history:
      :history_items
      """.trimIndent()
  }

  @SuspendListener(RealtimeEvents.CONNECT)
  suspend fun onConnect(event: RealtimeConnectEvent) {
    println("Connected call ${event.request.callId}")
  }

  @SuspendListener(RealtimeEvents.SESSION_START)
  suspend fun onSessionStart(event: RealtimeConnectEvent) {
    println("Sideband session started for ${event.request.callId}")
  }

  @SuspendListener(RealtimeEvents.RESPONSE_DONE)
  suspend fun onResponseDone(event: RealtimeHandlerEvent) {
    println("Response completed on ${event.request.channel}")
  }
}
```

Published event names:

| Event | When it is published |
| --- | --- |
| `kostack_mcp.token.pre_create` | Before an ephemeral token is created. |
| `kostack_mcp.connect` | Before the sideband connection is started. |
| `kostack_mcp.disconnect` | Before a sideband connection is cancelled. |
| `kostack_mcp.session.start` | After the sideband WebSocket session is available. |
| `kostack_mcp.conversation.item.done` | When a completed conversation item is received. |
| `kostack_mcp.conversation.item.input_audio_transcription.completed` | When input audio transcription completes. |
| `kostack_mcp.response.output.item.done` | When a response output item completes. |
| `kostack_mcp.response.done` | When a response completes. |
| `kostack_mcp.response.error` | When OpenAI sends an error event. |
| `kostack_mcp.session.updated` | When the Realtime session is updated. |

## Conversation History

`ConversationStore` stores conversation history by channel in memory. The
included `ConversationListener` records user and assistant messages and replaces
`:history_items` in `RealtimeTokenPreCreateEvent.instructions` when a token is
created.

Example instruction:

```text
You are a support assistant.
Use this conversation history when answering:
:history_items
```

For production applications, treat this as an in-memory default. If you need
durable history across restarts, persist your own history and update
`RealtimeTokenPreCreateEvent.instructions` in a token pre-create listener.

## Audio

Set `audioEnabled` to `true` in token and sideband requests only when your
application supports microphone access and audio output. If `enable-audio` is
`false` in configuration, the handler forces request audio off.

When audio is enabled, session creation includes:

- Server VAD turn detection.
- Input transcription using `kostack-mcp.transcription-model`.
- Output voice set to `alloy`.

## Error Handling

If tool execution throws an exception, `ToolDispatcher` returns:

```json
{
  "success": false,
  "result": "Error executing tool: tool_name, error: ..."
}
```

For expected business failures, return a normal `ToolResult` instead:

```kotlin
return ToolResult(
  success = false,
  result = mapOf("message" to "No matching account was found")
)
```

## Testing A Tool Directly

You can test tools without opening a Realtime session.

```kotlin
package com.example.demo.tools

import io.github.kostack.mcp_openai.dto.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class WeatherToolTest {
  @Test
  fun `returns weather`() = runTest {
    val tool = WeatherTool()

    val result =
      tool.execute(
        ToolContext(
          namespace = "support_assistant",
          channel = "test-channel",
          rawRequest = """{"city":"Athens"}"""
        )
      )

    assertTrue(result.success)
  }
}
```

## Full Minimal Backend Example

```kotlin
package com.example.demo

import io.github.kostack.event_dispatcher.SuspendListener
import io.github.kostack.mcp_openai.RealtimeEvents
import io.github.kostack.mcp_openai.event.RealtimeTokenPreCreateEvent
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
  @SuspendListener(RealtimeEvents.TOKEN_PRE_CREATE)
  suspend fun onTokenPreCreate(event: RealtimeTokenPreCreateEvent) {
    event.instructions = "You are a helpful assistant. Reply in English."
  }
}
```

## Security Notes

- Keep `kostack-mcp.api-key` server-side.
- Send only ephemeral Realtime client secrets to the browser.
- Protect your token and sideband endpoints with your application auth.
- Validate that the current user is allowed to access the requested channel.
- Do not trust tool arguments just because they came from the model; validate
  them in your tool implementation.
