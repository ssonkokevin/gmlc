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

export interface ClientView {
  clientId: string;
  name: string;
  purposes: string[];
  maxAccuracyM: number;
}

export interface Submission {
  requestId: string;
  state: string;
  purpose: string;
  priority: string;
  accuracyRequestedM?: number;
  errorCode?: string;
  errorDetail?: string;
  createdAt: string;
  updatedAt: string;
  deadlineAt?: string;
  completedAt?: string;
  replayed: boolean;
}

export interface Identifier {
  msisdn?: string;
  imsi?: string;
  masked: boolean;
}

export interface StoredResult {
  resultId: string;
  requestId: string;
  method: string;
  methodsAttempted: string[];
  shape?: string;
  latitude?: number;
  longitude?: number;
  uncertaintyM?: number;
  confidence?: number;
  ageSeconds?: number;
  accuracyRequestedM?: number;
  accuracyFulfilled?: boolean;
  servingCell?: string;
  engine?: string;
  positioningNode?: string;
  producedAt?: string;
}

export interface CdrDetail {
  cdrId: number;
  resultCode?: string;
  recordStatus?: string;
  protocol?: string;
  operation?: string;
  methodsAttempted?: string[];
  method?: string;
  rat?: string;
  responseTimeMs?: number;
  accuracyRequestedM?: number;
  accuracyAchievedM?: number;
  accuracyFulfilled?: boolean;
  ageSeconds?: number;
  pagingPerformed?: boolean;
  servingNode?: string;
  positioningNode?: string;
  uncertaintyM?: number;
  confidence?: number;
  shape?: string;
  latitude?: number;
  longitude?: number;
  createdAt?: string;
}

export interface TimelineEvent {
  at: string;
  action: string;
  outcome?: string;
  source?: string;
  detail?: Record<string, unknown> | null;
}

export interface RequestDetail {
  requestId: string;
  clientId: string;
  clientName: string;
  purpose: string;
  state: string;
  errorCode?: string;
  errorDetail?: string;
  protocol?: string;
  rat?: string;
  servingNode?: string;
  positioningNode?: string;
  accuracyRequestedM?: number;
  priority?: string;
  createdAt: string;
  updatedAt: string;
  deadlineAt?: string;
  completedAt?: string;
  resultId?: string;
  environment: string;
  dataSource: string;
  identifiers: Identifier;
  result?: StoredResult | null;
  cdr?: CdrDetail | null;
  timeline: TimelineEvent[];
}

export interface ExchangeItem {
  cdrId: number;
  createdAt: string;
  requestId: string;
  clientId: string;
  clientName?: string;
  protocol?: string;
  operation?: string;
  recordStatus: string;
  resultCode?: string;
  rat?: string;
  method?: string;
  methodsAttempted: string[];
  responseTimeMs?: number;
  pagingPerformed?: boolean;
  servingNode?: string;
  positioningNode?: string;
  uncertaintyM?: number;
  confidence?: number;
  ageSeconds?: number;
  accuracyRequestedM?: number;
  accuracyAchievedM?: number;
  accuracyFulfilled?: boolean;
  shape?: string;
  latitude?: number;
  longitude?: number;
}

export interface ExchangePage {
  range: string;
  from: string;
  to: string;
  limit: number;
  offset: number;
  total: number;
  dataSource: string;
  rawPayload: Feature;
  items: ExchangeItem[];
}

export interface ExchangeBucket {
  bucket: string;
  answered: number;
  failed: number;
  averageResponseMs?: number;
  p95ResponseMs?: number;
}

export interface ExchangeTotals {
  finished: number;
  answered: number;
  failed: number;
  averageResponseMs?: number;
  p50ResponseMs?: number;
  p95ResponseMs?: number;
}

export interface ExchangeSeries {
  range: string;
  from: string;
  to: string;
  bucketSeconds: number;
  protocol?: string;
  dataSource: string;
  points: ExchangeBucket[];
  totals: ExchangeTotals;
  byProtocol: NameCount[];
}

export interface ClientReport {
  name: string;
  requests: number;
  finished: number;
  answered: number;
  failed: number;
  inFlight: number;
  successRate?: number;
  averageResponseMs?: number;
  p95ResponseMs?: number;
}

export interface RatReport {
  name: string;
  answered: number;
  failed: number;
  total: number;
  averageResponseMs?: number;
  p95ResponseMs?: number;
}

export interface MethodReport {
  name: string;
  count: number;
  answered: number;
  averageResponseMs?: number;
  averageUncertaintyM?: number;
  averageAgeSeconds?: number;
}

export interface CellReport {
  name: string;
  results: number;
  averageUncertaintyM?: number;
  p95UncertaintyM?: number;
  averageAgeSeconds?: number;
  accuracyFulfilledRate?: number;
}

export interface ReportTotals {
  requests: number;
  finished: number;
  answered: number;
  failed: number;
  inFlight: number;
  averageResponseMs?: number;
  p50ResponseMs?: number;
  p95ResponseMs?: number;
  successRate?: number;
  accuracyFulfilledRate?: number;
}

export interface Reports {
  range: string;
  from: string;
  to: string;
  environment: string;
  dataSource: string;
  totals: ReportTotals;
  byClient: ClientReport[];
  byPurpose: ClientReport[];
  byRat: RatReport[];
  byMethod: MethodReport[];
  byCell: CellReport[];
  notes: string[];
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
