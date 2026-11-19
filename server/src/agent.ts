import type { AuditLog } from './audit.ts';
import type { Config } from './config.ts';
import { classifyConfirmation, type ConversationStore, type PendingAction } from './conversations.ts';
import { ModelError, type InputItem, type ModelProvider } from './llm/types.ts';
import { buildInstructions, type Channel } from './persona.ts';
import { ToolInputError, validateArgs, wrapUntrusted, type ToolContext, type ToolRegistry } from './tools/registry.ts';
import type { UsageMeter } from './usage.ts';
import { truncate, type Clock, type Logger } from './util.ts';

export interface AgentDeps {
  config: Config;
  provider: ModelProvider;
  tools: ToolRegistry;
  conversations: ConversationStore;
  audit: AuditLog;
  usage: UsageMeter;
  clock: Clock;
  fetch: typeof fetch;
  logger: Logger;
}

export interface TurnInput {
  message: string;
  conversationId?: string | null;
  channel: Channel;
  /** "admin" or "device:<id>". Conversations are only visible to their owner and the admin. */
  actor: string;
}

export interface ToolUse {
  name: string;
  ok: boolean;
  ms: number;
  error?: string;
}

export interface TurnResult {
  conversationId: string;
  reply: string;
  toolsUsed: ToolUse[];
  pendingAction: { id: string; summary: string; expiresAt: number } | null;
  resolvedAction: { id: string; status: 'confirmed' | 'cancelled' | 'failed' } | null;
  usage: { inputTokens: number; outputTokens: number; costUsd: number; modelCalls: number };
}

export class AgentError extends Error {
  readonly code: string;
  readonly httpStatus: number;
  readonly reply: string;
  constructor(code: string, httpStatus: number, reply: string, detail?: string) {
    super(detail ?? reply);
    this.code = code;
    this.httpStatus = httpStatus;
    this.reply = reply;
  }
}

export const MAX_MESSAGE_CHARS = 4000;
const MAX_TOOL_OUTPUT_CHARS = 16000;
const TOOL_TIMEOUT_MS = 15000;
const TURN_TIMEOUT_MS = 90000;

function modelErrorReply(err: ModelError): string {
  switch (err.code) {
    case 'no_key':
      return "My language model isn't configured yet. Add an OpenAI API key to the server's .env file.";
    case 'auth':
      return 'OpenAI rejected the API key. Please check it in the server settings.';
    case 'insufficient_quota':
      return 'The OpenAI account appears to be out of credit.';
    case 'rate_limited':
      return "OpenAI is asking me to slow down. Try again in a moment.";
    case 'timeout':
      return 'OpenAI took too long to answer. Try again in a moment.';
    case 'bad_request':
      return 'OpenAI rejected that request. The details are in the server log.';
    default:
      return "I can't reach OpenAI at the moment.";
  }
}

export class Agent {
  private d: AgentDeps;
  constructor(deps: AgentDeps) {
    this.d = deps;
  }

  private toolContext(signal: AbortSignal): ToolContext {
    return { config: this.d.config, clock: this.d.clock, fetch: this.d.fetch, signal };
  }

  /** Resolves (or creates) the conversation, enforcing ownership. */
  private conversationFor(input: TurnInput): string {
    const { conversations } = this.d;
    if (input.conversationId) {
      const owner = conversations.owner(input.conversationId);
      if (owner === null) return conversations.create(input.actor, input.channel); // expired: start afresh
      if (owner !== input.actor && input.actor !== 'admin') throw new AgentError('forbidden', 403, "That conversation belongs to someone else.");
      return input.conversationId;
    }
    return conversations.create(input.actor, input.channel);
  }

  /** Runs a confirmed action. Only ever called from a user decision, never from the model. */
  async executeAction(pending: PendingAction, actor: string, signal?: AbortSignal): Promise<{ ok: boolean; result?: unknown; error?: string }> {
    const tool = this.d.tools.get(pending.tool);
    if (!tool) {
      this.d.conversations.resolvePending(pending.id, 'failed', { error: 'tool no longer exists' });
      return { ok: false, error: 'That action is no longer available.' };
    }
    const started = this.d.clock();
    try {
      const ctl = AbortSignal.timeout(TOOL_TIMEOUT_MS);
      const result = await tool.run(pending.args, this.toolContext(signal ? AbortSignal.any([signal, ctl]) : ctl));
      this.d.conversations.resolvePending(pending.id, 'confirmed', result);
      this.d.audit.record(actor, 'action.confirmed', { id: pending.id, tool: pending.tool, args: pending.args, ms: this.d.clock() - started }, pending.conversationId);
      return { ok: true, result };
    } catch (err) {
      const message = truncate((err as Error).message, 300);
      this.d.conversations.resolvePending(pending.id, 'failed', { error: message });
      this.d.audit.record(actor, 'action.failed', { id: pending.id, tool: pending.tool, error: message }, pending.conversationId);
      return { ok: false, error: message };
    }
  }

  cancelAction(pending: PendingAction, actor: string, reason: string): void {
    if (this.d.conversations.resolvePending(pending.id, 'cancelled')) {
      this.d.audit.record(actor, 'action.cancelled', { id: pending.id, tool: pending.tool, reason }, pending.conversationId);
    }
  }

  async runTurn(input: TurnInput): Promise<TurnResult> {
    const { config, provider, tools, conversations, audit, usage, clock, logger } = this.d;
    const message = input.message.trim();
    if (!message) throw new AgentError('bad_request', 400, 'Say something and I will do my best.');
    if (message.length > MAX_MESSAGE_CHARS) throw new AgentError('bad_request', 400, 'That message is too long for me to take in one go.');

    const conversationId = this.conversationFor(input);
    const turnSignal = AbortSignal.timeout(TURN_TIMEOUT_MS);
    audit.record(input.actor, 'chat.message', { channel: input.channel, chars: message.length }, conversationId);

    // 1. A yes/no reply to a pending action is decided here, in code.
    let note: string | null = null;
    let resolvedAction: TurnResult['resolvedAction'] = null;
    const open = conversations.openPending(conversationId);
    if (open) {
      const decision = classifyConfirmation(message, config.assistantName);
      if (decision === 'yes') {
        const outcome = await this.executeAction(open, input.actor, turnSignal);
        resolvedAction = { id: open.id, status: outcome.ok ? 'confirmed' : 'failed' };
        note = outcome.ok
          ? `System: the user confirmed "${open.summary}". It has been carried out. Result: ${truncate(JSON.stringify(outcome.result ?? null), 2000)}. Tell the user briefly.`
          : `System: the user confirmed "${open.summary}", but it failed: ${outcome.error}. Tell the user briefly.`;
      } else if (decision === 'no') {
        this.cancelAction(open, input.actor, 'user declined');
        resolvedAction = { id: open.id, status: 'cancelled' };
        note = `System: the user declined "${open.summary}". It has been cancelled. Acknowledge briefly.`;
      } else {
        this.cancelAction(open, input.actor, 'reply was not a clear yes or no');
        resolvedAction = { id: open.id, status: 'cancelled' };
        note = `System: the pending action "${open.summary}" was cancelled because the user's reply wasn't a clear yes or no. Deal with their new message; if they still want the action, call the tool again.`;
      }
    }

    // 2. Build the model input from short-term history plus this message.
    const inputItems: InputItem[] = conversations
      .history(conversationId, config.historyMessages)
      .map((m) => ({ type: 'message', role: m.role, content: m.content }) as InputItem);
    inputItems.push({ type: 'message', role: 'user', content: message });
    if (note) inputItems.push({ type: 'message', role: 'developer', content: note });

    const toolsUsed: ToolUse[] = [];
    let pendingAction: TurnResult['pendingAction'] = null;
    const totals = { inputTokens: 0, outputTokens: 0, costUsd: 0, modelCalls: 0 };
    let reply = '';

    for (let step = 0; step < config.maxAgentSteps; step++) {
      if (usage.overBudget()) {
        audit.record('system', 'budget.blocked', { budgetUsd: config.monthlyBudgetUsd }, conversationId);
        throw new AgentError(
          'budget_exceeded',
          429,
          `I've reached this month's spending limit of $${config.monthlyBudgetUsd.toFixed(2)}. You can raise MONTHLY_BUDGET_USD on the server.`,
        );
      }
      const instructions = buildInstructions({ config, channel: input.channel, now: clock(), toolNames: tools.list().map((t) => t.name) });
      let res;
      try {
        res = await provider.respond({ instructions, input: inputItems, tools: tools.specs() }, turnSignal);
      } catch (err) {
        if (err instanceof ModelError) {
          logger.warn('model error', { code: err.code, status: err.status, message: err.message });
          audit.record('system', 'model.error', { code: err.code, status: err.status ?? null, message: truncate(err.message, 300) }, conversationId);
          throw new AgentError('model_' + err.code, err.code === 'no_key' ? 503 : 502, modelErrorReply(err), err.message);
        }
        throw err;
      }
      totals.modelCalls++;
      totals.inputTokens += res.usage.inputTokens;
      totals.outputTokens += res.usage.outputTokens;
      totals.costUsd += usage.record(res.model, res.usage, conversationId);

      inputItems.push(...res.output);
      if (res.toolCalls.length === 0) {
        reply = res.text;
        break;
      }

      for (const call of res.toolCalls) {
        const started = clock();
        const tool = tools.get(call.name);
        let output: unknown;
        let ok = false;
        let error: string | undefined;
        try {
          if (!tool) throw new ToolInputError(`there is no tool called "${call.name}"`);
          const args = validateArgs(tool.parameters, call.arguments);
          if (tool.risk === 'action') {
            const summary = tool.describe!(args);
            const p = conversations.createPending(conversationId, tool.name, args, summary, config.pendingActionMinutes);
            pendingAction = { id: p.id, summary, expiresAt: p.expiresAt };
            audit.record('assistant', 'action.proposed', { id: p.id, tool: tool.name, args }, conversationId);
            output = {
              status: 'awaiting_user_confirmation',
              action: summary,
              instruction: 'Not done yet. Ask the user one short yes-or-no question to confirm exactly this action.',
            };
            ok = true;
          } else {
            const ctl = AbortSignal.timeout(TOOL_TIMEOUT_MS);
            const result = await tool.run(args, this.toolContext(AbortSignal.any([turnSignal, ctl])));
            output = tool.untrusted ? wrapUntrusted(tool.source ?? tool.name, result) : result;
            ok = true;
          }
        } catch (err) {
          error = err instanceof ToolInputError ? err.message : `the ${call.name} tool failed: ${(err as Error).message}`;
          error = truncate(error, 300);
          output = { error };
        }
        const ms = clock() - started;
        toolsUsed.push({ name: call.name, ok, ms, ...(error ? { error } : {}) });
        audit.record('assistant', 'tool.call', { tool: call.name, args: truncate(call.arguments, 500), ok, ms, ...(error ? { error } : {}) }, conversationId);
        inputItems.push({ type: 'function_call_output', call_id: call.call_id, output: truncate(JSON.stringify(output), MAX_TOOL_OUTPUT_CHARS) });
      }
    }

    if (!reply) {
      reply = pendingAction
        ? `Shall I go ahead and ${pendingAction.summary}?`
        : "I'm afraid I couldn't finish that one. Could you put it another way?";
    }

    conversations.append(conversationId, 'user', message);
    conversations.append(conversationId, 'assistant', reply);
    audit.record(input.actor, 'chat.reply', { chars: reply.length, tools: toolsUsed.map((t) => t.name), costUsd: Number(totals.costUsd.toFixed(6)) }, conversationId);
    return { conversationId, reply, toolsUsed, pendingAction, resolvedAction, usage: totals };
  }
}
