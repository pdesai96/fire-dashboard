/**
 * Admin commands, run on the Pi:
 *   node src/cli.ts pair            create a 6-digit pairing code (valid 10 minutes)
 *   node src/cli.ts devices         list paired devices
 *   node src/cli.ts revoke <id>     revoke a device's access
 *   node src/cli.ts audit [n]       show the last n audit entries (default 20)
 *   node src/cli.ts check           check the OpenAI key and model
 * In Docker: docker compose exec jarvis node src/cli.ts pair
 */
import path from 'node:path';
import { AuditLog } from './audit.ts';
import { Auth } from './auth.ts';
import { loadConfig, loadEnvFile } from './config.ts';
import { openDb } from './db.ts';
import { OpenAIProvider } from './llm/openai.ts';
import { systemClock } from './util.ts';

loadEnvFile();
const config = loadConfig();
const [command, arg] = process.argv.slice(2);
const db = openDb(path.join(config.dataDir, 'jarvis.db'));
const audit = new AuditLog(db, systemClock);
const auth = new Auth(db, systemClock, audit, config.adminPassword);
const fmt = (ms: number | null) => (ms ? new Date(ms).toLocaleString('en-GB', { timeZone: config.timezone }) : '-');

switch (command) {
  case 'pair': {
    const { code, expiresAt } = auth.createPairingCode('cli');
    console.log(`Pairing code: ${code}  (expires ${fmt(expiresAt)})`);
    console.log('On the tablet: Settings > Connect to ' + config.assistantName + ', enter this server\'s address and the code.');
    break;
  }
  case 'devices':
    for (const d of auth.listDevices()) {
      console.log(`${d.id}  ${d.name.padEnd(24)} paired ${fmt(d.createdAt)}  last seen ${fmt(d.lastSeenAt)}${d.revokedAt ? '  REVOKED' : ''}`);
    }
    break;
  case 'revoke':
    if (!arg) throw new Error('usage: revoke <device-id>');
    console.log(auth.revokeDevice(arg, 'cli') ? 'Revoked.' : 'No active device with that id.');
    break;
  case 'audit':
    for (const e of audit.recent(Number(arg) || 20).reverse()) {
      console.log(`${fmt(e.ts)}  ${e.actor.padEnd(18)} ${e.action.padEnd(22)} ${e.detail ? JSON.stringify(e.detail) : ''}`);
    }
    break;
  case 'check': {
    const result = await new OpenAIProvider(config.openai).verify();
    console.log(`Model ${config.openai.model}: ${result.status}${result.detail ? ' - ' + result.detail : ''}`);
    break;
  }
  default:
    console.log('Commands: pair | devices | revoke <id> | audit [n] | check');
}
db.close();
