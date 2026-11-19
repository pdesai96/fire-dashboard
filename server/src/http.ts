import type { IncomingMessage, ServerResponse } from 'node:http';

export class HttpError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string, message?: string) {
    super(message ?? code);
    this.status = status;
    this.code = code;
  }
}

export interface Ctx {
  req: IncomingMessage;
  res: ServerResponse;
  url: URL;
  params: Record<string, string>;
  ip: string;
  body: () => Promise<Record<string, unknown>>;
}

export type Handler = (ctx: Ctx) => Promise<void> | void;

interface Route {
  method: string;
  parts: string[];
  handler: Handler;
}

const MAX_BODY = 64 * 1024;

export const SECURITY_HEADERS: Record<string, string> = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
};

export function sendJson(res: ServerResponse, status: number, body: unknown, extraHeaders: Record<string, string | string[]> = {}): void {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    ...SECURITY_HEADERS,
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(data),
    ...extraHeaders,
  });
  res.end(data);
}

export async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  const type = (req.headers['content-type'] ?? '').split(';')[0]!.trim().toLowerCase();
  // Requiring JSON forces a CORS preflight for any cross-site request, which we never approve.
  if (type !== 'application/json') throw new HttpError(415, 'json_required', 'Content-Type must be application/json');
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    size += (chunk as Buffer).length;
    if (size > MAX_BODY) throw new HttpError(413, 'body_too_large');
    chunks.push(chunk as Buffer);
  }
  if (size === 0) return {};
  let value: unknown;
  try {
    value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new HttpError(400, 'invalid_json');
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new HttpError(400, 'json_object_required');
  return value as Record<string, unknown>;
}

export function parseCookies(header: string | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of (header ?? '').split(';')) {
    const i = part.indexOf('=');
    if (i <= 0) continue;
    const k = part.slice(0, i).trim();
    const v = part.slice(i + 1).trim();
    try {
      out[k] = decodeURIComponent(v);
    } catch {
      out[k] = v;
    }
  }
  return out;
}

export class Router {
  private routes: Route[] = [];

  on(method: string, path: string, handler: Handler): this {
    this.routes.push({ method, parts: path.split('/').filter(Boolean), handler });
    return this;
  }

  match(method: string, pathname: string): { handler: Handler; params: Record<string, string> } | 'method_not_allowed' | null {
    const segs = pathname.split('/').filter(Boolean);
    let pathMatched = false;
    for (const r of this.routes) {
      if (r.parts.length !== segs.length) continue;
      const params: Record<string, string> = {};
      let ok = true;
      for (let i = 0; i < segs.length; i++) {
        const p = r.parts[i]!;
        if (p.startsWith(':')) {
          try {
            params[p.slice(1)] = decodeURIComponent(segs[i]!);
          } catch {
            ok = false;
            break;
          }
        } else if (p !== segs[i]) {
          ok = false;
          break;
        }
      }
      if (!ok) continue;
      pathMatched = true;
      if (r.method === method) return { handler: r.handler, params };
    }
    return pathMatched ? 'method_not_allowed' : null;
  }
}
