/* JARVIS admin console. Plain browser JavaScript, no build step, no secrets.
   The API key never reaches this page: every model call happens on the server. */
(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var state = { conversationId: null, name: 'JARVIS', codeTimer: null };

  function api(method, path, body) {
    var opts = { method: method, headers: {}, credentials: 'same-origin' };
    if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    return fetch(path, opts).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (json) {
        if (res.status === 401 && path.indexOf('/api/admin/login') !== 0) { showLogin(); }
        return { status: res.status, ok: res.ok, json: json };
      });
    });
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text; // textContent only: never innerHTML with data
    return e;
  }

  function when(ms) {
    if (!ms) return '–';
    var d = new Date(ms);
    return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }) + ' ' + d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  }

  function setName(name) {
    state.name = name || 'JARVIS';
    document.title = state.name + ' console';
    Array.prototype.forEach.call(document.querySelectorAll('[data-name]'), function (n) { n.textContent = state.name; });
    $('chat-input').placeholder = 'Ask ' + state.name + ' anything…';
  }

  // --- auth ---
  function showLogin() { $('shell').hidden = true; $('login').hidden = false; $('password').focus(); }
  function showShell() { $('login').hidden = true; $('shell').hidden = false; loadStatus(); $('chat-input').focus(); }

  $('login-form').addEventListener('submit', function (e) {
    e.preventDefault();
    $('login-error').textContent = '';
    api('POST', '/api/admin/login', { password: $('password').value }).then(function (r) {
      $('password').value = '';
      if (r.ok) showShell();
      else $('login-error').textContent = r.status === 429 ? 'Too many attempts. Wait 15 minutes.' : 'That password is not right.';
    });
  });
  $('logout').addEventListener('click', function () { api('POST', '/api/admin/logout', {}).then(showLogin); });

  // --- tabs ---
  Array.prototype.forEach.call(document.querySelectorAll('.tabs button'), function (b) {
    b.addEventListener('click', function () {
      Array.prototype.forEach.call(document.querySelectorAll('.tabs button'), function (x) { x.classList.toggle('active', x === b); });
      Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (t) { t.hidden = t.id !== 'tab-' + b.dataset.tab; });
      if (b.dataset.tab === 'devices') loadDevices();
      if (b.dataset.tab === 'activity') loadAudit();
      if (b.dataset.tab === 'system') loadStatus();
    });
  });

  // --- chat ---
  function addMessage(role, text, meta) {
    var m = el('div', 'msg ' + role, text);
    if (meta) m.appendChild(meta);
    $('messages').appendChild(m);
    $('messages').scrollTop = $('messages').scrollHeight;
    return m;
  }

  function metaFor(result) {
    var meta = el('div', 'meta');
    (result.toolsUsed || []).forEach(function (t) { meta.appendChild(el('span', 'chip' + (t.ok ? '' : ' bad'), t.name.replace(/_/g, ' ') + (t.ok ? '' : ' ✕'))); });
    if (result.usage && result.usage.costUsd) meta.appendChild(el('span', 'chip', '$' + result.usage.costUsd.toFixed(5)));
    return meta;
  }

  function confirmButtons(pending) {
    var box = el('div', 'confirm');
    var yes = el('button', 'primary small', 'Confirm');
    var no = el('button', 'ghost small', 'Cancel');
    function decide(decision) {
      yes.disabled = no.disabled = true;
      api('POST', '/api/actions/' + encodeURIComponent(pending.id) + '/' + decision, {}).then(function (r) {
        box.remove();
        addMessage('system', r.ok ? (r.json.status === 'confirmed' ? 'Confirmed.' : r.json.status === 'cancelled' ? 'Cancelled.' : 'That failed: ' + (r.json.error || '')) : (r.json.message || 'No longer pending.'));
      });
    }
    yes.addEventListener('click', function () { decide('confirm'); });
    no.addEventListener('click', function () { decide('cancel'); });
    box.appendChild(yes); box.appendChild(no);
    return box;
  }

  $('chat-form').addEventListener('submit', function (e) {
    e.preventDefault();
    var text = $('chat-input').value.trim();
    if (!text) return;
    $('chat-input').value = '';
    addMessage('user', text);
    var typing = addMessage('assistant', '');
    typing.appendChild(el('span', 'typing')).innerHTML = '<i></i><i></i><i></i>';
    document.querySelector('.brand .orb').classList.add('thinking');
    api('POST', '/api/chat', { message: text, conversationId: state.conversationId, channel: $('voice-style').checked ? 'voice' : 'text' }).then(function (r) {
      typing.remove();
      document.querySelector('.brand .orb').classList.remove('thinking');
      if (!r.ok) { addMessage('system', r.json.reply || r.json.message || 'Something went wrong.'); return; }
      state.conversationId = r.json.conversationId;
      var m = addMessage('assistant', r.json.reply, metaFor(r.json));
      if (r.json.pendingAction) m.appendChild(confirmButtons(r.json.pendingAction));
    });
  });
  $('new-chat').addEventListener('click', function () { state.conversationId = null; $('messages').textContent = ''; addMessage('system', 'New conversation.'); });

  // --- devices ---
  $('make-code').addEventListener('click', function () {
    api('POST', '/api/admin/pairing-codes', {}).then(function (r) {
      if (!r.ok) return;
      $('code-box').hidden = false;
      $('code').textContent = r.json.code;
      clearInterval(state.codeTimer);
      var tick = function () {
        var left = Math.max(0, Math.round((r.json.expiresAt - Date.now()) / 1000));
        $('code-expiry').textContent = left > 0 ? 'expires in ' + Math.floor(left / 60) + ':' + ('0' + (left % 60)).slice(-2) : 'expired';
        if (left <= 0) clearInterval(state.codeTimer);
      };
      tick();
      state.codeTimer = setInterval(tick, 1000);
      setTimeout(loadDevices, 15000);
    });
  });

  function loadDevices() {
    api('GET', '/api/admin/devices').then(function (r) {
      var body = $('devices');
      body.textContent = '';
      (r.json.devices || []).forEach(function (d) {
        var tr = el('tr', d.revokedAt ? 'revoked' : '');
        tr.appendChild(el('td', '', d.name));
        tr.appendChild(el('td', '', when(d.createdAt)));
        tr.appendChild(el('td', '', d.revokedAt ? 'revoked ' + when(d.revokedAt) : when(d.lastSeenAt)));
        var td = el('td');
        if (!d.revokedAt) {
          var b = el('button', 'danger small', 'Revoke');
          b.addEventListener('click', function () {
            if (!confirm('Revoke access for "' + d.name + '"? It will need to pair again.')) return;
            api('POST', '/api/admin/devices/' + encodeURIComponent(d.id) + '/revoke', {}).then(loadDevices);
          });
          td.appendChild(b);
        }
        tr.appendChild(td);
        body.appendChild(tr);
      });
      if (!body.children.length) { var tr = el('tr'); var td = el('td', 'muted', 'No devices yet.'); td.colSpan = 4; tr.appendChild(td); body.appendChild(tr); }
    });
  }

  // --- activity ---
  function loadAudit() {
    var action = $('audit-filter').value;
    api('GET', '/api/admin/audit?limit=200' + (action ? '&action=' + encodeURIComponent(action) : '')).then(function (r) {
      var body = $('audit');
      body.textContent = '';
      (r.json.entries || []).forEach(function (e) {
        var tr = el('tr');
        tr.appendChild(el('td', '', when(e.ts)));
        tr.appendChild(el('td', '', e.actor));
        tr.appendChild(el('td', '', e.action));
        tr.appendChild(el('td', 'detail', e.detail ? JSON.stringify(e.detail) : ''));
        body.appendChild(tr);
      });
    });
  }
  $('audit-filter').addEventListener('change', loadAudit);
  $('audit-refresh').addEventListener('click', loadAudit);

  // --- system ---
  function loadStatus() {
    api('GET', '/api/admin/status').then(function (r) {
      if (!r.ok) return;
      var s = r.json;
      setName(s.assistantName);
      var st = s.modelStatus.status;
      $('model-dot').className = 'dot ' + (st === 'ok' ? 'ok' : st === 'unverified' ? 'warn' : 'bad');
      $('model-label').textContent = s.model + (st === 'ok' ? '' : ' · ' + st.replace('_', ' '));
      $('model-name').textContent = s.model;
      $('model-detail').textContent = st === 'ok' ? 'Verified with OpenAI.' : (s.modelStatus.detail || st);
      var settings = $('settings');
      settings.textContent = '';
      [['Assistant', s.assistantName], ['Version', s.version], ['Time zone', s.timezone], ['Home', s.home || 'not set'],
       ['Monthly budget', s.monthlyBudgetUsd ? '$' + s.monthlyBudgetUsd.toFixed(2) : 'no cap'], ['History kept', s.conversationRetentionHours + ' h']].forEach(function (p) {
        settings.appendChild(el('dt', '', p[0])); settings.appendChild(el('dd', '', p[1]));
      });
      var tools = $('tools');
      tools.textContent = '';
      s.tools.forEach(function (t) {
        var li = el('li');
        li.appendChild(el('span', '', t.name));
        li.appendChild(el('span', 'chip', t.risk === 'action' ? 'needs confirmation' : 'read-only'));
        if (t.untrusted) li.appendChild(el('span', 'chip', 'external data'));
        tools.appendChild(li);
      });
    });
    api('GET', '/api/admin/usage').then(function (r) {
      if (!r.ok) return;
      var u = r.json.monthToDate, budget = r.json.monthlyBudgetUsd;
      $('spend').textContent = '$' + u.costUsd.toFixed(4);
      $('spend-bar').style.width = budget ? Math.min(100, (u.costUsd / budget) * 100) + '%' : '0';
      $('spend-detail').textContent = u.calls + ' model calls · ' + u.inputTokens.toLocaleString() + ' in / ' + u.outputTokens.toLocaleString() + ' out tokens' + (budget ? ' · budget $' + budget.toFixed(2) : '');
    });
  }

  // --- start ---
  api('GET', '/api/admin/me').then(function (r) {
    setName(r.json.assistantName);
    if (!r.json.adminEnabled) { showLogin(); $('login-form').hidden = true; $('admin-disabled').hidden = false; return; }
    if (r.json.loggedIn) showShell(); else showLogin();
  });
})();
