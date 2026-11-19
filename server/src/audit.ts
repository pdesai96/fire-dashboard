import type { DB } from './db.ts';
import type { Clock } from './util.ts';

export interface AuditEntry {
  id: number;
  ts: number;
  actor: string;
  action: string;
  detail: unknown;
  conversationId: string | null;
}

/** Append-only record of everything the assistant and its administrators do. */
export class AuditLog {
  private db: DB;
  private clock: Clock;
  constructor(db: DB, clock: Clock) {
    this.db = db;
    this.clock = clock;
  }

  record(actor: string, action: string, detail?: unknown, conversationId?: string | null): void {
    this.db
      .prepare('INSERT INTO audit_log (ts, actor, action, detail, conversation_id) VALUES (?, ?, ?, ?, ?)')
      .run(this.clock(), actor, action, detail === undefined ? null : JSON.stringify(detail), conversationId ?? null);
  }

  recent(limit = 100, action?: string): AuditEntry[] {
    const rows = (
      action
        ? this.db.prepare('SELECT * FROM audit_log WHERE action LIKE ? ORDER BY id DESC LIMIT ?').all(action + '%', limit)
        : this.db.prepare('SELECT * FROM audit_log ORDER BY id DESC LIMIT ?').all(limit)
    ) as Array<{ id: number; ts: number; actor: string; action: string; detail: string | null; conversation_id: string | null }>;
    return rows.map((r) => ({
      id: r.id,
      ts: r.ts,
      actor: r.actor,
      action: r.action,
      detail: r.detail === null ? null : JSON.parse(r.detail),
      conversationId: r.conversation_id,
    }));
  }
}
