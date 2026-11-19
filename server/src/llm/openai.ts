import type { Config } from '../config.ts';
import {
  ModelError,
  type FunctionCallItem,
  type InputItem,
  type ModelProvider,
  type ModelRequest,
  type ModelResponse,
  type ModelStatus,
} from './types.ts';

type Fetch = typeof fetch;

interface ResponsesOutputItem {
  type: string;
  [key: string]: unknown;
}

interface ResponsesBody {
  model?: string;
  status?: string;
  output?: ResponsesOutputItem[];
  usage?: {
    input_tokens?: number;
    output_tokens?: number;
    input_tokens_details?: { cached_tokens?: number };
  };
  error?: { message?: string; code?: string } | null;
}

function toApiInput(item: InputItem): unknown {
  switch (item.type) {
    case 'message':
      return { role: item.role, content: item.content };
    case 'function_call':
      return { type: 'function_call', call_id: item.call_id, name: item.name, arguments: item.arguments, ...(item.id ? { id: item.id } : {}) };
    case 'function_call_output':
      return { type: 'function_call_output', call_id: item.call_id, output: item.output };
    case 'opaque':
      return item.raw;
  }
}

/** Extracts assistant text and tool calls from a Responses API body. Exported for tests. */
export function parseResponsesBody(body: ResponsesBody, fallbackModel: string): ModelResponse {
  const output: InputItem[] = [];
  const toolCalls: FunctionCallItem[] = [];
  const text: string[] = [];
  for (const item of body.output ?? []) {
    // Pass every item back verbatim on the next step (reasoning items need this).
    output.push({ type: 'opaque', raw: item });
    if (item.type === 'function_call') {
      toolCalls.push({
        type: 'function_call',
        call_id: String(item.call_id ?? ''),
        name: String(item.name ?? ''),
        arguments: typeof item.arguments === 'string' ? item.arguments : JSON.stringify(item.arguments ?? {}),
      });
    } else if (item.type === 'message' && Array.isArray(item.content)) {
      for (const part of item.content as Array<{ type?: string; text?: string; refusal?: string }>) {
        if (part.type === 'output_text' && typeof part.text === 'string') text.push(part.text);
        else if (part.type === 'refusal' && typeof part.refusal === 'string') text.push(part.refusal);
      }
    }
  }
  const usage = body.usage ?? {};
  return {
    output,
    text: text.join('\n').trim(),
    toolCalls,
    model: body.model ?? fallbackModel,
    usage: {
      inputTokens: usage.input_tokens ?? 0,
      cachedInputTokens: usage.input_tokens_details?.cached_tokens ?? 0,
      outputTokens: usage.output_tokens ?? 0,
    },
  };
}

function mapHttpError(status: number, body: ResponsesBody | null): ModelError {
  const message = body?.error?.message ?? `OpenAI returned HTTP ${status}`;
  const code = body?.error?.code;
  if (status === 401 || status === 403) return new ModelError('auth', message, status);
  if (status === 429) return new ModelError(code === 'insufficient_quota' ? 'insufficient_quota' : 'rate_limited', message, status);
  if (status >= 500) return new ModelError('unavailable', message, status);
  return new ModelError('bad_request', message, status);
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** OpenAI Responses API provider (server-side only; the key never leaves the Pi). */
export class OpenAIProvider implements ModelProvider {
  readonly name = 'openai';
  readonly model: string;
  private cfg: Config['openai'];
  private fetchImpl: Fetch;

  constructor(cfg: Config['openai'], fetchImpl: Fetch = fetch) {
    this.cfg = cfg;
    this.model = cfg.model;
    this.fetchImpl = fetchImpl;
  }

  private headers(): Record<string, string> {
    return { Authorization: `Bearer ${this.cfg.apiKey}`, 'Content-Type': 'application/json' };
  }

  async respond(request: ModelRequest, signal?: AbortSignal): Promise<ModelResponse> {
    if (!this.cfg.apiKey) throw new ModelError('no_key', 'OPENAI_API_KEY is not set on the server');
    const body: Record<string, unknown> = {
      model: this.cfg.model,
      instructions: request.instructions,
      input: request.input.map(toApiInput),
      store: false,
      max_output_tokens: this.cfg.maxOutputTokens,
    };
    if (request.tools.length > 0) {
      body.tools = request.tools.map((t) => ({ type: 'function', name: t.name, description: t.description, parameters: t.parameters, strict: false }));
    }
    if (this.cfg.includeReasoning) body.include = ['reasoning.encrypted_content'];
    if (this.cfg.reasoningEffort) body.reasoning = { effort: this.cfg.reasoningEffort };

    let lastError: ModelError | null = null;
    for (let attempt = 0; attempt < 2; attempt++) {
      if (attempt > 0) await sleep(800);
      const timeout = AbortSignal.timeout(this.cfg.timeoutMs);
      const combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
      let res: Response;
      try {
        res = await this.fetchImpl(`${this.cfg.baseUrl}/responses`, {
          method: 'POST',
          headers: this.headers(),
          body: JSON.stringify(body),
          signal: combined,
        });
      } catch (err) {
        if (timeout.aborted) throw new ModelError('timeout', `OpenAI did not answer within ${this.cfg.timeoutMs} ms`);
        if (signal?.aborted) throw err;
        lastError = new ModelError('unavailable', `Could not reach OpenAI: ${(err as Error).message}`);
        continue;
      }
      let json: ResponsesBody | null = null;
      try {
        json = (await res.json()) as ResponsesBody;
      } catch {
        json = null;
      }
      if (!res.ok) {
        lastError = mapHttpError(res.status, json);
        if (lastError.code === 'rate_limited' || lastError.code === 'unavailable') continue;
        throw lastError;
      }
      if (!json) throw new ModelError('unavailable', 'OpenAI returned an unreadable response');
      if (json.status === 'failed') throw new ModelError('unavailable', json.error?.message ?? 'The model run failed');
      return parseResponsesBody(json, this.cfg.model);
    }
    throw lastError ?? new ModelError('unavailable', 'OpenAI request failed');
  }

  async verify(): Promise<{ status: ModelStatus; detail?: string }> {
    if (!this.cfg.apiKey) return { status: 'no_key', detail: 'OPENAI_API_KEY is not set' };
    try {
      const res = await this.fetchImpl(`${this.cfg.baseUrl}/models/${encodeURIComponent(this.cfg.model)}`, {
        headers: this.headers(),
        signal: AbortSignal.timeout(10000),
      });
      if (res.ok) return { status: 'ok' };
      if (res.status === 404) return { status: 'unavailable', detail: `Model "${this.cfg.model}" is not available to this API key. Set OPENAI_MODEL.` };
      if (res.status === 401 || res.status === 403) return { status: 'unavailable', detail: 'OpenAI rejected the API key' };
      return { status: 'unverified', detail: `Model check returned HTTP ${res.status}` };
    } catch (err) {
      return { status: 'unverified', detail: `Could not reach OpenAI: ${(err as Error).message}` };
    }
  }
}
