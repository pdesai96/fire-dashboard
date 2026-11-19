/**
 * Provider-neutral model interface. Item shapes follow the OpenAI Responses API
 * because that is the production provider; other providers translate to and from them.
 */

export interface MessageItem {
  type: 'message';
  role: 'user' | 'assistant' | 'developer';
  content: string;
}

export interface FunctionCallItem {
  type: 'function_call';
  call_id: string;
  name: string;
  /** JSON-encoded arguments, exactly as produced by the model. */
  arguments: string;
  id?: string;
}

export interface FunctionCallOutputItem {
  type: 'function_call_output';
  call_id: string;
  output: string;
}

/** Provider-specific output (e.g. reasoning) that must be passed back verbatim within a turn. */
export interface OpaqueItem {
  type: 'opaque';
  raw: Record<string, unknown>;
}

export type InputItem = MessageItem | FunctionCallItem | FunctionCallOutputItem | OpaqueItem;

export interface ToolSpec {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
}

export interface Usage {
  inputTokens: number;
  cachedInputTokens: number;
  outputTokens: number;
}

export interface ModelRequest {
  instructions: string;
  input: InputItem[];
  tools: ToolSpec[];
}

export interface ModelResponse {
  /** Every output item, in order, to append to the input for the next step. */
  output: InputItem[];
  text: string;
  toolCalls: FunctionCallItem[];
  usage: Usage;
  model: string;
}

export type ModelStatus = 'ok' | 'unverified' | 'unavailable' | 'no_key';

export interface ModelProvider {
  readonly name: string;
  readonly model: string;
  respond(request: ModelRequest, signal?: AbortSignal): Promise<ModelResponse>;
  /** Checks that the configured model exists and the key works. */
  verify(): Promise<{ status: ModelStatus; detail?: string }>;
}

export class ModelError extends Error {
  readonly code: 'no_key' | 'auth' | 'rate_limited' | 'bad_request' | 'unavailable' | 'timeout' | 'insufficient_quota';
  readonly status: number | undefined;
  constructor(code: ModelError['code'], message: string, status?: number) {
    super(message);
    this.name = 'ModelError';
    this.code = code;
    this.status = status;
  }
}
