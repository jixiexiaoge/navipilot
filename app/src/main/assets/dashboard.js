(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else {
    root.CPDashboard = api;
    if (new URLSearchParams(root.location.search).get('embedded') !== '1') {
      api.mount(root.document, root);
    }
  }
})(typeof window !== 'undefined' ? window : globalThis, function () {
  'use strict';
  const text = value => value === null || value === undefined ? '未知' : String(value);
  const MAX_RESPONSE_BYTES = 1024 * 1024;
  const MAX_PANELS = 64;
  const MAX_METRICS = 128;
  const MAX_TEXT_LENGTH = 16384;

  function validText(value, optional = false) {
    return optional && (value === undefined || value === null) ||
      typeof value === 'string' && value.length <= MAX_TEXT_LENGTH;
  }

  async function readPayload(response) {
    const length = Number(response.headers && response.headers.get('Content-Length'));
    if (length > MAX_RESPONSE_BYTES) throw new Error('看板响应超过大小上限');
    if (!response.body || typeof response.body.getReader !== 'function') {
      const raw = await response.text();
      if (new TextEncoder().encode(raw).byteLength > MAX_RESPONSE_BYTES) {
        throw new Error('看板响应超过大小上限');
      }
      return JSON.parse(raw);
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8', { fatal: true });
    let bytes = 0;
    let raw = '';
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        bytes += chunk.value.byteLength;
        if (bytes > MAX_RESPONSE_BYTES) throw new Error('看板响应超过大小上限');
        raw += decoder.decode(chunk.value, { stream: true });
      }
      raw += decoder.decode();
      return JSON.parse(raw);
    } catch (error) {
      // Cancel oversized or malformed streams before consuming any further chunks.
      try { await reader.cancel(); } catch (cancelError) {
        throw new Error(text(error.message) + '；取消读取失败：' + text(cancelError.message));
      }
      throw error;
    } finally {
      reader.releaseLock();
    }
  }

  function acceptPanels(payload, startedAt, now, previous = []) {
    if (!payload || payload.schemaVersion !== 1 || !Array.isArray(payload.panels) ||
        payload.panels.length > MAX_PANELS) {
      throw new Error('看板数据版本或格式无效');
    }
    const old = new Map(previous.map(panel => [panel.id, panel]));
    const ids = new Set();
    return payload.panels.map(panel => {
      if (!panel || !validText(panel.id) || !panel.id || ids.has(panel.id) ||
          !validText(panel.title, true) || !validText(panel.source, true) ||
          !validText(panel.note, true) || !validText(panel.tone, true) ||
          !validText(panel.receiptKey, true) ||
          !['fresh', 'expired', 'missing'].includes(panel.status) ||
          !Array.isArray(panel.metrics) || panel.metrics.length > MAX_METRICS ||
          !panel.metrics.every(metric => metric && validText(metric.id) &&
            validText(metric.label, true) && validText(metric.unit, true) &&
            (validText(metric.value, true) || typeof metric.value === 'boolean' ||
              typeof metric.value === 'number' && Number.isFinite(metric.value)))) {
        throw new Error('看板分区格式无效');
      }
      // Retain only the DTO fields, never arbitrary nested extension objects.
      panel = {
        id: panel.id, title: panel.title, source: panel.source, note: panel.note,
        tone: panel.tone, status: panel.status, remainingMs: panel.remainingMs,
        receiptKey: panel.receiptKey,
        metrics: panel.metrics.map(({ id, label, value, unit }) => ({ id, label, value, unit }))
      };
      ids.add(panel.id);
      const signature = JSON.stringify(panel);
      const lifetime = typeof panel.remainingMs === 'number' && Number.isFinite(panel.remainingMs)
        ? Math.max(0, panel.remainingMs - Math.max(0, now - startedAt)) : 0;
      let deadline = panel.status === 'fresh' ? now + lifetime : now;
      const prior = old.get(panel.id);
      // An identical frozen/cached payload is not evidence of a new service receipt.
      if (prior && prior.signature === signature) deadline = Math.min(deadline, prior.deadline);
      return { ...panel, signature, deadline };
    });
  }

  function expirePanels(panels, now, forced = false) {
    return panels.map(panel => ({
      ...panel,
      deadline: forced ? Math.min(panel.deadline, now) : panel.deadline,
      status: panel.status === 'missing' ? 'missing' :
        forced || panel.deadline <= now ? 'expired' : panel.status
    }));
  }

  function normalizedPoint(rect, outputWidth, outputHeight, clientX, clientY) {
    const scale = Math.min(rect.width / outputWidth, rect.height / outputHeight);
    const width = outputWidth * scale;
    const height = outputHeight * scale;
    if (!(width > 0) || !(height > 0)) return { x: 0, y: 0 };
    return {
      x: Math.max(0, Math.min(1, (clientX - rect.left - (rect.width - width) / 2) / width)),
      y: Math.max(0, Math.min(1, (clientY - rect.top - (rect.height - height) / 2) / height))
    };
  }

  function element(doc, tag, className, value) {
    const node = doc.createElement(tag);
    if (className) node.className = className;
    if (value !== undefined) node.textContent = text(value);
    return node;
  }

  function renderPanels(doc, container, hero, panels) {
    container.replaceChildren();
    hero.replaceChildren();
    const vehicle = panels.find(panel => panel.id === 'vehicle');
    for (const [id, label] of [['speed', '车速'], ['cruise', '巡航设定'], ['gear', '挡位']]) {
      const metric = vehicle && vehicle.metrics.find(item => item && item.id === id);
      const node = element(doc, 'div', 'dashboard-hero-metric');
      node.append(element(doc, 'span', '', label), element(doc, 'strong', '',
        vehicle && vehicle.status === 'fresh' && metric
          ? text(metric.value) + (metric.unit ? ' ' + text(metric.unit) : '') : '未知'));
      hero.append(node);
    }
    const ordered = panels.slice().sort((a, b) => Number(b.id === 'alert') - Number(a.id === 'alert'));
    if (!ordered.some(panel => panel.id === 'alert')) {
      ordered.unshift({ id: 'alert', title: '告警', source: '未提供', status: 'missing', metrics: [], note: '告警未知，不代表无告警。' });
    }
    for (const panel of ordered) {
      const card = element(doc, 'section', 'dashboard-card');
      card.dataset.status = panel.status;
      card.dataset.panel = panel.id;
      card.dataset.alert = String(panel.id === 'alert');
      card.dataset.critical = String(panel.status === 'fresh' &&
        ['critical', 'danger', 'error', 'red'].includes(panel.tone));
      const heading = element(doc, 'header');
      heading.append(element(doc, 'h3', '', panel.title || panel.id),
        element(doc, 'span', 'dashboard-state', { fresh: '实时接收', expired: '已过期 · 未知', missing: '未提供 · 未知' }[panel.status]));
      card.append(heading);
      const metrics = element(doc, 'dl', 'dashboard-metrics');
      for (const metric of panel.metrics) {
        if (!metric || typeof metric !== 'object') continue;
        const block = element(doc, 'div', 'dashboard-metric');
        block.append(element(doc, 'dt', '', metric.label || metric.id),
          element(doc, 'dd', '', panel.status === 'fresh'
            ? text(metric.value) + (metric.unit ? ' ' + text(metric.unit) : '') : '未知'));
        metrics.append(block);
      }
      if (!panel.metrics.length) metrics.append(element(doc, 'div', 'dashboard-metric', '当前无数据 · 未知'));
      card.append(metrics, element(doc, 'p', 'dashboard-source', '来源：' + text(panel.source)));
      if (panel.note) card.append(element(doc, 'p', 'dashboard-note', panel.note));
      container.append(card);
    }
  }

  function renderCockpit(doc, container, panels) {
    container.replaceChildren();
    const findPanel = id => panels.find(panel => panel.id === id);
    function display(metric, panel) {
      return panel && panel.status === 'fresh'
        ? text(metric.value) + (metric.unit ? ' ' + text(metric.unit) : '') : '未知';
    }
    function group(id, title, limit, metricIds) {
      const panel = findPanel(id);
      const node = element(doc, 'section', 'cockpit-group');
      node.dataset.panel = id;
      node.dataset.status = panel ? panel.status : 'missing';
      node.append(element(doc, 'h3', '', title));
      if (panel && panel.metrics.length) {
        const metrics = metricIds ? panel.metrics.filter(metric => metricIds.includes(metric.id)) : panel.metrics;
        for (const metric of metrics.slice(0, limit)) {
          const item = element(doc, 'div', 'cockpit-reading');
          item.append(element(doc, 'span', '', metric.label || metric.id),
            element(doc, 'strong', '', display(metric, panel)));
          node.append(item);
        }
        node.append(element(doc, 'p', 'dashboard-source', '来源：' + text(panel.source)),
          element(doc, 'p', 'dashboard-state', panel.status === 'fresh' ? '实时接收 · 只读' : '已过期或未提供 · 未知'));
        if (panel.note) node.append(element(doc, 'p', 'dashboard-note', panel.note));
      } else node.append(element(doc, 'p', '', '未提供 · 未知'));
      return node;
    }
    const instruments = element(doc, 'div', 'cockpit-instruments');
    const vehicle = findPanel('vehicle');
    function vehicleMetric(id, label) {
      const metric = vehicle && vehicle.metrics.find(item => item.id === id);
      const node = element(doc, 'div', 'cockpit-reading cockpit-' + id);
      node.append(element(doc, 'span', '', label),
        element(doc, 'strong', '', metric ? display(metric, vehicle) : '未知'));
      return node;
    }
    const flank = element(doc, 'div', 'cockpit-flank');
    flank.append(vehicleMetric('cruise', '巡航设定'), vehicleMetric('gear', '挡位'));
    const speed = vehicleMetric('speed', '实际车速');
    speed.append(element(doc, 'p', 'dashboard-source', '来源：' + text(vehicle && vehicle.source)));
    instruments.append(flank, speed,
      group('radar', '前车观测', 3, ['lead-status', 'lead-distance', 'lead-relative']));
    container.append(instruments);
    const rail = group('control', '控制状态 · 非控制授权', 4);
    rail.className += ' cockpit-rail';
    container.append(rail);
    const alert = group('alert', '告警观测', 128);
    alert.className += ' cockpit-alert';
    const alertPanel = findPanel('alert');
    alert.dataset.critical = String(Boolean(alertPanel && alertPanel.status === 'fresh' &&
      ['critical', 'danger', 'error', 'red'].includes(alertPanel.tone)));
    container.append(alert);
    const observations = element(doc, 'div', 'cockpit-observations');
    observations.append(group('navigation', '导航接收 · 非执行确认', 3),
      group('vehicle', '车辆运动与保持', 3, ['accel', 'hold', 'soft-hold']));
    container.append(observations,
      element(doc, 'p', 'dashboard-notes', '仪表为摘要；向下滚动查看全部服务、指标与原始说明。未检出不是安全，状态与请求不是执行确认。'));
  }

  function createController(env) {
    let panels = [];
    let enabled = false;
    let visible = false;
    let request = null;
    let timer = null;
    let nextPoll = 0;
    let error = '等待看板数据 · 未知';
    let renderKey = '';
    function publish(now, forced = false) {
      panels = expirePanels(panels, now, forced);
      const view = panels.map(({ signature, deadline, remainingMs, ...panel }) => panel);
      const key = JSON.stringify([view, error]);
      if (key !== renderKey) {
        renderKey = key;
        env.render(view, error);
      }
    }
    function schedule() {
      if (enabled && visible && timer === null) timer = env.setTimeout(tick, 50);
    }
    function fail(reason) {
      error = reason + ' · 已断开 / 数据未知';
      publish(env.now(), true);
    }
    async function poll() {
      const current = { start: env.now(), controller: new env.AbortController(), cancelled: false };
      request = current;
      nextPoll = current.start + 250;
      try {
        const response = await env.fetch('/dashboard', { cache: 'no-store', signal: current.controller.signal });
        if (!response.ok) throw new Error('HTTP ' + response.status);
        if (response.headers && Number(response.headers.get('Age')) > 0) throw new Error('拒绝缓存的看板数据');
        const payload = await readPayload(response);
        if (current.cancelled || !enabled || !visible) return;
        if (env.now() - current.start >= 1500) throw new Error('请求超时');
        panels = acceptPanels(payload, current.start, env.now(), panels);
        error = '';
        publish(env.now());
      } catch (failure) {
        if (!current.cancelled) fail('看板请求失败：' + text(failure.message));
      } finally {
        if (request === current) request = null;
        schedule();
      }
    }
    function tick() {
      timer = null;
      if (!enabled || !visible) return;
      const now = env.now();
      if (request && !request.cancelled && now - request.start >= 1500) {
        request.cancelled = true;
        request.controller.abort();
        fail('看板请求超时');
      }
      publish(now);
      if (!request && now >= nextPoll) poll();
      schedule();
    }
    function setActive(on, shown) {
      enabled = on;
      visible = shown;
      if (!enabled || !visible) {
        if (timer !== null) env.clearTimeout(timer);
        timer = null;
        if (request) {
          request.cancelled = true;
          request.controller.abort();
        }
        fail(!visible ? '页面不可见，已清除实时值' : '看板已暂停');
      } else {
        nextPoll = 0;
        if (timer === null) tick();
      }
    }
    return { setActive };
  }

  function mount(doc, win) {
    const toggle = doc.getElementById('dashboardToggle');
    const header = doc.getElementById('dashboardHeader');
    const telemetry = doc.getElementById('dashboardTelemetry');
    const connection = doc.getElementById('dashboardConnection');
    const cockpit = element(doc, 'div', 'cockpit');
    const details = doc.getElementById('dashboardPanels');
    telemetry.insertBefore(cockpit, details);
    let on = true;
    let pageVisible = true;
    const controller = createController({
      now: () => win.performance.now(),
      fetch: win.fetch.bind(win), AbortController: win.AbortController,
      setTimeout: win.setTimeout.bind(win), clearTimeout: win.clearTimeout.bind(win),
      render: (panels, error) => {
        renderPanels(doc, doc.getElementById('dashboardPanels'), doc.getElementById('dashboardHero'), panels);
        renderCockpit(doc, cockpit, panels);
        connection.textContent = error || '服务独立计时 · 过期自动撤下数值 · 只读';
        connection.dataset.error = String(Boolean(error));
      }
    });
    function update() {
      doc.documentElement.classList.toggle('dashboard', on);
      header.hidden = !on;
      telemetry.hidden = !on;
      toggle.textContent = on ? '纯地图' : '看板';
      toggle.setAttribute('aria-pressed', String(on));
      // Do not emit a viewport event: the virtual display remains window-sized.
      controller.setActive(on, pageVisible && !doc.hidden);
    }
    toggle.hidden = false;
    toggle.addEventListener('click', () => { on = !on; update(); });
    doc.addEventListener('visibilitychange', update);
    win.addEventListener('pagehide', () => { pageVisible = false; update(); });
    win.addEventListener('pageshow', () => { pageVisible = true; update(); });
    update();
    return controller;
  }
  return { acceptPanels, expirePanels, normalizedPoint, renderPanels, renderCockpit, createController, mount, readPayload };
});
