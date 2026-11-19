import type { FunctionCallItem, InputItem, ModelProvider, ModelRequest, ModelResponse, ModelStatus } from './types.ts';

export interface ScriptedStep {
  text?: string;
  toolCalls?: Array<{ name: string; args: unknown; callId?: string }>;
  usage?: { input?: number; output?: number; cached?: number };
  /** Throw this error instead of answering. */
  error?: Error;
}

export type ScriptEntry = ScriptedStep | ((request: ModelRequest) => ScriptedStep);

/**
 * Deterministic stand-in for a real model, used by the test suite.
 * Each call to respond() consumes the next script entry and records the request.
 */
export class ScriptedProvider implements ModelProvider {
  readonly name = 'scripted';
  readonly model = 'scripted-model';
  readonly requests: ModelRequest[] = [];
  private script: ScriptEntry[];
  private counter = 0;

  constructor(script: ScriptEntry[] = []) {
    this.script = [...script];
  }

  push(...entries: ScriptEntry[]): void {
    this.script.push(...entries);
  }

  get remaining(): number {
    return this.script.length;
  }

  async respond(request: ModelRequest): Promise<ModelResponse> {
    // Deep copy so later mutation of the agent's input array doesn't rewrite history.
    this.requests.push(JSON.parse(JSON.stringify(request)) as ModelRequest);
    const entry = this.script.shift();
    if (!entry) throw new Error('ScriptedProvider: script exhausted');
    const step = typeof entry === 'function' ? entry(request) : entry;
    if (step.error) throw step.error;
    const output: InputItem[] = [];
    const toolCalls: FunctionCallItem[] = [];
    if (step.text) output.push({ type: 'message', role: 'assistant', content: step.text });
    for (const call of step.toolCalls ?? []) {
      const item: FunctionCallItem = {
        type: 'function_call',
        call_id: call.callId ?? `call_${++this.counter}`,
        name: call.name,
        arguments: typeof call.args === 'string' ? call.args : JSON.stringify(call.args),
      };
      output.push(item);
      toolCalls.push(item);
    }
    return {
      output,
      text: step.text ?? '',
      toolCalls,
      model: this.model,
      usage: { inputTokens: step.usage?.input ?? 100, cachedInputTokens: step.usage?.cached ?? 0, outputTokens: step.usage?.output ?? 20 },
    };
  }

  async verify(): Promise<{ status: ModelStatus }> {
    return { status: 'ok' };
  }
}
