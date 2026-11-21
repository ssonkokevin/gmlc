# Build notes (development workstation)

These notes describe building this repository on Kevin's Windows machine. They
are not deployment instructions.

## Maven is offline-first here

- Maven lives at `C:\tools\apache-maven-3.9.9\bin\mvn`, not on PATH.
- Builds are run with `-o` (offline). The local repository under
  `C:\Users\KEVIN\.m2` is primed; the network path intercepts TLS, so an online
  build can fail with PKIX errors. That is an environment fact, not a code
  change.
- `mvn -o test` runs the whole reactor: one module, `gmlc-app`, about 25 s,
  most of it embedded PostgreSQL start/stop.
- `bcprov-jdk18on:1.78.1` was fetched from Maven Central with `curl` (jar and
  pom only) and placed in `~/.m2` by hand, because the `dependency` plugin is
  not primed offline. It is the only artifact added for Argon2id. On a host
  with a working online Maven, resolve it normally; on this host, the jar is
  already in the local repository and the offline build finds it.

## The Spring Boot plugin version is pinned to 4.2.0

The application runs on Spring Boot 3.3.4 (the BOM). The offline repository has
only the 4.2.0 `spring-boot-maven-plugin` primed, so the parent pins that
version in `pluginManagement`. It repackages the 3.3.4 application correctly -
the produced `gmlc-app/target/gmlc.jar` starts and serves in tests. When an
online build is available, align the plugin with the Boot BOM and remove the
note.

## PostgreSQL

Tests use zonky `embedded-postgres` 2.2.2 with the 17.12.0 Windows binaries,
so no Docker is needed. Flyway 10.10 supports PostgreSQL 16 officially and runs
against 17 with a warning; the compose file pins `postgres:16` for that reason.

## Docker

Docker is not installed on this machine. `Dockerfile` and `docker-compose.yml`
are written but have never been built or run here - treat them as unverified
until they are.

## The dashboard was checked in a browser, not pixel-verified

The page was exercised against a locally run `gmlc.jar` and a scratch
PostgreSQL 17 cluster (the zonky binaries, no Docker): connect with a
registered key, grant consent and locate (which fails closed with
`NO_LCS_SOURCE` because no Diameter or MAP peer is configured), read the
schematic map, open a log row and use Forget. The browser surface had no
visible viewport, so no screenshot was taken; the fetch contract, headers and
static files are covered by `DashboardTest`. A pixel review on a normal desktop
browser is still worth doing before an operator demo.

## The MAP face has never seen a real HLR

`MapClient` and `MapCellLocator` are tested against a loopback `M3uaTransport`
that parses the Begin, answers with a hand-built MAP result and checks the
called and calling addresses. That proves the dialogue shape and the codes the
gateway reports; it does not prove interoperability with an operator HLR, MSC
or SGSN. The lab run needs an STP reachable over M3UA/TCP (see the TCP, not
SCTP, limit in the README), a test point code, `GMLC_MAP_MLC_NUMBER`, and
`GMLC_LOCATION_TRANSPORT=MAP`.
