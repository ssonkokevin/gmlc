import type {
  AccessPolicy,
  ActiveSession,
  AdminClient,
  AlertPage,
  ArmView,
  AuditRow,
  BreakGlassGrant,
  CellPlan,
  ClientView,
  ConfigChange,
  ConfigSnapshot,
  CreatedChannel,
  CreatedOpsUser,
  CreatedSubscription,
  DashboardData,
  Deferred,
  DeliveryView,
  ExchangePage,
  ExchangeSeries,
  GeofenceEventPage,
  GeofenceGeometry,
  GeofenceSummary,
  GeofenceView,
  Mix,
  NotificationChannel,
  OpsUserView,
  PeerPage,
  RangeKey,
  Reports,
  RequestDetail,
  RequestPage,
  RoleMatrix,
  Session,
  Submission,
  SubscriptionView,
  Summary,
  Throughput,
  TrackedSubject,
  TrackingFix,
  TrackingRun,
  TrackingSchedule,
  TrackingSummary,
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

  geofences(): Promise<GeofenceView[]> {
    return call<GeofenceView[]>('/v1/ops/geofences');
  },

  geofenceSummary(range: RangeKey): Promise<GeofenceSummary> {
    return call<GeofenceSummary>(`/v1/ops/geofences/summary?range=${range}`);
  },

  geofenceEvents(range: RangeKey, type?: string, limit = 50): Promise<GeofenceEventPage> {
    const query = new URLSearchParams({ range, limit: String(limit) });
    if (type) {
      query.set('type', type);
    }
    return call<GeofenceEventPage>(`/v1/ops/geofences/events?${query}`);
  },

  arms(state: 'INSIDE' | 'OUTSIDE', limit = 100): Promise<ArmView[]> {
    return call<ArmView[]>(`/v1/ops/geofences/arms?state=${state}&limit=${limit}`);
  },

  createGeofence(input: {
    name: string;
    description?: string;
    group?: string;
    reportInside: boolean;
    geometry: GeofenceGeometry;
  }): Promise<GeofenceView> {
    return call<GeofenceView>('/v1/ops/geofences', {
      method: 'POST',
      body: JSON.stringify(input),
    });
  },

  patchGeofence(geofenceId: string, patch: { enabled?: boolean; reportInside?: boolean }): Promise<GeofenceView> {
    return call<GeofenceView>(`/v1/ops/geofences/${geofenceId}`, {
      method: 'PATCH',
      body: JSON.stringify(patch),
    });
  },

  armGeofence(geofenceId: string, input: {
    clientId: string;
    msisdn?: string;
    imsi?: string;
    purpose: string;
  }): Promise<{ created: boolean; arm: ArmView }> {
    return call<{ created: boolean; arm: ArmView }>(`/v1/ops/geofences/${geofenceId}/arm`, {
      method: 'POST',
      body: JSON.stringify(input),
    });
  },

  disarmGeofence(geofenceId: string, subjectRef: string): Promise<void> {
    return call<void>(`/v1/ops/geofences/${geofenceId}/arms/${subjectRef}`, { method: 'DELETE' });
  },

  subscriptions(): Promise<SubscriptionView[]> {
    return call<SubscriptionView[]>('/v1/ops/subscriptions');
  },

  createSubscription(input: {
    name: string;
    url: string;
    eventTypes: string[];
    geofenceId?: string;
    enabled: boolean;
  }): Promise<CreatedSubscription> {
    return call<CreatedSubscription>('/v1/ops/subscriptions', {
      method: 'POST',
      body: JSON.stringify(input),
    });
  },

  patchSubscription(subscriptionId: string, patch: { enabled: boolean }): Promise<SubscriptionView> {
    return call<SubscriptionView>(`/v1/ops/subscriptions/${subscriptionId}`, {
      method: 'PATCH',
      body: JSON.stringify(patch),
    });
  },

  deleteSubscription(subscriptionId: string): Promise<void> {
    return call<void>(`/v1/ops/subscriptions/${subscriptionId}`, { method: 'DELETE' });
  },

  deliveries(subscriptionId: string, limit = 20): Promise<DeliveryView[]> {
    return call<DeliveryView[]>(`/v1/ops/subscriptions/${subscriptionId}/deliveries?limit=${limit}`);
  },

  trackingSummary(): Promise<TrackingSummary> {
    return call<TrackingSummary>('/v1/ops/tracking/summary');
  },

  trackedSubjects(): Promise<TrackedSubject[]> {
    return call<TrackedSubject[]>('/v1/ops/tracking');
  },

  trackingTrail(trackedId: string, limit = 100): Promise<TrackingFix[]> {
    return call<TrackingFix[]>(`/v1/ops/tracking/${trackedId}/trail?limit=${limit}`);
  },

  addTrackedSubject(input: {
    clientId: string;
    msisdn?: string;
    imsi?: string;
    purpose: string;
  }): Promise<TrackedSubject> {
    return call<TrackedSubject>('/v1/ops/tracking', {
      method: 'POST',
      body: JSON.stringify(input),
    });
  },

  patchTrackedSubject(trackedId: string, patch: { enabled: boolean }): Promise<TrackedSubject> {
    return call<TrackedSubject>(`/v1/ops/tracking/${trackedId}`, {
      method: 'PATCH',
      body: JSON.stringify(patch),
    });
  },

  deleteTrackedSubject(trackedId: string): Promise<void> {
    return call<void>(`/v1/ops/tracking/${trackedId}`, { method: 'DELETE' });
  },

  schedules(): Promise<TrackingSchedule[]> {
    return call<TrackingSchedule[]>('/v1/ops/tracking/schedules');
  },

  createSchedule(input: {
    trackedId: string;
    intervalSeconds: number;
    startsAt?: string;
    endsAt?: string;
    accuracyMeters?: number;
    notifyOnFix: boolean;
    channelId?: string;
  }): Promise<TrackingSchedule> {
    return call<TrackingSchedule>('/v1/ops/tracking/schedules', {
      method: 'POST',
      body: JSON.stringify(input),
    });
  },

  patchSchedule(scheduleId: string, patch: { enabled: boolean }): Promise<TrackingSchedule> {
    return call<TrackingSchedule>(`/v1/ops/tracking/schedules/${scheduleId}`, {
      method: 'PATCH',
      body: JSON.stringify(patch),
    });
  },

  deleteSchedule(scheduleId: string): Promise<void> {
    return call<void>(`/v1/ops/tracking/schedules/${scheduleId}`, { method: 'DELETE' });
  },

  runSchedule(scheduleId: string): Promise<TrackingRun> {
    return call<TrackingRun>(`/v1/ops/tracking/schedules/${scheduleId}/run`, { method: 'POST' });
  },

  scheduleRuns(scheduleId: string, limit = 50): Promise<TrackingRun[]> {
    return call<TrackingRun[]>(`/v1/ops/tracking/schedules/${scheduleId}/runs?limit=${limit}`);
  },

  channels(): Promise<NotificationChannel[]> {
    return call<NotificationChannel[]>('/v1/ops/tracking/channels');
  },

  createChannel(input: {
    name: string;
    type: string;
    url: string;
    enabled: boolean;
  }): Promise<CreatedChannel> {
    return call<CreatedChannel>('/v1/ops/tracking/channels', {
      method: 'POST',
      body: JSON.stringify(input),
    });
  },

  patchChannel(channelId: string, patch: { enabled: boolean }): Promise<NotificationChannel> {
    return call<NotificationChannel>(`/v1/ops/tracking/channels/${channelId}`, {
      method: 'PATCH',
      body: JSON.stringify(patch),
    });
  },

  deleteChannel(channelId: string): Promise<void> {
    return call<void>(`/v1/ops/tracking/channels/${channelId}`, { method: 'DELETE' });
  },

  configuration(): Promise<ConfigSnapshot> {
    return call<ConfigSnapshot>('/v1/ops/configuration');
  },

  configChanges(status = 'ALL', limit = 50): Promise<ConfigChange[]> {
    const query = new URLSearchParams({ status, limit: String(limit) });
    return call<ConfigChange[]>(`/v1/ops/configuration/changes?${query}`);
  },

  requestConfigChange(key: string, value: string | number, reason: string): Promise<ConfigChange> {
    return call<ConfigChange>('/v1/ops/configuration/changes', {
      method: 'POST',
      body: JSON.stringify({ key, value, reason }),
    });
  },

  approveConfigChange(changeId: string, note?: string): Promise<ConfigChange> {
    return call<ConfigChange>(`/v1/ops/configuration/changes/${changeId}/approve`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    });
  },

  rejectConfigChange(changeId: string, note: string): Promise<ConfigChange> {
    return call<ConfigChange>(`/v1/ops/configuration/changes/${changeId}/reject`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    });
  },

  cellPlan(rat?: string, limit = 100): Promise<CellPlan> {
    const query = new URLSearchParams({ limit: String(limit) });
    if (rat) {
      query.set('rat', rat);
    }
    return call<CellPlan>(`/v1/ops/administration/cells?${query}`);
  },

  opsUsers(): Promise<OpsUserView[]> {
    return call<OpsUserView[]>('/v1/ops/users');
  },

  createOpsUser(input: {
    username: string;
    displayName: string;
    role: string;
    enabled: boolean;
  }): Promise<CreatedOpsUser> {
    return call<CreatedOpsUser>('/v1/ops/users', {
      method: 'POST',
      body: JSON.stringify(input),
    });
  },

  patchOpsUser(userId: string, patch: { role?: string; enabled?: boolean }): Promise<OpsUserView> {
    return call<OpsUserView>(`/v1/ops/users/${userId}`, {
      method: 'PATCH',
      body: JSON.stringify(patch),
    });
  },

  signOutOpsUser(userId: string): Promise<void> {
    return call<void>(`/v1/ops/users/${userId}/sign-out`, { method: 'POST' });
  },

  setOpsPassword(userId: string, password: string): Promise<void> {
    return call<void>(`/v1/ops/users/${userId}/password`, {
      method: 'POST',
      body: JSON.stringify({ password }),
    });
  },

  roleMatrix(): Promise<RoleMatrix> {
    return call<RoleMatrix>('/v1/ops/access/roles');
  },

  activeSessions(): Promise<ActiveSession[]> {
    return call<ActiveSession[]>('/v1/ops/access/sessions');
  },

  signOutSession(sessionId: string): Promise<void> {
    return call<void>(`/v1/ops/access/sessions/${sessionId}/sign-out`, { method: 'POST' });
  },

  auditLog(options: {
    action?: string;
    outcome?: string;
    actor?: string;
    limit?: number;
    before?: number;
  } = {}): Promise<AuditRow[]> {
    const query = new URLSearchParams({ limit: String(options.limit ?? 100) });
    if (options.action) {
      query.set('action', options.action);
    }
    if (options.outcome && options.outcome !== 'ALL') {
      query.set('outcome', options.outcome);
    }
    if (options.actor) {
      query.set('actor', options.actor);
    }
    if (options.before) {
      query.set('before', String(options.before));
    }
    return call<AuditRow[]>(`/v1/ops/access/audit?${query}`);
  },

  accessPolicy(): Promise<AccessPolicy> {
    return call<AccessPolicy>('/v1/ops/access/policy');
  },

  adminClients(): Promise<AdminClient[]> {
    return call<AdminClient[]>('/v1/ops/access/clients');
  },

  breakGlassGrants(status = 'ALL', limit = 50): Promise<BreakGlassGrant[]> {
    const query = new URLSearchParams({ status, limit: String(limit) });
    return call<BreakGlassGrant[]>(`/v1/ops/access/break-glass?${query}`);
  },

  startBreakGlass(reason: string, code: string): Promise<BreakGlassGrant> {
    return call<BreakGlassGrant>('/v1/ops/access/break-glass', {
      method: 'POST',
      body: JSON.stringify({ reason, code }),
    });
  },

  endBreakGlass(): Promise<BreakGlassGrant> {
    return call<BreakGlassGrant>('/v1/ops/access/break-glass', { method: 'DELETE' });
  },

  reviewBreakGlass(grantId: string, note: string): Promise<BreakGlassGrant> {
    return call<BreakGlassGrant>(`/v1/ops/access/break-glass/${grantId}/review`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    });
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
