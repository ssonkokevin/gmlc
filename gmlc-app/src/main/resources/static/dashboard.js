'use strict';

/*
 * The dashboard is a plain client of the Le API. It holds no credentials of its
 * own: the operator pastes a key, it lives in this tab's sessionStorage, and
 * every read or locate goes through the same authenticated endpoints any other
 * client would use. Nothing here decides a position; the panel shows what the
 * gateway returned, including the method, uncertainty, confidence and age.
 */

const KEY_STORAGE = 'gmlc.apiKey';
const CELL_PAGE = 500;
const LOG_PAGE = 20;
const POLL_LIMIT_S = 30;
const FINAL_STATES = new Set(['ANSWERED', 'DELIVERED', 'FAILED', 'TIMEOUT']);

const state = {
  key: null,
  client: null,
  cells: [],
  cellsTotal: 0,
  lastView: null,
  pollToken: 0,
};

const byId = (id) => document.getElementById(id);

function text(tag, value, className) {
  const node = document.createElement(tag);
  if (value !== undefined && value !== null) {
    node.textContent = String(value);
  }
  if (className) {
    node.className = className;
  }
  return node;
}

function clear(node) {
  node.replaceChildren();
}

function missing(value) {
  return value === null || value === undefined;
}

function setMessage(id, message, tone) {
  const node = byId(id);
  node.textContent = message || '';
  node.className = 'message' + (tone ? ' ' + tone : '');
}

async function api(path, options = {}) {
  const headers = Object.assign({Accept: 'application/json'}, options.headers || {});
  if (state.key) {
    headers.Authorization = 'Bearer ' + state.key;
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  let response;
  try {
    response = await fetch(path, Object.assign({}, options, {headers}));
  } catch (error) {
    return {ok: false, status: 0, body: null, problem: 'the gateway is not reachable'};
  }
  const raw = await response.text();
  let body = null;
  if (raw) {
    try {
      body = JSON.parse(raw);
    } catch (ignored) {
      body = null;
    }
  }
  return {
    ok: response.ok,
    status: response.status,
    body,
    problem: problemText(response, body),
  };
}

function problemText(response, body) {
  if (body && typeof body === 'object') {
    const parts = [];
    if (body.code) parts.push(body.code);
    if (body.title) parts.push(body.title);
    if (body.detail) parts.push(body.detail);
    if (parts.length) return parts.join(' - ');
  }
  return 'HTTP ' + response.status + (response.statusText ? ' ' + response.statusText : '');
}

/* -- Session -------------------------------------------------------------- */

async function connect(key, options = {}) {
  const trimmed = (key || '').trim();
  if (!trimmed) {
    setMessage('session-message', 'Enter an API key to connect.', 'error');
    return;
  }
  state.key = trimmed;
  const me = await api('/v1/clients/me');
  if (!me.ok) {
    state.key = null;
    setMessage('session-message', 'Not connected: ' + me.problem, 'error');
    return;
  }
  state.client = me.body;
  sessionStorage.setItem(KEY_STORAGE, state.key);
  if (!options.quiet) {
    setMessage('session-message', 'Connected as ' + me.body.name + '.', 'ok');
  }
  renderClient();
  await Promise.all([loadCells(), loadLog()]);
}

function forget() {
  state.key = null;
  state.client = null;
  state.cells = [];
  state.cellsTotal = 0;
  state.lastView = null;
  state.pollToken += 1;
  sessionStorage.removeItem(KEY_STORAGE);
  byId('api-key').value = '';
  byId('client-facts').replaceChildren();
  fillPurposes([]);
  setMessage('session-message', 'Key forgotten; reload the page for a clean slate.', 'warn');
  setMessage('locate-message', '', null);
  setMessage('map-message', 'Connect to load the cell plan.', null);
  setMessage('log-message', '', null);
  renderEmptyResult();
  renderLog([]);
  drawMap();
}

function renderClient() {
  const client = state.client;
  const facts = byId('client-facts');
  clear(facts);
  const add = (label, value) => {
    facts.appendChild(text('dt', label));
    facts.appendChild(text('dd', value, typeof value === 'string' ? undefined : 'mono'));
  };
  add('Client', client.name);
  add('Client id', client.clientId);
  add('Tenant', client.tenantId);
  add('Purposes', (client.purposes || []).join(', ') || 'none registered');
  add('Best accuracy', client.maxAccuracyM + ' m');
  add('Rate', client.ratePerSecond + '/s, ' + client.ratePerDay + '/day');
  add('Allowed from', (client.ipAllowlist || []).join(', ') || 'any address');
  byId('accuracy-hint').textContent =
    'This client may request no tighter than ' + client.maxAccuracyM + ' m.';
  byId('accuracy').min = client.maxAccuracyM;
  byId('accuracy').placeholder = 'optional, at least ' + client.maxAccuracyM;
  fillPurposes(client.purposes || []);
}

function fillPurposes(purposes) {
  const select = byId('purpose');
  clear(select);
  if (!purposes.length) {
    const option = text('option', 'No purpose registered for this client');
    option.value = '';
    option.disabled = true;
    select.appendChild(option);
    byId('locate').disabled = true;
    return;
  }
  purposes.forEach((purpose) => {
    const option = text('option', purpose);
    option.value = purpose;
    select.appendChild(option);
  });
  byId('locate').disabled = false;
}

/* -- Cells and map -------------------------------------------------------- */

async function loadCells() {
  const result = await api('/v1/cells?limit=' + CELL_PAGE);
  if (!result.ok) {
    setMessage('map-message', 'Cell plan not loaded: ' + result.problem, 'error');
    return;
  }
  state.cells = (result.body.cells || []).filter(
    (cell) => cell.latitude !== null && cell.longitude !== null);
  state.cellsTotal = result.body.total || state.cells.length;
  if (state.cellsTotal > state.cells.length) {
    setMessage('map-message', 'Showing ' + state.cells.length + ' of '
      + state.cellsTotal + ' cells; the API pages ' + CELL_PAGE + ' at a time.', 'warn');
  } else {
    setMessage('map-message', '', null);
  }
  drawMap();
}

const METRES_PER_DEG_LAT = 110540;

function metresPerDegLon(latitude) {
  return 111320 * Math.cos(latitude * Math.PI / 180);
}

function drawMap() {
  const svg = byId('map');
  clear(svg);
  const NS = 'http://www.w3.org/2000/svg';
  const make = (tag, attributes) => {
    const node = document.createElementNS(NS, tag);
    Object.keys(attributes || {}).forEach((name) => node.setAttribute(name, attributes[name]));
    return node;
  };

  const result = state.lastView && state.lastView.result ? state.lastView.result : null;
  const points = state.cells.slice();
  if (result && !missing(result.latitude) && !missing(result.longitude)) {
    points.push({latitude: result.latitude, longitude: result.longitude,
      radiusM: result.uncertaintyM, rat: 'RESULT', cellIdentity: 'returned circle'});
  }
  if (!points.length) {
    svg.setAttribute('viewBox', '0 0 100 100');
    const note = make('text', {x: 50, y: 50, 'text-anchor': 'middle',
      'font-size': '4', fill: '#5b6b7c'});
    note.textContent = state.key ? 'No cells with coordinates in the plan.'
      : 'Connect to load the cell plan.';
    svg.appendChild(note);
    return;
  }

  let minLat = Infinity;
  let maxLat = -Infinity;
  let minLon = Infinity;
  let maxLon = -Infinity;
  points.forEach((point) => {
    const lat = Number(point.latitude);
    const lon = Number(point.longitude);
    const radius = Number(point.radiusM) || 0;
    const dLat = radius / METRES_PER_DEG_LAT;
    const dLon = radius / Math.max(1, metresPerDegLon(lat));
    minLat = Math.min(minLat, lat - dLat);
    maxLat = Math.max(maxLat, lat + dLat);
    minLon = Math.min(minLon, lon - dLon);
    maxLon = Math.max(maxLon, lon + dLon);
  });

  const lat0 = (minLat + maxLat) / 2;
  const lon0 = (minLon + maxLon) / 2;
  const mPerLon = Math.max(1, metresPerDegLon(lat0));
  const project = (lat, lon) => ({
    x: (Number(lon) - lon0) * mPerLon,
    y: -(Number(lat) - lat0) * METRES_PER_DEG_LAT,
  });

  const corners = [
    project(minLat, minLon), project(minLat, maxLon),
    project(maxLat, minLon), project(maxLat, maxLon),
  ];
  let minX = Math.min(...corners.map((c) => c.x));
  let maxX = Math.max(...corners.map((c) => c.x));
  let minY = Math.min(...corners.map((c) => c.y));
  let maxY = Math.max(...corners.map((c) => c.y));
  const padX = Math.max((maxX - minX) * 0.08, 500);
  const padY = Math.max((maxY - minY) * 0.08, 500);
  minX -= padX;
  maxX += padX;
  minY -= padY;
  maxY += padY;
  const width = Math.max(maxX - minX, 1000);
  const height = Math.max(maxY - minY, 1000);
  svg.setAttribute('viewBox', [minX, minY, width, height].join(' '));

  const step = niceStep(Math.max(width, height) / 6);
  for (let x = Math.floor(minX / step) * step; x <= maxX; x += step) {
    svg.appendChild(make('line', {x1: x, y1: minY, x2: x, y2: maxY,
      stroke: '#e6ecf2', 'stroke-width': 1, 'vector-effect': 'non-scaling-stroke'}));
  }
  for (let y = Math.floor(minY / step) * step; y <= maxY; y += step) {
    svg.appendChild(make('line', {x1: minX, y1: y, x2: maxX, y2: y,
      stroke: '#e6ecf2', 'stroke-width': 1, 'vector-effect': 'non-scaling-stroke'}));
  }

  const colours = {GSM: '#b8860b', UMTS: '#7b4fa8', LTE: '#0b5cab', NR: '#0f766e'};
  state.cells.forEach((cell) => {
    const centre = project(cell.latitude, cell.longitude);
    const colour = colours[cell.rat] || '#5b6b7c';
    const radius = Number(cell.radiusM) || 0;
    if (radius > 0) {
      const circle = make('circle', {cx: centre.x, cy: centre.y, r: radius,
        fill: colour, 'fill-opacity': 0.12, stroke: colour, 'stroke-opacity': 0.65,
        'stroke-width': 1, 'vector-effect': 'non-scaling-stroke'});
      circle.appendChild(text('title', cell.cellIdentity + ' (' + cell.rat + '), '
        + formatMetres(radius) + ' plan radius'));
      svg.appendChild(circle);
    }
    const dot = make('circle', {cx: centre.x, cy: centre.y,
      r: Math.max(width / 600, 20), fill: colour});
    dot.appendChild(text('title', cell.cellIdentity + ' (' + cell.rat + ')'
      + (cell.siteName ? ', ' + cell.siteName : '')));
    svg.appendChild(dot);
  });

  if (result && !missing(result.latitude) && !missing(result.longitude)) {
    const centre = project(result.latitude, result.longitude);
    const circle = make('circle', {cx: centre.x, cy: centre.y,
      r: Math.max(Number(result.uncertaintyM) || 0, width / 500),
      fill: '#b42318', 'fill-opacity': 0.2, stroke: '#b42318',
      'stroke-width': 2, 'vector-effect': 'non-scaling-stroke'});
    circle.appendChild(text('title', 'Returned ' + result.method + ' circle, '
      + formatMetres(result.uncertaintyM)));
    svg.appendChild(circle);
    svg.appendChild(make('circle', {cx: centre.x, cy: centre.y,
      r: Math.max(width / 500, 25), fill: '#b42318'}));
  }

  const barMetres = niceStep(width / 4);
  const barHeight = height * 0.03;
  const barY = maxY - height * 0.08;
  svg.appendChild(make('line', {x1: minX + width * 0.03, y1: barY,
    x2: minX + width * 0.03 + barMetres, y2: barY,
    stroke: '#1b2430', 'stroke-width': 2, 'vector-effect': 'non-scaling-stroke'}));
  const label = make('text', {x: minX + width * 0.03, y: barY - barHeight,
    'font-size': height * 0.028, fill: '#1b2430'});
  label.textContent = formatMetres(barMetres);
  svg.appendChild(label);

  const north = make('text', {x: maxX - width * 0.04, y: minY + height * 0.06,
    'text-anchor': 'end', 'font-size': height * 0.035, fill: '#5b6b7c'});
  north.textContent = 'N';
  svg.appendChild(north);
}

function niceStep(target) {
  const exponent = Math.pow(10, Math.floor(Math.log10(Math.max(target, 1))));
  const candidates = [1, 2, 5, 10];
  for (const candidate of candidates) {
    if (candidate * exponent >= target) {
      return candidate * exponent;
    }
  }
  return 10 * exponent;
}

/* -- Locate --------------------------------------------------------------- */

async function submitLocate(event) {
  event.preventDefault();
  if (!state.key) {
    setMessage('locate-message', 'Connect with an API key first.', 'error');
    return;
  }
  const msisdn = byId('msisdn').value.trim();
  const purpose = byId('purpose').value;
  const accuracyRaw = byId('accuracy').value.trim();
  const priority = byId('priority').value;
  const grant = byId('consent').value === 'grant';
  if (!msisdn || !purpose) {
    setMessage('locate-message', 'A number and a purpose are required.', 'error');
    return;
  }

  byId('locate').disabled = true;
  setMessage('locate-message', 'Working...', null);
  try {
    if (grant) {
      const consent = await api('/v1/consents', {
        method: 'POST',
        body: JSON.stringify({msisdn, purpose, channel: 'PORTAL'}),
      });
      if (!consent.ok) {
        setMessage('locate-message', 'Consent not recorded: ' + consent.problem, 'error');
        return;
      }
    }

    const body = {msisdn, purpose, priority};
    if (accuracyRaw) {
      body.accuracyMeters = Number(accuracyRaw);
    }
    const response = await api('/v1/locations', {
      method: 'POST',
      headers: {'Idempotency-Key': newKey()},
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      setMessage('locate-message', 'Request refused: ' + response.problem, 'error');
      renderFailure(response.problem);
      return;
    }
    setMessage('locate-message', 'Request ' + response.body.requestId
      + ' is ' + response.body.state + '.', response.body.state === 'DELIVERED' ? 'ok' : null);
    showView(response.body);
    if (!FINAL_STATES.has(response.body.state)) {
      pollUntilFinal(response.body.requestId);
    }
    await loadLog();
  } finally {
    byId('locate').disabled = false;
  }
}

function pollUntilFinal(requestId) {
  const token = ++state.pollToken;
  let waited = 0;
  const tick = async () => {
    if (token !== state.pollToken) {
      return;
    }
    waited += 1;
    if (waited > POLL_LIMIT_S) {
      setMessage('locate-message', 'Still not final after ' + POLL_LIMIT_S
        + ' s; the request id can be followed from the log.', 'warn');
      return;
    }
    const result = await api('/v1/locations/' + encodeURIComponent(requestId));
    if (token !== state.pollToken) {
      return;
    }
    if (!result.ok) {
      setMessage('locate-message', 'Status check failed: ' + result.problem, 'error');
      return;
    }
    showView(result.body);
    if (FINAL_STATES.has(result.body.state)) {
      await loadLog();
      return;
    }
    setTimeout(tick, 1000);
  };
  setTimeout(tick, 1000);
}

/* -- Result panel --------------------------------------------------------- */

function renderEmptyResult() {
  const panel = byId('result');
  clear(panel);
  panel.appendChild(text('p', 'No request yet.', 'empty'));
}

function renderFailure(problem) {
  const panel = byId('result');
  clear(panel);
  panel.appendChild(text('p', 'Request refused', 'error-code'));
  panel.appendChild(text('p', problem, 'muted'));
}

function showView(view) {
  state.lastView = view;
  const panel = byId('result');
  clear(panel);

  const badges = document.createElement('div');
  badges.className = 'badges';
  badges.appendChild(text('span', view.state, 'badge ' + String(view.state).toLowerCase()));
  if (view.result && view.result.method) {
    badges.appendChild(text('span', view.result.method, 'badge'));
  }
  if (view.result && view.result.engine) {
    badges.appendChild(text('span', view.result.engine, 'badge'));
  }
  panel.appendChild(badges);

  const result = view.result;
  if (result) {
    panel.appendChild(text('p', '± ' + formatMetres(result.uncertaintyM), 'headline'));
    const second = [];
    if (!missing(result.confidence)) {
      second.push(result.confidence + '% confidence');
    }
    if (!missing(result.ageSeconds)) {
      second.push('age ' + formatAge(result.ageSeconds));
    }
    if (second.length) {
      panel.appendChild(text('p', second.join(' - '), 'muted'));
    }
    if (!missing(result.latitude) && !missing(result.longitude)) {
      panel.appendChild(text('p', formatCoord(result.latitude) + ', '
        + formatCoord(result.longitude), 'coords'));
    }
    panel.appendChild(definitionList([
      ['Shape', result.shape],
      ['Serving cell', result.servingCell],
      ['Engine', result.engine],
      ['Positioning node', result.positioningNode],
      ['Accuracy requested', missing(result.accuracyRequestedM) ? null
        : result.accuracyRequestedM + ' m'],
      ['Accuracy fulfilled', missing(result.accuracyFulfilled) ? null
        : (result.accuracyFulfilled ? 'yes' : 'no')],
      ['Methods attempted', (result.methodsAttempted || []).join(' -> ') || null],
      ['Produced at', result.producedAt ? formatTime(result.producedAt) : null],
      ['Request', view.requestId],
      ['Purpose', view.purpose],
      ['Priority', view.priority],
      ['Created', view.createdAt ? formatTime(view.createdAt) : null],
      ['Completed', view.completedAt ? formatTime(view.completedAt) : null],
    ]));
    if (view.replayed) {
      panel.appendChild(text('p', 'This POST replayed an earlier request with the same '
        + 'Idempotency-Key.', 'muted'));
    }
  } else if (view.errorCode) {
    panel.appendChild(text('p', view.errorCode, 'error-code'));
    if (view.errorDetail) {
      panel.appendChild(text('p', view.errorDetail, 'muted'));
    }
    panel.appendChild(text('p', 'No position was returned. The gateway fails closed '
      + 'rather than approximate.', 'muted'));
  } else {
    panel.appendChild(text('p', 'The request is ' + view.state + '; no position yet.', 'muted'));
  }
  drawMap();
}

function definitionList(rows) {
  const list = document.createElement('dl');
  list.className = 'facts';
  rows.forEach(([label, value]) => {
    if (value === null || value === undefined || value === '') {
      return;
    }
    list.appendChild(text('dt', label));
    const isIdentifier = label === 'Request';
    list.appendChild(text('dd', value, isIdentifier ? 'mono' : undefined));
  });
  return list;
}

function formatMetres(metres) {
  if (metres === null || metres === undefined) {
    return 'unknown';
  }
  const value = Number(metres);
  if (!Number.isFinite(value)) {
    return 'unknown';
  }
  if (value < 1000) {
    return Math.round(value) + ' m';
  }
  if (value < 10000) {
    return (value / 1000).toFixed(1) + ' km';
  }
  return Math.round(value / 1000) + ' km';
}

function formatAge(seconds) {
  const value = Number(seconds);
  if (!Number.isFinite(value)) {
    return 'unknown';
  }
  if (value < 90) {
    return Math.round(value) + ' s';
  }
  if (value < 5400) {
    return Math.round(value / 60) + ' min';
  }
  return (value / 3600).toFixed(1) + ' h';
}

function formatCoord(value) {
  return value === null || value === undefined ? '-' : Number(value).toFixed(6);
}

function formatTime(iso) {
  const when = new Date(iso);
  return Number.isNaN(when.getTime()) ? iso : when.toLocaleString();
}

/* -- Request log ---------------------------------------------------------- */

async function loadLog() {
  const result = await api('/v1/cdrs?limit=' + LOG_PAGE);
  if (!result.ok) {
    setMessage('log-message', 'Log not loaded: ' + result.problem, 'error');
    return;
  }
  setMessage('log-message', '', null);
  renderLog(result.body.cdrs || []);
}

function renderLog(rows) {
  const body = byId('log').querySelector('tbody');
  clear(body);
  if (!rows.length) {
    const row = document.createElement('tr');
    const cell = text('td', 'No completed requests for this client yet.', 'empty');
    cell.colSpan = 7;
    row.appendChild(cell);
    body.appendChild(row);
    return;
  }
  rows.forEach((cdr) => {
    const row = document.createElement('tr');
    row.dataset.request = cdr.requestId;
    row.title = 'Show request ' + cdr.requestId;
    row.appendChild(text('td', formatTime(cdr.createdAt)));
    row.appendChild(text('td', cdr.recordStatus));
    row.appendChild(text('td', cdr.resultCode));
    row.appendChild(text('td', cdr.positioningMethod || '-'));
    row.appendChild(text('td', missing(cdr.uncertaintyM) ? '-'
      : formatMetres(cdr.uncertaintyM)));
    row.appendChild(text('td', missing(cdr.latitude) || missing(cdr.longitude) ? '-'
      : formatCoord(cdr.latitude) + ', ' + formatCoord(cdr.longitude)));
    row.appendChild(text('td', cdr.requestId, 'id'));
    row.addEventListener('click', () => showRequest(cdr.requestId));
    body.appendChild(row);
  });
}

async function showRequest(requestId) {
  const result = await api('/v1/locations/' + encodeURIComponent(requestId));
  if (!result.ok) {
    setMessage('log-message', 'Could not read that request: ' + result.problem, 'error');
    return;
  }
  showView(result.body);
}

/* -- Wiring --------------------------------------------------------------- */

function newKey() {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID();
  }
  return 'dash-' + Date.now() + '-' + Math.random().toString(16).slice(2);
}

function boot() {
  byId('session-form').addEventListener('submit', (event) => {
    event.preventDefault();
    connect(byId('api-key').value);
  });
  byId('forget').addEventListener('click', forget);
  byId('locate-form').addEventListener('submit', submitLocate);
  byId('refresh-log').addEventListener('click', () => {
    if (state.key) {
      loadLog();
    }
  });

  renderEmptyResult();
  fillPurposes([]);
  drawMap();

  const stored = sessionStorage.getItem(KEY_STORAGE);
  if (stored) {
    byId('api-key').value = stored;
    connect(stored, {quiet: true});
  } else {
    setMessage('map-message', 'Connect to load the cell plan and the request log.');
  }
}

document.addEventListener('DOMContentLoaded', boot);
