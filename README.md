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
| Le location API, request state machine, CDRs | **Built and tested**; fails closed with `NO_LCS_SOURCE` until a location client exists |
| MAP (ATI, PSI, SRILCS, PSL, SLR) | Not yet |
| Diameter (SLh RIR, SLg PLR/PLA, Sh UDR) | Not yet |
| Dashboard | Not yet |
| Container build | Written, **unverified** - no Docker on the build host |

Nothing in this repository has talked to a real operator network.

## Endpoints today

```
GET  /v1/health          liveness, database, capability profile validity   (open)
GET  /v1/capabilities    the tenant's capability profile                   (API key)
GET  /v1/clients/me      the calling client's own configuration            (API key)

POST /v1/locations       submit a location request                         (API key)
GET  /v1/locations/{id}  its state, failure code and timestamps            (API key)

POST /v1/consents        record consent for a subject and purpose          (API key)
GET  /v1/consents/{id}   status of a consent record                        (API key)
DELETE /v1/consents/{id} revoke it                                         (API key)

GET  /v1/cdrs            the caller's own completed records                (API key)

POST /v1/admin/clients   register a client and mint its API key            (X-Admin-Key)
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

The request moves `RECEIVED → AUTHORISED → PRIVACY_CHECKED → ROUTED`, then
either to `REQUESTED`/`ANSWERED` or to a failure state. Terminal states cannot
be left, which is enforced both in the state enum and by a conditional update
on the row. A `POST` that has already finished answers 200 with its final
state; one still open answers 202.

This build has no MAP, SLh/SLg or SLs client yet, so every routed request ends
`FAILED` with `errorCode: NO_LCS_SOURCE` and a CDR row saying the same. That is
deliberate: the gateway reports what it cannot do instead of synthesising a
position. When the signalling clients land, the same state machine carries the
request through `REQUESTED` to `ANSWERED`.

`GET /v1/cdrs?limit=50` returns the caller's records, newest first. The
subscriber appears only as the pseudonymous reference; the raw MSISDN/IMSI stay
in the request row, which signalling needs.

Set `GMLC_SUBJECT_PEPPER` in a real deployment: with it the reference is
HMAC-SHA256, so a database copy cannot be matched against a list of known
numbers. Without it the reference is unkeyed SHA-256 and every start logs a
warning.

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
├── client/        API keys, authentication, allowlists, quotas, request filter
├── config/        deployment properties
├── consent/       consent records, expiry, emergency basis
├── health/        health endpoint
├── location/      Le requests, state machine, pseudonyms, validation
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
