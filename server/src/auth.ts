import { scryptSync, randomBytes, timingSafeEqual } from 'node:crypto';
import type { AuditLog } from './audit.ts';
import type { DB } from './db.ts';
import { randomId, randomToken, sha256, sixDigitCode, type Clock } from './util.ts';

const SESSION_HOURS = 12;
const PAIRING_MINUTES = 10;
export const MIN_ADMIN_PASSWORD = 10;

export interface Device {
  id: string;
  name: string;
  createdAt: number;
  lastSeenAt: number | null;
  revokedAt: number | null;
}

/** Fixed-window attempt limiter (per key, e.g. client IP) for login and pairing. */
export class RateLimiter {
  private hits = new Map<string, { count: number; resetAt: number }>();
  private limit: number;
  private windowMs: number;
  private clock: Clock;
  constructor(limit: number, windowMs: number, clock: Clock) {
    this.limit = limit;
    this.windowMs = windowMs;
    this.clock = clock;
  }
  /** Records an attempt; returns false when the key is over its limit. */
  attempt(key: string): boolean {
    const now = this.clock();
    let entry = this.hits.get(key);
    if (!entry || entry.resetAt <= now) {
      entry = { count: 0, resetAt: now + this.windowMs };
      this.hits.set(key, entry);
    }
    entry.count++;
    if (this.hits.size > 10000) for (const [k, v] of this.hits) if (v.resetAt <= now) this.hits.delete(k);
    return entry.count <= this.limit;
  }
  reset(key: string): void {
    this.hits.delete(key);
  }
}

export class Auth {
  private db: DB;
  private clock: Clock;
  private audit: AuditLog;
  private passwordHash: Buffer | null;
  private salt: Buffer;

  constructor(db: DB, clock: Clock, audit: AuditLog, adminPassword: string) {
    this.db = db;
    this.clock = clock;
    this.audit = audit;
    this.salt = randomBytes(16);
    this.passwordHash = adminPassword.length >= MIN_ADMIN_PASSWORD ? scryptSync(adminPassword, this.salt, 32) : null;
  }

  get adminEnabled(): boolean {
    return this.passwordHash !== null;
  }

  checkPassword(candidate: string): boolean {
    if (!this.passwordHash) return false;
    const h = scryptSync(candidate, this.salt, 32);
    return timingSafeEqual(h, this.passwordHash);
  }

  // --- admin sessions (HttpOnly cookie) ---

  createSession(ip: string): { token: string; expiresAt: number } {
    const token = randomToken('s_');
    const now = this.clock();
    const expiresAt = now + SESSION_HOURS * 3600_000;
    this.db.prepare('DELETE FROM sessions WHERE expires_at < ?').run(now);
    this.db.prepare('INSERT INTO sessions (token_hash, created_at, expires_at, ip) VALUES (?, ?, ?, ?)').run(sha256(token), now, expiresAt, ip);
    return { token, expiresAt };
  }

  validSession(token: string | undefined): boolean {
    if (!token || !this.adminEnabled) return false;
    const row = this.db.prepare('SELECT expires_at FROM sessions WHERE token_hash = ?').get(sha256(token)) as { expires_at: number } | undefined;
    return !!row && row.expires_at > this.clock();
  }

  endSession(token: string | undefined): void {
    if (token) this.db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(sha256(token));
  }

  // --- device pairing ---

  createPairingCode(actor: string): { code: string; expiresAt: number } {
    const now = this.clock();
    this.db.prepare('DELETE FROM pairing_codes WHERE expires_at < ? OR used_at IS NOT NULL').run(now);
    let code = sixDigitCode();
    while (this.db.prepare('SELECT 1 FROM pairing_codes WHERE code_hash = ?').get(sha256(code))) code = sixDigitCode();
    const expiresAt = now + PAIRING_MINUTES * 60_000;
    this.db.prepare('INSERT INTO pairing_codes (code_hash, created_at, expires_at) VALUES (?, ?, ?)').run(sha256(code), now, expiresAt);
    this.audit.record(actor, 'pairing.code_created', { expiresAt });
    return { code, expiresAt };
  }

  /** Exchanges a valid, unused pairing code for a device token. The raw token is returned once and stored hashed. */
  pair(code: string, name: string): { deviceId: string; token: string } | null {
    const now = this.clock();
    const codeHash = sha256(code.trim());
    const row = this.db.prepare('SELECT expires_at, used_at FROM pairing_codes WHERE code_hash = ?').get(codeHash) as
      | { expires_at: number; used_at: number | null }
      | undefined;
    if (!row || row.used_at !== null || row.expires_at <= now) return null;
    const deviceId = randomId('d_');
    const token = randomToken('jd_');
    const cleanName = name.replace(/[^\p{L}\p{N} ._()'-]/gu, '').trim().slice(0, 60) || 'Device';
    this.db.exec('BEGIN');
    try {
      const used = this.db.prepare('UPDATE pairing_codes SET used_at = ?, device_id = ? WHERE code_hash = ? AND used_at IS NULL').run(now, deviceId, codeHash);
      if (Number(used.changes) !== 1) {
        this.db.exec('ROLLBACK');
        return null;
      }
      this.db.prepare('INSERT INTO devices (id, name, token_hash, created_at) VALUES (?, ?, ?, ?)').run(deviceId, cleanName, sha256(token), now);
      this.db.exec('COMMIT');
    } catch (err) {
      this.db.exec('ROLLBACK');
      throw err;
    }
    this.audit.record(`device:${deviceId}`, 'device.paired', { name: cleanName });
    return { deviceId, token };
  }

  /** Returns the device for a bearer token, or null if unknown or revoked. */
  deviceForToken(token: string): Device | null {
    const row = this.db.prepare('SELECT * FROM devices WHERE token_hash = ?').get(sha256(token)) as
      | { id: string; name: string; created_at: number; last_seen_at: number | null; revoked_at: number | null }
      | undefined;
    if (!row || row.revoked_at !== null) return null;
    const now = this.clock();
    if (!row.last_seen_at || now - row.last_seen_at > 60_000) this.db.prepare('UPDATE devices SET last_seen_at = ? WHERE id = ?').run(now, row.id);
    return { id: row.id, name: row.name, createdAt: row.created_at, lastSeenAt: now, revokedAt: null };
  }

  listDevices(): Device[] {
    const rows = this.db.prepare('SELECT * FROM devices ORDER BY created_at DESC').all() as Array<{
      id: string;
      name: string;
      created_at: number;
      last_seen_at: number | null;
      revoked_at: number | null;
    }>;
    return rows.map((r) => ({ id: r.id, name: r.name, createdAt: r.created_at, lastSeenAt: r.last_seen_at, revokedAt: r.revoked_at }));
  }

  revokeDevice(id: string, actor: string): boolean {
    const ok = Number(this.db.prepare('UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL').run(this.clock(), id).changes) > 0;
    if (ok) this.audit.record(actor, 'device.revoked', { id });
    return ok;
  }
}
