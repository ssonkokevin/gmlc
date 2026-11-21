import type { RangeKey } from './types';

const integer = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 0 });
const oneDecimal = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 1 });

const clock = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Africa/Kampala',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

const dayClock = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Africa/Kampala',
  day: '2-digit',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

export const RANGE_LABEL: Record<RangeKey, string> = {
  '1h': 'Last hour',
  '24h': 'Last 24 hours',
  '7d': 'Last 7 days',
  '30d': 'Last 30 days',
};

export function fmtInt(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : integer.format(value);
}

export function fmtDecimal(value: number | null | undefined, digits = 1): string {
  return value === null || value === undefined ? '—' : oneDecimal.format(value).replace(/\.0$/, digits === 0 ? '' : '.0');
}

export function fmtMs(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '—';
  }
  return value >= 1000 ? `${oneDecimal.format(value / 1000)} s` : `${Math.round(value)} ms`;
}

export function fmtShare(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${oneDecimal.format(value * 100)}%`;
}

export function fmtTime(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : clock.format(date);
}

export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : dayClock.format(date);
}

export function axisLabel(iso: string, range: RangeKey): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  if (range === '1h' || range === '24h') {
    return clock.format(date).slice(0, 5);
  }
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Africa/Kampala',
    day: '2-digit',
    month: 'short',
  }).format(date);
}

export function fmtAge(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) {
    return '—';
  }
  if (seconds < 60) {
    return `${seconds} s`;
  }
  if (seconds < 3600) {
    return `${Math.round(seconds / 60)} min`;
  }
  return `${oneDecimal.format(seconds / 3600)} h`;
}
