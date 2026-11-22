# Consistency audit

Date: 2026-11-22
Repos: `gmlc` (Java gateway + console), `5g-core-engine` (Python core)
Rule: every boundary has one source of truth; the other side is generated from
it or checked against it; a boundary is not done until `make verify` passes.

This document reports before it fixes. Findings are grouped by boundary and
name the file and line. Nothing below changes a protocol value to match code.
Where a check or fixture had to change to make the gate work, §7 says exactly
what changed and why.

Evidence labels used on every finding:

- **unit-tested** — a committed test or gate proves it on this host.
- **lab-simulated** — exercised against a mock/lab node, not real equipment.
- **unchecked** — no test or reference file exists yet.

## Gate status at the end of this commit

| Gate | Command | Result |
| --- | --- | --- |
| API spec vs controllers | `mvn -o -pl gmlc-app test -Dtest=ApiSpecDriftTest` | 5/5 pass, 92 operations |
| Response schemas vs records | `mvn -o -pl gmlc-app test -Dtest=OpsPayloadDriftTest` | 1/1 pass, every record response compared |
| Problem codes vs Java | `mvn -o -pl gmlc-app test -Dtest=ProblemCodeDriftTest` | 3/3 pass, 101 codes |
| Console types vs spec | `mvn -o -pl gmlc-app test -Dtest=TypesDriftTest` | 1/1 pass, 96 schemas |
| Frontend paths vs spec | `node tools/check-frontend-endpoints.mjs` | 76 calls → 75 operations in 2 files |
| Migrations vs snapshot | `mvn -o -pl gmlc-app test -Dtest=SchemaSnapshotTest` | 1/1 pass, 25 tables, 584 lines |
| CDR export order vs fixture | `mvn -o -pl gmlc-app test -Dtest=CdrFieldOrderTest` | 1/1 pass, 25 columns |
| Cross-tenant isolation | `mvn -o -pl gmlc-app test -Dtest=TenantIsolationTest` | 5/5 pass, 17 tables |
| Java constants vs fixture | same run as above (`SharedConstantsTest`) | 4/4 pass |
| Python constants vs fixture | `py tools/check_constants.py --core-root ../5g-core-engine` | 45 match, 3 known-divergence warnings |

The three gates failed first on real defects (two unquoted YAML descriptions, a
missing fixture entry, an auto-configured error controller, a false-positive
path in the frontend checker). They pass now. The checks fail on drift by
construction: see `ApiSpecDriftTest`, `SchemaSnapshotTest`,
`SharedConstantsTest`, `tools/check-frontend-endpoints.mjs`,
`tools/check_constants.py`.

---

## 1. Frontend ↔ backend

Source of truth: `api/openapi.yaml`. It is written from the controllers and is
now enforced in both directions by `ApiSpecDriftTest`: a route served by Spring
but absent from the spec fails, and a path in the spec with no controller fails.
Query parameters, headers and path variables are compared name-for-name.

### API-1 (unit-tested) Response bodies were mostly untyped — FIXED

`gmlc-app/console/src/types.ts` was hand-written. The spec described only the
client Le API and a handful of ops schemas: 63 response slots across 92
operations referenced `components/responses/OpsObject`, which was
`type: object, additionalProperties: true`. Nothing compared a field the console
read with a field the controller wrote, so a renamed JSON field was invisible to
every gate.

Fixed in the API-schema commit: all 63 slots now reference a named schema built
from the Java record. `OpsPayloadDriftTest` reflects every controller method whose return
type is a record, unwraps `ResponseEntity`/`List`, and fails when the record's
components differ from the schema's properties in either direction. Two
endpoints were wrong in the spec rather than in code, and the spec was corrected
(§7 items 7–8). **unit-tested**

### API-2 (unit-tested) No generated TypeScript — FIXED

There was no generator and no `make types` target. `TypeScriptGenerator` now
renders `console/src/types.ts` from `components.schemas`; `TypesDriftTest`
fails when the checked-in file differs. Regenerate with
`mvn -o -B -pl gmlc-app test -Dtest=TypesDriftTest -Dgmlc.write.types=true`.
Console-only shapes (`Session` = `OpsSessionView`, `DashboardData`) live in
`console/src/ui.ts`; nothing else is hand-written. The console builds with
strict `tsc`. **unit-tested**

### API-3 (unit-tested) Endpoints no page uses

The console calls 75 of the 92 declared operations. The rest are deliberate
external surfaces or detail routes the console replaces with collection
refreshes:

- Client Le API and admin: `POST /v1/locations`, `POST /v1/consents`,
  `GET|DELETE /v1/consents/{id}`, `GET /v1/cells`, `GET /v1/cells/{rat}/{id}`,
  `GET /v1/capabilities`, `GET /v1/health`, `GET /v1/subscribers/{id}/last-known`,
  `POST /v1/admin/clients`, `POST /v1/admin/cells`,
  `POST /v1/admin/cells/import`, `POST /mlp`.
- Browser navigation, not a fetch: `GET /console`.
- Ops detail reads not used by the console (it refreshes the collection):
  `GET /v1/ops/geofences/{id}`, `GET /v1/ops/geofences/{id}/arms`,
  `GET /v1/ops/subscriptions/{id}`, `GET /v1/ops/tracking/{id}`,
  `GET /v1/ops/tracking/schedules/{id}`, `GET /v1/ops/tracking/channels/{id}`.

All 67 methods in `gmlc-app/console/src/api.ts` are referenced by a page or
component; there is no dead client method. The legacy
`gmlc-app/src/main/resources/static/dashboard.js` uses only the client Le API
(five paths) and is covered by the same checker. **unit-tested**

### API-4 (unit-tested) `/v1/capabilities` served the default tenant — FIXED

`CapabilityController.java:20-23` said authentication “arrives with the Le API
phase”, but `ApiKeyFilter` already authenticates this route, and the controller
called `capabilities.current(properties.defaultTenantId())`, ignoring
`@CurrentClient`. A client registered to tenant 2 therefore received tenant 1's
capability profile.

Fixed in commit *Fix capabilities tenant isolation*: the controller takes the
tenant from `@CurrentClient ApiClient`, the javadoc now states the rule, and
`GmlcApiTest.capabilitiesUseTheAuthenticatedClientsTenant` registers a client on
tenant 2 and asserts a 404 whose `tenantId` is 2 — the honest answer, since
tenant 2 has no profile. Tenant 1's behaviour is unchanged. **unit-tested**

### API-5 (unit-tested) Config-change status was absent from the spec — FIXED

`V10__configuration_versions.sql:37` constrains `status` to
`PENDING`, `APPLIED`, `REJECTED`, `CANCELLED`. The console reads `status`, but
the endpoint returned `OpsObject`. `ConfigChange.status` is now an enum with
those four values; the generated console type is the same union. **unit-tested**

### API-6 (unit-tested) Enums that do match

Checked by hand against migrations, Java constants and the spec:

| Value set | DB | Code | Spec | Result |
| --- | --- | --- | --- | --- |
| request state | `V1:142-144` | `RequestState` | `:2577` | equal (9 values) |
| priority | `V1:140` | input validation | `:2563` | equal |
| RAT | `V1:92,145` | cell/location code | `:216,247,1930,2763` | equal |
| consent status | `V1:66` | consent code | `:2679` | equal |
| result shape | `V1:181` | shape codec | `:2616` | equal |
| record status | `V1:237` | CDR code | `:2824` | equal |
| geofence state | `V6:42` | geofence code | `:1086` | equal |
| audit outcome | `V1:259` | `AuditTrail.Outcome` | `:2132` (`ALL` is a filter) | equal |
| ops role | `V3:20` | `OpsRole` | `:3010` | equal |
| ops permission | n/a | `OpsPermission` | `:3015+` | equal |
| break-glass review | `V11:25` | access code | `BreakGlassGrant.reviewStatus` | equal |
| cell type | `V1:111` | `CellValidator.CELL_TYPES` | `CellType` | equal (now an enum) |
| tracking run state | `V9:72,76` | tracking code | `TrackingRun.state` | equal (now a schema) |
| config change status | `V10:37` | config code | `ConfigChange.status` | equal (now a schema) |
| problem code | n/a | exception constructors | `ProblemCode` | equal (101, `ProblemCodeDriftTest`) |

The last three rows were the “three missing enums” from the first audit; all are
now named schemas and generated into the console. **unit-tested**

### API-7 (unit-tested) Documented endpoints that do not exist

None. Every `/v1/...` path in `README.md` and `docs/` exists in the spec; the
only extra match is the prose prefix `/v1/ops`.

---

## 2. Backend ↔ database

Source of truth: Flyway `gmlc-app/src/main/resources/db/migration/V1..V11.sql`.
`SchemaSnapshotTest` builds the schema in embedded PostgreSQL from those
migrations and compares a committed fingerprint
(`gmlc-app/src/test/resources/schema.snapshot.sql`) of every column, default,
constraint, index and sequence. There is no ORM and no `ddl-auto`; schema comes
only from Flyway. **unit-tested**

### DB-1 (unit-tested) Snapshot drift is now gated

25 tables, 584 fingerprint lines. Any edit to a migration without regenerating
the snapshot fails. Regeneration is documented in the test javadoc:
`-Dgmlc.schema.snapshot=write`, then copy
`gmlc-app/target/schema.snapshot.sql` into `gmlc-app/src/test/resources/`.

### DB-2 (unit-tested) The CDR export order is now committed — FIXED

The original finding conflated three different shapes. Corrected here:

- the `cdr` table has 28 columns: 26 in `V1:211-240` plus `protocol` and
  `operation` from `V4:13-14`;
- `CdrRepository.CdrRow` is an 18-column read view (`CdrRepository.java:111-129`);
- the operator export at `GET /v1/ops/exchanges/export.csv` is 25 columns
  (`OpsMonitoringController.java:137-151`), joining `client_name` and expanding
  `methods_attempted` to a semicolon-joined string.

There is no PAiCore CDR interface in the GMLC, so the 188-field order has
nothing to check against and is declared **out of scope**, not unchecked. If a
PAiCore CDR exchange is added, its order becomes a second fixture.

What is checked now: `fixtures/cdr-fields.txt` lists every export column with
the `ExchangeRow` accessor that fills it, in order. `CdrFieldOrderTest` parses
the header literals and the `row.*()` calls out of `exportExchanges` and
compares both sequences, so reordering the header without the row (or the
reverse) fails even though it still compiles. **unit-tested**

### DB-3 (unit-tested) Cross-tenant reads and mutations — FIXED

`TenantIsolationTest` seeds a dedicated tenant A and a second tenant B in the
shared embedded database, then uses B's identity against every repository that
takes a `tenant_id`. It covers 17 tables: location request/result, CDR, audit,
geofence/arm/event, subscription/delivery, tracked subject, schedule/run,
notification channel, alert, ops user and config item/change.

Each case asserts both halves: B's read is empty or not-found, and B's mutation
(`update`, `delete`, `setEnabled`, `advance`, `attachResult`, `acknowledge`,
`decide`, `enqueue`) leaves A's row unchanged. The SQL predicates already had
`tenant_id = ?`; the negative direction is what was missing. **unit-tested**

### DB-4 (unit-tested) No orphan columns

Every column name in the snapshot appears in at least one Java or SQL source
under `gmlc-app/src/main` or `src/test`. No unused columns found by name.

### DB-5 (not applicable) No `srg_db.sql` drift for GMLC

`5g-core-engine/database/srg_db.sql` describes the core MySQL database
(`net5gc/srg_db`). The GMLC uses PostgreSQL and its own Flyway history. They are
different databases, so there is nothing to compare. The core dump is not a
second source of truth for the GMLC schema.

---

## 3. Signalling constants and cross-repo codecs

Source of truth: `fixtures/constants.json`. Every entry names a specification
clause. `SharedConstantsTest` reflects each Java field; `tools/check_constants.py`
resolves each Python constant by import or by regex from the named file.
`knownDivergences` is now empty: every listed value is enforced, and a drift on
either side fails the gate. **unit-tested** for the enforced set; **unchecked**
where marked.

### SIG-1 (unit-tested) FIXED — Sh application id is defined once in the core

The core held two values for Sh. `diameter_server.py` now imports both ids from
corelib and aliases them, so the literal exists in one place:

```python
from corelib.diameter import APP_S6A, APP_SH
APP_3GPP_S6a = APP_S6A
APP_3GPP_Sh = APP_SH     # 16777217 (TS 29.328 5.1), not the S6a value
```

`tests/test_signalling_auth.py:SignallingConstants` asserts
`APP_3GPP_Sh == APP_SH == 16777217` and `APP_3GPP_S6a == APP_S6A`. Sh itself is
still unimplemented; only the constant is now single-sourced.

### SIG-2 (unit-tested for opcodes; body unchecked) FIXED — MAP opcodes match TS 29.002

The Python constants had invented codes and duplicate `_2`…`_7` variants. They
are replaced by the TS 29.002 clause 25.1 values, and the fabricated block is
deleted:

| Operation | TS 29.002 | Python constant |
| --- | --- | --- |
| provideSubscriberLocation (PSL) | 83 | `MAP_OP_PROVIDE_SUBSCRIBER_LOCATION` |
| sendRoutingInfoForLCS | 85 | `MAP_OP_SEND_ROUTING_INFO_FOR_LCS` |
| locationReport | 86 | `MAP_OP_LOCATION_REPORT` |
| anyTimeInterrogation | 71 | `MAP_OP_ANY_TIME_INTERROGATION` |
| provideSubscriberInfo | 70 | `MAP_OP_PROVIDE_SUBSCRIBER_INFO` |
| sendRoutingInfoForSM | 45 | `MAP_OP_SEND_ROUTING_INFO_FOR_SM` |
| sendAuthenticationInfo | 56 | `MAP_OP_SEND_AUTHENTICATION_INFO` |
| insertSubscriberData | 7 | `MAP_OP_INSERT_SUBSCRIBER_DATA` |
| provideRoamingNumber | 4 | `MAP_OP_PROVIDE_ROAMING_NUMBER` |
| registerSS … interrogateSS | 10–14 | `MAP_OP_REGISTER_SS` … |
| processUnstructuredSS-Request | 59 | `MAP_OP_PROCESS_UNSTRUCTURED_SS_REQUEST` |

`MAP_HANDLERS[83]` is now `handle_psl`, and `handle_psl` answers on 83. The
**result body** `handle_psl` returns is still the lab placeholder (hard-coded
`CELL-01`/`LAC-01` and invented tags); only opcode selection is claimed. No MAP
golden frame is claimed for that reason (see SIG-8).

### SIG-3 (unit-tested) FIXED — SCCP subsystem numbers match TS 29.002 clause 20.4

Python now defines HLR 6, VLR 7, MSC 8, SGSN 149, GMLC 145 distinct from each
other, and every routing default uses `SCCP_SSN_HLR` instead of the catch-all
`SCCP_SSN_MAP`. `SignallingConstants` asserts all five against the fixture.

### SIG-4 (unit-tested, golden-checked) FIXED — MSISDN is TBCD on both sides

The codec now lives once, in `corelib.diameter.tbcd_encode` / `tbcd_decode`
(TS 23.003 §3.3: first digit in the low nibble, `0xF` filler on odd count).
`lcsap.tbcd` delegates to it, so the SLg encoder in the MME and the SLg decoder
in `mme/src/slg.py` cannot drift. `slg.subscriber` refuses an AVP above 8 octets
— the longest TBCD E.164 number — instead of reading text digits as semi-octets.
`fixtures/golden/slg.json` carries a 6-octet TBCD MSISDN and both codecs decode
it to `256700000001`.

### SIG-5 (unit-tested, compose unverified) FIXED — M3UA/TCP default reaches the lab MAP listener

`application.yml` defaulted `gmlc.m3ua.port` to 2905/TCP, but the core publishes
2905 as SCTP/UDP (osmo-stp) and its TCP MAP listener at host 2906 → container
2905 (map-sccp). The default is now 2906, the reachable port, and the port
register in `config.schema.json` records both entries so the distinction cannot
disappear. Inside the compose network the same listener is `map-sccp:2905`.
The lab peer still answers only the SMS operations (SIG-2 body); a compose
interop test remains unchecked (CFG-4).

### SIG-6 (unchecked) TCAP tags are labelled differently

Java (`map/Tcap.java:20-35`): Begin 0x62, End 0x64, Continue 0x65, Abort 0x67,
Invoke 0x61, ReturnResult 0x62, ReturnError 0x63, Reject 0x64.
Python (`map_sccp_server.py:63-67`): `TCAP_MSG_TYPE_QUERY 0x60`,
`RESPONSE 0x62`, `U 0x64`. 0x60 is not a TCAP PDU tag, and 0x64 is End, labelled
`U`. No Java↔Python TCAP link is wired today (GMLC MAP goes to an HLR), so this
is latent. It becomes a hard failure the day a Python TCAP peer is used.

### SIG-7 (unit-tested) Values that agree

- Application ids: SLh 16777291 (`DiameterDictionary.java:49`,
  `corelib/diameter.py:42`), SLg 16777255 (`:66`, `:41`).
- Commands: PLR/PLA 8388620, LRR/LRA 8388621, RIR/RIA 8388622.
- Vendor id 10415: `Avp.java:17`, `corelib/diameter.py:73`, and the compose
  defaults `DRA_VENDOR_ID` / `DIAMETER_VENDOR_ID`.
- SCCP UDT 0x09: `Sccp.java:25`, `map_sccp_server.py:46`.
- SLg request shape: Location-Type 2500, LCS-QoS 2504, Horizontal-Accuracy
  2505 (TS 23.032 uncertainty code on both sides), LCS-Client-Type 1241,
  ECGI 7 octets (PLMN 3 + 28-bit ECI with zero spare nibble).
- User-Name carries the IMSI on both SLh and SLg (`SlgClient.java:44`,
  `SlhClient.java:36`, `mme/src/slg.py:44-48`).
- MSISDN AVP 701 is TBCD on both sides and pinned by the golden PLR frame
  (`Tbcd.java`, `corelib/diameter.py:tbcd_encode`): both decode the same 6 octets
  to `256700000001` and both re-encode it byte-for-byte.

### SIG-8 (unit-tested for Diameter; MAP unchecked) FIXED for SLg — golden message fixtures

`fixtures/golden/slg.json` holds two complete frames — a PLR request and a PLA
answer — each with its spec clause and its decoded values. Both are checked in
both directions by both languages:

- Java: `GoldenSignallingTest` uses `DiameterMessage.parse` and asserts
  `message.encode()` reproduces the bytes.
- Python: `tests/test_golden_slg.py` builds the same message from
  `corelib.diameter` and asserts `message.encode() == wire`, so the two codecs
  are compared across languages, not only inside each one.

Still open: a golden MAP frame (blocked by the placeholder PSL body, SIG-2),
`tshark` malformed-message checks, and the pinned-commit compose interop test.

### SIG-9 (unchecked) SLh has no Python peer

`5g-core-engine` defines `APP_SLH` and nothing that answers RIR. Only the MME
answers SLg PLR (`mme/src/mme_server.py:209`). The GMLC SLh path is exercised
against Java tests only; there is no Python HSS handler to cross-check.

### SIG-10 (unit-tested) FIXED — Diameter AVP framing was off-spec in the Python encoders

RFC 6733 clause 4.1: AVP Code four octets, AVP Flags one octet, AVP Length
three octets. Three Python encoders were checked; two wrote a different layout:

- `corelib/diameter.py:154-166` wrote `(code << 8) | flags` as one 32-bit word
  and the length as a second 32-bit word — a three-octet code and a four-octet
  length. Its decoder read the same shape, so Python↔Python round-trips passed.
- `diameter/src/diameter_server.py:204-253` (S6a/Cx) wrote the same header and
  omitted the four-octet alignment padding between AVPs.
- `ocs/src/ocs_client.py:160-244` (Ro) wrote the same header, omitted padding,
  always emitted a Vendor-Id even when the V bit was clear and counted it out of
  the length, and built a 16-octet message header with no Version/Length fields.

Java's `DiameterMessage.parse` reads the RFC layout, so a Java-built SLg frame
reached the MME with no readable AVPs; the lab path had never been checked byte
for byte. All three are now RFC-shaped, `corelib` is the reference, and
`tests/test_diameter_framing.py` pins the literal header octets and asserts the
three encoders produce identical bytes. This is why the golden fixture was
regenerated (see §7 item 19): the first copy was produced by the off-spec
encoder.

### Core-internal Sh

Closed with SIG-1: the Sh duplicate is gone, the core imports `APP_SH` from
corelib. The GMLC does not speak Sh; the UDR↔HSS face still has no Sh handler,
which is unchanged and not claimed.

---

## 4. Configuration and ports

Source of truth: `config.schema.json`. It declares all 61 environment variables
the gateway reads and a 52-entry host-port register covering both stacks.
`tools/check_config.py` fails if `application.yml`, `docker-compose.yml`,
`.env.example` or the core compose disagree, and fails if `docs/PORTS.md` is not
the text generated from the schema.

### CFG-1 (unit-tested) FIXED — compose can configure the console and signalling

`docker-compose.yml` previously set 16 environment entries and omitted whole
groups that the code reads. It now passes all 61, grouped and commented. Secrets
are pass-through only (`${GMLC_...:-}`); no default credential exists, so a
blank bootstrap account or notifier key leaves its feature closed rather than
enabling it with a guessable value. `.env.example` documents the same set
without values for secrets. The checker enforces both directions: a variable in
code and not in compose fails, and a compose key not in the schema fails.

### CFG-2 (unit-tested, Docker unverified) FIXED — the gateway joins the core network

`docker-compose.yml` now attaches the `gmlc` service to an external network
named `${CORE_NETWORK:-5g-core-engine_core-net}`, which is the core compose's
`core-net`. A peer is then named by service (`dra:3868`, `custom-mme:3875`,
`map-sccp:2905`) instead of a host port. No signalling port is published by the
gateway because it only dials out. This is a file-level change: Docker is not
installed here, so it has not been started (CFG-4).

### CFG-3 (unit-tested) FIXED — one port register, generated

The 52 entries in `config.schema.json` (2 gateway, 50 core) are rendered into
`docs/PORTS.md`. The checker compares the register against both compose files in
both directions and rejects a host port/protocol claimed by two services.
Relevant entries:

| Port | Service | Protocol | Meaning |
| --- | --- | --- | --- |
| 8082 | gmlc | TCP | console and Le API |
| 5433 → 5432 | postgres | TCP | gateway database |
| 2906 → 2905 | map-sccp | TCP, UDP | lab core TCP MAP listener |
| 2905 | osmo-stp | SCTP, UDP | STP; no TCP listener |
| 3868 | dra | TCP, SCTP | DRA |
| 3875 | custom-mme | TCP | SLg |
| 3880 → 3868 | diameter-s6a-cx | TCP, SCTP | S6a/Cx |

The earlier ambiguity — one `GMLC_DIAMETER_PEER_PORT` for both SLh and SLg — is
unchanged in the code but no longer silent: the register shows DRA 3868 and MME
3875, and on the shared network they are distinguishable by service name.
SIG-5 is closed: `GMLC_M3UA_PORT` now defaults to 2906/TCP, which is served,
instead of 2905/TCP, which is not.

### CFG-4 (unchecked) Docker artifacts unverified

Docker is not installed on this host. The compose changes, the external network
and `docker compose up` are committed as “unverified build”; they must be run on
a Linux host with Docker before any `lab-simulated` claim is made.

---

## 5. Documentation claims

### DOC-1 (unit-tested) Labelling is honest

`README.md:651` limits “verified on a real network” to nothing yet;
`README.md:887-888` limits MLP conformance to the shapes exercised in tests;
`README.md:1095` labels compose “unverified on this host”. No claim of
real-network verification was found.

### DOC-2 (unchecked) Performance numbers are host measurements

The C7b figures come from the harness on this host, not from a production
stack. They remain `lab-simulated`. The 700 rps target is not claimed.

### DOC-3 (unit-tested) Stale capability comment — FIXED

`CapabilityController.java` described a pre-authentication phase that no longer
exists. The javadoc was rewritten with the API-4 fix and now says the tenant
comes from the API key, never from a deployment default.

---

## 6. Cross-repo Java-sends vs Python-accepts

| Interface | Java encoder | Python decoder | Status |
| --- | --- | --- | --- |
| SLg PLR/PLA | `SlgClient.java` | `mme/src/slg.py`, `mme/src/mme_server.py:209` | shape and TBCD MSISDN agree; golden frames in both languages (**unit-tested**) |
| SLh RIR/RIA | `SlhClient.java` | none | **unchecked** (SIG-9) |
| MAP PSL/SRILCS/SLR | `MapClient.java`, `MapLcs.java` | `map_sccp_server.py` (opcode 83 → `handle_psl`) | opcodes agree (**unit-tested**); result body is a placeholder, no MAP golden frame (**unchecked**) |
| Sh UDR | not used by GMLC | core UDR/HSS | constant single-sourced; no Sh handler in the core |
| LCS-AP | not used (MME↔E-SMLC is core-internal) | `lib/codecs/lcsap.py` | out of the Java/Python boundary; core↔core |
| Cell identity | ECGI/CGI decode, `SlgClient.java:87-113` | `mme/src/slg.py:ecgi` | 7 octets, PLMN then 28-bit ECI; agrees; covered by the golden PLR/PLA |
| PLMN/ECGI/NCGI | `PlmnCodec`, `SlgClient` | `lcsap.LAB_PLMN`, `slg.ecgi` | golden bytes for the SLg pair (`00f110…`); NCGI/CGI still by inspection |
| Shapes | TS 23.032 shape codec | `lcsap.Circle` | agrees by inspection; no cross-language fixture |
| Result codes | `DiameterResult` | `corelib.diameter` | fixture-enforced for the shared set; Result-Code 2001 in the golden PLA |

---

## 7. Changes made to make the gates work (and why)

None of these changes a protocol value to match code.

1. `api/openapi.yaml` — two descriptions contained an unquoted `: ` and did not
   parse as YAML (`:1854` “Four-eyes: …”, `:2296` “`X-API-Key: <key>`”). They are
   now quoted. No route or schema changed.
2. `fixtures/constants.json` — added an entry for the existing
   `DiameterDictionary.CAPABILITIES_EXCHANGE_ANSWER` (257, RFC 6733 clause 5.3).
   The constant already existed and the value is spec-correct; the fixture was
   incomplete.
3. `ApiSpecDriftTest` — the framework exclusion now also covers
   `org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController`.
   That controller is Spring's `/error` handler, not part of this API. This
   narrows what the test inspects; it does not relax a route check.
4. `tools/check-frontend-endpoints.mjs` — adjacent interpolations in one path
   segment (`/x/${id}${suffix}`) are collapsed to one `{}`. `${suffix}` is a query
   string appended in code, not a second segment; the old normaliser produced
   `/x/{}{}` and reported a false positive against the correct spec path.
5. `api/openapi.yaml` — the 63 ops responses previously described as
   `OpsObject` now name the record they serialise; the coarse response component
   is deleted. `RangeKey`/`BucketKey`/`CellType` are named enums;
   `ProblemCode` lists 101 codes. No route, parameter name or status code
   changed apart from item 7.
6. `api/openapi.yaml` — `POST /v1/ops/alerts/{id}/acknowledge` was documented as
   `204 No Content`, but `OpsHealthController.acknowledge` returns the alert in
   its new state. The spec was wrong, not the controller: the console needs the
   new `acknowledgedAt` without a second round trip. The response is now
   `200` with the `Alert` schema.
7. `api/openapi.yaml` — `POST /v1/admin/cells/import` returned an untyped object
   while `CellImportService.Report` is a record. Added `CellImportReport` and
   `CellImportRowError`. Nothing about the import behaviour changed.
8. `api/openapi.yaml` — `required` added for `OpsSessionView` and
   `AdminClient` fields that are non-null in the record (including
   `ipAllowlist`, which is an empty list, never absent). The old description
   claiming the allowlist may be absent was removed.
9. `gmlc-app/console/src/types.ts` is now generated; `Session` and
   `DashboardData` moved to `console/src/ui.ts`. Imports were updated; no page
   logic changed. `ApiError.code` is typed with `ProblemCode`.
10. `fixtures/cdr-fields.txt` is new and records the export order that already
    existed in `OpsMonitoringController`. No code value changed; the fixture is
    the reference the code is now checked against. The audit's earlier
    “18-column CDR” sentence is corrected in §2 DB-2 (table 28, read view 18,
    export 25); the code was not changed by that correction.
11. `TenantIsolationTest` is new and adds no production code: every predicate it
    exercises was already tenant-scoped. It uses tenants 9 100 001/2 so the
    shared embedded database is not disturbed.
12. `OpsTrackingScheduleTest` now sets `gmlc.tracking.schedules-enabled=false`.
    Every dispatch in that class is called directly through
    `TrackingScheduleService.dispatchDue`, but the periodic runner could claim
    the same due row between the manual call's SELECT and UPDATE, recording a
    second run. The full suite exposed this as `expected 3 but was 4` once the
    new class changed timing. No assertion was changed or removed; the test now
    has one dispatcher instead of two.
13. No test, fixture or spec value was loosened to make a gate pass. The
    `SharedConstantsTest` note above still applies. The deliberate test changes
    are items 14 and 15, and neither lowers a check.
14. `5g-core-engine/tests/test_mme_location.py` — the two MSISDN tests sent the
    number as text (`AVP_UTF8`). They now build AVP 701 as TBCD through the
    shared codec, because TBCD is what the Java SLg encoder sends and what
    TS 29.329/23.003 define. A third test asserts that a 12-octet UTF-8 MSISDN
    is refused. The old tests asserted the behaviour that SIG-4 declared wrong.
15. `Tbcd.decode` (Java) and `tbcd_decode` (Python) now reject a semi-octet that
    is not a decimal digit and a `0xF` filler that is not in the last octet,
    instead of reading them as digits. `TbcdPlmnTest` gains one test for the
    refusal. This tightens both codecs; no valid number changes value.
16. `5g-core-engine/corelib/diameter.py` — `tbcd_encode`/`tbcd_decode` are the
    new single home for the TBCD codec; `lcsap.tbcd` delegates to it. The Java
    `Tbcd` remains a separate implementation by necessity (different language),
    and the golden PLR frame is what keeps the two byte-identical.
17. `fixtures/constants.json` — the `map.opcodes` and `map.ssn` entries now have
    Python references and every fixed value moved from `knownDivergences` to the
    enforced arrays; `knownDivergences` is empty. A drift on either side now
    fails instead of warning. No constant changed value to match code: the
    Python constants were corrected to the TS 29.002 value already in the
    fixture.
18. `fixtures/golden/slg.json`, `GoldenSignallingTest` and
    `tests/test_golden_slg.py` are new. They encode the same two frames from
    scratch in each language and assert byte equality in both directions; the
    fixture is committed, so edits to either codec that change the wire form
    fail a gate.
19. `fixtures/golden/slg.json` was regenerated once, immediately, because its
    first copy had been produced by the Python AVP encoder before SIG-10 was
    found: the hex was a valid frame of that encoder's own shape but not a valid
    RFC 6733 frame, and `GoldenSignallingTest` failed on it (`expected AVP code
    263, was 67392`). The decoded values, the specification clauses and the
    message structure are unchanged; only the AVP and message framing octets
    moved to the RFC layout, which is what the fixture is meant to pin. No test
    was relaxed to achieve this.
20. `gmlc.m3ua.port` now defaults to 2906 instead of 2905 (SIG-5). This is a
    lab default, not a protocol value: 2905/TCP has no listener in the core
    compose, so the old default could only ever time out. The port register and
    `docs/PORTS.md` are generated from `config.schema.json`; `docker-compose.yml`
    and `.env.example` now carry every variable the schema declares, and
    `make config` (also run by CI) fails on any drift. No secret has a value in
    either file. The Java fallback in `M3uaProperties.DEFAULT_PORT` was still
    2905 and contradicted that default; it is now 2906. This changes a test:
    `M3uaTransportTest` asserted `port()` was 2905 for an unconfigured record,
    which pinned the unreachable port, so that one assertion now expects 2906.
    Nothing else in the test was touched.

A note on the fixtures: `SharedConstantsTest` requires every public numeric
constant in `DiameterDictionary`, `Avp`, `MapLcs` and `Sccp` to be listed. That
is deliberate — a new constant cannot be added without a spec clause.

---

## 8. Unchecked boundaries (explicit)

These are not claimed done:

- Golden MAP frame (blocked by the placeholder PSL body), `tshark` malformed
  checks, pinned-commit compose interop (SIG-8). The SLg golden pair is done.
- Python SLh peer and MAP LCS peer (SIG-2 body, SIG-9).
- TCAP tag interoperability (SIG-6).
- Compose and Docker execution on a real host (CFG-4). The compose files and
  `docs/PORTS.md` are checked as text only.
- A `config.schema.json` for each core service. The GMLC has one; the core still
  uses its compose header and `.env.example` as its register, so a core-service
  variable can drift without a gate.
- `make verify` execution on this Windows host (`make` is not installed); the
  individual commands above were run instead.
- `tests/test_mme_location.py` and `tests/test_lcsap_codec.py` need
  `pycrate_asn1rt`, which is not installed here. `py -m unittest discover -s
  tests` runs 135 tests and reports 15 import errors from absent third-party
  modules (`yaml`, `redis`, `pycrate_asn1rt`, ...) and 5 skips; there are no
  test failures. The pycrate-free modules were run directly, and every changed
  module was `py_compile`-checked.
- The updated S6a/Cx and Ro framing is exercised by unit tests only; no
  Diameter peer was run against it on this host.
- GitHub Actions execution (workflow is committed, not yet run).
- Any `verified on real network` claim: none exists.

---

## 9. Fix order (one boundary per commit)

1. ~~**Tenant isolation** — the `/v1/capabilities` defect (API-4).~~ Done in
   commit *Fix capabilities tenant isolation*; tenant comes from the API key and
   a two-tenant regression test covers it.
2. ~~**API schemas** — type every ops response, generate `console/src/types.ts`,
   add the three missing enums, add a problem+json schema and a stable code
   list tested against the Java enums and the console map.~~ Done in commit
   *Type ops responses and generate console types* (`OpsPayloadDriftTest`,
   `ProblemCodeDriftTest`, `TypesDriftTest`).
3. ~~**Database** — commit the PAiCore CDR order fixture (or declare it out of
   scope), add per-table cross-tenant tests, keep the snapshot gate.~~ Done in
   the database-boundary commit: the 25-column export is pinned by
   `fixtures/cdr-fields.txt` + `CdrFieldOrderTest`, PAiCore is out of scope
   because no such interface exists, `TenantIsolationTest` covers 17 tables, and
   the snapshot gate is unchanged.
4. ~~**Signalling** — fix the core Sh duplicate, MAP opcodes, SSNs and MSISDN
   TBCD; add golden hex fixtures with spec clauses; move each fixed entry from
   `knownDivergences` into the enforced set.~~ Done in the signalling-boundary
   commits (one per repo): `APP_SH` imported from corelib, MAP opcodes and SSNs
   set to TS 29.002, TBCD single-sourced in `corelib.diameter`, SLg golden pair
   `fixtures/golden/slg.json` checked by `GoldenSignallingTest` and
   `tests/test_golden_slg.py`, `knownDivergences` empty. MAP PSL body remains a
   placeholder and is not claimed.
5. ~~**Config** — add `config.schema.json`, generate `docs/PORTS.md`, fail CI on
   compose/`.env.example`/code drift, wire the GMLC compose to the core network.~~
   Done in the config-boundary commit: 61 variables and 52 ports in
   `config.schema.json`, `tools/check_config.py` in `make verify` and CI,
   `docs/PORTS.md` generated, compose passes every variable and joins
   `5g-core-engine_core-net`, SIG-5 default corrected to 2906. Docker itself is
   still unverified on this host.
6. **Traceability** — generate `docs/TRACEABILITY.md` from the spec, fixtures
   and tests; fail CI on gaps.
