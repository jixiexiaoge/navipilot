const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../../main/assets/amap2tesla_player.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

function harness({ embedded = false, visual = true, fullscreen = 'success' } = {}) {
  const events = () => ({
    handlers: {},
    addEventListener(name, handler) { this.handlers[name] = handler; }
  });
  const screen = Object.assign(events(), {
    style: {}, naturalWidth: 960, naturalHeight: 720,
    setPointerCapture() {},
    getBoundingClientRect() {
      return {
        left: Number.parseFloat(this.style.left), top: Number.parseFloat(this.style.top),
        width: Number.parseFloat(this.style.width), height: Number.parseFloat(this.style.height)
      };
    }
  });
  const status = { style: {} };
  let fullscreenCalls = 0;
  const logs = [];
  const doc = Object.assign(events(), {
    hidden: false, fullscreenEnabled: true, fullscreenElement: null,
    documentElement: {
      requestFullscreen() {
        fullscreenCalls++;
        if (fullscreen === 'denied' || (fullscreen === 'gesture' && fullscreenCalls === 1)) {
          return Promise.reject(new Error('User gesture required'));
        }
        doc.fullscreenElement = this;
        return Promise.resolve();
      }
    },
    getElementById: id => id === 'screen' ? screen : status
  });
  const win = Object.assign(events(), { innerWidth: 1000, innerHeight: 800 });
  if (visual) win.visualViewport = Object.assign(events(), {
    width: 800, height: 450, offsetLeft: 12, offsetTop: 30
  });
  const messages = [];
  const sockets = [];
  class WebSocket {
    static OPEN = 1;
    static CONNECTING = 0;
    static CLOSED = 3;
    constructor(url) {
      Object.assign(this, events(), { readyState: WebSocket.CONNECTING, url });
      sockets.push(this);
    }
    send(message) { messages.push(JSON.parse(message)); }
  }
  let timerId = 0;
  const timers = new Map();
  const context = vm.createContext({
    document: doc, window: win, WebSocket, URLSearchParams, AbortController,
    location: { search: embedded ? '?embedded=1' : '', protocol: 'http:', host: 'localhost:8080' },
    console: { info: (...args) => logs.push(args) },
    setTimeout: (fn, delay) => { timers.set(++timerId, { fn, delay }); return timerId; },
    clearTimeout: id => timers.delete(id),
    setInterval: () => ++timerId, clearInterval() {},
    fetch: async () => ({
      ok: true, json: async () => ({ running: true, width: 960, height: 720 })
    })
  });
  vm.runInContext(script, context);
  const socket = sockets[0];
  socket.readyState = WebSocket.OPEN;
  socket.handlers.open();
  return {
    screen, doc, win, messages, socket, timers, logs,
    fullscreenCalls: () => fullscreenCalls,
    point: (x, y) => JSON.parse(vm.runInContext(
      `JSON.stringify(normalizedPoint({clientX:${x},clientY:${y}}))`, context
    )),
    touch: () => screen.handlers.pointerdown({
      preventDefault() {}, pointerId: 7, clientX: 412, clientY: 255
    })
  };
}

test('both embedded and external maps contain the complete frame without cropping', async () => {
  assert.match(html, /#screen\s*\{[^}]*position: fixed;[^}]*object-fit: contain;/);
  assert.doesNotMatch(html, /object-fit:\s*cover|fill-pane/);
  for (const embedded of [false, true]) {
    const h = harness({ embedded });
    await flush();
    // 960x720 fits into 800x450: 600x450, with 100px side margins.
    assert.deepEqual(h.point(112, 30), { x: 0, y: 0 });
    assert.deepEqual(h.point(712, 480), { x: 1, y: 1 });
    assert.deepEqual(h.point(412, 255), { x: .5, y: .5 });
    // The displayed image's dimensions win over stale /health dimensions.
    h.screen.naturalWidth = 1600;
    h.screen.naturalHeight = 900;
    assert.deepEqual(h.point(12, 30), { x: 0, y: 0 });
    assert.deepEqual(h.point(812, 480), { x: 1, y: 1 });
  }
});

test('visible viewport and its offsets follow browser chrome, rotation and fullscreen changes', async () => {
  const h = harness();
  await flush();
  assert.equal(h.screen.style.height, '450px');
  assert.equal(h.screen.style.top, '30px');
  assert.deepEqual(h.messages[0], { type: 'viewport', width: 800, height: 450 });
  Object.assign(h.win.visualViewport, { width: 400, height: 700, offsetLeft: 0, offsetTop: 15 });
  h.win.visualViewport.handlers.resize();
  assert.equal(h.screen.style.width, '400px');
  assert.equal(h.screen.style.height, '700px');
  assert.equal(h.screen.style.top, '15px');
  for (const { fn, delay } of [...h.timers.values()]) if (delay === 300) fn();
  assert.deepEqual(h.messages.at(-1), { type: 'viewport', width: 400, height: 700 });
  assert.deepEqual(h.point(0, 215), { x: 0, y: 0 });
  assert.deepEqual(h.point(400, 515), { x: 1, y: 1 });
});

test('legacy browsers fall back to inner viewport dimensions', async () => {
  const h = harness({ visual: false });
  await flush();
  assert.equal(h.screen.style.width, '1000px');
  assert.equal(h.screen.style.height, '800px');
  assert.deepEqual(h.messages[0], { type: 'viewport', width: 1000, height: 800 });
  h.win.innerWidth = 500;
  h.win.innerHeight = 900;
  h.win.handlers.resize();
  assert.equal(h.screen.style.width, '500px');
  assert.equal(h.screen.style.height, '900px');
});

test('fullscreen denial retries on first touch without losing pointer control or forcing reentry', async () => {
  const h = harness({ fullscreen: 'gesture' });
  await flush();
  assert.equal(h.fullscreenCalls(), 1);
  assert.equal(h.logs.length, 1);
  h.touch();
  await flush();
  assert.equal(h.fullscreenCalls(), 2);
  assert.equal(h.doc.fullscreenElement, h.doc.documentElement);
  const pointer = h.messages.find(message => message.type === 'pointer');
  assert.deepEqual(pointer, { type: 'pointer', action: 0, pointerId: 0, x: .5, y: .5 });
  h.doc.fullscreenElement = null;
  h.doc.handlers.fullscreenchange();
  h.touch();
  assert.equal(h.fullscreenCalls(), 2);
});

test('fullscreen remains optional and embedded clients never resize or fullscreen the shared projection', async () => {
  const denied = harness({ fullscreen: 'denied' });
  await flush();
  denied.touch();
  await flush();
  denied.touch();
  assert.equal(denied.fullscreenCalls(), 2);
  assert.equal(denied.screen.style.height, '450px');
  const embedded = harness({ embedded: true });
  await flush();
  embedded.touch();
  assert.equal(embedded.fullscreenCalls(), 0);
  assert.equal(embedded.socket.url, 'ws://localhost:8080/control?embedded=1');
  assert.ok(embedded.messages.every(message => message.type !== 'viewport'));
  assert.ok([...embedded.timers.values()].some(timer => timer.delay === 0));
  embedded.screen.style.width = '0px';
  assert.equal(embedded.point(0, 0), null);
});
