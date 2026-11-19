import { readFileSync } from 'node:fs';
import type { AddressInfo } from 'node:net';
import { createApp, type App } from '../src/app.ts';
import { loadConfig, type Config } from '../src/config.ts';
import { openDb } from '../src/db.ts';
import { ScriptedProvider, type ScriptEntry } from '../src/llm/scripted.ts';
import type { Tool } from '../src/tools/registry.ts';
import { silentLogger } from '../src/util.ts';

export const ADMIN_PASSWORD = 'correct-horse-battery';

export function testConfig(overrides: Record<string, string> = {}): Config {
  return loadConfig({
    ADMIN_PASSWORD,
    TIMEZONE: 'Europe/London',
    HOME_NAME: 'Manchester',
    HOME_LAT: '53.48',
    HOME_LON: '-2.24',
    OPENAI_API_KEY: 'sk-test',
    DATA_DIR: '/tmp/jarvis-test-unused',
    ...overrides,
  });
}

export const fixture = (name: string) => readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8');

/** A fetch stand-in that answers Open-Meteo URLs from fixtures and records every URL requested. */
export function fakeFetch(): typeof fetch & { urls: string[] } {
  const urls: string[] = [];
  const fn = (async (input: string | URL | Request) => {
    const url = String(input instanceof Request ? input.url : input);
    urls.push(url);
    if (url.startsWith('https://geocoding-api.open-meteo.com/')) {
      const name = new URL(url).searchParams.get('name') ?? '';
      if (/nowhereville/i.test(name)) return Response.json({});
      return Response.json({ results: [{ name: 'Leeds', admin1: 'England', country: 'United Kingdom', latitude: 53.8, longitude: -1.55 }] });
    }
    if (url.startsWith('https://api.open-meteo.com/v1/forecast')) return new Response(fixture('forecast.json'), { headers: { 'content-type': 'application/json' } });
    return new Response('not found', { status: 404 });
  }) as typeof fetch & { urls: string[] };
  fn.urls = urls;
  return fn;
}

export interface TestApp {
  app: App;
  provider: ScriptedProvider;
  base: string;
  fetch: ReturnType<typeof fakeFetch>;
  clock: { now: number; advance(ms: number): void };
  close(): Promise<void>;
}

export async function startTestApp(opts: { script?: ScriptEntry[]; env?: Record<string, string>; tools?: Tool[] } = {}): Promise<TestApp> {
  const clock = {
    now: Date.UTC(2026, 10, 19, 13, 15),
    advance(ms: number) {
      this.now += ms;
    },
  };
  const provider = new ScriptedProvider(opts.script ?? []);
  const f = fakeFetch();
  const app = createApp({
    config: testConfig(opts.env),
    db: openDb(':memory:'),
    provider,
    clock: () => clock.now,
    fetch: f,
    logger: silentLogger,
    extraTools: opts.tools ?? [],
  });
  await new Promise<void>((resolve) => app.server.listen(0, '127.0.0.1', resolve));
  const port = (app.server.address() as AddressInfo).port;
  return {
    app,
    provider,
    base: `http://127.0.0.1:${port}`,
    fetch: f,
    clock,
    close: () => new Promise<void>((resolve) => app.server.close(() => resolve())),
  };
}

export async function api(t: TestApp, method: string, path: string, body?: unknown, headers: Record<string, string> = {}) {
  const res = await fetch(t.base + path, {
    method,
    headers: { ...(body !== undefined ? { 'content-type': 'application/json' } : {}), ...headers },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json: any = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = text;
  }
  return { status: res.status, json, headers: res.headers };
}

export async function adminCookie(t: TestApp): Promise<string> {
  const res = await api(t, 'POST', '/api/admin/login', { password: ADMIN_PASSWORD });
  if (res.status !== 200) throw new Error('login failed: ' + JSON.stringify(res.json));
  return res.headers.get('set-cookie')!.split(';')[0]!;
}

/** Pairs a device through the public API and returns its bearer header. */
export async function pairDevice(t: TestApp, name = 'Test tablet'): Promise<{ authorization: string; deviceId: string }> {
  const cookie = await adminCookie(t);
  const code = await api(t, 'POST', '/api/admin/pairing-codes', {}, { cookie });
  const paired = await api(t, 'POST', '/api/pair', { code: code.json.code, name });
  if (paired.status !== 200) throw new Error('pairing failed: ' + JSON.stringify(paired.json));
  return { authorization: `Bearer ${paired.json.token}`, deviceId: paired.json.deviceId };
}

/** A harmless action tool for testing the confirmation gate. */
export function fakeActionTool(calls: Array<Record<string, unknown>>): Tool {
  return {
    name: 'set_reminder',
    description: 'Set a reminder (test double).',
    parameters: { type: 'object', properties: { text: { type: 'string' }, at: { type: 'string' } }, required: ['text'] },
    risk: 'action',
    untrusted: false,
    describe: (a) => `set a reminder to "${String(a.text)}"${a.at ? ` at ${String(a.at)}` : ''}`,
    async run(args) {
      calls.push(args);
      return { ok: true, reminderId: 'r1' };
    },
  };
}
