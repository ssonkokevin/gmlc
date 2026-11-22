# GMLC - Gateway Mobile Location Centre

The location gateway: the Le API for clients, client and consent
governance, the authoritative cell database, CDRs, and the signalling towards
the HSS, MME, HLR and MSC/SGSN.

It is a separate service from the core (`github.com/ssonkokevin/5gcore`, where
the MME LCS function, E-SMLC and LMF live). The two talk over standard 3GPP
interfaces only - SLh and SLg over Diameter, MAP for 2G/3G, and `Ngmlc_Location`
over SBI - and share no code.

The split is recorded with its reasoning in the core repository under
`docs/location/DECISIONS.md` (decisions D1-D17).

## Status

| Area | State |
|---|---|
| Spring Boot service, PostgreSQL, Flyway | **Built and tested** on this host |
| `/v1/health`, `/v1/capabilities`, `/v1/clients/me` | **Built and tested** |
| Tenant-aware schema (tenants, clients, consent, cells, requests, results, CDRs, audit, usage) | **Built**; migrations apply against PostgreSQL 17 in tests |
| Client registration, API keys, address allowlists | **Built and tested** |
| Per-second and per-day quotas, audited refusals | **Built and tested** |
| Consent: grant, read, revoke, expiry, emergency override | **Built and tested** |
| Le location API, request state machine, CDRs | **Built and tested**; answers cell-ID from the cell plan over Diameter or MAP, fails closed otherwise |
| Authoritative cell database, coverage geometry, CSV import with dry-run | **Built and tested** |
| Diameter SLh RIR and SLg PLR/PLA clients, CER/CEA, TCP framing | **Built and tested**; fail closed until a peer is configured |
| MAP codecs (SRILCS, PSL, ATI), GAD circles, SCCP and M3UA framing | **Built and tested** |
| MAP service location: SRILCS then PSL at the MSC/SGSN, or ATI for an EPS subscriber | **Built and tested**; selected with `gmlc.location.transport=MAP` |
| MAP SLR (network-initiated service response) | Not yet |
| Diameter Sh (UDR) | Deferred - TS 29.329 not on the build host; codes are not guessed |
| Dashboard | **Built and tested**; static page, schematic map, no basemap |
| Operator console identity: Argon2id, TOTP, sessions, CSRF, roles, lockout, audit | **Built and tested** |
| Operator console reads: summary, throughput, mix, requests, detail, deferred | **Built and tested** (phase C1b) |
| Peer health from observed exchanges, alert engine, acknowledgement | **Built and tested** (phase C1c) |
| Operator console UI (React): shell, sign-in, Operations Dashboard | **Built and tested** (phase C1d) |
| Console Location Request: client picker, governed submission, state, result envelope, timeline | **Built and tested** (phase C2) |
| Console Monitoring & Tracing: stored exchanges, filters, rate chart, peer state, CSV export | **Built and tested** (phase C3) |
| Console Reports: client, purpose, RAT, method and cell aggregates with CSV export | **Built and tested** (phase C3) |
| Geofences: TS 23.032 shapes, consent-checked arms, ENTER/LEAVE/INSIDE from stored results | **Built and tested** (phase C4a) |
| Event webhooks: HMAC-signed deliveries, per-subscription filters, retries with backoff | **Built and tested** (phase C4b) |
| Console Geofencing screen: shapes, arms, event tabs, webhooks, honesty panel | **Built and tested** (phase C4c) |
| Tracking roster: consented subjects, stored-result trails, client last-known | **Built and tested** (phase C5a) |
| Automated tracking schedules: window, interval floor, run log, manual run | **Built and tested** (phase C5b) |
| Notifier channels: sealed signing secrets, signed WEBHOOK delivery only | **Built and tested** (phase C5b); EMAIL, TELEGRAM and SLACK answer `NOTIFIER_NOT_AVAILABLE` |
| Console Tracking screen: subjects, stored-result trails, schedules, channels | **Built and tested** (phase C5c) |
| Console Administration (configuration, four-eyes) | Not yet (phase C6) |
| Container build | Written, **unverified** - no Docker on the build host |

Nothing in this repository has talked to a real operator network.

## Endpoints today

```
GET  /                   the API-key client dashboard (static page)        (open)
GET  /console/           the operator console (React bundle, static)       (open, sign-in inside)
GET  /v1/health          liveness, database, capability profile validity   (open)
GET  /v1/capabilities    the tenant's capability profile                   (API key)
GET  /v1/clients/me      the calling client's own configuration            (API key)

POST /v1/locations       submit a location request                         (API key)
GET  /v1/locations/{id}  its state, failure code, timestamps and result       (API key)
GET  /v1/subscribers/{trackedId}/last-known  the caller's tracked subject   (API key)

POST /v1/consents        record consent for a subject and purpose          (API key)
GET  /v1/consents/{id}   status of a consent record                        (API key)
DELETE /v1/consents/{id} revoke it                                         (API key)

GET  /v1/cdrs            the caller's own completed records                (API key)

GET  /v1/cells?rat=&area=      list cells, paged, no geometry rings        (API key)
GET  /v1/cells/{rat}/{id}      one cell with its coverage geometry         (API key)

POST /v1/ops/session     operator sign-in: password plus TOTP              (open, rate-limited by lockout)
DELETE /v1/ops/session   operator sign-out                                 (session + CSRF)
GET  /v1/ops/me          the signed-in operator and its permissions        (session)

GET  /v1/ops/users       list operator accounts                            (session, MANAGE_USERS)
POST /v1/ops/users       create one; returns its password and TOTP once    (session, MANAGE_USERS)
PATCH /v1/ops/users/{id} change role or enabled state                      (session, MANAGE_USERS)
POST /v1/ops/users/{id}/sign-out  end every session that user holds        (session, MANAGE_USERS)
POST /v1/ops/users/{id}/password  set a new password                       (session, MANAGE_USERS)

GET  /v1/ops/summary?range=1h|24h|7d|30d   KPI totals, deltas, chart points (session, VIEW_DASHBOARD)
GET  /v1/ops/throughput?range=&bucket=     MAP vs DIAMETER per bucket      (session, VIEW_DASHBOARD)
GET  /v1/ops/mix?range=                    operation, method, RAT, result     (session, VIEW_DASHBOARD)
GET  /v1/ops/deferred                      the honest state of LDR support    (session, VIEW_DASHBOARD)
GET  /v1/ops/peers                         observed peer state and thresholds (session, VIEW_DASHBOARD)
GET  /v1/ops/alerts?state=&limit=          stored alerts, deduplicated while open (session, VIEW_DASHBOARD)
POST /v1/ops/alerts/{id}/acknowledge       record the person, keep it open    (session, ACK_ALERTS + CSRF)
GET  /v1/ops/requests?range=&state=&protocol=&purpose=&clientId=&recordStatus=&limit=&offset=
                                           paged request list, masked        (session, VIEW_REQUESTS)
GET  /v1/ops/requests/{id}?reveal=false    one request, result, CDR, timeline (session, VIEW_REQUESTS;
                                           reveal also needs REVEAL_IDENTIFIERS)
GET  /v1/ops/clients                       enabled clients of this tenant     (session, LOCATE)
POST /v1/ops/locations                     governed request from the console  (session, LOCATE + CSRF)

GET  /v1/ops/exchanges?range=&protocol=&recordStatus=&requestId=&limit=&offset=
                                           paged stored exchanges, masked      (session, VIEW_REQUESTS)
GET  /v1/ops/exchanges/series?range=&protocol=  answered/failed per bucket     (session, VIEW_REQUESTS)
GET  /v1/ops/exchanges/export.csv?...      the same list as CSV                (session, EXPORT_CDR)
GET  /v1/ops/reports?range=                client, purpose, RAT, method, cell  (session, VIEW_DASHBOARD)
GET  /v1/ops/reports/export.csv?range=&report=clients|purposes|rat|method|cell
                                           one report section as CSV           (session, EXPORT_CDR)

GET  /v1/ops/geofences                     definitions with armed/inside counts  (session, VIEW_DASHBOARD)
GET  /v1/ops/geofences/summary?range=      counts and the honest reporting state (session, VIEW_DASHBOARD)
GET  /v1/ops/geofences/arms?state=INSIDE|OUTSIDE  current arms, masked label    (session, VIEW_DASHBOARD)
GET  /v1/ops/geofences/events?range=&type=&geofenceId=&limit=&offset=
                                           ENTER/LEAVE/INSIDE event feed        (session, VIEW_REQUESTS)
GET  /v1/ops/geofences/{id}                one definition                       (session, VIEW_DASHBOARD)
GET  /v1/ops/geofences/{id}/arms           the subjects armed on it             (session, VIEW_DASHBOARD)
POST /v1/ops/geofences                     create (circle/polygon/ellipse/arc)  (session, MANAGE_GEOFENCES + CSRF)
PATCH /v1/ops/geofences/{id}               rename, regroup, reportInside, enable (session, MANAGE_GEOFENCES + CSRF)
POST /v1/ops/geofences/{id}/arm            arm a consented subject              (session, MANAGE_GEOFENCES + CSRF)
DELETE /v1/ops/geofences/{id}/arms/{subjectRef}  disarm                         (session, MANAGE_GEOFENCES + CSRF)

GET  /v1/ops/subscriptions                 webhook subscriptions, no secret      (session, VIEW_DASHBOARD)
GET  /v1/ops/subscriptions/{id}            one subscription                     (session, VIEW_DASHBOARD)
GET  /v1/ops/subscriptions/{id}/deliveries recent delivery attempts             (session, VIEW_REQUESTS)
POST /v1/ops/subscriptions                 create; returns the signing secret once (session, MANAGE_GEOFENCES + CSRF)
PATCH /v1/ops/subscriptions/{id}           rename, re-target, filter, disable    (session, MANAGE_GEOFENCES + CSRF)
DELETE /v1/ops/subscriptions/{id}          remove subscription and its log       (session, MANAGE_GEOFENCES + CSRF)

GET  /v1/ops/tracking                      the roster with fixes and last fix    (session, VIEW_REQUESTS)
GET  /v1/ops/tracking/summary              counts and the honest tracking state  (session, VIEW_DASHBOARD)
GET  /v1/ops/tracking/{id}                 one subject with its latest fix       (session, VIEW_REQUESTS)
GET  /v1/ops/tracking/{id}/trail?limit=    stored results, newest first          (session, VIEW_REQUESTS)
POST /v1/ops/tracking                      add a consented subject               (session, LOCATE + CSRF)
PATCH /v1/ops/tracking/{id}                pause or resume the subject           (session, LOCATE + CSRF)
DELETE /v1/ops/tracking/{id}               remove the roster row                 (session, LOCATE + CSRF)

GET  /v1/ops/tracking/schedules            schedules with window and last state  (session, VIEW_REQUESTS)
GET  /v1/ops/tracking/schedules/{id}       one schedule                          (session, VIEW_REQUESTS)
GET  /v1/ops/tracking/schedules/{id}/runs  attempt log, newest first             (session, VIEW_REQUESTS)
POST /v1/ops/tracking/schedules            create a window and interval          (session, LOCATE + CSRF)
PATCH /v1/ops/tracking/schedules/{id}      pause or resume                       (session, LOCATE + CSRF)
DELETE /v1/ops/tracking/schedules/{id}     remove a schedule and its run log     (session, LOCATE + CSRF)
POST /v1/ops/tracking/schedules/{id}/run   run once now, recorded as MANUAL      (session, LOCATE + CSRF)
GET  /v1/ops/tracking/channels             channels without their secret         (session, VIEW_REQUESTS)
GET  /v1/ops/tracking/channels/{id}        one channel without its secret        (session, VIEW_REQUESTS)
POST /v1/ops/tracking/channels             create; returns the secret once       (session, MANAGE_NOTIFIERS + CSRF)
PATCH /v1/ops/tracking/channels/{id}       enable or disable                     (session, MANAGE_NOTIFIERS + CSRF)
DELETE /v1/ops/tracking/channels/{id}      remove; refused while in use          (session, MANAGE_NOTIFIERS + CSRF)

POST /v1/admin/clients   register a client and mint its API key            (X-Admin-Key)
POST /v1/admin/cells     insert or update one cell                         (X-Admin-Key)
POST /v1/admin/cells/import  CSV import, dry-run unless ?dryRun=false     (X-Admin-Key)
```

API keys are presented as `Authorization: Bearer gk_...` or `X-API-Key`. The
registry keeps only the SHA-256 hash, and the plaintext is shown once, at
registration. Missing or unknown keys answer 401; a client outside its address
allowlist answers 403; an exhausted quota answers 429 with `Retry-After`
(one second for the burst window, the time to UTC midnight for the daily one).
Every refusal is written to `audit_event` with the reason and no coordinates.

`POST /v1/admin/clients` requires `GMLC_ADMIN_KEY` from the environment. With
no key configured the endpoint answers 404, so an unfinished deployment cannot
mint credentials. Purposes are restricted to the operator's list - law
enforcement interception is not one of them.

```bash
curl -s -X POST localhost:8082/v1/admin/clients \
  -H "X-Admin-Key: $GMLC_ADMIN_KEY" -H 'content-type: application/json' \
  -d '{"name":"ops-console","purposes":["FLEET_MANAGEMENT"],"ratePerSecond":5,"ratePerDay":1000}'
```

The capability profile is the promise the API makes: which methods each RAT
can use, which of those are actually built, and whether the network supports
LPP, LPPa or NRPPA. A profile that fails validation is not served - the gateway
answers 503 rather than claim a method it cannot deliver.

`X-Capability-Source: bundled|database` says where the served profile came
from.

## Dashboard

`GET /` serves a self-contained page from `resources/static/` that talks to the
same Le API with an API key pasted by the operator. The key lives in that tab's
`sessionStorage`, is sent only as a bearer header to this origin, and is gone
when the tab closes; the page embeds no credential and loads no third-party
asset.

It shows:

- the cell plan as a **schematic** - cell centres and plan radii on a local
  equirectangular projection, with a scale bar and no basemap. The page says
  so: the circles are the plan's geometry, not a measurement;
- a locate form that records consent first (or uses an existing record) and
  submits the Le request with an `Idempotency-Key`;
- the result envelope: method, engine, the uncertainty circle, confidence,
  age, serving cell, accuracy asked and fulfilled, and the methods attempted,
  or the failure code with its explanation;
- the request log from `/v1/cdrs`, newest first. Clicking a row fetches
  `/v1/locations/{id}` and shows the same envelope.

The three files carry a strict content-security policy (`default-src 'none'`,
`script-src 'self'`), `nosniff`, `no-referrer` and `no-store`; see
`web/DashboardHeadersFilter`. The page itself is served without a key, but it
opens no data path: every API call still needs one, and that contract is
covered by `DashboardTest`.

Limits, stated plainly: there is no basemap (tiles would be a third-party
request and a licensing question), the map draws the first 500 cells of the
plan, there is no server-side session, and the locate form cannot produce a
position when no signalling peer is configured - it shows `NO_LCS_SOURCE`,
which is the same answer the API gives.

## Operator console identity (phase C1)

Operators are people with accounts, not clients with API keys. The identity
layer and the dashboard's read endpoints are built; the React console that
consumes them follows. Two surfaces exist today:

- `/client` (the page above) is for customers who hold their own API key;
- `/v1/ops/**` is for operators and uses a session, never an API key. The API
  key filter deliberately skips the prefix, and the operator filter ignores
  `Authorization` entirely, so a client key cannot open the console.

**Sign-in.** Username, password (Argon2id, OWASP defaults: 19 MiB, 2 passes,
1 lane, 16-byte salt, 32-byte hash, PHC string) and a six-digit TOTP code
(RFC 6238, HMAC-SHA1, 30-second step, one step of clock skew). A wrong
username burns the same Argon2 cost as a wrong password. Three or five
failures (`GMLC_OPS_LOCKOUT_THRESHOLD`) lock the account for
`GMLC_OPS_LOCKOUT_MINUTES`, and the response is `423` with `Retry-After`.

**Session.** A 256-bit random token in the cookie `GMLC_OPS`
(`HttpOnly; Secure; SameSite=Strict; Path=/v1/ops`). Only its SHA-256 is
stored. The cookie is `Secure` by default; a plain-HTTP lab must set
`GMLC_OPS_COOKIE_SECURE=false` explicitly or the browser will not return it.
Two clocks apply: idle (`GMLC_OPS_SESSION_IDLE_MINUTES`, default 15, sliding)
and absolute (`GMLC_OPS_SESSION_ABSOLUTE_HOURS`, default 8). An expired,
revoked or disabled session is 401 on the next call.

**CSRF.** The session row also holds a CSRF token. Sign-in returns it in the
JSON body (never in a script-readable cookie); every `POST`, `PUT`, `PATCH`
and `DELETE` under `/v1/ops` must echo it in `X-CSRF-Token`, or the answer is
`403 CSRF_REQUIRED`.

**Roles.** `ADMIN`, `OPERATOR`, `ANALYST`, `VIEWER`, each an explicit set in
`ops/OpsRole`:

| Permission | ADMIN | OPERATOR | ANALYST | VIEWER |
|---|---|---|---|---|
| VIEW_DASHBOARD, VIEW_REQUESTS | yes | yes | yes | yes |
| ACK_ALERTS | yes | yes | no | no |
| LOCATE, REVEAL_IDENTIFIERS, VIEW_RAW_SIGNALLING | yes | yes | no | no |
| MANAGE_GEOFENCES | yes | yes | no | no |
| EXPORT_CDR | yes | no | yes | no |
| MANAGE_CLIENTS, MANAGE_CONFIG, MANAGE_USERS, MANAGE_LICENCE | yes | no | no | no |

Routes declare `@OpsRequires(...)`; an interceptor enforces it and audits
every denial. The last enabled administrator cannot be disabled or demoted,
and an operator cannot disable their own account.

**Audit.** Sign-ins (allowed and denied, with reason and remote address),
sign-outs, user creation, role and enablement changes, password changes and
forced sign-outs are written to `audit_event.actor_user_id`. No audit row ever
holds a password, TOTP secret or position.

**Seeding.** Set `GMLC_OPS_BOOTSTRAP_USER`, `GMLC_OPS_BOOTSTRAP_PASSWORD` and
`GMLC_OPS_BOOTSTRAP_TOTP_SECRET` once. The first start creates that
administrator if the username does not exist and never updates it later, so a
stale environment value cannot reset a live account. With any of the three
blank, no account is created and the console stays closed.

**Masking.** `ops/OpsMasking` keeps the first five and last two characters of
MSISDN, IMSI and IMEI. Revealing is a permission and writes an audit row; the
rule is covered by unit tests and applies to every list row. On a request
detail `?reveal=true` needs `REVEAL_IDENTIFIERS`; a refusal is audited as
`OPS_IDENTIFIER_REVEAL` with outcome `DENIED`, an allowed reveal with
`ALLOWED`, and the identifier itself is never written to the trail.

**Dashboard reads (phase C1b).** Four windows (`1h`, `24h`, `7d`, `30d`) with
default buckets of 5m, 1h, 6h and 1d; `bucket=` overrides one of `1m`..`1d`,
refused with `BUCKET_TOO_SMALL` beyond 1000 points. A request is the spine of
every figure: success rate divides answered by *finished* work, a request with
no CDR yet counts as in flight, and p50/p95 are computed from recorded response
times. `GMLC_OPS_SLO_P95_MS` supplies the objective; while it is 0 the console
shows the percentiles and makes no SLO claim.

Every response carries an honesty label. `GMLC_OPS_ENVIRONMENT` is `LAB`,
`BENCH` or `PRODUCTION`; `dataSource` in the body reads `LAB_SIM`, `BENCH` or
`REAL` accordingly, and an unset value is treated as `LAB_SIM`. The protocol
(`MAP`/`DIAMETER`) is recorded when the request is created and the operation
when the CDR is written; a CDR with no recognised operation appears as
`UNATTRIBUTED` rather than being guessed into one.

A feature that is not built says so: `deferred` and `licence` return
`available: false` with a reason, and `deferred` additionally reports the real
`open` and `pastDeadline` counts. Alerts are real since phase C1c: `alerts`
reports the open and acknowledged-open counts, or `available: false` with a
reason when the evaluator is switched off. The figures are aggregate SQL over
stored rows; `OpsDashboardTest` recomputes each of them with direct SQL and
fails if the endpoint and the database disagree.

**Peers and alerts (phase C1c).** The transport wrappers record one outcome per
exchange, so `GET /v1/ops/peers` reports `IDLE` before any traffic, `UP` when
the last exchange succeeded and `DOWN` when it failed, with the running
counters, the consecutive-failure streak, the last exchange times and the last
error code. An unconfigured face is `NOT_CONFIGURED`; point codes are never
returned. The same response shows the thresholds in force.

`OpsAlertEngine` evaluates four conditions from stored rows every
`GMLC_OPS_ALERTS_EVAL_SECONDS` over the last
`GMLC_OPS_ALERTS_WINDOW_MINUTES`: `PEER_DOWN` (a configured face with
`GMLC_OPS_ALERTS_PEER_FAILURES` consecutive failures), `ERROR_RATE` (failed
share at or above `GMLC_OPS_ALERTS_ERROR_RATE`, judged only after
`GMLC_OPS_ALERTS_MIN_FINISHED` finished requests), `LATENCY_SLO` (measured p95
above `GMLC_OPS_SLO_P95_MS`, only while that objective is non-zero) and
`CELL_DB_GAP` (failed requests whose result code says the cell plan cannot
answer). One open row per condition: a repeated evaluation refreshes
`lastSeenAt` instead of adding rows, and the engine closes a condition it no
longer observes. Acknowledging with `ACK_ALERTS` records the operator and
writes `OPS_ALERT_ACKNOWLEDGED`; it never closes the alert.

Honest limits: the console UI covers phases C1 to C5; OIDC and scheduled
reports are not built yet. A
tracked subject is a roster row over stored results, not a live network feed,
and a schedule places governed requests on a timer rather than subscribing to
the network. Those arrive with their own phases and are not claimed here. Peer
`DOWN` requires observed exchange failures - a gateway that has never sent to a
peer reports `IDLE`, never a health claim.

**Console UI (phase C1d shell, phase C2 Location Request, phase C3 Monitoring
and Reports, phase C4 Geofencing, phase C5 Tracking).** `/console/` serves
the operator console: a React
19 + TypeScript bundle built by Vite into
`gmlc-app/src/main/resources/static/console`. The shell has the sidebar
sections (Operations, Location intelligence, Administration), a top bar with
breadcrumb, deployment badge (`environment` and `dataSource` from the session),
search, alert count, help and the account menu. Sign-in posts to
`/v1/ops/session`; the CSRF token stays in memory and is echoed as
`X-CSRF-Token`; the session cookie stays HttpOnly.

The Operations Dashboard reads the phase C1b/C1c endpoints for the chosen
window: six KPI cards with sparklines and deltas, throughput split by MAP,
DIAMETER and unattributed, an operation-mix donut with positioning method and
result codes, peer health, active alerts with acknowledgement for holders of
`ACK_ALERTS`, and the newest masked requests with method, uncertainty, age,
response time and result code. Figures the gateway cannot supply say why
(alerts switched off, LDR not built, no p95 objective) instead of showing a
zero.

**Console Location Request (phase C2).** The screen lists this tenant's enabled
clients from `GET /v1/ops/clients` (permission `LOCATE`) and submits through
`POST /v1/ops/locations`, which calls the same `LocationRequestService` as the
Le API. Consent, client purpose, accuracy entitlement, licence and the state
machine are unchanged; the operator is written to the trail separately from the
client (`OPS_LOCATION_SUBMITTED`, outcome `ALLOWED` or `DENIED` with a reason).
The form offers only the chosen client's purposes, refuses an accuracy tighter
than its entitlement before sending, and takes an optional idempotency key (a
replay shows the earlier request and says no second row was created). After
submission the screen re-reads `GET /v1/ops/requests/{id}` every 2 s while the
request is open, then shows the stored state, error, result envelope (method,
uncertainty, confidence, age, accuracy requested and met, shape, coordinates,
cell, engine, node), the CDR summary and the merged timeline. Identifiers stay
masked; revealing them needs `REVEAL_IDENTIFIERS` and writes
`OPS_IDENTIFIER_REVEAL`. A request with no position says so instead of showing
a placeholder, and a deployment with no LCS peer ends in a final state with its
code rather than a guessed answer. `OpsLocationTest` checks the client picker
permission, CSRF, permission refusal, unknown clients, consent refusal with a
`DENIED` audit row, a permitted submission with its `ALLOWED` row, masked
detail and timeline, idempotent replay, and accuracy refusal.

**Console Monitoring & Tracing (phase C3).** The screen reads
`GET /v1/ops/exchanges`, which lists stored CDRs - one row per signalling
attempt - joined to the client name, with protocol, operation, record status,
result code, RAT, positioning method, methods attempted, response time, paging,
serving and positioning nodes, uncertainty, confidence, age, accuracy requested
and met, shape, coordinates and timestamp. Filters cover protocol
(`MAP`/`DIAMETER`), outcome (`ANSWERED`/`FAILED`/`TIMEOUT`/`ABORTED`) and an
exact request id; the table is paged 25 rows at a time. `GET
/v1/ops/exchanges/series` returns answered and failed counts per bucket with
the average and p95 response time, optionally restricted to one protocol, and
the page draws it as a stacked SVG chart with a `<details>` table.

The read model is the stored CDR, so the screen says exactly that. Raw
signalling payloads are **not captured** anywhere; the API answers
`rawPayload: {available: false, reason: ...}` and the page prints the reason
rather than pretending to decode frames. The peer panel reuses
`GET /v1/ops/peers` (observed state only), and the faces-in-window panel shows
which interfaces actually produced exchanges.

**Console Reports (phase C3).** `GET /v1/ops/reports` aggregates the chosen
window five ways: by client (requests, answered, failed, in flight, success
rate, average response), by purpose (grouped at request creation), by RAT and
by positioning method (grouped at the CDR write, with average uncertainty and
age for answered results only), and by serving cell (from stored results, with
an `UNATTRIBUTED` row when a result has no cell). The screen states each
grouping basis and calls the cell table "the cells that served stored results,
not a coverage prediction". Success rate is answered divided by finished; in
flight is requests minus finished, so the three figures reconcile instead of
overlapping.

**Exports and their permissions.** `GET /v1/ops/exchanges/export.csv` and
`GET /v1/ops/reports/export.csv?report=...` require `EXPORT_CDR`, independent of
the read permission. Both write an `ALLOWED` audit row (`OPS_CDR_EXPORTED` with
the filters and row count, `OPS_REPORT_EXPORTED` with the range and report) and
a refusal writes `OPS_FORBIDDEN`/`DENIED` through the permission interceptor.
The CSV uses quoted fields, ISO-8601 instants, no subscriber identifiers
(`request_id` is the join key; MSISDN and IMSI never appear), and neutralises
spreadsheet formula injection by prefixing a leading `= + - @` with an
apostrophe. A cap of 50,000 rows is signalled with `X-Export-Truncated` and a
header line instead of silently dropping data.

**Geofences (phase C4).** A geofence is stored with the parameters the operator
entered, in one of the four TS 23.032 area shapes:

- **Circle**: centre and radius up to 500 km; containment is haversine distance.
- **Polygon**: 3 to 15 vertices (a repeated GeoJSON closing point is dropped);
  containment is ray casting with longitudes unwrapped around the test point,
  so a polygon may cross the antimeridian. A vertex within 0.5 degrees of a
  pole, consecutive duplicate vertices and a zero-area ring are refused.
- **Ellipse**: centre, semi-major, semi-minor and the clockwise orientation of
  the major axis. Containment uses a local metre plane at the shape's latitude
  (a fraction of a percent at these radii); a centre beyond 80 degrees is
  refused because the approximation stops being useful.
- **Ellipsoid arc**: centre, outer and inner radius, start bearing and included
  angle. Containment is geodesic distance plus initial bearing.

Arming binds a pseudonymous `subject_ref` to a geofence and stores only a masked
MSISDN/IMSI label for operators to recognise. Arming names a client and purpose
and calls the same consent check as a locate: without an active consent it is
refused with `PRIVACY_NOT_SATISFIED` and the refusal is audited
(`GEOFENCE_ARMED`, `DENIED`). Every answered Le or console request then
evaluates the subject's enabled fences against the result the gateway stored:
the first position inside writes `ENTER`, a move out writes `LEAVE`, and an
`INSIDE` event is written only when the fence sets `reportInside`. Events carry
the result id, request id, method, uncertainty and coordinates, never the
MSISDN. A disabled fence keeps its arms and history but is not evaluated.

Honest limits: there is no network-initiated deferred, periodic, motion or
UE-availability reporting (`GET /v1/ops/geofences/summary` returns those as
`available: false` with a reason). Events exist only for results the gateway
already obtained through a governed request. Re-evaluating the same result
cannot duplicate an event: the event table's unique key includes the result id
and event type.

**Event webhooks (phase C4b).** A subscription selects event types
(`ENTER`, `LEAVE`, `INSIDE`) and optionally one geofence; a null `geofenceId`
covers every fence in the tenant. Creating one needs `MANAGE_GEOFENCES` and
answers with a 64-character signing secret that is shown exactly once. A read
never returns it, and it is never written to a log line or an audit row. The
audit record keeps the subscription id and the URL host.

Each stored event creates one `PENDING` delivery per matching enabled
subscription, inside the same transaction as the event. The flusher sends:

- `POST` to the subscription URL with `Content-Type: application/json`;
- `X-GMLC-Event`, `X-GMLC-Delivery` and `X-GMLC-Timestamp` headers;
- `X-GMLC-Signature: v1=<hex HMAC-SHA256(secret, timestamp + "." + body)>`.

The body carries the event id and type, `occurredAt`, request and result ids,
the geofence id and name, the pseudonymous subject `ref` with its masked
`label`, and the position with method, uncertainty and coordinates. The MSISDN
and IMSI never appear. A receiver should check the timestamp against its own
clock (the signature covers it) and treat `X-GMLC-Delivery` as the idempotency
key: delivery is at-least-once, because a timeout after the remote side
committed is indistinguishable from a lost request.

A 2xx marks the delivery `DELIVERED`. Anything else is retried after 30 s,
2 minutes, 10 minutes and 1 hour, then marked `FAILED` after five attempts
(`gmlc.webhooks.max-attempts`). The delivery log keeps the status code and a
short error, not a response body. A webhook failure never removes the event,
never changes the arm state and never affects the location answer that produced
it.

Honest limits: this sends only events derived from results the gateway already
stored. There is still no network area-event subscription, no deferred or
periodic location, and no motion or UE-availability reporting; the summary
endpoint says so per feature. The flusher is a single scheduled loop, so a
multi-instance deployment would need a lease or queue to avoid duplicate sends
(the receiver's delivery-id check already makes that safe, merely wasteful).

**Tracking (phase C5a).** A tracked subject is one row naming a client, one of
the four tracking purposes (`SAFETY`, `FLEET_MANAGEMENT`, `COMMERCIAL_LBS`,
`IOT_ASSET`) and a subscriber. Adding one needs `LOCATE` and is consent-checked
exactly like a locate; the refusal is audited. The row keeps the pseudonymous
`subject_ref` that consent and audit use, a masked label for the console, and
the identifier only so the trail can find the requests the network already
carried. No read returns the identifier, the label is masked, and deleting the
row deletes it.

A trail is not a new feed: it is the stored results of governed requests this
client placed for this purpose, newest first, each carrying the method, shape,
uncertainty, confidence and age the request path recorded. This deployment has
no deferred (LDR), periodic or event-driven location subscription, so a subject
does not move by itself; `GET /v1/ops/tracking/summary` says that in its
`reporting` map rather than implying a live position. Automated schedules are
phase C5b and place governed requests; no network subscription is claimed.

**Automated tracking and notifiers (phase C5b).** A schedule names one tracked
subject, a from/to window and an interval. Each run goes through the same
`LocationRequestService` a client or operator uses, so purpose, consent,
accuracy and the client's daily quota decide whether a fix is obtained; the
run is recorded as `LOCATED`, `NO_FIX`, `SKIPPED` (`CLIENT_DISABLED`,
`RATE_LIMITED`) or `FAILED` (with the refusing code). After every attempt the
next run is a full interval away, so a revoked consent or an absent subscriber
is not retried in a tight loop. `POST .../schedules/{id}/run` places one
attempt immediately and records it as `MANUAL` without moving the timer.

The interval floor is 300 seconds and the schema refuses less. Licence metering
is not wired in this deployment, so the floor is a conservative paging guard,
not a purchased figure; the console summary says that in its `reporting` map.
A schedule is not an LDR or periodic location subscription - the gateway does
not ask the network to keep reporting.

A channel stores where a notification goes and the secret used to sign it.
Only `WEBHOOK` is built; `EMAIL`, `TELEGRAM` and `SLACK` are refused with
`NOTIFIER_NOT_AVAILABLE` rather than accepted and silently dropped. The
gateway generates the signing secret, seals it with AES-256-GCM under
`GMLC_TRACKING_NOTIFIER_KEY` (base64, exactly 32 bytes), returns it once and
never reads it back; a blank key refuses channel creation instead of storing
plaintext. A channel with schedules cannot be deleted. A run that stores a fix
sends one signed `tracking.fix` notification with the `X-GMLC-Timestamp` and
`X-GMLC-Signature` headers a geofence webhook uses; the body carries the
pseudonymous reference and masked label, never the MSISDN or IMSI. Delivery is
one attempt per run - the next schedule run is the retry - and the outcome
(`SENT`, `FAILED`, `NOT_CONFIGURED`) is kept on the run.

The client-facing `GET /v1/subscribers/{trackedId}/last-known` is scoped to the
client that added the subject; another client's identifier answers 404 with no
hint. It returns `found: false` with `NO_LOCATION_YET`, `NO_CONSENT` (consent is
re-checked on every read, so a revocation takes effect) or `TRACKING_DISABLED`.
A found answer carries `dataSource` - `REAL`, `BENCH` or `LAB_SIM` from the
deployment's environment - beside the full fix envelope.

**Console Tracking (phase C5c).** The screen keeps four tabs. Subjects adds a
tracked subject (client, purpose, one MSISDN or IMSI) and lists the roster with
each subject's masked label, purpose, state and latest stored fix. Trail & map
draws the stored fixes for one subject as markers, a polyline, optional
uncertainty circles or a density heatmap on a hand-built SVG plan; every point
is a result the gateway already stored for a governed request, so the panel
carries the method, uncertainty, confidence and age beside it and the page says
plainly that there is no live feed. Schedules creates a window and interval
(the form enforces the 300 s floor client-side; the schema enforces it again),
and the table runs, pauses, enables, opens the attempt log and deletes each
schedule. Notification channels creates a channel, shows the signing secret
once in a panel above the table, and never reads it back; `EMAIL`, `TELEGRAM`
and `SLACK` are disabled in the type picker rather than selectable. A
"What this deployment can report" panel renders `GET
/v1/ops/tracking/summary`'s `reporting` map, so live network tracking (LDR),
email, Telegram and Slack each say `NOT AVAILABLE` with the gateway's reason.
The screen is subject to the same CSP as the rest of the console.

**Console Geofencing (phase C4c).** The screen keeps four tabs - Geofences,
Entering, Leaving and Inside - plus a Webhooks tab. The Geofences tab lists each
definition with its shape summary (centre and radius, vertex count, axes and
orientation, or inner/outer radius and bearing) and the current armed and
inside counts, with enable/disable for holders of `MANAGE_GEOFENCES`. The
create form switches between all four TS 23.032 shapes and sends exactly the
parameters the operator entered; the arm form offers only the chosen client's
purposes and accepts an MSISDN or IMSI, and the armed table shows the masked
label only. The three event tabs read `GET /v1/ops/geofences/events` and show
each row with the producing result, method, uncertainty and coordinates.

The Webhooks tab lists subscriptions with their event filter, scope and
delivery counters, and expands a delivery log per subscription (status,
attempts, status code, short error, next attempt). Creating a subscription
shows the signing secret once in a panel above the table, with the same notice
the API returns; the table never reads it back. The "What this deployment can
report" panel renders
`GET /v1/ops/geofences/summary`'s `reporting` map, so network area events,
deferred/periodic location, motion events and UE availability each say
`NOT AVAILABLE` with the gateway's reason instead of showing a zero.

The page keeps the strict policy: `script-src 'self'; style-src 'self'`, no
inline script or style, no CDN, no external font, no map tiles. Charts are
hand-built SVG (presentation attributes, not style attributes) and each one
has a `<details>` data table so the same numbers are readable without the
graphic. Other sidebar screens say plainly that they are not built yet; no
placeholder figures are shown.

Building the console (Node is needed only on the build machine; the output is
committed so an offline Maven build still serves it):

```bash
cd gmlc-app/console
npm install
npm run build      # writes ../src/main/resources/static/console
```

`npm run typecheck` is a strict `tsc --noEmit`. Rebuild after changing
anything under `gmlc-app/console/src`. `ConsoleTest` checks that the entry
point is served as HTML, loads only same-origin bundled assets, carries the
strict policy on the page and its assets, and that serving it does not open
`/v1/ops/**`.

Screenshots from a local lab run with seeded rows and the `LAB_SIM` label:
[sign-in](docs/location/console-signin.png),
[dashboard](docs/location/console-dashboard.png),
[Location Request](docs/location/console-locate.png),
[a refused request followed to its final state](docs/location/console-locate-result.png),
[Monitoring and Tracing](docs/location/console-monitoring.png),
[one exchange record](docs/location/console-monitoring-exchange.png),
[Reports](docs/location/console-reports.png),
[Geofencing with all four shapes, arms and the honesty panel](docs/location/console-geofencing.png),
[the entering-event feed](docs/location/console-geofencing-entering.png),
[webhook subscriptions with their delivery log](docs/location/console-geofencing-webhooks.png),
[the tracking roster](docs/location/console-tracking.png),
[a stored-result trail as markers with uncertainty](docs/location/console-tracking-trail.png),
[the same stored fixes as a density heatmap](docs/location/console-tracking-heatmap.png),
[schedules with an expanded attempt log](docs/location/console-tracking-schedules.png),
[notifier channels and the honesty panel](docs/location/console-tracking-channels.png),
[a screen that is not built yet](docs/location/console-not-built.png).

## The Le request path

```bash
curl -s -X POST localhost:8082/v1/consents \
  -H "Authorization: Bearer $GMLC_API_KEY" -H 'content-type: application/json' \
  -d '{"msisdn":"256700000001","purpose":"COMMERCIAL_LBS","channel":"PORTAL","expiresAt":"2026-12-31T00:00:00Z"}'

curl -s -X POST localhost:8082/v1/locations \
  -H "Authorization: Bearer $GMLC_API_KEY" -H 'content-type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"msisdn":"256700000001","purpose":"COMMERCIAL_LBS","accuracyMeters":2000}'
```

A request is accepted only when consent covers the subject, purpose and
calling client. Consent is stored against a pseudonymous subject reference, and
the Le response never repeats the MSISDN or IMSI. An `EMERGENCY` request may
proceed without stored consent only when `GMLC_EMERGENCY_OVERRIDE=true`; the
audit row records `EMERGENCY_OVERRIDE` as the basis.

`accuracyMeters` may not ask for a better error than the client's
`maxAccuracyM` entitlement; a tighter request answers 400
`ACCURACY_NOT_ALLOWED` rather than delivering a position the subscription does
not buy. `Idempotency-Key` (header or body field) replays the original request
instead of creating a second one.

The request moves `RECEIVED → AUTHORISED → PRIVACY_CHECKED → ROUTED →
REQUESTED`, then either to `ANSWERED → DELIVERED` or to a failure state.
Terminal states cannot be left, which is enforced both in the state enum and by
a conditional update on the row. A `POST` that has already finished answers 200
with its final state; one still open answers 202.

### The result envelope

When the locator answers, the position is written to `location_result` and
attached to the request *before* the response is built, so a client that has seen
a coordinate can always fetch the same one again. The response carries it as
`result`:

```json
{
  "requestId": "…", "state": "DELIVERED", "purpose": "COMMERCIAL_LBS",
  "result": {
    "method": "CELL_ID", "shape": "CIRCLE",
    "latitude": 0.3476, "longitude": 32.5825,
    "uncertaintyM": 2500, "confidence": 80, "ageSeconds": 45,
    "accuracyRequestedM": 10000, "accuracyFulfilled": true,
    "servingCell": "001-01-1-662273", "engine": "CELL_DB",
    "positioningNode": "mme1.epc.mnc001.mcc001.3gppnetwork.org",
    "methodsAttempted": ["SLH_RIR", "SLG_PLA"]
  }
}
```

Every answer is a circle: `latitude`/`longitude` are the centre,
`uncertaintyM` its radius and `confidence` the plan's stated quality (neutral
`50` when the cell row has none). A request fails rather than approximate:
`CELL_NOT_IN_PLAN`, `CELL_WITHOUT_RADIUS`, `NO_SERVING_NODE`, or the
signalling's own code. `NO_LCS_SOURCE` means the serving network named no cell -
or no peer is configured at all.

The path still ends `FAILED` with `errorCode: NO_LCS_SOURCE` when no Diameter
peer is configured: the transport refuses `SIGNALLING_NOT_CONFIGURED`, which the
gateway maps to the client-facing `NO_LCS_SOURCE`. A CDR row records the same
outcome.

`GET /v1/cdrs?limit=50` returns the caller's records, newest first. The
subscriber appears only as the pseudonymous reference; the raw MSISDN/IMSI stay
in the request row, which signalling needs.

Set `GMLC_SUBJECT_PEPPER` in a real deployment: with it the reference is
HMAC-SHA256, so a database copy cannot be matched against a list of known
numbers. Without it the reference is unkeyed SHA-256 and every start logs a
warning.

## Diameter signalling

The gateway speaks Diameter itself - no vendor SS7/Diameter library - so the
codec, the stream framing and the capabilities exchange are all in
`diameter/`:

- `Avp`, `DiameterMessage`: RFC 6733 codec. Every encode pads AVPs to four
  octets; every parse refuses a truncated AVP or a frame shorter than its
  declared length rather than misreading bytes.
- `TcpDiameterTransport`: one stream peer, CER/CEA on connect (SLh and SLg
  application IDs, vendor 10415), hop-by-hop matching, answers inbound CER, and
  a 1 MiB frame ceiling. **TCP, not SCTP**: this host has no SCTP provider for
  the JVM, so the transport is `Diameter/TCP`; a DRA that only accepts SCTP
  needs that provider added before this will connect.
- `SlhClient.routeInfo(imsi|msisdn)`: LCS-Routing-Info-Request (app 16777291)
  to the HSS, returning the serving node's Diameter name and realm, or empty
  when the HSS answers success without an addressable node.
- `SlgClient.provideLocation(...)`: Provide-Location-Request (app 16777255) to
  the MME, carrying the accuracy as the standard uncertainty code, the LCS
  client type and the LCS QoS. The answer's ECGI becomes an LTE cell, a
  Cell-Global-Identity becomes a GSM cell with its LAC, and anything else is
  empty - the client never invents a cell that the answer did not contain.

`DiameterCellLocator` joins the two: SLh resolves the serving node, SLg asks it
for the cell, and the cell is looked up in the plan. The ECGI carries no TAC, so
the lookup matches PLMN and cell code only; more than one row means the identity
is ambiguous in this plan and the request fails (`AMBIGUOUS_CELL`) rather than
pick one. The locator reports `method: CELL_ID`, the plan's `radius_m` as the
uncertainty, and the plan's `data_quality` as the confidence. An empty plan row
is `CELL_NOT_IN_PLAN`, never a guessed coordinate.

Codes come from TS 29.172 (SLh/SLg), 29.274 (ECGI and CGI octet layout) and
29.002 (TBCD, MSISDN). Errors map to a small stable set the Le caller sees:
`SUBSCRIBER_UNKNOWN`, `SUBSCRIBER_NOT_REACHABLE`, `POSITIONING_DENIED`,
`POSITIONING_FAILED`, `GMLC_NOT_AUTHORISED`, or `DIAMETER_ERROR_<code>`.

Sh (UDR towards the HSS, for the "service node" subscription data) is not
built: TS 29.329 is not on the build host and the AVP codes must not be
guessed. Phase 1 cell-ID does not need it.

Configuration is the `gmlc.diameter` block; with `GMLC_DIAMETER_PEER_HOST`
empty the transport refuses every exchange with `SIGNALLING_NOT_CONFIGURED`,
so an unconfigured deployment fails fast and visibly instead of hanging on a
socket.

## SS7 and MAP transport

2G/3G location needs MAP, which rides TCAP over SCCP over MTP3. The gateway
speaks that stack itself too - still no vendor SS7 library:

- `map/Ber`: the BER subset MAP uses (single-octet tags, definite shortest
  lengths) with a parser that refuses truncation, indefinite lengths, the
  high-tag-number form, and long-form lengths that the short form could hold.
- `map/Tcap`: Q.773 transaction capabilities - Begin, End, Continue, Abort,
  Invoke, Return Result and Return Error, the dialogue portion with its
  application-context OID, and AARQ/AARE. Local operation and error codes are
  plain integers; a global opcode is reported as `MAP_GLOBAL_OPCODE` instead
  of being guessed.
- `map/MapLcs`: the location operations of TS 29.002 - SRILCS and PSL for the
  serving MSC/SGSN, ATI for cell information, and the LCS-ClientID, QoS and
  LocationType elements. Every argument and answer is built from explicit
  octets, not a schema generator.
- `map/Gad`: TS 23.032 shape 1, an ellipsoid point with uncertainty circle.
  The uncertainty code becomes the same radius as Diameter's, so a position
  from either face carries a comparable bound.
- `ss7/Sccp`: Q.713 connectionless unitdata. Addresses may be a subsystem
  number alone, a point code and subsystem number, or an E.164 global title
  of indicator type 4 (translation type 0, ISDN numbering plan, international
  nature of address, BCD with the 0000 filler Q.713 asks for). Truncated
  pointers, a foreign title format and a non-decimal digit are errors, not
  zero-filled guesses.
- `ss7/M3uaMessage`, `ss7/TcpM3uaTransport`: RFC 4666 ASPSM (ASP Up, ASP
  Down, Heartbeat), ASPTM (ASP Active) and DATA. The protocol-data parameter
  carries the point codes, service indicator, network indicator and
  signalling link selection; the SCCP message follows it. **TCP, not SCTP**:
  RFC 4666 runs M3UA over SCTP and this host has no SCTP provider for the
  JVM, so the transport is M3UA/TCP and a lab gateway must accept that. It is
  not multi-homed and has no SCTP-level failover; do not treat it as a
  production link.

An exchange sends one unitdata message and returns the next one addressed to
the gateway; correlating an answer with its dialogue is TCAP's job (by
transaction identifier). `map/MapClient` does that and rejects an answer from
another dialogue, application context or operation rather than reading it as a
location. `location/MapCellLocator` drives the sequence: SRILCS at the HLR for
the serving node, then PSL at that MSC or SGSN (addressed by the E.164 number
the HLR named), or ATI at the HLR when the subscriber is on an MME, because an
MME is not a MAP PSL peer.

The cell that comes back is resolved in the operator's plan, so the answer
keeps the plan's radius, its quality as confidence, and the network's age. A
LAC or TAC in the answer makes the plan lookup the exact canonical identity,
so a cell code reused in another area is not mistaken for this one. A MAP
error keeps its TS 29.002 name (`ABSENT_SUBSCRIBER`, `UNAUTHORIZED_LCS_CLIENT`,
`POSITION_METHOD_FAILURE`, ...). An answer that carries a circle but no cell is
reported as no source: the engine column says the plan produced the position,
so a bare GAD circle is not attributed to it.

Configuration is the `gmlc.m3ua` block. Point codes belong to the operator,
so none is committed: with `GMLC_M3UA_LOCAL_PC` zero, or `GMLC_M3UA_HOST`
empty, the link is disabled and every MAP exchange fails closed with
`SIGNALLING_NOT_CONFIGURED`. `GMLC_M3UA_LOCAL_GT` adds the gateway's own
E.164 number to the calling party; without it the calling party is the point
code and GMLC subsystem number 145.

The signalling face is a deployment decision, not a fallback race:
`GMLC_LOCATION_TRANSPORT=MAP` builds the MAP locator, `DIAMETER` (the default)
builds the SLh/SLg one, and exactly one `LocationLocator` exists in the
context. `GMLC_MAP_MLC_NUMBER` is the gateway's own E.164 LCS number carried in
the SRILCS and PSL arguments; blank leaves MAP disabled and a MAP-selected
deployment answers `NO_LCS_SOURCE` instead of signalling. With
`GMLC_MAP_HLR_PC` zero, SRILCS and ATI are routed on subsystem 6 at the M3UA
peer's point code; a non-zero value names the HLR's point code directly.
Choosing between an HLR face and an HSS face per subscriber is not built: a
mixed-network deployment must not enable both at once.

## The cell database

The operator's cell plan decides where a cell-ID answer points, so the gateway
owns it: one store, one import format, one validation report. A cell is
identified by its RAT and a canonical string, `mcc-mnc-area-cell`, where the
area is the LAC for GSM/UMTS and the TAC for LTE/NR; leading zeros are stripped
so `001-01-0101-0001` and `001-01-101-1` are the same cell.

Reads need only an API key. Writes are operator actions under
`X-Admin-Key`, at `/v1/admin/cells`, because an LBS client that could edit the
plan could move a subscriber. The gateway does not invent geometry: `radius_m`
is mandatory, and a request for a cell that does not exist answers 404 rather
than a made-up coordinate.

`GET /v1/cells/{rat}/{cellIdentity}` returns the cell plus a GeoJSON
`coveragePolygon` (WGS-84, `[lon, lat]`, ring closed). The shape follows what is
known: `POINT` without a radius, `CIRCLE` with a radius but no beam, `ARC` for
a sector (centre included). `dataQuality` is 0-100; when the import supplies
one it wins, otherwise it is a weighted completeness score (coordinates 40,
radius 20, beam 10, antenna height 5, site 5, PCI 3, ARFCN 2, source 5, and 10
for verification within the last year).

### CSV import

```bash
curl -s -X POST 'localhost:8082/v1/admin/cells/import' \
  -H "X-Admin-Key: $GMLC_ADMIN_KEY" -H 'content-type: text/csv' \
  --data-binary @cells.csv                       # dry run: the report only
# then, when the report is clean:
curl -s -X POST 'localhost:8082/v1/admin/cells/import?dryRun=false' \
  -H "X-Admin-Key: $GMLC_ADMIN_KEY" -H 'content-type: text/csv' \
  --data-binary @cells.csv
```

```csv
rat,plmn_mcc,plmn_mnc,area_code,cell_code,site_name,latitude,longitude,antenna_height_m,azimuth_deg,beamwidth_deg,radius_m,pci,arfcn,cell_type,data_source,data_quality,last_verified
LTE,001,01,101,1,Kampala-Nakasero-1,0.313611,32.581111,25,90,65,1200,101,1650,MACRO,drive-test,,2026-03-01
```

| Column | Required | Notes |
|---|---|---|
| `rat` | yes | `GSM`, `UMTS`, `LTE`, `NR` |
| `plmn_mcc`, `plmn_mnc` | yes | 3 digits; MNC 2 or 3 (`mcc`/`mnc` accepted as aliases) |
| `area_code` | yes | LAC or TAC, 0-65535 |
| `cell_code` | yes | 16 bits GSM, 28 UMTS/LTE, 36 NR |
| `latitude`, `longitude` | yes | WGS-84 decimal degrees |
| `radius_m` | yes | 1-200000; without it the geometry cannot bound uncertainty |
| `site_name`, `antenna_height_m`, `azimuth_deg`, `beamwidth_deg`, `pci`, `arfcn`, `cell_type`, `data_source`, `data_quality`, `last_verified` | no | `azimuth_deg` 0-359, `beamwidth_deg` 1-360, PCI 0-1007, `last_verified` ISO date |

Every row is validated before anything is written; a commit runs in one
transaction, so a file with one bad row changes nothing. Errors come back with
the line number and the field: `{"line": 4, "field": "radius_m", ...}`. The
response also says how many rows would be inserted versus updated, so an import
that is really a large update is visible before it runs. The same format is the
Phase 2 contract with the E-SMLC import.

## Build and test

Maven is offline-first on this workstation; see `BUILD-NOTES.md`.

```bash
/c/tools/apache-maven-3.9.9/bin/mvn -o test
/c/tools/apache-maven-3.9.9/bin/mvn -o -DskipTests package
```

Tests start an embedded PostgreSQL (no Docker needed) and apply the real
Flyway migrations before asserting anything.

## Run

```bash
cp .env.example .env   # set DB_PASSWORD
docker compose up -d   # unverified on this host
```

Or against an existing PostgreSQL:

```bash
GMLC_DB_URL=jdbc:postgresql://localhost:5432/gmlc \
GMLC_DB_USER=gmlc GMLC_DB_PASSWORD=... \
java -jar gmlc-app/target/gmlc.jar
```

## Layout

```
gmlc-app/src/main/java/com/hamiltel/gmlc/
├── admin/         client registration (admin key)
├── audit/         append-only trail, no coordinates
├── capability/    profile model rules, loader, controller
├── cdr/           one row per finished request, subscriber as pseudonym
├── cell/          identities, validation, geometry, quality, CSV import
├── client/        API keys, authentication, allowlists, quotas, request filter
├── config/        deployment properties
├── consent/       consent records, expiry, emergency basis
├── diameter/      AVP/message codec, TCP transport, SLh and SLg clients
├── health/        health endpoint
├── location/      Le requests, state machine, locator, results, pseudonyms
├── map/           BER, TCAP, MAP LCS operations, TS 23.032 GAD
├── ops/           operator accounts, sessions, CSRF, roles, masking
├── ss7/           SCCP unitdata, M3UA messages, M3UA/TCP transport
└── web/           error mapping to RFC 7807 problems, dashboard headers
gmlc-app/src/main/resources/db/migration/   Flyway SQL, V1 onward
gmlc-app/src/main/resources/location/       bundled capability profile
gmlc-app/src/main/resources/static/         client dashboard and built console bundle
gmlc-app/console/                           React + TypeScript console source (Vite)
```

## Boundaries

- No coordinate is ever returned without its method, uncertainty, confidence
  and age.
- Audit rows reference a result; they never contain coordinates.
- Secrets live in environment variables, never in the repository.
- The bundled capability profile is a copy of the core repository's tenant-1
  profile. When either changes, bump `version` and update both.
