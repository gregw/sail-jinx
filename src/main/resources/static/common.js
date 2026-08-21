function esc(val) {
  if (val == null) return '';
  return String(val).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

let _pendingRequests = 0;
function setBusy(on) {
  _pendingRequests = on ? _pendingRequests + 1 : Math.max(0, _pendingRequests - 1);
  if (typeof document !== 'undefined' && document.body) {
    document.body.classList.toggle('busy', _pendingRequests > 0);
  }
}

async function fetchJson(url, options) {
  setBusy(true);
  try {
    const resp = await fetch(url, options);
    if (!resp.ok) {
      console.error('fetchJson non-OK:', resp.status, url);
      let body = null;
      try { body = await resp.json(); } catch (_) {}
      return { ok: false, status: resp.status, body };
    }
    return { ok: true, status: resp.status, body: await resp.json() };
  } catch (e) {
    console.error('fetchJson failed:', url, e);
    return { ok: false, status: 0, error: e };
  } finally {
    setBusy(false);
  }
}

async function postJson(url, payload) {
  return fetchJson(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload == null ? '' : JSON.stringify(payload)
  });
}

async function deleteJson(url) {
  return fetchJson(url, { method: 'DELETE' });
}

/** The error text from a failed fetchJson result, for showing to the user. */
function errorOf(r) {
  return (r && r.body && r.body.error) || ('HTTP ' + (r ? r.status : '?'));
}

function highlightCurrentNav() {
  const here = location.pathname === '/' ? '/index.html' : location.pathname;
  document.querySelectorAll('nav.site-nav a').forEach(a => {
    const href = a.getAttribute('href');
    const target = (href === '/') ? '/index.html' : href;
    if (target === here) a.classList.add('active');
  });
}

let _config = null;
let _configPromise = null;

function getConfig() {
  if (_configPromise) return _configPromise;
  _configPromise = fetchJson('/api/config').then(r => {
    _config = (r.ok && r.body) ? r.body : null;
    return _config;
  });
  return _configPromise;
}

let _whoPromise = null;
let _who = { authEnabled: false, signedIn: false, admin: true, role: 'ADMIN' };

/**
 * Who the server says we are. Cached for the life of the page: the answer cannot change
 * without a round trip through the identity provider, which reloads the page anyway.
 */
function getWhoami() {
  if (_whoPromise) return _whoPromise;
  _whoPromise = fetchJson('/api/whoami').then(r => {
    if (r.ok && r.body) _who = r.body;
    return _who;
  });
  return _whoPromise;
}

/**
 * Whether this caller may edit handicaps and unlock races.
 *
 * <p>With no login configured this is everybody, which is what sail-jinx did before it
 * had one. It answers from the cached whoami, so a page that has not called
 * {@link getWhoami} yet gets the permissive default — which is a UI hint only. The
 * server checks the same thing for real and returns 403; this exists so the buttons
 * match what the button will actually do, not to enforce anything.
 */
function isAdmin() {
  return _who.admin !== false;
}

/** The signed-in account, or null. For display. */
function signedInAs() {
  return _who.signedIn ? (_who.email || null) : null;
}

/**
 * Show the club, the build, and — loudly — any store file that failed to load.
 * A corrupt file must never present as merely missing data: this store is the
 * only copy there is.
 */
async function refreshBuildWidget() {
  const widget = document.getElementById('build-widget');
  const cfg = await getConfig();
  const who = await getWhoami();
  if (!widget) return;
  const authWidget = document.getElementById('auth-widget');
  if (authWidget) {
    // Nothing at all when there is no login: an empty "not signed in" would be a
    // permanent complaint about a machine that is working exactly as intended.
    authWidget.innerHTML = !who.authEnabled ? ''
      : who.signedIn
        ? esc(who.email) + (who.admin ? '' : ' <span class="tag">race officer</span>')
          + ' <a href="' + esc(who.logoutPath || '/auth/logout') + '">sign out</a>'
        : '<span class="tag">local</span>';
  }
  if (!cfg) {
    widget.innerHTML = '<span class="build-err">server unreachable</span>';
    return;
  }
  const errors = cfg.storeErrors || [];
  let html = esc(cfg.club && (cfg.club.shortName || cfg.club.longName)) + ' <span class="build-version">v'
    + esc(cfg.version) + '</span>';
  if (errors.length) {
    html += ' <a class="build-err" href="/audit.html" title="'
      + esc(errors.join('\n')) + '">⚠ ' + errors.length + ' unreadable file'
      + (errors.length === 1 ? '' : 's') + '</a>';
  }
  widget.innerHTML = html;
}

/**
 * A series id for use in a URL path. Series ids are club-scoped and contain a slash
 * (myc.org.au/2026-winter-twilight); encodeURIComponent would turn that into %2F, which
 * the server rejects as an ambiguous path separator. Encode each segment and keep the
 * separator, so the path stays a path.
 */
function seriesPath(id) {
  return String(id || '').split('/').map(encodeURIComponent).join('/');
}

/** Remember the race the user was last looking at, so /race.html has a default. */
function rememberRaceId(raceId) {
  if (raceId) localStorage.setItem('sail-jinx.lastRaceId', raceId);
}

function lastRaceId() {
  return localStorage.getItem('sail-jinx.lastRaceId');
}

function fmtDate(s) {
  return s ? String(s).slice(0, 10) : '';
}

highlightCurrentNav();
refreshBuildWidget();
