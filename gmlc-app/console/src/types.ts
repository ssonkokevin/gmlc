export interface Session {
  userId: string;
  username: string;
  displayName: string;
  role: string;
  permissions: string[];
  csrfToken: string;
  expiresAt: string;
  environment: string;
  dataSource: string;
}

export interface Feature {
  available: boolean;
  reason?: string;
}

export interface AlertState extends Feature {
  open: number;
  acknowledgedOpen: number;
}

export interface SeriesPoint {
  bucket: string;
  requests: number;
  answered: number;
  failed: number;
  averageResponseMs?: number;
  p95ResponseMs?: number;
  successRate?: number;
}

export interface Summary {
  range: string;
  from: string;
  to: string;
  environment: string;
  dataSource: string;
  requests: number;
  previousRequests: number;
  finished: number;
  answered: number;
  failed: number;
  inFlight: number;
  successRate?: number;
  previousSuccessRate?: number;
  averageResponseMs?: number;
  previousAverageResponseMs?: number;
  p50ResponseMs?: number;
  p95ResponseMs?: number;
  previousP95ResponseMs?: number;
  accuracyFulfilledRate?: number;
  sloP95Ms?: number;
  deferred: Feature;
  alerts: AlertState;
  licence: Feature;
  series: SeriesPoint[];
}

export interface ProtocolPoint {
  bucket: string;
  mapRequests: number;
  diameterRequests: number;
  unattributed: number;
}

export interface ProtocolTotals {
  map: number;
  diameter: number;
  unattributed: number;
  total: number;
}

export interface Throughput {
  range: string;
  from: string;
  to: string;
  bucketSeconds: number;
  points: ProtocolPoint[];
  totals: ProtocolTotals;
}

export interface NameCount {
  name: string;
  count: number;
}

export interface Mix {
  range: string;
  from: string;
  to: string;
  dataSource: string;
  byOperation: NameCount[];
  byMethod: NameCount[];
  byRat: NameCount[];
  byResult: NameCount[];
  fallback: Feature;
}

export interface Peer {
  face: string;
  configured: boolean;
  host?: string;
  port?: number;
  state: 'NOT_CONFIGURED' | 'IDLE' | 'UP' | 'DOWN';
  attempts: number;
  successes: number;
  failures: number;
  consecutiveFailures: number;
  lastAttemptAt?: string;
  lastSuccessAt?: string;
  lastFailureAt?: string;
  lastCode?: string;
}

export interface AlertSettings {
  enabled: boolean;
  evalSeconds: number;
  windowMinutes: number;
  minFinished: number;
  errorRateThreshold: number;
  peerFailureThreshold: number;
  cellGapThreshold: number;
  sloP95Ms?: number;
}

export interface PeerPage {
  environment: string;
  dataSource: string;
  settings: AlertSettings;
  peers: Peer[];
}

export interface Alert {
  alertId: number;
  dedupeKey: string;
  kind: 'PEER_DOWN' | 'ERROR_RATE' | 'LATENCY_SLO' | 'CELL_DB_GAP';
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  summary: string;
  detail?: Record<string, unknown>;
  openedAt: string;
  lastSeenAt: string;
  closedAt?: string;
  acknowledgedAt?: string;
  acknowledgedBy?: string;
  open: boolean;
  acknowledged: boolean;
}

export interface AlertPage {
  state: string;
  limit: number;
  evaluationEnabled: boolean;
  open: number;
  acknowledgedOpen: number;
  alerts: Alert[];
}

export interface RequestItem {
  requestId: string;
  createdAt: string;
  completedAt?: string;
  clientId: string;
  clientName: string;
  purpose: string;
  msisdn?: string;
  imsi?: string;
  state: string;
  errorCode?: string;
  protocol?: string;
  operation?: string;
  recordStatus?: string;
  resultCode?: string;
  method?: string;
  rat?: string;
  uncertaintyM?: number;
  confidence?: number;
  ageSeconds?: number;
  responseTimeMs?: number;
  accuracyFulfilled?: boolean;
  accuracyRequestedM?: number;
  latitude?: number;
  longitude?: number;
  shape?: string;
  servingNode?: string;
}

export interface RequestPage {
  range: string;
  from: string;
  to: string;
  limit: number;
  offset: number;
  total: number;
  identifiersMasked: boolean;
  dataSource: string;
  items: RequestItem[];
}

export interface Deferred {
  available: boolean;
  reason?: string;
  open: number;
  pastDeadline: number;
}

export type RangeKey = '1h' | '24h' | '7d' | '30d';

export interface DashboardData {
  summary: Summary;
  throughput: Throughput;
  mix: Mix;
  peers: PeerPage;
  alerts: AlertPage;
  requests: RequestPage;
  deferred: Deferred;
}
