# Ports and environment

Generated from `config.schema.json` by `tools/check_config.py --write-docs`.
Do not edit by hand.

## Host ports

| Host port | Container | Protocol | Service | Stack |
| --- | --- | --- | --- | --- |
| 2152 | 2152 | udp | custom-upf | core |
| 2905 | 2905 | sctp | osmo-stp | core |
| 2905 | 2905 | udp | osmo-stp | core |
| 2906 | 2905 | tcp | map-sccp | core |
| 2906 | 2905 | udp | map-sccp | core |
| 2907 | 2906 | sctp | camel | core |
| 2907 | 2906 | tcp | camel | core |
| 2908 | 2905 | sctp | scp | core |
| 3000 | 3000 | tcp | grafana | core |
| 3001 | 80 | tcp | oam-frontend | core |
| 3306 | 3306 | tcp | db | core |
| 3307 | 3306 | tcp | hssdb | core |
| 3868 | 3868 | sctp | dra | core |
| 3868 | 3868 | tcp | dra | core |
| 3870 | 3869 | sctp | ocs | core |
| 3870 | 3869 | tcp | ocs | core |
| 3871 | 3869 | tcp | scp | core |
| 3873 | 3870 | tcp | custom-pcf | core |
| 3874 | 3871 | tcp | custom-pcf | core |
| 3875 | 3875 | tcp | custom-mme | core |
| 3880 | 3868 | sctp | diameter-s6a-cx | core |
| 3880 | 3868 | tcp | diameter-s6a-cx | core |
| 6379 | 6379 | tcp | redis | core |
| 8000 | 8000 | tcp | custom-nrf | core |
| 8001 | 8001 | tcp | custom-amf | core |
| 8002 | 7777 | tcp | custom-smf | core |
| 8003 | 8003 | tcp | custom-lmf | core |
| 8004 | 7777 | tcp | custom-pcf | core |
| 8006 | 8006 | tcp | custom-udr | core |
| 8007 | 8080 | tcp | custom-udm | core |
| 8075 | 8075 | tcp | custom-mme | core |
| 8081 | 8081 | tcp | oam | core |
| 8090 | 8090 | tcp | hss | core |
| 8805 | 8805 | udp | custom-upf | core |
| 8806 | 8805 | udp | custom-smf | core |
| 9082 | 9082 | tcp | esmlc-sim | core |
| 9090 | 9090 | tcp | prometheus | core |
| 18000 | 8000 | tcp | nrf | core |
| 18080 | 80 | tcp | amf | core |
| 18081 | 80 | tcp | smf | core |
| 18082 | 80 | tcp | ausf | core |
| 18083 | 80 | tcp | udm | core |
| 18084 | 80 | tcp | pcf | core |
| 18085 | 8805 | udp | smf | core |
| 18086 | 8805 | udp | upf | core |
| 18087 | 80 | tcp | chf | core |
| 18152 | 2152 | udp | upf | core |
| 27017 | 27017 | tcp | mongo | core |
| 38413 | 38412 | sctp | amf | core |
| 42050 | 42050 | tcp | custom-upf | core |
| 5433 | 5432 | tcp | postgres | gmlc |
| 8082 | 8082 | tcp | gmlc | gmlc |

## Environment


### api

| Variable | Type | Default | Secret | Meaning |
| --- | --- | --- | --- | --- |
| `GMLC_ADMIN_KEY` | string | *(empty)* | yes | Operator key for POST /v1/admin/clients; blank disables the admin API |

### console

| Variable | Type | Default | Secret | Meaning |
| --- | --- | --- | --- | --- |
| `GMLC_OPS_ALERTS_CELL_GAP` | integer | 1 | no | Cell-gap threshold |
| `GMLC_OPS_ALERTS_ENABLED` | boolean | true | no | Peer/cell alert evaluation |
| `GMLC_OPS_ALERTS_ERROR_RATE` | number | 0.25 | no | Error-rate threshold |
| `GMLC_OPS_ALERTS_EVAL_SECONDS` | integer | 60 | no | Alert evaluation interval |
| `GMLC_OPS_ALERTS_MIN_FINISHED` | integer | 10 | no | Finished requests needed before an error rate is judged |
| `GMLC_OPS_ALERTS_PEER_FAILURES` | integer | 3 | no | Failed exchanges before a peer is DOWN |
| `GMLC_OPS_ALERTS_WINDOW_MINUTES` | integer | 15 | no | Alert look-back window |
| `GMLC_OPS_ARGON2_ITERATIONS` | integer | 2 | no | Argon2id iteration count |
| `GMLC_OPS_ARGON2_MEMORY_KIB` | integer | 19456 | no | Argon2id memory cost |
| `GMLC_OPS_ARGON2_PARALLELISM` | integer | 1 | no | Argon2id parallelism |
| `GMLC_OPS_BOOTSTRAP_PASSWORD` | string | *(empty)* | yes | First administrator password (secret); blank leaves the console closed |
| `GMLC_OPS_BOOTSTRAP_TOTP_SECRET` | string | *(empty)* | yes | First administrator TOTP seed (secret) |
| `GMLC_OPS_BOOTSTRAP_USER` | string | *(empty)* | no | First administrator username; blank leaves the console closed |
| `GMLC_OPS_BREAK_GLASS_CODE_HASH` | string | *(empty)* | yes | SHA-256 hex of the break-glass code; blank disables it |
| `GMLC_OPS_COOKIE_SECURE` | boolean | true | no | Secure session cookie; false only on a plain-HTTP lab |
| `GMLC_OPS_ENVIRONMENT` | enum | LAB (LAB, BENCH, PRODUCTION) | no | LAB, BENCH or PRODUCTION; labels every console figure |
| `GMLC_OPS_LOCKOUT_MINUTES` | integer | 15 | no | Lockout duration |
| `GMLC_OPS_LOCKOUT_THRESHOLD` | integer | 5 | no | Failed sign-ins before lockout |
| `GMLC_OPS_SESSION_ABSOLUTE_HOURS` | integer | 8 | no | Console absolute session lifetime |
| `GMLC_OPS_SESSION_IDLE_MINUTES` | integer | 15 | no | Console idle timeout |
| `GMLC_OPS_SLO_P95_MS` | integer | 0 | no | p95 objective shown beside the measured percentile; 0 means none agreed |
| `GMLC_OPS_TOTP_ISSUER` | string | GMLC | no | Issuer label in the operator's authenticator app |
| `GMLC_OPS_TOTP_PERIOD_SECONDS` | integer | 30 | no | TOTP period |

### database

| Variable | Type | Default | Secret | Meaning |
| --- | --- | --- | --- | --- |
| `GMLC_DB_PASSWORD` | string | *(empty)* | yes | Database password (secret) |
| `GMLC_DB_POOL` | integer | 10 | no | Hikari maximum pool size |
| `GMLC_DB_URL` | string | jdbc:postgresql://localhost:5432/gmlc | no | JDBC URL of the GMLC database |
| `GMLC_DB_USER` | string | gmlc | no | Database role |

### diameter

| Variable | Type | Default | Secret | Meaning |
| --- | --- | --- | --- | --- |
| `GMLC_DIAMETER_ORIGIN_HOST` | string | gmlc.local | no | Diameter Origin-Host of this GMLC |
| `GMLC_DIAMETER_ORIGIN_REALM` | string | local | no | Diameter Origin-Realm of this GMLC |
| `GMLC_DIAMETER_PEER_HOST` | string | *(empty)* | no | DRA/HSS/MME address; blank leaves signalling disabled |
| `GMLC_DIAMETER_PEER_PORT` | integer | 3868 | no | Diameter peer port |
| `GMLC_DIAMETER_TIMEOUT_MS` | integer | 5000 | no | Diameter exchange timeout |

### http

| Variable | Type | Default | Secret | Meaning |
| --- | --- | --- | --- | --- |
| `GMLC_PORT` | integer | 8082 | no | Spring Boot server.port |

### location

| Variable | Type | Default | Secret | Meaning |
| --- | --- | --- | --- | --- |
| `GMLC_EMERGENCY_OVERRIDE` | boolean | false | no | Allow EMERGENCY requests without stored consent; every use is audited |
| `GMLC_LCS_CLIENT_NAME` | string | GMLC | no | LCS-ClientID the serving network sees |
| `GMLC_LOCATION_TRANSPORT` | enum | DIAMETER (DIAMETER, MAP) | no | Le interface transport: DIAMETER (SLh/SLg) or MAP (SS7) |
| `GMLC_SUBJECT_PEPPER` | string | *(empty)* | yes | HMAC key for subscriber pseudonyms; blank falls back to unkeyed SHA-256 |

### map

| Variable | Type | Default | Secret | Meaning |
| --- | --- | --- | --- | --- |
| `GMLC_M3UA_HOST` | string | *(empty)* | no | STP/MAP peer address; blank leaves MAP disabled |
| `GMLC_M3UA_LOCAL_GT` | string | *(empty)* | no | Local global title |
| `GMLC_M3UA_LOCAL_PC` | integer | 0 | no | Local point code; 0 leaves the link disabled |
| `GMLC_M3UA_NI` | enum | 2 (0, 1, 2) | no | Network indicator |
| `GMLC_M3UA_PORT` | integer | 2906 | no | M3UA/TCP peer port; 2906 is the lab core's map-sccp listener |
| `GMLC_M3UA_REMOTE_PC` | integer | 0 | no | Remote point code |
| `GMLC_M3UA_ROUTING_CONTEXT` | integer | -1 | no | M3UA routing context; -1 means none |
| `GMLC_M3UA_TIMEOUT_MS` | integer | 5000 | no | M3UA exchange timeout |
| `GMLC_MAP_HLR_PC` | integer | 0 | no | HLR point code |
| `GMLC_MAP_MLC_NUMBER` | string | *(empty)* | no | E.164 LCS number of this GMLC; blank leaves MAP disabled |

### tracking

| Variable | Type | Default | Secret | Meaning |
| --- | --- | --- | --- | --- |
| `GMLC_TRACKING_BATCH_SIZE` | integer | 20 | no | Trails read per tick |
| `GMLC_TRACKING_FLUSH_SECONDS` | integer | 30 | no | Trail flush interval |
| `GMLC_TRACKING_INITIAL_DELAY_SECONDS` | integer | 45 | no | Tracking runner start delay |
| `GMLC_TRACKING_MIN_INTERVAL_SECONDS` | integer | 300 | no | Minimum schedule interval (no licence metering is wired) |
| `GMLC_TRACKING_NOTIFIER_KEY` | string | *(empty)* | yes | Key that seals channel signing secrets (secret); blank refuses channel creation |
| `GMLC_TRACKING_SCHEDULES_ENABLED` | boolean | true | no | Automated tracking schedules |
| `GMLC_TRACKING_TIMEOUT_SECONDS` | integer | 10 | no | Tracking request timeout |

### webhooks

| Variable | Type | Default | Secret | Meaning |
| --- | --- | --- | --- | --- |
| `GMLC_WEBHOOKS_BATCH_SIZE` | integer | 50 | no | Webhooks per flush |
| `GMLC_WEBHOOKS_ENABLED` | boolean | true | no | Outbound geofence webhook delivery |
| `GMLC_WEBHOOKS_FLUSH_SECONDS` | integer | 10 | no | Webhook flush interval |
| `GMLC_WEBHOOKS_INITIAL_DELAY_SECONDS` | integer | 15 | no | Webhook runner start delay |
| `GMLC_WEBHOOKS_MAX_ATTEMPTS` | integer | 5 | no | Webhook attempts before giving up |
| `GMLC_WEBHOOKS_TIMEOUT_SECONDS` | integer | 5 | no | Webhook request timeout |
