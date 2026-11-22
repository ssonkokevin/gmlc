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
  breakGlass?: boolean;
  breakGlassExpiresAt?: string;
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

export type GeoShape = 'CIRCLE' | 'POLYGON' | 'ELLIPSE' | 'ARC';

export interface GeofenceGeometry {
  shape: GeoShape;
  latitude?: number;
  longitude?: number;
  radiusM?: number;
  points?: Array<{ latitude: number; longitude: number }>;
  semiMajorM?: number;
  semiMinorM?: number;
  orientationDeg?: number;
  outerRadiusM?: number;
  innerRadiusM?: number;
  startAngleDeg?: number;
  includedAngleDeg?: number;
}

export interface GeofenceView {
  geofenceId: string;
  name: string;
  description?: string;
  group?: string;
  shape: GeoShape;
  geometry: GeofenceGeometry;
  reportInside: boolean;
  enabled: boolean;
  armed: number;
  inside: number;
  createdAt: string;
  updatedAt: string;
}

export interface GeofenceCounts {
  total: number;
  enabled: number;
  armed: number;
  inside: number;
  events: number;
  entering: number;
  leaving: number;
  insideEvents: number;
}

export interface GeofenceSummary {
  range: string;
  from: string;
  to: string;
  counts: GeofenceCounts;
  reporting: Record<string, Feature>;
}

export interface GeofenceEvent {
  eventId: number;
  geofenceId: string;
  geofenceName: string;
  subjectLabel: string;
  eventType: 'ENTER' | 'LEAVE' | 'INSIDE';
  occurredAt: string;
  resultId: string;
  requestId: string;
  latitude: number;
  longitude: number;
  uncertaintyM: number;
  method: string;
}

export interface GeofenceEventPage {
  range: string;
  from: string;
  to: string;
  type?: string;
  geofenceId?: string;
  limit: number;
  offset: number;
  total: number;
  items: GeofenceEvent[];
}

export interface ArmView {
  geofenceId: string;
  geofenceName: string;
  subjectRef: string;
  subjectLabel: string;
  state?: 'INSIDE' | 'OUTSIDE';
  armedAt: string;
  lastEvaluatedAt?: string;
  lastResultId?: string;
}

export interface SubscriptionView {
  subscriptionId: string;
  name: string;
  url: string;
  eventTypes: string[];
  geofenceId?: string;
  geofenceName?: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  pending: number;
  delivered: number;
  failed: number;
  lastDeliveredAt?: string;
}

export interface DeliveryView {
  deliveryId: number;
  eventId: number;
  status: 'PENDING' | 'DELIVERED' | 'FAILED';
  attempts: number;
  lastStatusCode?: number;
  lastError?: string;
  createdAt: string;
  nextAttemptAt: string;
  deliveredAt?: string;
}

export interface CreatedSubscription {
  subscription: SubscriptionView;
  secret: string;
  secretNotice: string;
}

export interface TrackingFix {
  requestId: string;
  resultId: string;
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

export interface TrackedSubject {
  trackedId: string;
  clientId: string;
  clientName: string;
  subjectLabel: string;
  purpose: string;
  enabled: boolean;
  fixes: number;
  lastFix?: TrackingFix | null;
  createdAt: string;
  updatedAt: string;
}

export interface TrackingSummary {
  total: number;
  enabled: number;
  withFix: number;
  fixes: number;
  schedules: number;
  schedulesEnabled: number;
  runs: number;
  channels: number;
  notifierConfigured: boolean;
  dataSource: string;
  reporting: Record<string, Feature>;
}

export interface TrackingSchedule {
  scheduleId: string;
  trackedId: string;
  tenantId: number;
  clientId: string;
  clientName: string;
  subjectLabel: string;
  purpose: string;
  intervalSeconds: number;
  startsAt: string;
  endsAt?: string;
  accuracyM?: number;
  notifyOnFix: boolean;
  channelId?: string;
  channelName?: string;
  enabled: boolean;
  nextRunAt: string;
  lastRunAt?: string;
  lastState?: string;
  lastError?: string;
  runs: number;
  createdAt: string;
  updatedAt: string;
}

export interface TrackingRun {
  runId: number;
  scheduleId: string;
  trigger: 'SCHEDULE' | 'MANUAL' | string;
  state: 'LOCATED' | 'NO_FIX' | 'SKIPPED' | 'FAILED' | string;
  requestId?: string;
  errorCode?: string;
  notifyState: 'NONE' | 'SENT' | 'FAILED' | 'NOT_CONFIGURED' | string;
  notifyDetail?: string;
  occurredAt: string;
}

export interface NotificationChannel {
  channelId: string;
  name: string;
  type: string;
  url: string;
  enabled: boolean;
  schedules: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreatedChannel {
  channel: NotificationChannel;
  secret: string;
  secretNotice: string;
}

export interface ConfigChange {
  changeId: string;
  itemKey: string;
  baseVersion: number;
  previousValue?: unknown;
  requestedValue?: unknown;
  reason: string;
  status: 'PENDING' | 'APPLIED' | 'REJECTED' | 'CANCELLED' | string;
  requiresApproval: boolean;
  requestedBy: string;
  requestedByName: string;
  requestedAt: string;
  decidedBy?: string;
  decidedByName?: string;
  decidedAt?: string;
  decisionNote?: string;
  appliedVersion?: number;
  appliedAt?: string;
}

export interface ConfigItem {
  key: string;
  label: string;
  description: string;
  type: 'INTEGER' | 'DECIMAL' | 'TEXT' | string;
  signalling: boolean;
  value: string | number;
  defaultValue: string | number;
  source: 'DEFAULT' | 'DATABASE' | string;
  version: number;
  updatedBy?: string;
  updatedByName?: string;
  updatedAt?: string;
  min?: number;
  max?: number;
  maxLength?: number;
  pending?: ConfigChange | null;
}

export interface ConfigSnapshot {
  environment: string;
  secondApproverRequired: boolean;
  items: ConfigItem[];
}

export interface CellPlanEntry {
  rat: string;
  cellIdentity: string;
  areaCode: string;
  siteName?: string;
  latitude?: number;
  longitude?: number;
  shape: string;
  radiusM?: number;
  dataQuality?: number;
  updatedAt: string;
}

export interface CellPlan {
  total: number;
  limit: number;
  offset: number;
  rat?: string;
  byRat: NameCount[];
  byQuality: NameCount[];
  cells: CellPlanEntry[];
}

export interface OpsUserView {
  userId: string;
  username: string;
  displayName: string;
  role: string;
  enabled: boolean;
  lastLoginAt?: string;
  createdAt: string;
}

export interface CreatedOpsUser {
  user: OpsUserView;
  oneTimePassword: string;
  totpSecret: string;
}

export interface RoleMatrix {
  permissions: string[];
  roles: Array<{ role: string; permissions: string[] }>;
}

export interface ActiveSession {
  sessionId: string;
  userId: string;
  username: string;
  displayName: string;
  role: string;
  remoteAddr?: string;
  createdAt: string;
  lastSeenAt: string;
  expiresAt: string;
  current: boolean;
}

export interface AuditRow {
  auditId: number;
  occurredAt: string;
  action: string;
  outcome: string;
  requestId?: string;
  subjectRef?: string;
  actorType: 'OPERATOR' | 'CLIENT' | 'SYSTEM' | string;
  actorUsername?: string;
  actorDisplayName?: string;
  clientName?: string;
  remoteAddr?: string;
  detail?: Record<string, unknown> | null;
}

export interface AccessPolicy {
  mfaRequired: boolean;
  passwordMinLength: number;
  totpDigits: number;
  totpPeriodSeconds: number;
  totpIssuer: string;
  lockoutThreshold: number;
  lockoutMinutes: number;
  sessionIdleMinutes: number;
  sessionAbsoluteHours: number;
  passwordHash: string;
  argon2MemoryKib: number;
  argon2Iterations: number;
  argon2Parallelism: number;
  breakGlassMinutes: number;
  breakGlassConfigured: boolean;
  breakGlassScope: string;
}

export interface AdminClient {
  clientId: string;
  name: string;
  purposes: string[];
  maxAccuracyM: number;
  ratePerSecond: number;
  ratePerDay: number;
  ipAllowlist: string[];
  enabled: boolean;
  createdAt: string;
}

export interface BreakGlassGrant {
  grantId: string;
  userId: string;
  username: string;
  displayName: string;
  sessionId?: string;
  reason: string;
  grantedAt: string;
  expiresAt: string;
  endedAt?: string;
  active: boolean;
  reviewStatus: 'PENDING' | 'REVIEWED' | string;
  reviewedBy?: string;
  reviewedByName?: string;
  reviewedAt?: string;
  reviewNote?: string;
  reviewDue: boolean;
}
