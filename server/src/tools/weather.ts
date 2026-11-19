import { ToolInputError, type Tool, type ToolContext } from './registry.ts';

const WMO: Record<number, string> = {
  0: 'clear sky', 1: 'mainly clear', 2: 'partly cloudy', 3: 'overcast',
  45: 'fog', 48: 'freezing fog',
  51: 'light drizzle', 53: 'drizzle', 55: 'heavy drizzle', 56: 'light freezing drizzle', 57: 'freezing drizzle',
  61: 'light rain', 63: 'rain', 65: 'heavy rain', 66: 'light freezing rain', 67: 'freezing rain',
  71: 'light snow', 73: 'snow', 75: 'heavy snow', 77: 'snow grains',
  80: 'light showers', 81: 'showers', 82: 'violent showers', 85: 'light snow showers', 86: 'snow showers',
  95: 'thunderstorm', 96: 'thunderstorm with hail', 99: 'severe thunderstorm with hail',
};

export function describeWmo(code: number | null | undefined): string {
  return code === null || code === undefined ? 'unknown' : (WMO[code] ?? `weather code ${code}`);
}

interface Place {
  name: string;
  region?: string;
  country?: string;
  lat: number;
  lon: number;
}

async function getJson(ctx: ToolContext, url: string): Promise<any> {
  const res = await ctx.fetch(url, { signal: AbortSignal.any([ctx.signal, AbortSignal.timeout(10000)]) });
  if (!res.ok) throw new Error(`weather service returned HTTP ${res.status}`);
  return res.json();
}

export async function geocode(ctx: ToolContext, query: string): Promise<Place> {
  const url = `https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=${encodeURIComponent(query)}`;
  const body = await getJson(ctx, url);
  const hit = body?.results?.[0];
  if (!hit) throw new ToolInputError(`couldn't find a place called "${query}"`);
  return { name: hit.name, region: hit.admin1, country: hit.country, lat: hit.latitude, lon: hit.longitude };
}

export function forecastUrl(lat: number, lon: number, days: number): string {
  const p = new URLSearchParams({
    latitude: lat.toFixed(4),
    longitude: lon.toFixed(4),
    current: 'temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,is_day',
    hourly: 'temperature_2m,precipitation_probability,weather_code',
    daily: 'weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,sunrise,sunset,uv_index_max,wind_speed_10m_max',
    timezone: 'auto',
    forecast_days: String(days),
    wind_speed_unit: 'mph',
  });
  return `https://api.open-meteo.com/v1/forecast?${p}`;
}

const hhmm = (iso: string | undefined) => (iso ? iso.slice(11, 16) : null);
const round = (n: unknown) => (typeof n === 'number' ? Math.round(n * 10) / 10 : null);

/** Condenses an Open-Meteo forecast into a small, token-cheap summary. */
export function summariseForecast(body: any, place: Place, days: number) {
  const cur = body.current ?? {};
  const hourly = body.hourly ?? {};
  const times: string[] = hourly.time ?? [];
  const nowKey: string = typeof cur.time === 'string' ? cur.time.slice(0, 13) : '';
  let start = times.findIndex((t) => t.slice(0, 13) === nowKey);
  if (start < 0) start = 0;
  const nextHours = [];
  for (let i = start + 1; i < Math.min(times.length, start + 13); i += 3) {
    nextHours.push({
      time: hhmm(times[i]),
      temperature: round(hourly.temperature_2m?.[i]),
      rain_chance_percent: hourly.precipitation_probability?.[i] ?? null,
      description: describeWmo(hourly.weather_code?.[i]),
    });
  }
  const daily = body.daily ?? {};
  const dayList = [];
  for (let i = 0; i < Math.min(days, (daily.time ?? []).length); i++) {
    dayList.push({
      date: daily.time[i],
      description: describeWmo(daily.weather_code?.[i]),
      max: round(daily.temperature_2m_max?.[i]),
      min: round(daily.temperature_2m_min?.[i]),
      rain_chance_percent: daily.precipitation_probability_max?.[i] ?? null,
      rain_mm: round(daily.precipitation_sum?.[i]),
      wind_max_mph: round(daily.wind_speed_10m_max?.[i]),
      uv_index_max: round(daily.uv_index_max?.[i]),
      sunrise: hhmm(daily.sunrise?.[i]),
      sunset: hhmm(daily.sunset?.[i]),
    });
  }
  return {
    location: { name: place.name, region: place.region ?? null, country: place.country ?? null },
    local_timezone: body.timezone ?? null,
    units: { temperature: '°C', wind: 'mph', rain: 'mm' },
    current: {
      local_time: cur.time ?? null,
      description: describeWmo(cur.weather_code),
      temperature: round(cur.temperature_2m),
      feels_like: round(cur.apparent_temperature),
      humidity_percent: cur.relative_humidity_2m ?? null,
      wind_mph: round(cur.wind_speed_10m),
      gusts_mph: round(cur.wind_gusts_10m),
      precipitation_mm: round(cur.precipitation),
      daylight: cur.is_day === 1,
    },
    next_hours: nextHours,
    days: dayList,
    data_source: 'Open-Meteo',
  };
}

export const weatherTool: Tool = {
  name: 'get_weather',
  description:
    'Get current conditions and the forecast (up to 7 days) for the user\'s home or a named place. Temperatures in °C, wind in mph.',
  parameters: {
    type: 'object',
    properties: {
      location: { type: 'string', description: 'Town or city, e.g. "Leeds" or "Paris, France". Omit for the user\'s home.', maxLength: 100 },
      days: { type: 'integer', description: 'Number of forecast days, 1 to 7. Default 2.', minimum: 1, maximum: 7 },
    },
    additionalProperties: false,
  },
  risk: 'read',
  untrusted: true,
  source: 'Open-Meteo weather API',
  async run(args, ctx) {
    const days = typeof args.days === 'number' ? args.days : 2;
    let place: Place;
    if (typeof args.location === 'string' && args.location.trim()) {
      place = await geocode(ctx, args.location.trim());
    } else if (ctx.config.home) {
      place = { name: ctx.config.home.name, lat: ctx.config.home.lat, lon: ctx.config.home.lon };
    } else {
      throw new ToolInputError('no home location is configured (HOME_LAT/HOME_LON); ask the user which town they mean');
    }
    const body = await getJson(ctx, forecastUrl(place.lat, place.lon, days));
    return summariseForecast(body, place, days);
  },
};
