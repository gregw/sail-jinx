// sail-jinx — shared client utilities.

function esc(val) {
  if (val == null) return '';
  return String(val).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

async function fetchJson(url, options) {
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
  }
}

async function postJson(url, payload) {
  return fetchJson(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload == null ? '' : JSON.stringify(payload)
  });
}

// Mark the current page's link in the nav. The home link ("/") is treated as
// matching only when the path is exactly "/" or "/index.html"; other links
// match on filename equality.
function highlightCurrentNav() {
  const here = location.pathname === '/' ? '/index.html' : location.pathname;
  document.querySelectorAll('nav.site-nav a').forEach(a => {
    const href = a.getAttribute('href');
    const target = (href === '/') ? '/index.html' : href;
    if (target === here) a.classList.add('active');
  });
}

// Cache of the auth status fetched by refreshAuthWidget so other code can
// synchronously read whether the current user is a SailSys admin (vs race
// officer). Pages that need to gate UI on this should call awaitAuth()
// rather than reading the cache directly to avoid races on first paint.
let _authStatus = null;
let _authPromise = null;

function awaitAuth() {
  if (_authPromise) return _authPromise;
  _authPromise = fetchJson('/api/auth/status').then(r => {
    _authStatus = (r.ok && r.body) ? r.body : { authenticated: false };
    return _authStatus;
  });
  return _authPromise;
}

// Drop the cached auth so the next awaitAuth() refetches. Call after login
// or logout — otherwise the cached pre-login state would shadow the new role
// until a full page reload.
function invalidateAuth() {
  _authStatus = null;
  _authPromise = null;
}

function isAdmin() {
  return !!(_authStatus && _authStatus.user && _authStatus.user.isAdmin);
}

// Cache of /api/config — pages need the configured handicapDefinitionId to
// decide whether a race uses our handicap (and therefore whether TCF editing
// + handicap processing is offered). The config is process-global and rarely
// changes, so one fetch per page load is fine.
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

// Disable (gray out + remove click) the Series nav link for non-admin SailSys
// users. Race officers get HTTP 403 on /series/{id} endpoints, so the page
// would be useless to them. We keep the link in the DOM (so the nav layout
// doesn't shift) but mark it disabled via a CSS class.
function applyRoleToNav() {
  const navSeries = document.getElementById('nav-series');
  if (!navSeries) return;
  const link = navSeries.querySelector('a') || navSeries;
  if (_authStatus && _authStatus.authenticated && !isAdmin()) {
    link.classList.add('nav-disabled');
    link.setAttribute('aria-disabled', 'true');
    link.title = 'Series details require an admin SailSys account';
  } else {
    link.classList.remove('nav-disabled');
    link.removeAttribute('aria-disabled');
    link.removeAttribute('title');
  }
}

// Refresh the auth widget in the top-right of the nav. Called on every page.
async function refreshAuthWidget() {
  const widget = document.getElementById('auth-widget');
  if (!widget) {
    // No widget on this page, but still resolve auth so isAdmin() works.
    await awaitAuth();
    applyRoleToNav();
    return;
  }
  const data = await awaitAuth();
  if (!data) {
    widget.innerHTML = 'auth: <em>error</em>';
    return;
  }
  if (data.authenticated && data.user) {
    const role = data.user.isAdmin ? 'admin' : 'race officer';
    widget.innerHTML = 'SailSys: ' + esc(data.user.email)
      + ' <span style="color:#888;">(' + esc(role) + ')</span>';
  } else {
    widget.innerHTML = '<a href="/">Sign in to SailSys</a>';
  }
  applyRoleToNav();
}

highlightCurrentNav();
refreshAuthWidget();
