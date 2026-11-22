# Consistency gates. Run from the gmlc repository root.
#
# `make verify` is the full combined-workspace gate and assumes the Python core
# is checked out next to this repository (../5g-core-engine). CI, which checks
# out one repository, runs `make verify-ci` and notes that the Python half of
# the shared-constants check is not available there.

SHELL := /bin/sh
PYTHON ?= python3
MVN ?= mvn

.PHONY: verify verify-ci backend frontend console constants

verify: backend frontend console constants

verify-ci: backend frontend console

backend:
	cd gmlc-app && $(MVN) -B test

frontend:
	node tools/check-frontend-endpoints.mjs

console:
	cd gmlc-app/console && npm run build

constants:
	$(PYTHON) tools/check_constants.py --core-root ../5g-core-engine
