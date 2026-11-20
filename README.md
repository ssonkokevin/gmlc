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
| `/v1/health`, `/v1/capabilities` | **Built and tested** |
| Tenant-aware schema (tenants, clients, consent, cells, requests, results, CDRs, audit) | **Built**; migrations apply against PostgreSQL 17 in tests |
| Le location API (`POST /v1/locations`, ...) | Not yet - next work package |
| Client authentication, quotas | Not yet |
| MAP (ATI, PSI, SRILCS, PSL, SLR) | Not yet |
| Diameter (SLh RIR, SLg PLR/PLA, Sh UDR) | Not yet |
| Dashboard | Not yet |
| Container build | Written, **unverified** - no Docker on the build host |

Nothing in this repository has talked to a real operator network.

## Endpoints today

```
GET /v1/health         liveness, database, capability profile validity
GET /v1/capabilities   the tenant's capability profile (tenant 1 until auth exists)
```

The capability profile is the promise the API makes: which methods each RAT
can use, which of those are actually built, and whether the network supports
LPP, LPPa or NRPPA. A profile that fails validation is not served - the gateway
answers 503 rather than claim a method it cannot deliver.

`X-Capability-Source: bundled|database` says where the served profile came
from.

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
├── capability/    profile model rules, loader, controller
├── config/        deployment properties
├── health/        health endpoint
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
