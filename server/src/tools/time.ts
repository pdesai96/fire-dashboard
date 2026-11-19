import { validTimezone } from '../config.ts';
import { ToolInputError, type Tool } from './registry.ts';

export function describeTime(epochMs: number, timeZone: string) {
  const d = new Date(epochMs);
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZoneName: 'short',
  }).formatToParts(d);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return {
    timezone: timeZone,
    iso_utc: d.toISOString(),
    date: `${get('weekday')} ${get('day')} ${get('month')} ${get('year')}`,
    time_24h: `${get('hour')}:${get('minute')}`,
    zone_abbreviation: get('timeZoneName'),
  };
}

export const timeTool: Tool = {
  name: 'get_current_time',
  description: 'Get the current date and time. Defaults to the user\'s home time zone; pass an IANA time zone (e.g. "America/New_York") for elsewhere.',
  parameters: {
    type: 'object',
    properties: {
      timezone: { type: 'string', description: 'IANA time zone name, e.g. "Asia/Tokyo". Omit for the user\'s home time zone.', maxLength: 64 },
    },
    additionalProperties: false,
  },
  risk: 'read',
  untrusted: false,
  async run(args, ctx) {
    const tz = typeof args.timezone === 'string' && args.timezone ? args.timezone : ctx.config.timezone;
    if (!validTimezone(tz)) throw new ToolInputError(`"${tz}" is not a recognised time zone`);
    return describeTime(ctx.clock(), tz);
  },
};
