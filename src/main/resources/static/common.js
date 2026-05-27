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

// Refresh the auth widget in the top-right of the nav. Called on every page.
async function refreshAuthWidget() {
  const widget = document.getElementById('auth-widget');
  if (!widget) return;
  const r = await fetchJson('/api/auth/status');
  if (!r.ok) {
    widget.innerHTML = 'auth: <em>error</em>';
    return;
  }
  const data = r.body;
  if (data.authenticated && data.user) {
    widget.innerHTML = 'SailSys: ' + esc(data.user.email);
  } else {
    widget.innerHTML = '<a href="/">Sign in to SailSys</a>';
  }
}

highlightCurrentNav();
refreshAuthWidget();
