// GENERATED FILE - do not edit.
// Source: api/openapi.yaml, components.schemas.
// Regenerate: mvn -o -B -pl gmlc-app test -Dtest=TypesDriftTest -Dgmlc.write.types=true

export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: ProblemCode;
}

export interface Health {
  service: string;
  version: string;
  checkedAt: string;
  database: 'UP' | 'DOWN';
  capabilities?: 'UP' | 'INVALID';
  capabilityProfileVersion?: string;
  capabilitySource?: 'bundled' | 'database';
  status: 'UP' | 'DOWN';
  reason?: string;
}

export type CapabilityProfile = Record<string, unknown>;

export interface ClientMe {
  clientId: string;
  tenantId: number;
  name: string;
  purposes: string[];
  maxAccuracyM: number;
  ratePerSecond: number;
  ratePerDay: number;
  ipAllowlist: string[];
}

export interface LocationRequestInput {
  msisdn?: string;
  imsi?: string;
  purpose: string;
  accuracyMeters?: number;
  priority?: 'LOW' | 'NORMAL' | 'HIGH';
  idempotencyKey?: string;
}

export interface LocationView {
  requestId?: string;
  state?: 'RECEIVED' | 'AUTHORISED' | 'PRIVACY_CHECKED' | 'ROUTED' | 'REQUESTED' | 'ANSWERED' | 'TIMEOUT' | 'FAILED' | 'DELIVERED';
  purpose?: string;
  priority?: string;
  accuracyRequestedM?: number;
  errorCode?: string;
  errorDetail?: string;
  createdAt?: string;
  updatedAt?: string;
  deadlineAt?: string;
  completedAt?: string;
  replayed?: boolean;
  result?: LocationResult;
}

export interface LocationResult {
  method?: string;
  shape?: 'POINT' | 'CIRCLE' | 'ELLIPSE' | 'POLYGON' | 'ARC';
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
  methodsAttempted?: string[];
}

export interface ConsentGrantRequest {
  msisdn?: string;
  imsi?: string;
  purpose: string;
  channel?: string;
  expiresAt?: string;
  evidence?: unknown;
}

export interface ConsentView {
  consentId?: string;
  subjectRef?: string;
  purpose?: string;
  status?: 'PENDING' | 'GRANTED' | 'REVOKED' | 'EXPIRED';
  grantedAt?: string;
  expiresAt?: string;
}

export interface CellPage {
  cells: CellSummary[];
  limit: number;
  offset: number;
  total: number;
}

export interface CellSummary {
  rat?: string;
  cellIdentity?: string;
  areaCode?: string;
  siteName?: string;
  latitude?: number;
  longitude?: number;
  shape?: string;
  radiusM?: number;
  dataQuality?: number;
  updatedAt?: string;
}

export type CellView = CellSummary & { plmn?: string; cellCode?: string; antennaHeightM?: number; azimuthDeg?: number; beamwidthDeg?: number; coveragePolygon?: Record<string, unknown>; pci?: number; arfcn?: number; cellType?: CellType; dataSource?: string; lastVerified?: string };

export interface CellInput {
  rat?: 'GSM' | 'UMTS' | 'LTE' | 'NR';
  plmnMcc?: string;
  plmnMnc?: string;
  areaCode?: string;
  cellCode?: string;
  siteName?: string;
  latitude?: number;
  longitude?: number;
  antennaHeightM?: number;
  azimuthDeg?: number;
  beamwidthDeg?: number;
  radiusM?: number;
  pci?: number;
  arfcn?: number;
  cellType?: CellType;
  dataSource?: string;
  dataQuality?: number;
  lastVerified?: string;
}

export interface CellImportReport {
  dryRun: boolean;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  inserted: number;
  updated: number;
  errorsTruncated: boolean;
  errors: CellImportRowError[];
}

export interface CellImportRowError {
  line: number;
  field?: string;
  message: string;
}

export interface CdrPage {
  cdrs: CdrRow[];
  limit: number;
}

export interface CdrRow {
  cdrId?: number;
  requestId?: string;
  resultCode?: string;
  recordStatus?: 'ANSWERED' | 'FAILED' | 'ABORTED' | 'TIMEOUT';
  ratType?: string;
  positioningMethod?: string;
  methodsAttempted?: string[];
  accuracyRequestedM?: number;
  accuracyAchievedM?: number;
  accuracyFulfilled?: boolean;
  responseTimeMs?: number;
  ageOfLocationS?: number;
  shape?: string;
  latitude?: number;
  longitude?: number;
  uncertaintyM?: number;
  confidence?: number;
  createdAt?: string;
}

export interface ClientRegistrationRequest {
  name: string;
  purposes: ('EMERGENCY' | 'SAFETY' | 'FLEET_MANAGEMENT' | 'COMMERCIAL_LBS' | 'IOT_ASSET' | 'TEST')[];
  maxAccuracyM?: number;
  ratePerSecond?: number;
  ratePerDay?: number;
  ipAllowlist?: string[];
}

export interface ClientRegistrationResponse {
  clientId: string;
  tenantId: number;
  name: string;
  purposes: string[];
  maxAccuracyM?: number;
  ratePerSecond?: number;
  ratePerDay?: number;
  ipAllowlist?: string[];
  apiKey: string;
  apiKeyNote?: string;
}

export interface LastKnownView {
  found: boolean;
  reason?: string;
  trackedId?: string;
  clientId?: string;
  purpose?: string;
  dataSource: 'LAB_SIM' | 'BENCH' | 'REAL';
  fix?: Fix;
}

export interface Fix {
  requestId?: string;
  resultId?: string;
  method?: string;
  methodsAttempted?: string[];
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

export interface OpsLoginRequest {
  username: string;
  password: string;
  totp: string;
}

export interface OpsSessionView {
  userId: string;
  username: string;
  displayName: string;
  role: 'ADMIN' | 'OPERATOR' | 'ANALYST' | 'VIEWER';
  permissions: ('VIEW_DASHBOARD' | 'VIEW_REQUESTS' | 'ACK_ALERTS' | 'LOCATE' | 'REVEAL_IDENTIFIERS' | 'VIEW_RAW_SIGNALLING' | 'EXPORT_CDR' | 'MANAGE_GEOFENCES' | 'MANAGE_NOTIFIERS' | 'MANAGE_CLIENTS' | 'MANAGE_CONFIG' | 'MANAGE_USERS' | 'MANAGE_LICENCE')[];
  csrfToken: string;
  expiresAt: string;
  environment: string;
  dataSource: string;
  breakGlass: boolean;
  breakGlassExpiresAt?: string;
}

export type RangeKey = '1h' | '24h' | '7d' | '30d';

export type BucketKey = '1m' | '5m' | '15m' | '30m' | '1h' | '3h' | '6h' | '12h' | '1d';

export type CellType = 'MACRO' | 'MICRO' | 'INDOOR' | 'UNKNOWN';

export interface Feature {
  available: boolean;
  reason?: string;
}

export type AlertState = Feature & { open: number; acknowledgedOpen: number };

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

export interface Deferred {
  available: boolean;
  reason?: string;
  open: number;
  pastDeadline: number;
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
  protocol?: 'MAP' | 'DIAMETER';
  operation?: string;
  recordStatus?: 'ANSWERED' | 'FAILED' | 'ABORTED' | 'TIMEOUT';
  resultCode?: string;
  method?: string;
  rat?: 'GSM' | 'UMTS' | 'LTE' | 'NR';
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
  recordStatus: 'ANSWERED' | 'FAILED' | 'ABORTED' | 'TIMEOUT';
  protocol?: 'MAP' | 'DIAMETER';
  operation?: string;
  methodsAttempted: string[];
  method?: string;
  rat?: 'GSM' | 'UMTS' | 'LTE' | 'NR';
  responseTimeMs?: number;
  accuracyRequestedM?: number;
  accuracyAchievedM?: number;
  accuracyFulfilled?: boolean;
  ageSeconds?: number;
  pagingPerformed: boolean;
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
  detail?: unknown;
}

export interface RequestDetail {
  requestId: string;
  clientId: string;
  clientName: string;
  purpose: string;
  state: string;
  errorCode?: string;
  errorDetail?: string;
  protocol?: 'MAP' | 'DIAMETER';
  rat?: 'GSM' | 'UMTS' | 'LTE' | 'NR';
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
  result?: StoredResult;
  cdr?: CdrDetail;
  timeline: TimelineEvent[];
}

export interface ExchangeItem {
  cdrId: number;
  createdAt: string;
  requestId: string;
  clientId: string;
  clientName?: string;
  protocol?: 'MAP' | 'DIAMETER';
  operation?: string;
  recordStatus: 'ANSWERED' | 'FAILED' | 'ABORTED' | 'TIMEOUT';
  resultCode?: string;
  rat?: 'GSM' | 'UMTS' | 'LTE' | 'NR';
  method?: string;
  methodsAttempted: string[];
  responseTimeMs?: number;
  pagingPerformed: boolean;
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
  protocol?: 'MAP' | 'DIAMETER';
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

export interface Peer {
  face: string;
  service: string;
  bearer: string;
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

export interface ServiceInterface {
  label: string;
  service: string;
  bearer: string;
  state: 'NOT_APPLICABLE' | 'NOT_BUILT' | 'BUILT';
  note: string;
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
  interfaces: ServiceInterface[];
}

export interface Alert {
  alertId: number;
  dedupeKey: string;
  kind: 'PEER_DOWN' | 'ERROR_RATE' | 'LATENCY_SLO' | 'CELL_DB_GAP';
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  summary: string;
  detail?: unknown;
  openedAt: string;
  lastSeenAt: string;
  closedAt?: string;
  acknowledgedAt?: string;
  acknowledgedBy?: string;
  open: boolean;
  acknowledged: boolean;
}

export interface AlertPage {
  state: 'OPEN' | 'ACKNOWLEDGED' | 'CLOSED' | 'ALL';
  limit: number;
  evaluationEnabled: boolean;
  open: number;
  acknowledgedOpen: number;
  alerts: Alert[];
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

export type GeoShape = 'CIRCLE' | 'POLYGON' | 'ELLIPSE' | 'ARC';

export interface GeofenceGeometry {
  shape: GeoShape;
  latitude?: number;
  longitude?: number;
  radiusM?: number;
  points?: { latitude: number; longitude: number }[];
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

export interface ArmView {
  geofenceId: string;
  geofenceName: string;
  subjectRef: string;
  subjectLabel: string;
  state: 'INSIDE' | 'OUTSIDE';
  armedAt: string;
  lastEvaluatedAt?: string;
  lastResultId?: string;
}

export interface ArmResponse {
  created: boolean;
  geofenceId: string;
  arm: ArmView;
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
  type?: 'ENTER' | 'LEAVE' | 'INSIDE';
  geofenceId?: string;
  limit: number;
  offset: number;
  total: number;
  items: GeofenceEvent[];
}

export interface SubscriptionView {
  subscriptionId: string;
  name: string;
  url: string;
  eventTypes: ('ENTER' | 'LEAVE' | 'INSIDE')[];
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

export interface CreatedSubscription {
  subscription: SubscriptionView;
  secret: string;
  secretNotice: string;
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
  lastFix?: TrackingFix;
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
  nextRunAt?: string;
  lastRunAt?: string;
  lastState?: 'LOCATED' | 'NO_FIX' | 'SKIPPED' | 'FAILED';
  lastError?: string;
  runs: number;
  createdAt: string;
  updatedAt: string;
}

export interface TrackingRun {
  runId: number;
  scheduleId: string;
  trigger: 'SCHEDULE' | 'MANUAL';
  state: 'LOCATED' | 'NO_FIX' | 'SKIPPED' | 'FAILED';
  requestId?: string;
  errorCode?: string;
  notifyState: 'NONE' | 'SENT' | 'FAILED' | 'NOT_CONFIGURED';
  notifyDetail?: string;
  occurredAt: string;
}

export interface NotificationChannel {
  channelId: string;
  name: string;
  type: 'WEBHOOK' | 'EMAIL' | 'TELEGRAM' | 'SLACK';
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
  status: 'PENDING' | 'APPLIED' | 'REJECTED' | 'CANCELLED';
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
  type: 'INTEGER' | 'DECIMAL' | 'TEXT';
  signalling: boolean;
  value?: unknown;
  defaultValue?: unknown;
  source: 'DEFAULT' | 'DATABASE';
  version: number;
  updatedBy?: string;
  updatedByName?: string;
  updatedAt?: string;
  min?: number;
  max?: number;
  maxLength?: number;
  pending?: ConfigChange;
}

export interface ConfigSnapshot {
  environment: string;
  secondApproverRequired: boolean;
  items: ConfigItem[];
}

export type CellPlanEntry = CellSummary;

export interface CellPlan {
  total: number;
  limit: number;
  offset: number;
  rat?: 'GSM' | 'UMTS' | 'LTE' | 'NR';
  byRat: NameCount[];
  byQuality: NameCount[];
  cells: CellPlanEntry[];
}

export interface OpsUserView {
  userId: string;
  username: string;
  displayName: string;
  role: 'ADMIN' | 'OPERATOR' | 'ANALYST' | 'VIEWER';
  enabled: boolean;
  lastLoginAt?: string;
  createdAt: string;
}

export interface CreatedOpsUser {
  user: OpsUserView;
  oneTimePassword: string;
  totpSecret: string;
}

export interface RoleView {
  role: string;
  permissions: string[];
}

export interface RoleMatrix {
  permissions: string[];
  roles: RoleView[];
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
  outcome: 'ALLOWED' | 'DENIED' | 'ERROR';
  requestId?: string;
  subjectRef?: string;
  actorType?: string;
  actorUsername?: string;
  actorDisplayName?: string;
  clientName?: string;
  remoteAddr?: string;
  detail?: unknown;
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
  reviewStatus: 'PENDING' | 'REVIEWED';
  reviewedBy?: string;
  reviewedByName?: string;
  reviewedAt?: string;
  reviewNote?: string;
  reviewDue: boolean;
}

export type ProblemCode = 'ACCOUNT_LOCKED' | 'ACCURACY_NOT_ALLOWED' | 'ALERTS_STATE_NOT_SUPPORTED' | 'ALERT_CLOSED' | 'ALREADY_ACKNOWLEDGED' | 'AUDIT_ACTION_TOO_LONG' | 'AUDIT_ACTOR_TOO_LONG' | 'AUDIT_OUTCOME_NOT_SUPPORTED' | 'AUDIT_RANGE_INVALID' | 'AUDIT_TIME_INVALID' | 'BAD_CREDENTIALS' | 'BREAK_GLASS_ACTIVE' | 'BREAK_GLASS_ALREADY_REVIEWED' | 'BREAK_GLASS_CODE_INVALID' | 'BREAK_GLASS_DISABLED' | 'BREAK_GLASS_NOT_ACTIVE' | 'BREAK_GLASS_REASON_REQUIRED' | 'BREAK_GLASS_REVIEW_NOTE_REQUIRED' | 'BREAK_GLASS_SELF_REVIEW' | 'BREAK_GLASS_STATUS_NOT_SUPPORTED' | 'BREAK_GLASS_STILL_ACTIVE' | 'BUCKET_LARGER_THAN_RANGE' | 'BUCKET_NOT_SUPPORTED' | 'BUCKET_TOO_SMALL' | 'CHANNEL_ALREADY_EXISTS' | 'CHANNEL_DISABLED' | 'CHANNEL_IN_USE' | 'CONFIG_APPROVAL_NOT_REQUIRED' | 'CONFIG_CHANGE_CLOSED' | 'CONFIG_CHANGE_PENDING' | 'CONFIG_SELF_APPROVAL' | 'CONFIG_STATUS_NOT_SUPPORTED' | 'CONFIG_VALUE_MISSING' | 'CONFIG_VALUE_RANGE' | 'CONFIG_VALUE_TYPE' | 'CONFIG_VERSION_CONFLICT' | 'CSV_EMPTY' | 'CSV_MISSING_COLUMNS' | 'CSV_TOO_LARGE' | 'CSV_TOO_MANY_ROWS' | 'DECISION_NOTE_REQUIRED' | 'EVIDENCE_NOT_SERIALISABLE' | 'EVIDENCE_TOO_LARGE' | 'GEOFENCE_ARC_INVALID' | 'GEOFENCE_DISABLED' | 'GEOFENCE_ELLIPSE_INVALID' | 'GEOFENCE_EVENT_NOT_SUPPORTED' | 'GEOFENCE_NAME_REQUIRED' | 'GEOFENCE_NAME_TAKEN' | 'GEOFENCE_NAME_TOO_LONG' | 'GEOFENCE_NEAR_POLE' | 'GEOFENCE_POINT_INVALID' | 'GEOFENCE_POLYGON_DEGENERATE' | 'GEOFENCE_POLYGON_DUPLICATE' | 'GEOFENCE_POLYGON_POINTS' | 'GEOFENCE_RADIUS_INVALID' | 'GEOFENCE_SHAPE_INVALID' | 'GEOFENCE_SHAPE_REQUIRED' | 'GEOFENCE_SHAPE_UNSUPPORTED' | 'GEOFENCE_STATE_NOT_SUPPORTED' | 'IDEMPOTENCY_KEY_TOO_LONG' | 'IMSI_NOT_IN_TENANT_PLMN' | 'INTERVAL_TOO_LONG' | 'INTERVAL_TOO_SHORT' | 'INVALID_CELL_IDENTITY' | 'INVALID_RAT' | 'LAST_ADMIN' | 'LIMIT_OUT_OF_RANGE' | 'NOTIFIER_KEY_NOT_CONFIGURED' | 'NOTIFIER_NOT_AVAILABLE' | 'NOTIFIER_URL_INVALID' | 'NOTIFY_CHANNEL_REQUIRED' | 'NOTIFY_CHANNEL_UNUSED' | 'OFFSET_OUT_OF_RANGE' | 'PERMISSION_DENIED' | 'PRIVACY_NOT_SATISFIED' | 'PROTOCOL_NOT_SUPPORTED' | 'PURPOSE_NOT_ALLOWED' | 'RANGE_NOT_SUPPORTED' | 'REASON_TOO_SHORT' | 'RECORDSTATUS_NOT_SUPPORTED' | 'REPORT_NOT_SUPPORTED' | 'SCHEDULE_ALREADY_EXISTS' | 'SCHEDULE_WINDOW_EMPTY' | 'SCHEDULE_WINDOW_INVALID' | 'SELF_DISABLE' | 'STATE_NOT_SUPPORTED' | 'SUBJECT_ALREADY_TRACKED' | 'SUBJECT_IDENTIFIER_AMBIGUOUS' | 'SUBJECT_REQUIRED' | 'SUBSCRIPTION_LIMIT_REACHED' | 'SUBSCRIPTION_NAME_REQUIRED' | 'SUBSCRIPTION_NAME_TAKEN' | 'TRACKED_SUBJECT_NOT_FOUND' | 'TRACKING_DISABLED' | 'TRACKING_PURPOSE_NOT_SUPPORTED' | 'USERNAME_EXISTS' | 'WEBHOOK_EVENT_TYPES_REQUIRED' | 'WEBHOOK_EVENT_TYPE_NOT_SUPPORTED' | 'WEBHOOK_URL_INVALID' | 'WEBHOOK_URL_REQUIRED';
