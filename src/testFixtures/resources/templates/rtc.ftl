<!doctype html>
<html>
<head>
  <meta charset="UTF-8" />
  <title>Realtime WebRTC Chat</title>
  <meta name="authToken" content="your-jwt-token">
  <style>
    body { font-family: sans-serif; max-width: 900px; margin: 30px auto; }
    #messages { border: 1px solid #ccc; padding: 12px; height: 400px; overflow-y: auto; }
    .msg { margin: 8px 0; padding: 8px; border-radius: 6px; }
    .user { background: #eef; }
    .assistant { background: #efe; }
    .system { background: #eee; font-size: 13px; }
    .thinking { background: #efe; color: #888; font-style: italic; }
    .thinking::after { content: ''; animation: dots 1.2s steps(3, end) infinite; }
    @keyframes dots { 0%,20%{content:'.'} 40%{content:'..'} 60%,100%{content:'...'} }
    #inputRow { margin-top: 12px; display: flex; gap: 8px; }
    #textInput { flex: 1; padding: 8px; }
    #talkBtn { margin-left: 8px; padding: 8px 14px; }
    #talkBtn.talking { font-weight: bold; }
  </style>
</head>
<body>
<h2>Realtime WebRTC Chat</h2>

<label><input type="checkbox" id="audioCheckbox" /> Enable audio</label>
<button id="connectBtn">Connect</button>
<button id="disconnectBtn" disabled>Disconnect</button>
<button id="talkBtn" disabled hidden>Start talking</button>

<div id="messages"></div>

<div id="inputRow">
  <input id="textInput" placeholder="Type message..." disabled />
  <button id="sendBtn" disabled>Send</button>
</div>

<script>
  <#noparse>
  let pc = null;
  let dc = null;
  let localStream = null;
  let callId = null;
  let audioEl = null;
  let thinkingEl = null;
  let streamingEl = null;
  let pendingVoiceUserEl = null;
  let pendingFunctionCall = false;
  let currentResponseId = null;
  let assistantResponseActive = false;
  let assistantAudioPlaying = false;
  let pushToTalkActive = false;
  let realtimeConnected = false;

  const NAMESPACE = "default";
  const channel = "channel-1";
  const LANGUAGE = "en";
  const AUDIO_ENABLED = () => document.getElementById("audioCheckbox").checked;

  const AUTH_TOKEN = document.querySelector('meta[name="authToken"]').content;
  const authToken = encodeURIComponent(AUTH_TOKEN);

  function showThinking(label = "thinking") {
    if (thinkingEl) {
      thinkingEl.textContent = `assistant: ${label}`;
      return;
    }

    const messages = document.getElementById("messages");
    thinkingEl = document.createElement("div");
    thinkingEl.className = "msg thinking";
    thinkingEl.textContent = `assistant: ${label}`;
    messages.appendChild(thinkingEl);
    messages.scrollTop = messages.scrollHeight;
  }

  function clearThinking() {
    if (thinkingEl) {
      thinkingEl.remove();
      thinkingEl = null;
    }
  }

  function appendAssistantDelta(delta) {
    if (!delta) return;

    const messages = document.getElementById("messages");

    if (!streamingEl) {
      clearThinking();
      streamingEl = document.createElement("div");
      streamingEl.className = "msg assistant";
      streamingEl.textContent = "assistant: ";
      messages.appendChild(streamingEl);
    }

    streamingEl.textContent += delta;
    messages.scrollTop = messages.scrollHeight;
  }

  function finalizeAssistantMessage(fullText) {
    clearThinking();

    if (streamingEl) {
      if (fullText) {
        streamingEl.textContent = `assistant: ${fullText}`;
      }
      streamingEl = null;
      return;
    }

    if (fullText) {
      addMessage("assistant", fullText);
    }
  }

  function resetAssistantStreaming() {
    clearThinking();

    if (streamingEl) {
      streamingEl.remove();
      streamingEl = null;
    }
  }

  function addMessage(role, text) {
    if (!text) return;

    clearThinking();

    const messages = document.getElementById("messages");
    const div = document.createElement("div");

    div.className = "msg " + role;
    div.textContent = `${role}: ${text}`;

    messages.appendChild(div);
    messages.scrollTop = messages.scrollHeight;
  }

  function showPendingVoiceUserMessage(label = "listening") {
    if (pendingVoiceUserEl) {
      pendingVoiceUserEl.textContent = `user: ${label}`;
      return;
    }

    const messages = document.getElementById("messages");
    pendingVoiceUserEl = document.createElement("div");
    pendingVoiceUserEl.className = "msg user";
    pendingVoiceUserEl.textContent = `user: ${label}`;
    messages.appendChild(pendingVoiceUserEl);
    messages.scrollTop = messages.scrollHeight;
  }

  function finalizeVoiceUserMessage(transcript) {
    if (pendingVoiceUserEl) {
      if (transcript) {
        pendingVoiceUserEl.textContent = `user: ${transcript}`;
      } else {
        pendingVoiceUserEl.remove();
      }

      pendingVoiceUserEl = null;
      return;
    }

    if (transcript) {
      addMessage("user", transcript);
    }
  }

  function clearPendingVoiceUserMessage() {
    if (pendingVoiceUserEl) {
      pendingVoiceUserEl.remove();
      pendingVoiceUserEl = null;
    }
  }

  function extractCallId(location) {
    if (!location) throw new Error("Missing Location header");

    const match = location.match(/\/v1\/realtime\/calls\/([^/?#]+)/);
    if (!match) throw new Error("Invalid Location header: " + location);

    return match[1];
  }

  function sendRealtimeEvent(event) {
    if (!dc || dc.readyState !== "open") {
      throw new Error("Data channel is not open");
    }

    dc.send(JSON.stringify(event));
  }

  function setMicrophoneEnabled(enabled) {
    if (!localStream) return;

    for (const track of localStream.getAudioTracks()) {
      track.enabled = enabled;
    }
  }

  function isInputBlocked() {
    return pendingFunctionCall;
  }

  function updateInputControls() {
    const textInput = document.getElementById("textInput");
    const sendBtn = document.getElementById("sendBtn");
    const connected = realtimeConnected && dc?.readyState === "open";
    const blocked = isInputBlocked();

    textInput.disabled = !connected || blocked;
    sendBtn.disabled = !connected || blocked;
    updateTalkButton();
  }

  function updateTalkButton() {
    const talkBtn = document.getElementById("talkBtn");
    const audioEnabled = AUDIO_ENABLED();
    const canTalk = audioEnabled && realtimeConnected && !!localStream && !isInputBlocked();

    talkBtn.hidden = !audioEnabled;
    talkBtn.disabled = !canTalk;
    talkBtn.textContent = pushToTalkActive ? "Stop talking" : "Start talking";
    talkBtn.classList.toggle("talking", pushToTalkActive);
  }

  function setToolExecutionPending(pending) {
    const wasPending = pendingFunctionCall;
    pendingFunctionCall = pending;

    if (pending) {
      setMicrophoneEnabled(false);
    } else if (wasPending && pushToTalkActive && AUDIO_ENABLED() && realtimeConnected && localStream) {
      setMicrophoneEnabled(true);
      showPendingVoiceUserMessage("listening");
    }

    updateInputControls();
  }

  function startTalking() {
    if (!AUDIO_ENABLED() || !realtimeConnected || !localStream || pushToTalkActive || isInputBlocked()) return;

    cancelAssistantResponse();
    pushToTalkActive = true;
    setMicrophoneEnabled(true);
    showPendingVoiceUserMessage("listening");
    updateTalkButton();
  }

  function stopTalking() {
    if (!pushToTalkActive) return;

    pushToTalkActive = false;
    setMicrophoneEnabled(false);
    showPendingVoiceUserMessage("transcribing");
    updateTalkButton();
  }

  function toggleTalking() {
    if (pushToTalkActive) {
      stopTalking();
    } else {
      startTalking();
    }
  }

  function createResponseEvent(instructions = null) {
    const response = {
      output_modalities: AUDIO_ENABLED() ? ["audio"] : ["text"]
    };

    if (instructions) {
      response.instructions = instructions;
    }

    return {
      type: "response.create",
      response
    };
  }

  function cancelAssistantResponse() {
    if (!assistantResponseActive && !assistantAudioPlaying) return;

    if (assistantResponseActive) {
      try {
        sendRealtimeEvent(
            currentResponseId
                ? { type: "response.cancel", response_id: currentResponseId }
                : { type: "response.cancel" }
        );
      } catch (e) {
        console.warn("Unable to cancel active response:", e);
      }
    }

    if (AUDIO_ENABLED()) {
      try {
        sendRealtimeEvent({ type: "output_audio_buffer.clear" });
      } catch (e) {
        console.warn("Unable to clear output audio buffer:", e);
      }
    }

    assistantResponseActive = false;
    assistantAudioPlaying = false;
    currentResponseId = null;
    setToolExecutionPending(false);
    resetAssistantStreaming();
  }

  function handleAssistantInterrupted() {
    assistantResponseActive = false;
    assistantAudioPlaying = false;
    currentResponseId = null;
    setToolExecutionPending(false);
    resetAssistantStreaming();
  }

  function isBenignCancelError(data) {
    const message = data.error?.message || "";
    return (
        data.type === "error" &&
        message.includes("Cancellation failed") &&
        message.includes("no active response")
    );
  }

  function handleRealtimeEvent(event) {
    const data = JSON.parse(event.data);

    console.log("Realtime event:", data);

    switch (data.type) {
      case "input_audio_buffer.speech_started":
        handleAssistantInterrupted();
        showPendingVoiceUserMessage("listening");
        break;

      case "input_audio_buffer.speech_stopped":
        showPendingVoiceUserMessage("transcribing");
        break;

      case "conversation.item.input_audio_transcription.completed":
        finalizeVoiceUserMessage(data.transcript);
        break;

      case "conversation.item.input_audio_transcription.failed":
        finalizeVoiceUserMessage("[transcription failed]");
        break;

      case "response.created":
        currentResponseId = data.response?.id || null;
        assistantResponseActive = true;
        setToolExecutionPending(false);
        resetAssistantStreaming();
        showThinking();
        break;

      case "response.function_call_arguments.done":
        setToolExecutionPending(true);
        showThinking("executing");
        break;

      case "response.output_item.done":
        if (data.item?.type === "function_call") {
          setToolExecutionPending(true);
          showThinking("executing");
        }
        break;

        /**
         * Text mode.
         */
      case "response.output_text.delta":
        appendAssistantDelta(data.delta);
        break;

      case "response.output_text.done":
        finalizeAssistantMessage(data.text);
        break;

        /**
         * Audio mode transcript.
         *
         * Audio itself arrives through the WebRTC media track.
         * These events are the transcript text for the chat UI.
         *
         * OpenAI Realtime can emit the transcript event names as either:
         * - response.output_audio_transcript.*
         * - response.audio_transcript.*
         */
      case "response.output_audio_transcript.delta":
      case "response.audio_transcript.delta":
        appendAssistantDelta(data.delta);
        break;

      case "response.output_audio_transcript.done":
      case "response.audio_transcript.done":
        finalizeAssistantMessage(data.transcript);
        break;

        /**
         * Alternate/backward-compatible event names.
         * Keep these if your realtime logs show them.
         */
      case "response.text.delta":
        appendAssistantDelta(data.delta);
        break;

      case "response.text.done":
        finalizeAssistantMessage(data.text);
        break;

      case "output_audio_buffer.started":
        assistantAudioPlaying = true;
        break;

      case "output_audio_buffer.stopped":
      case "output_audio_buffer.cleared":
        assistantAudioPlaying = false;
        break;

      case "response.done":
        assistantResponseActive = false;
        currentResponseId = null;
        if (!pendingFunctionCall) {
          clearThinking();
          streamingEl = null;
        }
        updateInputControls();
        break;

      case "error":
        if (isBenignCancelError(data)) {
          console.warn("Ignoring benign cancellation race:", data.error?.message);
          break;
        }

        assistantResponseActive = false;
        currentResponseId = null;
        setToolExecutionPending(false);
        clearThinking();
        addMessage("system", data.error?.message || "Realtime error");
        break;

      default:
        if (
            data.type &&
            (
                data.type.includes("transcript") ||
                data.type.includes("text") ||
                data.type.includes("audio")
            )
        ) {
          console.log("Unhandled output event:", data.type, data);
        }
        break;
    }
  }

  async function connectRealtime() {
    addMessage("system", "Creating session...");

    const tokenResponse = await fetch("/api/realtime/token", {
      method: "POST",
      headers: {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "Authorization": "Bearer " + authToken
      },
      body: JSON.stringify({
        namespace: NAMESPACE,
        channel,
        audioEnabled: AUDIO_ENABLED(),
        language: LANGUAGE
      })
    });

    if (!tokenResponse.ok) {
      throw new Error(await tokenResponse.text());
    }

    const tokenData = await tokenResponse.json();
    const clientSecret = tokenData.clientSecret;

    pc = new RTCPeerConnection();

    if (AUDIO_ENABLED()) {
      audioEl = document.createElement("audio");
      audioEl.autoplay = true;

      pc.ontrack = (event) => {
        audioEl.srcObject = event.streams[0];
      };

      localStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      });

      setMicrophoneEnabled(false);

      for (const track of localStream.getTracks()) {
        pc.addTrack(track, localStream);
      }
    } else {
      pc.addTransceiver("audio", { direction: "recvonly" });
    }

    dc = pc.createDataChannel("oai-events");

    dc.onopen = () => {
      addMessage("system", "Data channel connected");
      updateInputControls();
    };

    dc.onmessage = handleRealtimeEvent;

    dc.onerror = (event) => {
      console.error("Data channel error:", event);
      addMessage("system", "Data channel error");
    };

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);

    const sdpResponse = await fetch("https://api.openai.com/v1/realtime/calls", {
      method: "POST",
      body: offer.sdp,
      headers: {
        Authorization: `Bearer ${clientSecret}`,
        "Content-Type": "application/sdp"
      }
    });

    if (!sdpResponse.ok) {
      throw new Error(await sdpResponse.text());
    }

    const answerSdp = await sdpResponse.text();

    await pc.setRemoteDescription({
      type: "answer",
      sdp: answerSdp
    });

    callId = extractCallId(sdpResponse.headers.get("Location"));

    await fetch("/api/realtime/connect", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer " + authToken
      },
      body: JSON.stringify({
        clientSecret,
        callId,
        namespace: NAMESPACE,
        channel,
        audioEnabled: AUDIO_ENABLED(),
        language: LANGUAGE
      })
    });

    realtimeConnected = true;

    document.getElementById("connectBtn").disabled = true;
    document.getElementById("disconnectBtn").disabled = false;
    document.getElementById("audioCheckbox").disabled = true;
    updateInputControls();

    addMessage("system", AUDIO_ENABLED() ? "Connected. Click Start talking to enable the microphone, then click Stop talking to mute it." : "Connected");
  }

  function sendTextMessage() {
    if (!realtimeConnected || isInputBlocked()) return;

    const input = document.getElementById("textInput");
    const text = input.value.trim();

    if (!text) return;

    cancelAssistantResponse();
    addMessage("user", text);
    const itemId = crypto.randomUUID().replaceAll("-", "");

    sendRealtimeEvent({
      type: "conversation.item.create",
      item: {
        id: itemId,
        type: "message",
        role: "user",
        content: [
          {
            type: "input_text",
            text
          }
        ]
      }
    });

    sendRealtimeEvent(createResponseEvent());

    input.value = "";
  }

  async function disconnectRealtime() {
    addMessage("system", "Disconnecting...");

    if (callId) {
      await fetch("/api/realtime/disconnect", {
        method: "POST",
        headers: {
          "Authorization": "Bearer " + authToken,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
              callId,
              namespace: NAMESPACE,
              channel
            }
        )
      }).catch(console.error);
    }

    if (dc) {
      dc.close();
      dc = null;
    }

    if (pc) {
      pc.close();
      pc = null;
    }

    if (localStream) {
      for (const track of localStream.getTracks()) track.stop();
      localStream = null;
    }

    callId = null;
    realtimeConnected = false;
    setToolExecutionPending(false);
    assistantResponseActive = false;
    assistantAudioPlaying = false;
    currentResponseId = null;
    pushToTalkActive = false;
    clearPendingVoiceUserMessage();
    resetAssistantStreaming();

    document.getElementById("connectBtn").disabled = false;
    document.getElementById("disconnectBtn").disabled = true;
    document.getElementById("audioCheckbox").disabled = false;
    updateInputControls();

    addMessage("system", "Disconnected");
  }

  document.getElementById("connectBtn").onclick = () => {
    connectRealtime().catch((e) => {
      console.error(e);
      addMessage("system", "Connect error: " + e.message);
    });
  };

  document.getElementById("disconnectBtn").onclick = () => {
    disconnectRealtime().catch((e) => {
      console.error(e);
      addMessage("system", "Disconnect error: " + e.message);
    });
  };

  document.getElementById("sendBtn").onclick = sendTextMessage;

  const talkBtn = document.getElementById("talkBtn");

  talkBtn.addEventListener("click", toggleTalking);

  document.getElementById("audioCheckbox").addEventListener("change", updateTalkButton);
  updateTalkButton();

  document.getElementById("textInput").addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      sendTextMessage();
    }
  });
  </#noparse>
</script>
</body>
</html>
