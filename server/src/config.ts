import { existsSync } from 'node:fs';
import path from 'node:path';

export interface Config {
  port: number;
  host: string;
  dataDir: string;
  assistantName: string;
  userName: string;
  timezone: string;
  home: { name: string; lat: number; lon: number } | null;
  adminPassword: string;
  openai: {
    apiKey: string;
    baseUrl: string;
    model: string;
    reasoningEffort: string;
    includeReasoning: boolean;
    maxOutputTokens: number;
    timeoutMs: number;
    pricePerMillion: { input: number; cachedInput: number; output: number };
  };
  monthlyBudgetUsd: number;
  historyMessages: number;
  conversationRetentionHours: number;
  maxAgentSteps: number;
  pendingActionMinutes: number;
}

type Env = Record<string, string | undefined>;

function num(env: Env, key: string, fallback: number, min = -Infinity, max = Infinity): number {
  const raw = env[key];
  if (raw === undefined || raw.trim() === '') return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value) || value < min || value > max) {
    throw new Error(`${key} must be a number between ${min} and ${max} (got "${raw}")`);
  }
  return value;
}

function str(env: Env, key: string, fallback = ''): string {
  const raw = env[key];
  return raw === undefined ? fallback : raw.trim();
}

function bool(env: Env, key: string, fallback: boolean): boolean {
  const raw = str(env, key).toLowerCase();
  if (raw === '') return fallback;
  return raw === '1' || raw === 'true' || raw === 'yes' || raw === 'on';
}

export function validTimezone(tz: string): boolean {
  try {
    new Intl.DateTimeFormat('en-GB', { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

/** Builds the config from environment variables. Throws on invalid values. */
export function loadConfig(env: Env = process.env): Config {
  const timezone = str(env, 'TIMEZONE', 'Europe/London');
  if (!validTimezone(timezone)) throw new Error(`TIMEZONE "${timezone}" is not a valid IANA time zone`);

  const lat = str(env, 'HOME_LAT');
  const lon = str(env, 'HOME_LON');
  let home: Config['home'] = null;
  if (lat !== '' && lon !== '') {
    home = {
      name: str(env, 'HOME_NAME', 'Home'),
      lat: num(env, 'HOME_LAT', 0, -90, 90),
      lon: num(env, 'HOME_LON', 0, -180, 180),
    };
  }

  const assistantName = str(env, 'ASSISTANT_NAME', 'JARVIS') || 'JARVIS';
  if (assistantName.length > 40) throw new Error('ASSISTANT_NAME must be 40 characters or fewer');

  return {
    port: num(env, 'PORT', 8787, 0, 65535),
    host: str(env, 'HOST', '0.0.0.0'),
    dataDir: path.resolve(str(env, 'DATA_DIR', './data')),
    assistantName,
    userName: str(env, 'USER_NAME'),
    timezone,
    home,
    adminPassword: str(env, 'ADMIN_PASSWORD'),
    openai: {
      apiKey: str(env, 'OPENAI_API_KEY'),
      baseUrl: str(env, 'OPENAI_BASE_URL', 'https://api.openai.com/v1').replace(/\/+$/, ''),
      model: str(env, 'OPENAI_MODEL', 'gpt-6-luna'),
      reasoningEffort: str(env, 'OPENAI_REASONING_EFFORT'),
      includeReasoning: bool(env, 'OPENAI_INCLUDE_REASONING', true),
      maxOutputTokens: num(env, 'OPENAI_MAX_OUTPUT_TOKENS', 1200, 64, 32000),
      timeoutMs: num(env, 'OPENAI_TIMEOUT_MS', 30000, 1000, 120000),
      pricePerMillion: {
        input: num(env, 'OPENAI_PRICE_INPUT_PER_M', 0.1, 0),
        cachedInput: num(env, 'OPENAI_PRICE_CACHED_INPUT_PER_M', 0.01, 0),
        output: num(env, 'OPENAI_PRICE_OUTPUT_PER_M', 0.5, 0),
      },
    },
    monthlyBudgetUsd: num(env, 'MONTHLY_BUDGET_USD', 5, 0),
    historyMessages: num(env, 'HISTORY_MESSAGES', 20, 0, 200),
    conversationRetentionHours: num(env, 'CONVERSATION_RETENTION_HOURS', 24, 1, 24 * 90),
    maxAgentSteps: num(env, 'MAX_AGENT_STEPS', 6, 1, 20),
    pendingActionMinutes: num(env, 'PENDING_ACTION_MINUTES', 5, 1, 60),
  };
}

/** Loads a .env file into process.env if present (existing variables win). */
export function loadEnvFile(file = '.env'): void {
  if (existsSync(file)) process.loadEnvFile(file);
}
