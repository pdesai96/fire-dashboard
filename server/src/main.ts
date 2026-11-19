import path from 'node:path';
import { createApp, VERSION } from './app.ts';
import { loadConfig, loadEnvFile } from './config.ts';
import { openDb } from './db.ts';
import { OpenAIProvider } from './llm/openai.ts';
import { consoleLogger } from './util.ts';

loadEnvFile();
const config = loadConfig();
const db = openDb(path.join(config.dataDir, 'jarvis.db'));
const provider = new OpenAIProvider(config.openai);
const app = createApp({ config, db, provider });

const purge = () => {
  const n = app.conversations.purge(config.conversationRetentionHours);
  if (n > 0) consoleLogger.info('purged expired conversations', { count: n });
};
purge();
setInterval(purge, 3600_000).unref();

app.server.listen(config.port, config.host, () => {
  consoleLogger.info(`${config.assistantName} core ${VERSION} listening`, { host: config.host, port: config.port, model: config.openai.model });
  void app.verifyModel();
});

function shutdown(signal: string): void {
  consoleLogger.info('shutting down', { signal });
  app.server.close(() => {
    db.close();
    process.exit(0);
  });
  setTimeout(() => process.exit(0), 5000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
