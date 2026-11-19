import { createHash, randomBytes, randomInt } from 'node:crypto';

export type Clock = () => number;
export const systemClock: Clock = () => Date.now();

export function sha256(value: string): string {
  return createHash('sha256').update(value).digest('hex');
}

export function randomToken(prefix: string, bytes = 32): string {
  return prefix + randomBytes(bytes).toString('base64url');
}

export function randomId(prefix: string): string {
  return prefix + randomBytes(9).toString('base64url');
}

export function sixDigitCode(): string {
  return String(randomInt(0, 1_000_000)).padStart(6, '0');
}

export function truncate(text: string, max: number): string {
  return text.length <= max ? text : text.slice(0, max - 1) + '…';
}

export interface Logger {
  info(msg: string, extra?: Record<string, unknown>): void;
  warn(msg: string, extra?: Record<string, unknown>): void;
  error(msg: string, extra?: Record<string, unknown>): void;
}

function line(level: string, msg: string, extra?: Record<string, unknown>): string {
  return JSON.stringify({ t: new Date().toISOString(), level, msg, ...extra });
}

export const consoleLogger: Logger = {
  info: (m, e) => console.log(line('info', m, e)),
  warn: (m, e) => console.warn(line('warn', m, e)),
  error: (m, e) => console.error(line('error', m, e)),
};

export const silentLogger: Logger = { info() {}, warn() {}, error() {} };
