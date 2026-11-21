import type {
  AlertPage,
  DashboardData,
  Deferred,
  Mix,
  PeerPage,
  RangeKey,
  RequestPage,
  Session,
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
