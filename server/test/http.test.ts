import assert from 'node:assert/strict';
import { afterEach, describe, test } from 'node:test';
import { adminCookie, api, ADMIN_PASSWORD, pairDevice, startTestApp, type TestApp } from './helpers.ts';

let t: TestApp | null = null;
afterEach(async () => {
  await t?.close();
  t = null;
});

describe('http: public endpoints', () => {
  test('health reports name, model and status without secrets', async () => {
    t = await startTestApp();
    const res = await api(t, 'GET', '/api/health');
    assert.equal(res.status, 200);
    assert.equal(res.json.name, 'JARVIS');
    assert.equal(res.json.model, 'scripted-model');
    assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
    assert.doesNotMatch(JSON.stringify(res.json), /sk-test/);
  });

  test('serves the console with a strict content security policy', async () => {
    t = await startTestApp();
    const res = await fetch(t.base + '/');
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-security-policy') ?? '', /script-src 'self'/);
    assert.match(await res.text(), /<title>/);
  });

  test('unknown routes 404, wrong methods 405, non-JSON bodies 415', async () => {
    t = await startTestApp();
    assert.equal((await api(t, 'GET', '/api/nope')).status, 404);
    assert.equal((await api(t, 'GET', '/api/pair')).status, 405);
    const res = await fetch(t.base + '/api/pair', { method: 'POST', headers: { 'content-type': 'text/plain' }, body: '{"code":"123456"}' });
    assert.equal(res.status, 415);
  });

  test('chat requires a device token or admin session', async () => {
    t = await startTestApp();
    assert.equal((await api(t, 'POST', '/api/chat', { message: 'hi' })).status, 401);
    assert.equal((await api(t, 'POST', '/api/chat', { message: 'hi' }, { authorization: 'Bearer jd_fake' })).status, 401);
  });
});

describe('http: device pairing', () => {
  test('pair with a one-time code, then chat with the bearer token', async () => {
    t = await startTestApp({ script: [{ text: 'At your service.' }] });
    const cookie = await adminCookie(t);
    const code = await api(t, 'POST', '/api/admin/pairing-codes', {}, { cookie });
    assert.match(code.json.code, /^\d{6}$/);
    const paired = await api(t, 'POST', '/api/pair', { code: code.json.code, name: 'Kitchen Fire HD 10' });
    assert.equal(paired.status, 200);
    assert.match(paired.json.token, /^jd_/);
    assert.equal(paired.json.assistantName, 'JARVIS');

    const again = await api(t, 'POST', '/api/pair', { code: code.json.code, name: 'Thief' });
    assert.equal(again.status, 401, 'codes are single-use');

    const chat = await api(t, 'POST', '/api/chat', { message: 'Hello' }, { authorization: `Bearer ${paired.json.token}` });
    assert.equal(chat.status, 200);
    assert.equal(chat.json.reply, 'At your service.');

    const devices = await api(t, 'GET', '/api/admin/devices', undefined, { cookie });
    assert.equal(devices.json.devices[0].name, 'Kitchen Fire HD 10');
    assert.doesNotMatch(JSON.stringify(devices.json), /jd_|token/);
  });

  test('pairing codes expire after 10 minutes', async () => {
    t = await startTestApp();
    const cookie = await adminCookie(t);
    const code = await api(t, 'POST', '/api/admin/pairing-codes', {}, { cookie });
    t.clock.advance(11 * 60_000);
    assert.equal((await api(t, 'POST', '/api/pair', { code: code.json.code })).status, 401);
  });

  test('pairing attempts are rate limited', async () => {
    t = await startTestApp();
    let last = 0;
    for (let i = 0; i < 11; i++) last = (await api(t, 'POST', '/api/pair', { code: '000000' })).status;
    assert.equal(last, 429);
  });

  test('revoked devices lose access immediately', async () => {
    t = await startTestApp();
    const dev = await pairDevice(t);
    assert.equal((await api(t, 'GET', '/api/device', undefined, { authorization: dev.authorization })).status, 200);
    const cookie = await adminCookie(t);
    assert.equal((await api(t, 'POST', `/api/admin/devices/${dev.deviceId}/revoke`, {}, { cookie })).status, 200);
    assert.equal((await api(t, 'GET', '/api/device', undefined, { authorization: dev.authorization })).status, 401);
    assert.ok(t.app.audit.recent(20).some((e) => e.action === 'device.revoked'));
  });
});

describe('http: admin console', () => {
  test('wrong password is rejected and audited; login is rate limited', async () => {
    t = await startTestApp();
    const bad = await api(t, 'POST', '/api/admin/login', { password: 'nope' });
    assert.equal(bad.status, 401);
    assert.ok(t.app.audit.recent(5).some((e) => e.action === 'admin.login_failed'));
    let last = 0;
    for (let i = 0; i < 5; i++) last = (await api(t, 'POST', '/api/admin/login', { password: 'nope' })).status;
    assert.equal(last, 429);
    assert.equal((await api(t, 'POST', '/api/admin/login', { password: ADMIN_PASSWORD })).status, 429, 'still locked out');
  });

  test('session cookie is HttpOnly and SameSite=Strict', async () => {
    t = await startTestApp();
    const res = await api(t, 'POST', '/api/admin/login', { password: ADMIN_PASSWORD });
    const cookie = res.headers.get('set-cookie') ?? '';
    assert.match(cookie, /HttpOnly/);
    assert.match(cookie, /SameSite=Strict/);
  });

  test('admin endpoints need a session; cross-site requests are refused', async () => {
    t = await startTestApp();
    assert.equal((await api(t, 'GET', '/api/admin/audit')).status, 401);
    const cookie = await adminCookie(t);
    assert.equal((await api(t, 'GET', '/api/admin/audit', undefined, { cookie })).status, 200);
    const csrf = await api(t, 'POST', '/api/admin/pairing-codes', {}, { cookie, origin: 'http://evil.example' });
    assert.equal(csrf.status, 403);
    const host = new URL(t.base).host;
    assert.equal((await api(t, 'POST', '/api/admin/pairing-codes', {}, { cookie, origin: `http://${host}` })).status, 200);
  });

  test('logout ends the session', async () => {
    t = await startTestApp();
    const cookie = await adminCookie(t);
    await api(t, 'POST', '/api/admin/logout', {}, { cookie });
    assert.equal((await api(t, 'GET', '/api/admin/status', undefined, { cookie })).status, 401);
  });

  test('sessions expire after 12 hours', async () => {
    t = await startTestApp();
    const cookie = await adminCookie(t);
    t.clock.advance(12 * 3600_000 + 1);
    assert.equal((await api(t, 'GET', '/api/admin/status', undefined, { cookie })).status, 401);
  });

  test('the console is disabled without a strong admin password', async () => {
    t = await startTestApp({ env: { ADMIN_PASSWORD: 'short' } });
    assert.equal((await api(t, 'POST', '/api/admin/login', { password: 'short' })).status, 503);
    assert.equal((await api(t, 'GET', '/api/admin/me')).json.adminEnabled, false);
  });

  test('status lists tools and never exposes the API key', async () => {
    t = await startTestApp();
    const cookie = await adminCookie(t);
    const res = await api(t, 'GET', '/api/admin/status', undefined, { cookie });
    assert.deepEqual(res.json.tools.map((x: any) => x.name).sort(), ['get_current_time', 'get_weather']);
    assert.doesNotMatch(JSON.stringify(res.json), /sk-test/);
  });

  test('usage and audit endpoints report activity', async () => {
    t = await startTestApp({ script: [{ text: 'Hi.', usage: { input: 500, output: 50 } }] });
    const cookie = await adminCookie(t);
    await api(t, 'POST', '/api/chat', { message: 'Hello', channel: 'text' }, { cookie });
    const usage = await api(t, 'GET', '/api/admin/usage', undefined, { cookie });
    assert.equal(usage.json.monthToDate.calls, 1);
    assert.equal(usage.json.monthToDate.inputTokens, 500);
    const audit = await api(t, 'GET', '/api/admin/audit?action=chat', undefined, { cookie });
    assert.deepEqual(audit.json.entries.map((e: any) => e.action).sort(), ['chat.message', 'chat.reply']);
  });
});
