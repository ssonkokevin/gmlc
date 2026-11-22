# Consistency gates. Run from the gmlc repository root.
#
# `make verify` is the full combined-workspace gate and assumes the Python core
# is checked out next to this repository (../5g-core-engine). CI, which checks
# out one repository, runs `make verify-ci` and notes that the Python halves of
# the shared-constants and golden-frame checks are not available there.

SHELL := /bin/sh
PYTHON ?= python3
MVN ?= mvn

.PHONY: verify verify-ci backend frontend console constants config golden traceability

verify: backend frontend console constants config golden traceability

verify-ci: backend frontend console config traceability

backend:
	cd gmlc-app && $(MVN) -B test

frontend:
	node tools/check-frontend-endpoints.mjs

console:
	cd gmlc-app/console && npm run build

constants:
	$(PYTHON) tools/check_constants.py --core-root ../5g-core-engine

# Checks application.yml, docker-compose.yml and .env.example against
# config.schema.json, verifies the port register against both composes, and
# fails if docs/PORTS.md is stale. The core compose half is skipped when the
# core repository is not present.
config:
	$(PYTHON) tools/check_config.py --core-root ../5g-core-engine

# Decodes and re-encodes fixtures/golden/slg.json with the Python codec; the Java
# half runs inside `backend` (GoldenSignallingTest).
golden:
	cd ../5g-core-engine && $(PYTHON) -m unittest tests.test_golden_slg -v

# Every clause in the fixtures must be named by a requirement that points at an
# existing file and test, and docs/TRACEABILITY.md must be the generated text.
traceability:
	$(PYTHON) tools/check_traceability.py --core-root ../5g-core-engine
