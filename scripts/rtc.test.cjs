const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const template = fs.readFileSync(path.join(__dirname, '../src/testFixtures/resources/templates/rtc.ftl'), 'utf8');
const script = template.match(/<#noparse>([\s\S]*?)<\/#noparse>/)[1];

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

function page(options = {}) {
  const elements = new Map();
  function element() {
    return {
      checked: true, disabled: false, value: '', textContent: '',
      classList: { toggle() {} }, addEventListener() {}, appendChild() {},
      remove() {}, pause() {},
    };
  }
  const peers = [];
  const track = { stopped: false, enabled: true, stop() { this.stopped = true; } };
  const requests = [];
  class Peer {
    constructor() { this.closed = false; this.connectionState = 'new'; peers.push(this); }
    addTrack() {}
    addTransceiver() {}
    createDataChannel() {
      this.channel = { readyState: 'open', close() { this.closed = true; }, send() {} };
      return this.channel;
    }
    async createOffer() { return { sdp: 'offer' }; }
    async setLocalDescription() {}
    async setRemoteDescription() {
      if (options.sdpFailure) throw new Error('SDP rejected');
      this.connectionState = 'connected';
    }
    close() { this.closed = true; }
  }
  const sandbox = {
    console: { log() {}, warn() {}, error() {} },
    AbortController, AbortSignal, RTCPeerConnection: Peer,
    navigator: { mediaDevices: { async getUserMedia() {
      if (options.microphoneFailure) throw new Error('Microphone denied');
      return { getAudioTracks: () => [track], getTracks: () => [track] };
    } } },
    document: {
      querySelector: () => ({ content: 'dummy-token' }),
      createElement: element,
      getElementById(id) {
        if (!elements.has(id)) elements.set(id, element());
        return elements.get(id);
      },
    },
    async fetch(url, init) {
      requests.push({ url, init });
      if (url.endsWith('/disconnect')) {
        if (options.hangup) return options.hangup.promise;
        return { ok: true };
      }
      if (options.create) return options.create.promise;
      if (options.httpFailure) return { ok: false, text: async () => 'Create failed' };
      return { ok: true, json: async () => ({ callId: 'call-123', sdp: 'answer' }) };
    },
  };
  const context = vm.createContext(sandbox);
  vm.runInContext(script, context);
  return { peers, track, requests, elements, run: (code) => vm.runInContext(code, context) };
}

test('microphone denial closes the peer and restores the controls', async () => {
  const p = page({ microphoneFailure: true });
  await assert.rejects(p.run('connectRealtime()'), /Microphone denied/);
  assert.equal(p.peers[0].closed, true);
  assert.equal(p.elements.get('connectBtn').disabled, false);
  assert.equal(p.run('pc'), null);
});

test('HTTP creation failure closes media and the data channel', async () => {
  const p = page({ httpFailure: true });
  await assert.rejects(p.run('connectRealtime()'), /Create failed/);
  assert.equal(p.track.stopped, true);
  assert.equal(p.peers[0].closed, true);
  assert.equal(p.peers[0].channel.closed, true);
  assert.equal(p.run('audioEl'), null);
  assert.equal(p.elements.get('connectBtn').disabled, false);
});

test('SDP failure hangs up the created call and releases local resources', async () => {
  const p = page({ sdpFailure: true });
  await assert.rejects(p.run('connectRealtime()'), /SDP rejected/);
  assert.equal(p.track.stopped, true);
  const hangups = p.requests.filter(({ url }) => url.endsWith('/disconnect'));
  assert.equal(hangups.length, 1);
  assert.equal(JSON.parse(hangups[0].init.body).callId, 'call-123');
});

test('repeated connect clicks create only one peer', async () => {
  const create = deferred();
  const p = page({ create });
  const first = p.run('connectRealtime()');
  await p.run('connectRealtime()');
  assert.equal(p.peers.length, 1);
  assert.equal(p.elements.get('connectBtn').disabled, true);
  create.resolve({ ok: true, json: async () => ({ callId: 'call-123', sdp: 'answer' }) });
  await first;
  await p.run('disconnectRealtime()');
});

test('disconnect releases resources before hangup and rejects HTTP errors', async () => {
  const hangup = deferred();
  const p = page({ hangup });
  await p.run('connectRealtime()');
  const pending = p.run('disconnectRealtime()');
  assert.equal(p.track.stopped, true);
  assert.equal(p.peers[0].closed, true);
  assert.equal(p.run('realtimeConnected'), false);
  assert.equal(p.elements.get('connectBtn').disabled, true);
  hangup.resolve({ ok: false, status: 503 });
  await assert.rejects(pending, /Hangup failed: HTTP 503/);
  assert.equal(p.elements.get('connectBtn').disabled, false);
});

test('remote data channel close resets controls and hangs up once', async () => {
  const p = page();
  await p.run('connectRealtime()');
  p.peers[0].channel.onclose();
  await new Promise(setImmediate);
  assert.equal(p.track.stopped, true);
  assert.equal(p.run('realtimeConnected'), false);
  assert.equal(p.elements.get('connectBtn').disabled, false);
  assert.equal(p.requests.filter(({ url }) => url.endsWith('/disconnect')).length, 1);
});
