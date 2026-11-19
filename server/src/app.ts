import { readFileSync } from 'node:fs';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Agent, AgentError } from './agent.ts';
import { AuditLog } from './audit.ts';
import { Auth, MIN_ADMIN_PASSWORD, RateLimiter } from './auth.ts';
import type { Config } from './config.ts';
import { ConversationStore } from './conversations.ts';
import type { DB } from './db.ts';
import { HttpError, Router, SECURITY_HEADERS, parseCookies, readJson, sendJson, type Ctx } from './http.ts';
import type { ModelProvider, ModelStatus } from './llm/types.ts';
import type { Channel } from './persona.ts';
import { ToolRegistry, type Tool } from './tools/registry.ts';
import { timeTool } from './tools/time.ts';
import { weatherTool } from './tools/weather.ts';
import { UsageMeter } from './usage.ts';
import { consoleLogger, systemClock, type Clock, type Logger } from './util.ts';

export const VERSION = '0.1.0';
const COOKIE = 'jarvis_session';
const PUBLIC_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');
const STATIC: Record<string, string> = {
  '/': 'index.html',
  '/index.html': 'index.html',
  '/app.js': 'app.js',
  '/app.css': 'app.css',
};
const MIME: Record<string, string> = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8' };
const CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

export interface AppOptions {
  config: Config;
  db: DB;
  provider: ModelProvider;
  clock?: Clock;
  fetch?: typeof fetch;
  logger?: Logger;
  extraTools?: Tool[];
}

export interface App {
  server: Server;
  agent: Agent;
  auth: Auth;
  audit: AuditLog;
  conversations: ConversationStore;
  usage: UsageMeter;
  tools: ToolRegistry;
  modelStatus: { status: ModelStatus; detail?: string };
  verifyModel(): Promise<void>;
}

type Actor = { kind: 'admin'; id: 'admin' } | { kind: 'device'; id: string; name: string };

export function defaultTools(): Tool[] {
  return [timeTool, weatherTool];
}

export function createApp(opts: AppOptions): App {
  const clock = opts.clock ?? systemClock;
  const logger = opts.logger ?? consoleLogger;
  const { config, db, provider } = opts;
  const audit = new AuditLog(db, clock);
  const auth = new Auth(db, clock, audit, config.adminPassword);
  const conversations = new ConversationStore(db, clock);
  const usage = new UsageMeter(db, config, clock);
  const tools = new ToolRegistry();
  for (const t of [...defaultTools(), ...(opts.extraTools ?? [])]) tools.register(t);
  const agent = new Agent({ config, provider, tools, conversations, audit, usage, clock, fetch: opts.fetch ?? fetch, logger });

  const loginLimiter = new RateLimiter(5, 15 * 60_000, clock);
  const pairLimiter = new RateLimiter(10, 15 * 60_000, clock);

  const app: App = {
    server: null as unknown as Server,
    agent,
    auth,
    audit,
    conversations,
    usage,
    tools,
    modelStatus: { status: 'unverified' },
    async verifyModel() {
      app.modelStatus = await provider.verify();
      if (app.modelStatus.status === 'ok') logger.info('model verified', { model: provider.model });
      else logger.warn('model not verified', { model: provider.model, ...app.modelStatus });
    },
  };

  if (!auth.adminEnabled) logger.warn(`ADMIN_PASSWORD is unset or shorter than ${MIN_ADMIN_PASSWORD} characters: the admin console is disabled`);

  // --- auth helpers ---

  const sessionToken = (ctx: Ctx) => parseCookies(ctx.req.headers.cookie)[COOKIE];

  /** Rejects cross-site requests to cookie-authenticated endpoints. */
  function checkOrigin(ctx: Ctx): void {
    const origin = ctx.req.headers.origin;
    if (origin === undefined) return; // non-browser client
    const host = ctx.req.headers.host;
    if (!host || (origin !== `http://${host}` && origin !== `https://${host}`)) throw new HttpError(403, 'bad_origin');
  }

  function requireAdmin(ctx: Ctx): Actor {
    if (!auth.adminEnabled) throw new HttpError(503, 'admin_disabled', 'Set ADMIN_PASSWORD (10+ characters) to enable the admin console');
    if (ctx.req.method !== 'GET') checkOrigin(ctx);
    if (!auth.validSession(sessionToken(ctx))) throw new HttpError(401, 'login_required');
    return { kind: 'admin', id: 'admin' };
  }

  function requireActor(ctx: Ctx): Actor {
    const header = ctx.req.headers.authorization ?? '';
    if (header.startsWith('Bearer ')) {
      const device = auth.deviceForToken(header.slice(7).trim());
      if (!device) throw new HttpError(401, 'invalid_device_token');
      return { kind: 'device', id: `device:${device.id}`, name: device.name };
    }
    return requireAdmin(ctx);
  }

  function canAccessConversation(actor: Actor, conversationId: string): boolean {
    const owner = conversations.owner(conversationId);
    return owner !== null && (actor.kind === 'admin' || owner === actor.id);
  }

  const str = (v: unknown, max: number): string => (typeof v === 'string' ? v.slice(0, max) : '');

  // --- routes ---

  const r = new Router();

  r.on('GET', '/api/health', (ctx) => {
    sendJson(ctx.res, 200, {
      ok: true,
      name: config.assistantName,
      version: VERSION,
      model: provider.model,
      modelStatus: app.modelStatus.status,
      time: new Date(clock()).toISOString(),
    });
  });

  r.on('POST', '/api/pair', async (ctx) => {
    if (!pairLimiter.attempt(ctx.ip)) throw new HttpError(429, 'too_many_attempts');
    const body = await ctx.body();
    const code = str(body.code, 12).replace(/\s+/g, '');
    if (!/^\d{6}$/.test(code)) throw new HttpError(400, 'invalid_code', 'Pairing codes are 6 digits');
    const result = auth.pair(code, str(body.name, 60) || 'Fire tablet');
    if (!result) {
      audit.record('anonymous', 'pairing.failed', { ip: ctx.ip });
      throw new HttpError(401, 'invalid_or_expired_code');
    }
    sendJson(ctx.res, 200, { deviceId: result.deviceId, token: result.token, assistantName: config.assistantName });
  });

  r.on('GET', '/api/device', (ctx) => {
    const actor = requireActor(ctx);
    sendJson(ctx.res, 200, { actor: actor.id, name: actor.kind === 'device' ? actor.name : 'admin', assistantName: config.assistantName });
  });

  r.on('POST', '/api/chat', async (ctx) => {
    const actor = requireActor(ctx);
    const body = await ctx.body();
    const channel: Channel = body.channel === 'voice' ? 'voice' : 'text';
    const conversationId = typeof body.conversationId === 'string' && body.conversationId ? body.conversationId.slice(0, 64) : null;
    if (typeof body.message !== 'string') throw new HttpError(400, 'message_required');
    const result = await agent.runTurn({ message: body.message, conversationId, channel, actor: actor.id });
    sendJson(ctx.res, 200, result);
  });

  r.on('GET', '/api/conversations/:id', (ctx) => {
    const actor = requireActor(ctx);
    const id = ctx.params.id!;
    if (!canAccessConversation(actor, id)) throw new HttpError(404, 'not_found');
    sendJson(ctx.res, 200, { id, messages: conversations.history(id, 200), pendingAction: conversations.openPending(id) });
  });

  r.on('DELETE', '/api/conversations/:id', (ctx) => {
    const actor = requireActor(ctx);
    const id = ctx.params.id!;
    if (!canAccessConversation(actor, id)) throw new HttpError(404, 'not_found');
    conversations.delete(id);
    audit.record(actor.id, 'conversation.deleted', { id });
    sendJson(ctx.res, 200, { ok: true });
  });

  r.on('POST', '/api/actions/:id/:decision', async (ctx) => {
    const actor = requireActor(ctx);
    const decision = ctx.params.decision;
    if (decision !== 'confirm' && decision !== 'cancel') throw new HttpError(404, 'not_found');
    const pending = conversations.getPending(ctx.params.id!);
    if (!pending || !canAccessConversation(actor, pending.conversationId)) throw new HttpError(404, 'not_found');
    const open = conversations.openPending(pending.conversationId);
    if (!open || open.id !== pending.id) throw new HttpError(409, 'not_pending', 'That action has already been dealt with or has expired');
    if (decision === 'cancel') {
      agent.cancelAction(open, actor.id, 'cancelled from the screen');
      conversations.append(open.conversationId, 'assistant', `Cancelled: ${open.summary}.`);
      sendJson(ctx.res, 200, { status: 'cancelled' });
      return;
    }
    const outcome = await agent.executeAction(open, actor.id);
    conversations.append(open.conversationId, 'assistant', outcome.ok ? `Done: ${open.summary}.` : `That didn't work: ${outcome.error}`);
    sendJson(ctx.res, 200, { status: outcome.ok ? 'confirmed' : 'failed', result: outcome.result ?? null, error: outcome.error ?? null });
  });

  // --- admin console API ---

  r.on('GET', '/api/admin/me', (ctx) => {
    sendJson(ctx.res, 200, { adminEnabled: auth.adminEnabled, loggedIn: auth.validSession(sessionToken(ctx)), assistantName: config.assistantName });
  });

  r.on('POST', '/api/admin/login', async (ctx) => {
    if (!auth.adminEnabled) throw new HttpError(503, 'admin_disabled', 'Set ADMIN_PASSWORD (10+ characters) to enable the admin console');
    checkOrigin(ctx);
    if (!loginLimiter.attempt(ctx.ip)) throw new HttpError(429, 'too_many_attempts', 'Too many attempts. Wait 15 minutes.');
    const body = await ctx.body();
    if (!auth.checkPassword(str(body.password, 200))) {
      audit.record('anonymous', 'admin.login_failed', { ip: ctx.ip });
      throw new HttpError(401, 'wrong_password');
    }
    loginLimiter.reset(ctx.ip);
    const session = auth.createSession(ctx.ip);
    audit.record('admin', 'admin.login', { ip: ctx.ip });
    const cookie = `${COOKIE}=${session.token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${Math.floor((session.expiresAt - clock()) / 1000)}`;
    sendJson(ctx.res, 200, { ok: true }, { 'Set-Cookie': cookie });
  });

  r.on('POST', '/api/admin/logout', (ctx) => {
    checkOrigin(ctx);
    auth.endSession(sessionToken(ctx));
    sendJson(ctx.res, 200, { ok: true }, { 'Set-Cookie': `${COOKIE}=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0` });
  });

  r.on('GET', '/api/admin/status', (ctx) => {
    requireAdmin(ctx);
    sendJson(ctx.res, 200, {
      assistantName: config.assistantName,
      version: VERSION,
      provider: provider.name,
      model: provider.model,
      modelStatus: app.modelStatus,
      timezone: config.timezone,
      home: config.home ? config.home.name : null,
      monthlyBudgetUsd: config.monthlyBudgetUsd,
      conversationRetentionHours: config.conversationRetentionHours,
      tools: tools.list().map((t) => ({ name: t.name, risk: t.risk, untrusted: t.untrusted })),
    });
  });

  r.on('POST', '/api/admin/pairing-codes', (ctx) => {
    requireAdmin(ctx);
    sendJson(ctx.res, 200, auth.createPairingCode('admin'));
  });

  r.on('GET', '/api/admin/devices', (ctx) => {
    requireAdmin(ctx);
    sendJson(ctx.res, 200, { devices: auth.listDevices() });
  });

  r.on('POST', '/api/admin/devices/:id/revoke', (ctx) => {
    requireAdmin(ctx);
    if (!auth.revokeDevice(ctx.params.id!, 'admin')) throw new HttpError(404, 'not_found');
    sendJson(ctx.res, 200, { ok: true });
  });

  r.on('GET', '/api/admin/audit', (ctx) => {
    requireAdmin(ctx);
    const limit = Math.min(500, Math.max(1, Number(ctx.url.searchParams.get('limit')) || 100));
    const action = ctx.url.searchParams.get('action') ?? undefined;
    sendJson(ctx.res, 200, { entries: audit.recent(limit, action || undefined) });
  });

  r.on('GET', '/api/admin/usage', (ctx) => {
    requireAdmin(ctx);
    sendJson(ctx.res, 200, { monthToDate: usage.monthToDate(), monthlyBudgetUsd: config.monthlyBudgetUsd });
  });

  r.on('GET', '/api/admin/conversations', (ctx) => {
    requireAdmin(ctx);
    sendJson(ctx.res, 200, { conversations: conversations.list(null) });
  });

  // --- server ---

  const staticCache = new Map<string, Buffer>();
  function serveStatic(res: ServerResponse, file: string): void {
    let data = staticCache.get(file);
    if (!data) {
      data = readFileSync(path.join(PUBLIC_DIR, file));
      staticCache.set(file, data);
    }
    res.writeHead(200, {
      ...SECURITY_HEADERS,
      'Content-Type': MIME[path.extname(file)] ?? 'application/octet-stream',
      'Content-Security-Policy': CSP,
      'Cache-Control': 'no-cache',
      'Content-Length': data.length,
    });
    res.end(data);
  }

  async function handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? '/', 'http://localhost');
    const method = req.method ?? 'GET';
    try {
      if (method === 'GET' && STATIC[url.pathname]) return serveStatic(res, STATIC[url.pathname]!);
      const m = r.match(method, url.pathname);
      if (m === null) throw new HttpError(404, 'not_found');
      if (m === 'method_not_allowed') throw new HttpError(405, 'method_not_allowed');
      const ctx: Ctx = { req, res, url, params: m.params, ip: req.socket.remoteAddress ?? 'unknown', body: () => readJson(req) };
      await m.handler(ctx);
    } catch (err) {
      if (res.headersSent) {
        res.end();
        return;
      }
      if (err instanceof AgentError) {
        sendJson(res, err.httpStatus, { error: err.code, reply: err.reply });
      } else if (err instanceof HttpError) {
        sendJson(res, err.status, { error: err.code, message: err.message });
      } else {
        logger.error('unhandled error', { path: url.pathname, error: (err as Error).stack ?? String(err) });
        sendJson(res, 500, { error: 'internal', message: 'Something went wrong on the server' });
      }
    }
  }

  app.server = createServer((req, res) => void handle(req, res));
  app.server.requestTimeout = 120_000;
  return app;
}
