import type { Config } from './config.ts';
import type { DB } from './db.ts';
import type { Usage } from './llm/types.ts';
import type { Clock } from './util.ts';

/** Tracks model token usage and enforces the monthly spending cap. */
export class UsageMeter {
  private db: DB;
  private config: Config;
  private clock: Clock;
  constructor(db: DB, config: Config, clock: Clock) {
    this.db = db;
    this.config = config;
    this.clock = clock;
  }

  cost(usage: Usage): number {
    const p = this.config.openai.pricePerMillion;
    const cached = Math.min(usage.cachedInputTokens, usage.inputTokens);
    return ((usage.inputTokens - cached) * p.input + cached * p.cachedInput + usage.outputTokens * p.output) / 1_000_000;
  }

  record(model: string, usage: Usage, conversationId: string | null): number {
    const cost = this.cost(usage);
    this.db
      .prepare(
        'INSERT INTO usage (ts, model, input_tokens, cached_tokens, output_tokens, cost_usd, conversation_id) VALUES (?, ?, ?, ?, ?, ?, ?)',
      )
      .run(this.clock(), model, usage.inputTokens, usage.cachedInputTokens, usage.outputTokens, cost, conversationId);
    return cost;
  }

  /** Start of the current calendar month (UTC) in epoch ms. */
  monthStart(): number {
    const d = new Date(this.clock());
    return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1);
  }

  monthToDate(): { costUsd: number; inputTokens: number; outputTokens: number; calls: number } {
    const row = this.db
      .prepare(
        'SELECT COALESCE(SUM(cost_usd),0) AS cost, COALESCE(SUM(input_tokens),0) AS inp, COALESCE(SUM(output_tokens),0) AS outp, COUNT(*) AS calls FROM usage WHERE ts >= ?',
      )
      .get(this.monthStart()) as { cost: number; inp: number; outp: number; calls: number };
    return { costUsd: row.cost, inputTokens: row.inp, outputTokens: row.outp, calls: row.calls };
  }

  /** True when the configured monthly budget has been used up. A budget of 0 disables the cap. */
  overBudget(): boolean {
    const budget = this.config.monthlyBudgetUsd;
    return budget > 0 && this.monthToDate().costUsd >= budget;
  }
}
