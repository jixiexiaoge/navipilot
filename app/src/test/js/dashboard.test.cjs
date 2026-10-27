const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const dashboard = require('../../main/assets/dashboard.js');

const panel = (id, remainingMs, metrics = [{ id: 'speed', label: '速度', value: 0, unit: 'km/h' }]) =>
  ({ id, title: id, source: '7000 / carState', status: 'fresh', remainingMs, metrics });
const payload = (...panels) => ({ schemaVersion: 1, panels });
const response = data => ({ ok: true, headers: { get: () => null }, text: async () => JSON.stringify(data) });

class Node {
  constructor(tag) {
    this.tagName = tag;
    this.children = [];
    this.dataset = {};
    this.attributes = {};
    this.events = {};
    this.hidden = false;
    this._text = '';
    this.classes = new Set();
    this.classList = { toggle: (name, on) => on ? this.classes.add(name) : this.classes.delete(name) };
  }
  set innerHTML(_) { throw new Error('Unsafe HTML rendering'); }
  set textContent(value) { this._text = value; this.children = []; }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  append(...children) { this.children.push(...children); }
  insertBefore(child, reference) {
    const index = this.children.indexOf(reference);
    this.children.splice(index < 0 ? this.children.length : index, 0, child);
  }
  replaceChildren(...children) { this._text = ''; this.children = children; }
  setAttribute(name, value) { this.attributes[name] = value; }
  addEventListener(name, listener) { this.events[name] = listener; }
}
function documentFake() {
  const nodes = new Map();
  const doc = new Node('document');
  doc.hidden = false;
  doc.documentElement = new Node('html');
  doc.createElement = tag => new Node(tag);
  doc.getElementById = id => {
    if (!nodes.has(id)) nodes.set(id, new Node(id));
    return nodes.get(id);
  };
  return doc;
}

function harness() {
  let now = 0;
  let sequence = 0;
  const timers = new Map();
  const calls = [];
  const views = [];
  const env = {
    now: () => now,
    AbortController,
    setTimeout: (fn, delay) => { const id = ++sequence; timers.set(id, { at: now + delay, fn }); return id; },
    clearTimeout: id => timers.delete(id),
    fetch: (url, options) => new Promise((resolve, reject) => calls.push({ url, options, resolve, reject })),
    render: (panels, error) => views.push({ panels, error })
  };
  const controller = dashboard.createController(env);
  function advance(ms) {
    const end = now + ms;
    while (true) {
      const next = [...timers.entries()].sort((a, b) => a[1].at - b[1].at)[0];
      if (!next || next[1].at > end) break;
      now = next[1].at;
      timers.delete(next[0]);
      next[1].fn();
    }
    now = end;
  }
  return { env, controller, calls, views, timers, advance, last: () => views.at(-1) };
}
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

test('a distinct actual receipt may refresh unchanged values at the same remaining TTL', () => {
  const first = dashboard.acceptPanels(payload({ ...panel('vehicle', 500), receiptKey: '100' }), 0, 0);
  const second = dashboard.acceptPanels(payload({ ...panel('vehicle', 500), receiptKey: '350' }), 250, 250, first);
  assert.equal(second[0].deadline, 750);
  const cached = dashboard.acceptPanels(payload({ ...panel('vehicle', 500), receiptKey: '350' }), 500, 500, second);
  assert.equal(cached[0].deadline, 750);
});

test('whole RTT is subtracted and each service expires independently at its deadline', () => {
  const panels = dashboard.acceptPanels(payload(panel('vehicle', 1000), panel('model', 200)), 100, 250);
  assert.equal(panels[0].deadline, 1100);
  assert.equal(panels[1].deadline, 300);
  const expired = dashboard.expirePanels(panels, 300);
  assert.equal(expired[0].status, 'fresh');
  assert.equal(expired[1].status, 'expired');
  assert.equal(dashboard.expirePanels(expired, 1100)[0].status, 'expired');
  assert.equal(dashboard.acceptPanels(payload(panel('slow', 100)), 0, 200)[0].deadline, 200);
});

test('identical frozen/cached payload cannot extend deadlines or resurrect expired values', () => {
  const data = payload(panel('vehicle', 1000));
  const first = dashboard.acceptPanels(data, 0, 100);
  const second = dashboard.acceptPanels(data, 200, 300, first);
  assert.equal(second[0].deadline, 1000);
  const third = dashboard.acceptPanels(data, 1200, 1300, dashboard.expirePanels(second, 1200));
  assert.equal(dashboard.expirePanels(third, 1300)[0].status, 'expired');
  const withdrawn = dashboard.expirePanels(first, 200, true);
  const cached = dashboard.acceptPanels(data, 250, 300, withdrawn);
  assert.equal(dashboard.expirePanels(cached, 300)[0].status, 'expired');
});

test('missing and invalid lifetimes never grant freshness; malformed schema fails explicitly', () => {
  const items = ['100', NaN, Infinity, -1, undefined].map((ttl, i) => panel(String(i), ttl));
  assert.ok(dashboard.expirePanels(dashboard.acceptPanels(payload(...items), 0, 10), 10)
    .every(item => item.status === 'expired'));
  assert.throws(() => dashboard.acceptPanels({ schemaVersion: 2, panels: [] }, 0, 0), /版本/);
  assert.throws(() => dashboard.acceptPanels(payload(panel('a', 1), panel('a', 1)), 0, 0), /格式/);
  const missing = { ...panel('a', 100), status: 'missing' };
  assert.equal(dashboard.expirePanels(dashboard.acceptPanels(payload(missing), 0, 0), 1)[0].status, 'missing');
});

test('text-only renderer preserves tri-state values, raw text, zeroes, all panels and sources', () => {
  const doc = documentFake();
  const container = new Node('main');
  const hero = new Node('header');
  const attack = '<img src=x onerror="globalThis.attacked=true">';
  const vehicle = panel('vehicle', 1000, [
    { id: 'speed', label: '速度', value: 0, unit: 'km/h' },
    { id: 'cruise', label: '巡航', value: '未知' },
    { id: 'gear', label: '挡位', value: 'D' },
    ...['未触发', '未知', '已触发', false, true].map(value => ({ id: 'tri', label: '状态', value }))
  ]);
  const alert = { ...panel('alert', 500), title: attack, note: attack, tone: 'critical',
    metrics: [{ id: 'alert-detail', label: attack, value: attack }] };
  const panels = [vehicle, ...['control', 'lateral', 'lane', 'plan', 'radar', 'sides', 'model',
    'result', 'navigation', 'extras', 'device', 'map-road', 'map-current', 'map-next', 'future-panel']
    .map(id => panel(id, 1000)), alert];
  dashboard.renderPanels(doc, container, hero, panels);
  assert.equal(container.children.length, panels.length);
  assert.equal(container.children[0].dataset.panel, 'alert');
  assert.equal(container.children[0].dataset.critical, 'true');
  for (const value of ['0 km/h', '未触发', '未知', '已触发', 'false', 'true', attack, '7000 / carState']) {
    assert.ok(container.textContent.includes(value), value);
  }
  assert.ok(hero.textContent.includes('0 km/h'));
  function check(node) {
    assert.notEqual(node.tagName, 'img');
    for (const child of node.children) check(child);
  }
  check(container);
  const expired = panels.map(item => ({ ...item, status: 'expired' }));
  dashboard.renderPanels(doc, container, hero, expired);
  assert.ok(!hero.textContent.includes('0 km/h'));
  assert.ok(!container.textContent.includes('0 km/h'));
  assert.ok(container.textContent.includes('已过期'));
});

test('absent alert remains a prominent unknown panel, not a claim of no alerts', () => {
  const container = new Node('main');
  dashboard.renderPanels(documentFake(), container, new Node('header'), []);
  assert.equal(container.children[0].dataset.panel, 'alert');
  assert.match(container.textContent, /告警未知，不代表无告警/);
});

test('sequential no-store polling never overlaps, and ticks expire panels during a hung request', async () => {
  const h = harness();
  h.controller.setActive(true, true);
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].url, '/dashboard');
  assert.equal(h.calls[0].options.cache, 'no-store');
  h.advance(100);
  h.calls[0].resolve(response(payload(panel('vehicle', 800), panel('radar', 300))));
  await flush();
  h.advance(150);
  assert.equal(h.calls.length, 2);
  h.advance(100);
  assert.equal(h.last().panels.find(item => item.id === 'radar').status, 'expired');
  assert.equal(h.last().panels.find(item => item.id === 'vehicle').status, 'fresh');
  h.advance(500);
  assert.equal(h.last().panels.find(item => item.id === 'vehicle').status, 'expired');
  h.advance(1000);
  assert.equal(h.calls.length, 2);
  assert.ok(h.calls[1].options.signal.aborted);
  assert.match(h.last().error, /超时.*未知/);
  // Even an abort-ignoring fetch must settle before a replacement can start.
  h.calls[1].resolve(response(payload(panel('vehicle', 9000))));
  await flush();
  assert.equal(h.last().panels[0].status, 'expired');
  h.advance(50);
  assert.equal(h.calls.length, 3);
});

test('network, HTTP, JSON, schema and cache errors withdraw every panel with an explicit reason', async () => {
  for (const kind of ['network', 'http', 'json', 'schema', 'cache']) {
    const h = harness();
    h.controller.setActive(true, true);
    h.calls[0].resolve(response(payload(panel('vehicle', 9000), panel('model', 8000))));
    await flush();
    h.advance(250);
    if (kind === 'network') h.calls[1].reject(new Error('offline'));
    if (kind === 'http') h.calls[1].resolve({ ok: false, status: 503 });
    if (kind === 'json') h.calls[1].resolve({ ...response(null), text: async () => '{bad JSON' });
    if (kind === 'schema') h.calls[1].resolve(response({ schemaVersion: 5 }));
    if (kind === 'cache') h.calls[1].resolve({ ...response(null), headers: { get: () => '5' } });
    await flush();
    assert.ok(h.last().panels.every(item => item.status === 'expired'), kind);
    assert.match(h.last().error, /失败.*断开.*未知/);
  }
});

test('hiding or disabling immediately withdraws values, clears timer, aborts, and rejects late responses', async () => {
  for (const [on, visible] of [[true, false], [false, true]]) {
    const h = harness();
    h.controller.setActive(true, true);
    h.calls[0].resolve(response(payload(panel('vehicle', 9000))));
    await flush();
    h.advance(250);
    h.controller.setActive(on, visible);
    assert.equal(h.timers.size, 0);
    assert.equal(h.last().panels[0].status, 'expired');
    assert.ok(h.calls[1].options.signal.aborted);
    h.calls[1].resolve(response(payload(panel('vehicle', 10000))));
    await flush();
    assert.equal(h.timers.size, 0);
    assert.equal(h.last().panels[0].status, 'expired');
    h.controller.setActive(true, true);
    assert.equal(h.calls.length, 3);
  }
});

test('map contain coordinates follow the actual region after layout without changing viewport dimensions', () => {
  const wide = { left: 20, top: 180, width: 800, height: 600 };
  assert.deepEqual(dashboard.normalizedPoint(wide, 1280, 720, 420, 480), { x: .5, y: .5 });
  assert.deepEqual(dashboard.normalizedPoint(wide, 1280, 720, 20, 180), { x: 0, y: 0 });
  const narrow = { left: 12, top: 260, width: 320, height: 400 };
  assert.deepEqual(dashboard.normalizedPoint(narrow, 1280, 720, 172, 460), { x: .5, y: .5 });
  assert.deepEqual(dashboard.normalizedPoint(narrow, 1280, 720, 332, 660), { x: 1, y: 1 });
  assert.deepEqual(dashboard.normalizedPoint({ left: 0, top: 0, width: 0, height: 0 }, 1280, 720, 0, 0), { x: 0, y: 0 });
});

test('mount defaults on, toggles map mode, resumes lifecycle, and emits no viewport or control commands', async () => {
  const h = harness();
  const doc = documentFake();
  const win = new Node('window');
  Object.assign(win, h.env, { performance: { now: h.env.now } });
  dashboard.mount(doc, win);
  assert.ok(doc.documentElement.classes.has('dashboard'));
  assert.equal(h.calls.length, 1);
  assert.equal(doc.getElementById('dashboardToggle').hidden, false);
  h.calls[0].resolve(response(payload(panel('vehicle', 1000))));
  await flush();
  win.events.pagehide();
  assert.equal(h.timers.size, 0);
  assert.match(doc.getElementById('dashboardHero').textContent, /未知/);
  win.events.pageshow();
  assert.equal(h.calls.length, 2);
  doc.hidden = true;
  doc.events.visibilitychange();
  assert.ok(h.calls[1].options.signal.aborted);
  h.calls[1].reject(new Error('aborted'));
  await flush();
  doc.hidden = false;
  doc.events.visibilitychange();
  assert.equal(h.calls.length, 3);
  doc.getElementById('dashboardToggle').events.click();
  assert.ok(!doc.documentElement.classes.has('dashboard'));
  assert.equal(doc.getElementById('dashboardHeader').hidden, true);
  assert.equal(doc.getElementById('dashboardToggle').textContent, '看板');
  assert.deepEqual(Object.keys(win.events).sort(), ['pagehide', 'pageshow']);
});

test('embedded bootstrap never mounts or fetches dashboard data', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../main/assets/dashboard.js'), 'utf8');
  const win = { location: { search: '?embedded=1' }, document: {
    getElementById() { throw new Error('Embedded DOM must not be touched'); }
  }, fetch() { throw new Error('Embedded must not fetch dashboard'); } };
  vm.runInNewContext(source, { window: win, URLSearchParams });
  assert.equal(typeof win.CPDashboard.normalizedPoint, 'function');
});

test('player is map only: protocols and back-control kept, all chrome removed', () => {
  const html = fs.readFileSync(path.join(__dirname, '../../main/assets/amap2tesla_player.html'), 'utf8');
  assert.match(html, /<img id="screen"/);
  assert.match(html, /width: Math\.round\(viewport\.width\)/);
  assert.match(html, /height: Math\.round\(viewport\.height\)/);
  for (const endpoint of ['/control', '/health', '/frame', '/stream']) assert.ok(html.includes(endpoint));
  for (const event of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel']) {
    assert.ok(html.includes(`screen.addEventListener('${event}'`));
  }
  assert.ok(!html.includes('ResizeObserver'));
  // 纯地图：返回键、看板切换、FPS、推广条、看板 DOM 与看板脚本一律不出现
  for (const removed of ['id="back"', 'id="dashboardToggle"', 'id="stats"', 'id="promoBanner"',
    'id="dashboardHeader"', 'id="dashboardTelemetry"', 'dashboard.css', 'dashboard.js', 'keyCode']) {
    assert.ok(!html.includes(removed), `player must not contain ${removed}`);
  }
});

test('parser bounds response bytes before JSON parsing and cancels oversized streams', async () => {
  await assert.rejects(dashboard.readPayload({
    headers: { get: () => String(1024 * 1024 + 1) },
    text() { throw new Error('Must reject before reading'); }
  }), /大小上限/);
  await assert.rejects(dashboard.readPayload({ ...response(null), text: async () => '界'.repeat(400000) }), /大小上限/);
  let reads = 0;
  let cancelled = false;
  let released = false;
  const reader = {
    read: async () => { reads++; return { value: new Uint8Array(600000), done: false }; },
    cancel: async () => { cancelled = true; },
    releaseLock: () => { released = true; }
  };
  await assert.rejects(dashboard.readPayload({
    headers: { get: () => null }, body: { getReader: () => reader }
  }), /大小上限/);
  assert.equal(reads, 2);
  assert.ok(cancelled && released);
  const encoded = new TextEncoder().encode(JSON.stringify(payload(panel('map-trip', 500))));
  let offset = 0;
  const result = await dashboard.readPayload({
    headers: { get: () => null },
    body: { getReader: () => ({
      read: async () => offset < encoded.length
        ? { value: encoded.slice(offset, ++offset), done: false } : { done: true },
      releaseLock() {}, cancel: async () => {}
    }) }
  });
  assert.equal(result.panels[0].id, 'map-trip');
});

test('DTO parser bounds counts, rejects oversized text without truncation, and strips nested extensions', () => {
  assert.throws(() => dashboard.acceptPanels(payload(...Array.from({ length: 65 }, (_, i) => panel(String(i), 100))), 0, 0), /格式/);
  assert.throws(() => dashboard.acceptPanels(payload(panel('vehicle', 100,
    Array.from({ length: 129 }, () => ({ id: 'metric', value: 1 })))), 0, 0), /格式/);
  for (const field of ['source', 'title', 'note']) {
    assert.throws(() => dashboard.acceptPanels(payload({ ...panel('vehicle', 100), [field]: '界'.repeat(16385) }), 0, 0), /格式/);
  }
  assert.throws(() => dashboard.acceptPanels(payload(panel('a', 100, [{ id: 'bad', value: { nested: true } }])), 0, 0), /格式/);
  const accepted = dashboard.acceptPanels(payload({ ...panel('a', 100), unexpected: { nested: true } }), 0, 0);
  assert.equal(accepted[0].unexpected, undefined);
});

test('new optional panels and multiline sources render in full without omitting unknown future panels', () => {
  const ids = ['map-trip', 'map-events', 'map-section', 'map-lanes', 'navi-events', 'navi-signal', 'future'];
  const source = '7000 / source '.repeat(500) + '\n独立真实接收截止时间';
  const items = ids.map(id => ({ ...panel(id, 1000), source, note: '原始说明\n未知非安全' }));
  const container = new Node('main');
  dashboard.renderPanels(documentFake(), container, new Node('header'),
    dashboard.acceptPanels(payload(...items), 0, 0));
  for (const id of ids) assert.ok(container.children.some(node => node.dataset.panel === id));
  assert.ok(container.textContent.includes(source));
  assert.ok(container.textContent.includes('原始说明\n未知非安全'));
});

test('scheduled expiry updates hero independently while another request remains hung', async () => {
  const h = harness();
  const doc = documentFake();
  const hero = new Node('header');
  const cards = new Node('main');
  h.env.render = (panels, error) => {
    h.views.push({ panels, error });
    dashboard.renderPanels(doc, cards, hero, panels);
  };
  h.controller.setActive(true, true);
  h.calls[0].resolve(response(payload(panel('vehicle', 400, [
    { id: 'speed', value: '88', unit: 'km/h' }, { id: 'cruise', value: '100' }, { id: 'gear', value: 'D' }
  ]), panel('map-trip', 1000))));
  await flush();
  assert.ok(hero.textContent.includes('88 km/h'));
  h.advance(400);
  assert.equal(h.calls.length, 2);
  assert.ok(!hero.textContent.includes('88'));
  assert.ok(!hero.textContent.includes('100'));
  assert.ok(hero.textContent.includes('未知'));
  assert.equal(h.last().panels.find(item => item.id === 'map-trip').status, 'fresh');
});

test('map status cannot capture gestures and no telemetry is layered over the map', () => {
  const assets = path.join(__dirname, '../../main/assets');
  const css = fs.readFileSync(path.join(assets, 'dashboard.css'), 'utf8');
  const html = fs.readFileSync(path.join(assets, 'amap2tesla_player.html'), 'utf8');
  assert.match(html, /#status\s*\{[^}]*pointer-events: none/);
  assert.match(html, /#screen\s*\{[^}]*touch-action: none/);
  // 投屏页改为纯地图：不再引入看板样式，也不再有遥测侧栏叠在地图上
  assert.ok(!html.includes('dashboard.css'));
  assert.ok(!html.includes('<aside id="dashboardTelemetry"'));
  assert.ok(!html.includes('id="dashboardHeader"'));
  assert.ok(!css.includes('text-overflow'));
  assert.ok(!css.includes('line-clamp'));
  assert.match(css, /\.dashboard-source, \.dashboard-note, \.dashboard-metric dd \{ white-space: pre-wrap/);
});

test('integrated cockpit uses sourced metrics and expiry while complete details stay available', () => {
  const doc = documentFake();
  const cockpit = new Node('main');
  const items = [
    panel('vehicle', 500, [{ id: 'speed', value: 88, unit: 'km/h' }, { id: 'cruise', value: 100 }, { id: 'gear', value: 'D' }]),
    panel('radar', 500, [{ id: 'lead-distance', label: '前车距离', value: 22, unit: 'm' }, { id: 'lead-relative', label: '相对速度', value: -3, unit: 'km/h' }]),
    panel('navigation', 500), panel('alert', 500)
  ];
  dashboard.renderCockpit(doc, cockpit, items);
  assert.ok(!cockpit.textContent.includes('Tesla'));
  assert.ok(!cockpit.textContent.includes('停止线'));
  for (const value of ['88 km/h', '22 m', '-3 km/h', '车辆运动与保持', '来源：7000 / carState']) {
    assert.ok(cockpit.textContent.includes(value));
  }
  dashboard.renderCockpit(doc, cockpit, items.map(item => ({ ...item, status: 'expired' })));
  assert.ok(!cockpit.textContent.includes('88 km/h'));
  assert.ok(!cockpit.textContent.includes('22 m'));
  assert.ok(cockpit.textContent.includes('未知'));
});

test('motion summary reads only actual carState metrics and expires independently of navigation', () => {
  const doc = documentFake();
  const cockpit = new Node('main');
  const items = [
    panel('vehicle', 500, [
      { id: 'accel', label: '实际加速度', value: -0.5, unit: 'm/s²' },
      { id: 'hold', label: '制动保持', value: '否' },
      { id: 'soft-hold', label: 'Soft hold', value: '未知' },
    ]),
    panel('navigation', 1500, [{ id: 'navi-current', value: '左转' }]),
  ];
  dashboard.renderCockpit(doc, cockpit, items);
  assert.ok(cockpit.textContent.includes('-0.5 m/s²'));
  assert.ok(cockpit.textContent.includes('否'));
  dashboard.renderCockpit(doc, cockpit, [{ ...items[0], status: 'expired' }, items[1]]);
  assert.ok(!cockpit.textContent.includes('-0.5 m/s²'));
  assert.ok(cockpit.textContent.includes('左转'));
});

test('cockpit and all received panels remain continuously available without a details toggle', async () => {
  const h = harness();
  const doc = documentFake();
  const win = new Node('window');
  Object.assign(win, h.env, { performance: { now: h.env.now } });
  dashboard.mount(doc, win);
  const telemetry = doc.getElementById('dashboardTelemetry');
  const cockpit = telemetry.children.find(node => node.className === 'cockpit');
  const details = doc.getElementById('dashboardPanels');
  assert.equal(details.hidden, false);
  assert.ok(!telemetry.children.some(node => node.tagName === 'button'));
  const ids = ['vehicle', 'map-section', 'device', 'gps-quality', 'model', 'plan'];
  h.calls[0].resolve(response(payload(...ids.map(id => panel(id, 1000)))));
  await flush();
  assert.equal(details.hidden, false);
  assert.equal(cockpit.hidden, false);
  for (const id of ids) assert.ok(details.children.some(node => node.dataset.panel === id));
  assert.match(cockpit.textContent, /向下滚动查看全部/);
});
