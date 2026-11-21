import type {
  AlertPage,
  ClientView,
  DashboardData,
  Deferred,
  ExchangePage,
  ExchangeSeries,
  Mix,
  PeerPage,
  RangeKey,
  Reports,
  RequestDetail,
  RequestPage,
  Session,
  Submission,
  Summary,
  Throughput,
} from './types';

let csrfToken: string | null = null;

export function setCsrfToken(token: string | null): void {
  csrfToken = token;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;

  constructor(status: number, message: string, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

interface Problem {
  title?: string;
  detail?: string;
  code?: string;
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  const method = (init.method ?? 'GET').toUpperCase();
  if (init.body !== undefined) {
    headers.set('Content-Type', 'application/json');
  }
  if (method !== 'GET' && method !== 'HEAD' && csrfToken) {
    headers.set('X-CSRF-Token', csrfToken);
  }
  const response = await fetch(path, { ...init, method, headers, credentials: 'same-origin' });
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  let body: unknown = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = null;
    }
  }
  if (!response.ok) {
    const problem = (body ?? {}) as Problem;
    throw new ApiError(response.status, problem.detail ?? problem.title ?? response.statusText,
      problem.code);
  }
  return body as T;
}

export const api = {
  async signIn(username: string, password: string, totp: string): Promise<Session> {
    const session = await call<Session>('/v1/ops/session', {
      method: 'POST',
      body: JSON.stringify({ username, password, totp }),
    });
    setCsrfToken(session.csrfToken);
    return session;
  },

  async me(): Promise<Session> {
    const session = await call<Session>('/v1/ops/me');
    setCsrfToken(session.csrfToken);
    return session;
  },

  async signOut(): Promise<void> {
    try {
      await call<void>('/v1/ops/session', { method: 'DELETE' });
    } finally {
      setCsrfToken(null);
    }
  },

  summary(range: RangeKey): Promise<Summary> {
    return call<Summary>(`/v1/ops/summary?range=${range}`);
  },

  throughput(range: RangeKey): Promise<Throughput> {
    return call<Throughput>(`/v1/ops/throughput?range=${range}`);
  },

  mix(range: RangeKey): Promise<Mix> {
    return call<Mix>(`/v1/ops/mix?range=${range}`);
  },

  peers(): Promise<PeerPage> {
    return call<PeerPage>('/v1/ops/peers');
  },

  alerts(): Promise<AlertPage> {
    return call<AlertPage>('/v1/ops/alerts?state=OPEN&limit=50');
  },

  requests(range: RangeKey, limit = 8): Promise<RequestPage> {
    return call<RequestPage>(`/v1/ops/requests?range=${range}&limit=${limit}`);
  },

  deferred(): Promise<Deferred> {
    return call<Deferred>('/v1/ops/deferred');
  },

  acknowledge(alertId: number): Promise<void> {
    return call<void>(`/v1/ops/alerts/${alertId}/acknowledge`, { method: 'POST' });
  },

  clients(): Promise<ClientView[]> {
    return call<ClientView[]>('/v1/ops/clients');
  },

  submitLocation(input: {
    clientId: string;
    msisdn?: string;
    imsi?: string;
    purpose: string;
    accuracyMeters?: number;
    priority?: string;
    idempotencyKey?: string;
  }): Promise<Submission> {
    return call<Submission>('/v1/ops/locations', {
      method: 'POST',
      body: JSON.stringify(input),
    });
  },

  requestDetail(requestId: string, reveal = false): Promise<RequestDetail> {
    const suffix = reveal ? '?reveal=true' : '';
    return call<RequestDetail>(`/v1/ops/requests/${requestId}${suffix}`);
  },

  exchanges(range: RangeKey, options: {
    protocol?: string;
    recordStatus?: string;
    limit?: number;
    offset?: number;
  } = {}): Promise<ExchangePage> {
    const query = new URLSearchParams({ range });
    if (options.protocol) {
      query.set('protocol', options.protocol);
    }
    if (options.recordStatus) {
      query.set('recordStatus', options.recordStatus);
    }
    query.set('limit', String(options.limit ?? 25));
    query.set('offset', String(options.offset ?? 0));
    return call<ExchangePage>(`/v1/ops/exchanges?${query}`);
  },

  exchangeSeries(range: RangeKey, protocol?: string): Promise<ExchangeSeries> {
    const query = new URLSearchParams({ range });
    if (protocol) {
      query.set('protocol', protocol);
    }
    return call<ExchangeSeries>(`/v1/ops/exchanges/series?${query}`);
  },

  reports(range: RangeKey): Promise<Reports> {
    return call<Reports>(`/v1/ops/reports?range=${range}`);
  },

  exchangeExportUrl(range: RangeKey, options: { protocol?: string; recordStatus?: string } = {}): string {
    const query = new URLSearchParams({ range });
    if (options.protocol) {
      query.set('protocol', options.protocol);
    }
    if (options.recordStatus) {
      query.set('recordStatus', options.recordStatus);
    }
    return `/v1/ops/exchanges/export.csv?${query}`;
  },

  reportExportUrl(range: RangeKey, report: 'clients' | 'purposes' | 'rat' | 'method' | 'cell'): string {
    return `/v1/ops/reports/export.csv?range=${range}&report=${report}`;
  },
};

export async function loadDashboard(range: RangeKey): Promise<DashboardData> {
  const [summary, throughput, mix, peers, alerts, requests, deferred] = await Promise.all([
    api.summary(range),
    api.throughput(range),
    api.mix(range),
    api.peers(),
    api.alerts(),
    api.requests(range),
    api.deferred(),
  ]);
  return { summary, throughput, mix, peers, alerts, requests, deferred };
}
