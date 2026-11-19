import type { DB } from './db.ts';
import { randomId, type Clock } from './util.ts';

export interface StoredMessage {
  role: 'user' | 'assistant';
  content: string;
  createdAt: number;
}

export interface PendingAction {
  id: string;
  conversationId: string;
  tool: string;
  args: Record<string, unknown>;
  summary: string;
  status: 'pending' | 'confirmed' | 'cancelled' | 'expired' | 'failed';
  createdAt: number;
  expiresAt: number;
}

interface PendingRow {
  id: string;
  conversation_id: string;
  tool: string;
  args: string;
  summary: string;
  status: PendingAction['status'];
  created_at: number;
  expires_at: number;
}

const toPending = (r: PendingRow): PendingAction => ({
  id: r.id,
  conversationId: r.conversation_id,
  tool: r.tool,
  args: JSON.parse(r.args) as Record<string, unknown>,
  summary: r.summary,
  status: r.status,
  createdAt: r.created_at,
  expiresAt: r.expires_at,
});

/**
 * Short-term conversation history, used only for follow-up questions.
 * It is purged automatically after CONVERSATION_RETENTION_HOURS. Long-term memory is a
 * separate, explicit feature (Stage 5) and nothing here is promoted to it automatically.
 */
export class ConversationStore {
  private db: DB;
  private clock: Clock;
  constructor(db: DB, clock: Clock) {
    this.db = db;
    this.clock = clock;
  }

  create(owner: string, channel: string): string {
    const id = randomId('c_');
    const now = this.clock();
    this.db.prepare('INSERT INTO conversations (id, owner, channel, created_at, updated_at) VALUES (?, ?, ?, ?, ?)').run(id, owner, channel, now, now);
    return id;
  }

  owner(id: string): string | null {
    const row = this.db.prepare('SELECT owner FROM conversations WHERE id = ?').get(id) as { owner: string } | undefined;
    return row?.owner ?? null;
  }

  append(conversationId: string, role: 'user' | 'assistant', content: string): void {
    const now = this.clock();
    this.db.prepare('INSERT INTO messages (conversation_id, role, content, created_at) VALUES (?, ?, ?, ?)').run(conversationId, role, content, now);
    this.db.prepare('UPDATE conversations SET updated_at = ? WHERE id = ?').run(now, conversationId);
  }

  /** The most recent `limit` messages, oldest first. */
  history(conversationId: string, limit: number): StoredMessage[] {
    if (limit <= 0) return [];
    const rows = this.db
      .prepare('SELECT role, content, created_at FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?')
      .all(conversationId, limit) as Array<{ role: 'user' | 'assistant'; content: string; created_at: number }>;
    return rows.reverse().map((r) => ({ role: r.role, content: r.content, createdAt: r.created_at }));
  }

  list(owner: string | null, limit = 50): Array<{ id: string; owner: string; channel: string; updatedAt: number; messages: number }> {
    const sql = `SELECT c.id, c.owner, c.channel, c.updated_at, (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) AS n
                 FROM conversations c ${owner ? 'WHERE c.owner = ?' : ''} ORDER BY c.updated_at DESC LIMIT ?`;
    const rows = (owner ? this.db.prepare(sql).all(owner, limit) : this.db.prepare(sql).all(limit)) as Array<{
      id: string;
      owner: string;
      channel: string;
      updated_at: number;
      n: number;
    }>;
    return rows.map((r) => ({ id: r.id, owner: r.owner, channel: r.channel, updatedAt: r.updated_at, messages: r.n }));
  }

  delete(id: string): boolean {
    return Number(this.db.prepare('DELETE FROM conversations WHERE id = ?').run(id).changes) > 0;
  }

  /** Deletes conversations idle for longer than the retention window. Returns how many were removed. */
  purge(retentionHours: number): number {
    const cutoff = this.clock() - retentionHours * 3600_000;
    return Number(this.db.prepare('DELETE FROM conversations WHERE updated_at < ?').run(cutoff).changes);
  }

  // --- pending actions (consequential tool calls awaiting the user's yes/no) ---

  createPending(conversationId: string, tool: string, args: Record<string, unknown>, summary: string, ttlMinutes: number): PendingAction {
    const now = this.clock();
    // Only one pending action per conversation: a new one supersedes the old.
    this.db.prepare("UPDATE pending_actions SET status = 'cancelled', resolved_at = ? WHERE conversation_id = ? AND status = 'pending'").run(now, conversationId);
    const id = randomId('a_');
    const expiresAt = now + ttlMinutes * 60_000;
    this.db
      .prepare("INSERT INTO pending_actions (id, conversation_id, tool, args, summary, status, created_at, expires_at) VALUES (?, ?, ?, ?, ?, 'pending', ?, ?)")
      .run(id, conversationId, tool, JSON.stringify(args), summary, now, expiresAt);
    return { id, conversationId, tool, args, summary, status: 'pending', createdAt: now, expiresAt };
  }

  getPending(id: string): PendingAction | null {
    const row = this.db.prepare('SELECT * FROM pending_actions WHERE id = ?').get(id) as PendingRow | undefined;
    return row ? toPending(row) : null;
  }

  /** The open pending action for a conversation, expiring it if it has timed out. */
  openPending(conversationId: string): PendingAction | null {
    const row = this.db
      .prepare("SELECT * FROM pending_actions WHERE conversation_id = ? AND status = 'pending' ORDER BY created_at DESC LIMIT 1")
      .get(conversationId) as PendingRow | undefined;
    if (!row) return null;
    if (row.expires_at <= this.clock()) {
      this.resolvePending(row.id, 'expired');
      return null;
    }
    return toPending(row);
  }

  resolvePending(id: string, status: Exclude<PendingAction['status'], 'pending'>, result?: unknown): boolean {
    const changes = this.db
      .prepare("UPDATE pending_actions SET status = ?, resolved_at = ?, result = ? WHERE id = ? AND status = 'pending'")
      .run(status, this.clock(), result === undefined ? null : JSON.stringify(result), id).changes;
    return Number(changes) > 0;
  }
}

const YES = /^(yes|yeah|yep|yup|sure|ok|okay|confirm(ed)?|go ahead|do it|please do|affirmative|correct|that's right)\b[\s.!,]*(please|thanks|thank you)?[\s.!]*$/i;
const NO = /^(no|nope|nah|cancel|stop|don't|do not|never ?mind|negative|abort|leave it)\b.*$/i;

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Deterministic check of the user's own words. Confirmation is decided here, in code,
 * never by the model, so injected content can't approve an action.
 * Anything other than a plain yes or no returns null (and the action is not run).
 */
export function classifyConfirmation(text: string, assistantName = 'JARVIS'): 'yes' | 'no' | null {
  const name = escapeRe(assistantName);
  const t = text
    .trim()
    .replace(new RegExp(`^(hey\\s+)?${name}[,\\s]+`, 'i'), '')
    .replace(new RegExp(`[,\\s]+${name}[.!\\s]*$`, 'i'), '');
  if (YES.test(t)) return 'yes';
  if (NO.test(t)) return 'no';
  return null;
}
