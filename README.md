# GMLC - Gateway Mobile Location Centre

Hamiltel's location gateway: the Le API for clients, client and consent
governance, the authoritative cell database, CDRs, and the signalling towards
the HSS, MME, HLR and MSC/SGSN.

It is a separate service from the core (`github.com/ssonkokevin/5gcore`, where
the MME LCS function, E-SMLC and LMF live). The two talk over standard 3GPP
interfaces only - SLh and SLg over Diameter, MAP for 2G/3G, and `Ngmlc_Location`
over SBI - and share no code.

The split is recorded with its reasoning in the core repository under
`docs/location/DECISIONS.md` (decisions D1-D16).

## Status

| Area | State |
|---|---|
| Spring Boot service, PostgreSQL, Flyway | **Built and tested** on this host |
| `/v1/health`, `/v1/capabilities`, `/v1/clients/me` | **Built and tested** |
| Tenant-aware schema (tenants, clients, consent, cells, requests, results, CDRs, audit, usage) | **Built**; migrations apply against PostgreSQL 17 in tests |
| Client registration, API keys, address allowlists | **Built and tested** |
| Per-second and per-day quotas, audited refusals | **Built and tested** |
| Consent: grant, read, revoke, expiry, emergency override | **Built and tested** |
| Le location API, request state machine, CDRs | **Built and tested**; answers cell-ID from the cell plan when a Diameter peer is configured, fails closed otherwise |
| Authoritative cell database, coverage geometry, CSV import with dry-run | **Built and tested** |
| Diameter SLh RIR and SLg PLR/PLA clients, CER/CEA, TCP framing | **Built and tested**; fail closed until a peer is configured |
| MAP codecs (SRILCS, PSL, ATI), GAD circles, SCCP and M3UA framing | **Built and tested**; not yet wired to the Le request path |
| MAP service location (SRILCS then PSL/ATI), SLR | Not yet |
| Diameter Sh (UDR) | Deferred - TS 29.329 not on the build host; codes are not guessed |
| Dashboard | Not yet |
| Container build | Written, **unverified** - no Docker on the build host |

Nothing in this repository has talked to a real operator network.

## Endpoints today

```
GET  /v1/health          liveness, database, capability profile validity   (open)
GET  /v1/capabilities    the tenant's capability profile                   (API key)
GET  /v1/clients/me      the calling client's own configuration            (API key)

POST /v1/locations       submit a location request                         (API key)
GET  /v1/locations/{id}  its state, failure code, timestamps and result       (API key)

POST /v1/consents        record consent for a subject and purpose          (API key)
GET  /v1/consents/{id}   status of a consent record                        (API key)
DELETE /v1/consents/{id} revoke it                                         (API key)

GET  /v1/cdrs            the caller's own completed records                (API key)

GET  /v1/cells?rat=&area=      list cells, paged, no geometry rings        (API key)
GET  /v1/cells/{rat}/{id}      one cell with its coverage geometry         (API key)

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
transaction identifier). The transport is not connected to the Le request
path yet: cell-ID answers still come from Diameter SLh/SLg, and this codec
set is the foundation for the MAP locator.

Configuration is the `gmlc.m3ua` block. Point codes belong to the operator,
so none is committed: with `GMLC_M3UA_LOCAL_PC` zero, or `GMLC_M3UA_HOST`
empty, the link is disabled and every MAP exchange fails closed with
`SIGNALLING_NOT_CONFIGURED`. `GMLC_M3UA_LOCAL_GT` adds the gateway's own
E.164 number to the calling party; without it the calling party is the point
code and GMLC subsystem number 145.

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
├── ss7/           SCCP unitdata, M3UA messages, M3UA/TCP transport
└── web/           error mapping to RFC 7807 problems
gmlc-app/src/main/resources/db/migration/   Flyway SQL, V1 onward
gmlc-app/src/main/resources/location/       bundled capability profile
```

## Boundaries

- No coordinate is ever returned without its method, uncertainty, confidence
  and age.
- Audit rows reference a result; they never contain coordinates.
- Secrets live in environment variables, never in the repository.
- The bundled capability profile is a copy of the core repository's tenant-1
  profile. When either changes, bump `version` and update both.
