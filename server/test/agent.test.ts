import assert from 'node:assert/strict';
import { afterEach, describe, test } from 'node:test';
import type { FunctionCallOutputItem, ModelRequest } from '../src/llm/types.ts';
import { ModelError } from '../src/llm/types.ts';
import { api, fakeActionTool, pairDevice, startTestApp, type TestApp } from './helpers.ts';

let t: TestApp | null = null;
afterEach(async () => {
  await t?.close();
  t = null;
});

const outputs = (req: ModelRequest) => req.input.filter((i): i is FunctionCallOutputItem => i.type === 'function_call_output');

describe('agent: conversation', () => {
  test('answers a plain question and records usage', async () => {
    t = await startTestApp({ script: [{ text: 'Good afternoon. All quiet on the home front.', usage: { input: 1000, output: 100 } }] });
    const res = await t.app.agent.runTurn({ message: 'Hello', channel: 'text', actor: 'admin' });
    assert.equal(res.reply, 'Good afternoon. All quiet on the home front.');
    assert.match(res.conversationId, /^c_/);
    assert.deepEqual(res.toolsUsed, []);
    assert.equal(res.usage.modelCalls, 1);
    // 1000 in * $0.10/M + 100 out * $0.50/M
    assert.ok(Math.abs(res.usage.costUsd - 0.00015) < 1e-9);
    assert.equal(t.app.usage.monthToDate().calls, 1);
  });

  test('keeps short-term history so follow-up questions work', async () => {
    t = await startTestApp({ script: [{ text: 'It is 13:15.' }, { text: 'That would be 22:15 in Tokyo.' }] });
    const first = await t.app.agent.runTurn({ message: 'What time is it?', channel: 'text', actor: 'admin' });
    await t.app.agent.runTurn({ message: 'And in Tokyo?', channel: 'text', actor: 'admin', conversationId: first.conversationId });
    const second = t.provider.requests[1]!;
    const messages = second.input.filter((i) => i.type === 'message').map((i) => (i.type === 'message' ? `${i.role}:${i.content}` : ''));
    assert.deepEqual(messages, ['user:What time is it?', 'assistant:It is 13:15.', 'user:And in Tokyo?']);
  });

  test('starts a fresh conversation when the old one has been purged', async () => {
    t = await startTestApp({ script: [{ text: 'One.' }, { text: 'Two.' }] });
    const first = await t.app.agent.runTurn({ message: 'Hi', channel: 'text', actor: 'admin' });
    t.clock.advance(25 * 3600_000);
    assert.equal(t.app.conversations.purge(24), 1);
    const second = await t.app.agent.runTurn({ message: 'Hi again', channel: 'text', actor: 'admin', conversationId: first.conversationId });
    assert.notEqual(second.conversationId, first.conversationId);
    assert.equal(t.provider.requests[1]!.input.length, 1);
  });

  test('persona: assistant name, British English and voice formatting are in the instructions', async () => {
    t = await startTestApp({ env: { ASSISTANT_NAME: 'FRIDAY', USER_NAME: 'Priyesh' }, script: [{ text: 'Ready.' }] });
    await t.app.agent.runTurn({ message: 'Status?', channel: 'voice', actor: 'admin' });
    const ins = t.provider.requests[0]!.instructions;
    assert.match(ins, /You are FRIDAY/);
    assert.match(ins, /British English/);
    assert.match(ins, /spoken aloud/);
    assert.match(ins, /Priyesh/);
    assert.match(ins, /Thursday 19 November 2026, 13:15/);
    assert.match(ins, /untrusted_external_content/);
  });

  test('rejects empty and oversized messages', async () => {
    t = await startTestApp();
    await assert.rejects(t.app.agent.runTurn({ message: '   ', channel: 'text', actor: 'admin' }), /Say something/);
    await assert.rejects(t.app.agent.runTurn({ message: 'x'.repeat(4001), channel: 'text', actor: 'admin' }), /too long/);
  });
});

describe('agent: tools', () => {
  test('calls the weather tool for home and wraps the result as untrusted data', async () => {
    t = await startTestApp({
      script: [{ toolCalls: [{ name: 'get_weather', args: {} }] }, { text: 'Light rain, nine degrees.' }],
    });
    const res = await t.app.agent.runTurn({ message: "What's the weather like?", channel: 'voice', actor: 'admin' });
    assert.equal(res.reply, 'Light rain, nine degrees.');
    assert.deepEqual(res.toolsUsed.map((u) => [u.name, u.ok]), [['get_weather', true]]);
    assert.match(t.fetch.urls[0]!, /latitude=53\.4800&longitude=-2\.2400/);
    const out = JSON.parse(outputs(t.provider.requests[1]!)[0]!.output);
    assert.equal(out.untrusted_external_content, true);
    assert.equal(out.source, 'Open-Meteo weather API');
    assert.equal(out.data.location.name, 'Manchester');
    assert.equal(out.data.current.description, 'light rain');
    assert.equal(out.data.current.temperature, 9.4);
    // The model's own function_call item is passed back before the output.
    const types = t.provider.requests[1]!.input.map((i) => i.type);
    assert.deepEqual(types, ['message', 'function_call', 'function_call_output']);
  });

  test('geocodes a named place', async () => {
    t = await startTestApp({ script: [{ toolCalls: [{ name: 'get_weather', args: { location: 'Leeds', days: 3 } }] }, { text: 'Leeds: damp.' }] });
    await t.app.agent.runTurn({ message: 'Weather in Leeds?', channel: 'text', actor: 'admin' });
    assert.match(t.fetch.urls[0]!, /geocoding-api.*name=Leeds/);
    assert.match(t.fetch.urls[1]!, /latitude=53\.8000.*forecast_days=3/);
  });

  test('time tool honours a requested time zone', async () => {
    t = await startTestApp({ script: [{ toolCalls: [{ name: 'get_current_time', args: { timezone: 'Asia/Tokyo' } }] }, { text: '22:15 in Tokyo.' }] });
    await t.app.agent.runTurn({ message: 'Time in Tokyo?', channel: 'text', actor: 'admin' });
    const out = JSON.parse(outputs(t.provider.requests[1]!)[0]!.output);
    assert.equal(out.time_24h, '22:15');
    assert.equal(out.timezone, 'Asia/Tokyo');
  });

  test('invalid arguments, unknown tools and failures go back to the model as errors', async () => {
    t = await startTestApp({
      script: [
        {
          toolCalls: [
            { name: 'get_weather', args: { days: 99 } },
            { name: 'launch_rockets', args: {} },
            { name: 'get_weather', args: { location: 'Nowhereville' } },
            { name: 'get_current_time', args: '{not json' },
          ],
        },
        { text: 'Sorry, that did not work.' },
      ],
    });
    const res = await t.app.agent.runTurn({ message: 'Go', channel: 'text', actor: 'admin' });
    assert.equal(res.reply, 'Sorry, that did not work.');
    assert.deepEqual(res.toolsUsed.map((u) => u.ok), [false, false, false, false]);
    const errors = outputs(t.provider.requests[1]!).map((o) => JSON.parse(o.output).error as string);
    assert.match(errors[0]!, /at most 7/);
    assert.match(errors[1]!, /no tool called "launch_rockets"/);
    assert.match(errors[2]!, /couldn't find a place called "Nowhereville"/);
    assert.match(errors[3]!, /not valid JSON/);
  });

  test('stops after the step limit with a graceful reply', async () => {
    const loop = { toolCalls: [{ name: 'get_current_time', args: {} }] };
    t = await startTestApp({ env: { MAX_AGENT_STEPS: '3' }, script: [loop, loop, loop, loop] });
    const res = await t.app.agent.runTurn({ message: 'Loop forever', channel: 'text', actor: 'admin' });
    assert.equal(t.provider.requests.length, 3);
    assert.match(res.reply, /couldn't finish/);
  });
});

describe('agent: confirmation gate for actions', () => {
  test('an action tool is proposed, not run, until the user says yes', async () => {
    const calls: Array<Record<string, unknown>> = [];
    t = await startTestApp({
      tools: [fakeActionTool(calls)],
      script: [
        { toolCalls: [{ name: 'set_reminder', args: { text: 'call Mum', at: '18:00' } }] },
        { text: 'Shall I set a reminder to call Mum at 18:00?' },
        { text: 'Done. I will remind you at 18:00.' },
      ],
    });
    const first = await t.app.agent.runTurn({ message: 'Remind me to call Mum at six', channel: 'voice', actor: 'admin' });
    assert.equal(calls.length, 0, 'must not run before confirmation');
    assert.ok(first.pendingAction);
    assert.equal(first.pendingAction.summary, 'set a reminder to "call Mum" at 18:00');
    assert.equal(JSON.parse(outputs(t.provider.requests[1]!)[0]!.output).status, 'awaiting_user_confirmation');

    const second = await t.app.agent.runTurn({ message: 'Yes please', channel: 'voice', actor: 'admin', conversationId: first.conversationId });
    assert.equal(calls.length, 1);
    assert.deepEqual(calls[0], { text: 'call Mum', at: '18:00' });
    assert.deepEqual(second.resolvedAction, { id: first.pendingAction.id, status: 'confirmed' });
    const note = t.provider.requests[2]!.input.at(-1)!;
    assert.equal(note.type === 'message' && note.role, 'developer');
    assert.match(note.type === 'message' ? note.content : '', /confirmed/);
    assert.ok(t.app.audit.recent(50).some((e) => e.action === 'action.confirmed'));
  });

  test('"no" cancels, and an unclear reply also cancels rather than running', async () => {
    const calls: Array<Record<string, unknown>> = [];
    const propose = { toolCalls: [{ name: 'set_reminder', args: { text: 'bins' } }] };
    t = await startTestApp({
      tools: [fakeActionTool(calls)],
      script: [propose, { text: 'Confirm?' }, { text: 'Cancelled.' }, propose, { text: 'Confirm?' }, { text: 'Sure, tell me more.' }],
    });
    const a = await t.app.agent.runTurn({ message: 'Remind me about the bins', channel: 'text', actor: 'admin' });
    const b = await t.app.agent.runTurn({ message: 'No, cancel that', channel: 'text', actor: 'admin', conversationId: a.conversationId });
    assert.equal(b.resolvedAction?.status, 'cancelled');
    const c = await t.app.agent.runTurn({ message: 'Remind me about the bins', channel: 'text', actor: 'admin', conversationId: a.conversationId });
    const d = await t.app.agent.runTurn({ message: 'Hmm, what time is collection?', channel: 'text', actor: 'admin', conversationId: c.conversationId });
    assert.equal(d.resolvedAction?.status, 'cancelled');
    assert.equal(calls.length, 0);
  });

  test('instructions injected via tool output cannot trigger an action', async () => {
    const calls: Array<Record<string, unknown>> = [];
    t = await startTestApp({
      tools: [fakeActionTool(calls)],
      script: [
        { toolCalls: [{ name: 'get_weather', args: {} }] },
        // Simulate a model that was fooled by injected text and tries to act on it.
        { toolCalls: [{ name: 'set_reminder', args: { text: 'transfer money' } }] },
        { text: 'Shall I set that reminder?' },
      ],
    });
    const res = await t.app.agent.runTurn({ message: 'Weather?', channel: 'text', actor: 'admin' });
    assert.equal(calls.length, 0);
    assert.ok(res.pendingAction, 'the action is only proposed; the user must still say yes');
  });

  test('pending actions expire', async () => {
    const calls: Array<Record<string, unknown>> = [];
    t = await startTestApp({ tools: [fakeActionTool(calls)], script: [{ toolCalls: [{ name: 'set_reminder', args: { text: 'x' } }] }, { text: 'Confirm?' }, { text: 'Hello.' }] });
    const a = await t.app.agent.runTurn({ message: 'Remind me', channel: 'text', actor: 'admin' });
    t.clock.advance(6 * 60_000);
    const b = await t.app.agent.runTurn({ message: 'yes', channel: 'text', actor: 'admin', conversationId: a.conversationId });
    assert.equal(calls.length, 0);
    assert.equal(b.resolvedAction, null);
  });

  test('screen buttons can confirm a pending action over HTTP', async () => {
    const calls: Array<Record<string, unknown>> = [];
    t = await startTestApp({ tools: [fakeActionTool(calls)], script: [{ toolCalls: [{ name: 'set_reminder', args: { text: 'tea' } }] }, { text: 'Confirm?' }] });
    const dev = await pairDevice(t);
    const chat = await api(t, 'POST', '/api/chat', { message: 'Remind me about tea' }, { authorization: dev.authorization });
    const id = chat.json.pendingAction.id;
    const other = await pairDevice(t, 'Other');
    assert.equal((await api(t, 'POST', `/api/actions/${id}/confirm`, {}, { authorization: other.authorization })).status, 404);
    const ok = await api(t, 'POST', `/api/actions/${id}/confirm`, {}, { authorization: dev.authorization });
    assert.equal(ok.json.status, 'confirmed');
    assert.equal(calls.length, 1);
    assert.equal((await api(t, 'POST', `/api/actions/${id}/confirm`, {}, { authorization: dev.authorization })).status, 409);
  });
});

describe('agent: limits and failures', () => {
  test('blocks model calls once the monthly budget is spent', async () => {
    t = await startTestApp({ env: { MONTHLY_BUDGET_USD: '0.001' }, script: [{ text: 'Expensive.', usage: { input: 20000, output: 0 } }, { text: 'never' }] });
    await t.app.agent.runTurn({ message: 'One', channel: 'text', actor: 'admin' });
    await assert.rejects(t.app.agent.runTurn({ message: 'Two', channel: 'text', actor: 'admin' }), (err: any) => err.code === 'budget_exceeded' && err.httpStatus === 429);
    assert.equal(t.provider.requests.length, 1);
  });

  test('model errors become a friendly reply and an audit entry', async () => {
    t = await startTestApp({ script: [{ error: new ModelError('insufficient_quota', 'You exceeded your current quota', 429) }] });
    const dev = await pairDevice(t);
    const res = await api(t, 'POST', '/api/chat', { message: 'Hello' }, { authorization: dev.authorization });
    assert.equal(res.status, 502);
    assert.equal(res.json.error, 'model_insufficient_quota');
    assert.match(res.json.reply, /out of credit/);
    assert.ok(t.app.audit.recent(20).some((e) => e.action === 'model.error'));
  });

  test("a device can't read or continue another device's conversation", async () => {
    t = await startTestApp({ script: [{ text: 'Private.' }] });
    const a = await pairDevice(t, 'A');
    const b = await pairDevice(t, 'B');
    const chat = await api(t, 'POST', '/api/chat', { message: 'Secret' }, { authorization: a.authorization });
    const id = chat.json.conversationId;
    assert.equal((await api(t, 'GET', `/api/conversations/${id}`, undefined, { authorization: b.authorization })).status, 404);
    const hijack = await api(t, 'POST', '/api/chat', { message: 'Hi', conversationId: id }, { authorization: b.authorization });
    assert.equal(hijack.status, 403);
    const own = await api(t, 'GET', `/api/conversations/${id}`, undefined, { authorization: a.authorization });
    assert.equal(own.json.messages.length, 2);
  });
});
