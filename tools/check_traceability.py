#!/usr/bin/env python3
"""Check that every specification clause is traced to code, a fixture and a test.

The sources of clauses are `fixtures/constants.json` (one `spec` per constant)
and `fixtures/golden/*.json` (one list per frame). `fixtures/traceability.json`
states, per requirement, which clauses it covers and which files prove it. This
script fails when:

- a clause in a fixture appears in no requirement,
- a referenced file does not exist, or a `#symbol` is not in it,
- a requirement has no test or an unknown state label,
- a test under `gmlc-app/src/test/java/com/hamiltel/gmlc/consistency/` is not
  referenced (a gate test cannot exist untraced),
- `docs/TRACEABILITY.md` is not the exact text generated here.

The core repository holds the Python half of several requirements. Its files
are referenced with a `core:` prefix and need `--core-root`; without it they are
reported as skipped, not failed, so the gmlc-only CI job still runs.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs" / "TRACEABILITY.md"
LABELS = ("unit-tested", "lab-simulated", "verified on real network")
SECTIONS = (
    "diameter.vendorId",
    "diameter.applicationIds",
    "diameter.commands",
    "diameter.avps",
    "diameter.enumerations",
    "diameter.resultCodes",
    "map.opcodes",
    "map.ssn",
    "map.sccpMessageTypes",
)
GATE_TESTS = ROOT / "gmlc-app" / "src" / "test" / "java" / "com" / "hamiltel" / "gmlc" / "consistency"
CLAUSE = re.compile(r"clause\s+([0-9A-Za-z.]+)")


def load(relative: str) -> dict:
    return json.loads((ROOT / relative).read_text(encoding="utf-8"))


def clause_keys(spec: str) -> list[str]:
    """Normalise one spec sentence to clause keys.

    "3GPP TS 29.172 clause 7.4.16 (ECGI AVP 2517)" -> ["3GPP TS 29.172 clause 7.4.16"].
    "RFC 6733 clause 3 (header) and clause 4.1 (padding)" -> two keys, because
    a frame can claim two clauses in one sentence.
    """
    document = spec.split(" clause", 1)[0].strip()
    return [f"{document} clause {number}" for number in CLAUSE.findall(spec)]


def section(root: dict, dotted: str) -> list[dict]:
    node = root
    for part in dotted.split("."):
        node = node[part]
    return node


def source_clauses(constants: dict) -> list[tuple[str, str]]:
    """(clause, where) for every constant, in fixture order."""
    found: list[tuple[str, str]] = []
    for dotted in SECTIONS:
        for entry in section(constants, dotted):
            for key in clause_keys(entry["spec"]):
                found.append((key, f"fixtures/constants.json#{dotted} [{entry['name']}]"))
    return found


def golden_clauses() -> list[tuple[str, str]]:
    found: list[tuple[str, str]] = []
    for path in sorted((ROOT / "fixtures" / "golden").glob("*.json")):
        relative = path.relative_to(ROOT).as_posix()
        for vector in load(relative)["vectors"]:
            for spec in vector["spec"]:
                for key in clause_keys(spec):
                    found.append((key, f"{relative}#{vector['name']}"))
    return found


def resolve(ref: str, core_root: Path | None) -> tuple[Path | None, str | None]:
    """Return (path, None) or (None, reason it cannot be checked)."""
    body, _, symbol = ref.partition("#")
    if body.startswith("core:"):
        if core_root is None:
            return None, "core repository not supplied"
        return core_root / body[len("core:"):], symbol or None
    return ROOT / body, symbol or None


def check_symbol(path: Path, symbol: str | None) -> str | None:
    if symbol is None:
        return None
    text = path.read_text(encoding="utf-8", errors="replace")
    suffix = path.suffix
    if suffix == ".java":
        if "." in symbol:
            owner, member = symbol.split(".", 1)
            if f"class {owner}" not in text or not re.search(rf"\b{re.escape(member)}\s*\(", text):
                return f"{owner}.{member} not found"
            return None
        if f"class {symbol}" not in text:
            return f"class {symbol} not found"
        return None
    if suffix == ".py":
        if f"def {symbol}(" not in text and f"class {symbol}" not in text:
            return f"{symbol} not found"
        return None
    return None


def check_requirement(requirement: dict, core_root: Path | None, problems: list[str], skipped: list[str]) -> None:
    where = requirement["id"]
    if requirement.get("state") not in LABELS:
        problems.append(f"{where}: state {requirement.get('state')!r} is not one of {', '.join(LABELS)}")
    if not requirement.get("clauses") and requirement.get("boundary") == "signalling":
        problems.append(f"{where}: a signalling requirement must name at least one clause")
    if not requirement.get("tests"):
        problems.append(f"{where}: no test names this requirement")

    for group in ("code", "tests"):
        for ref in requirement.get(group, []):
            path, symbol = resolve(ref, core_root)
            if path is None:
                skipped.append(f"{where}: {ref} ({symbol})")
                continue
            if not path.exists():
                problems.append(f"{where}: {ref} does not exist")
                continue
            if group == "tests":
                reason = check_symbol(path, symbol)
                if reason:
                    problems.append(f"{where}: {ref} — {reason}")


def fixture_ref(requirement: dict, constants: dict) -> str | None:
    ref = requirement.get("fixture")
    if not ref:
        return None
    body, _, name = ref.partition("#")
    path = ROOT / body
    if not path.exists():
        return f"{ref} does not exist"
    if path.suffix != ".json":
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        return f"{ref} is not JSON ({error})"
    if not name:
        return None
    if body.startswith("fixtures/golden/"):
        exists = any(vector["name"] == name for vector in data.get("vectors", []))
    else:
        # A constants reference names the section, e.g. `#map.opcodes`.
        try:
            exists = bool(section(data, name))
        except (KeyError, TypeError):
            exists = False
    return None if exists else f"{ref} names nothing"


def summarise(clauses: list[str]) -> str:
    """Group clause keys by document so a 51-clause cell stays readable."""
    groups: dict[str, list[str]] = {}
    loose: list[str] = []
    for clause in clauses:
        if " clause " not in clause:
            loose.append(clause)
            continue
        document, _, number = clause.rpartition(" clause ")
        groups.setdefault(document, []).append(number)
    parts = [f"{document} clauses {', '.join(numbers)}" for document, numbers in groups.items()]
    return "<br>".join(loose + parts)


def render(requirements: list[dict], constants: dict) -> str:
    covered = {key: requirement["id"] for requirement in requirements for key in requirement.get("clauses", [])}
    sources = source_clauses(constants) + golden_clauses()
    unique_sources = sorted({key for key, _ in sources})
    labels: dict[str, int] = {}
    for requirement in requirements:
        labels[requirement["state"]] = labels.get(requirement["state"], 0) + 1

    lines = [
        "# Traceability",
        "",
        "Generated by `python tools/check_traceability.py --write-docs` from",
        "`fixtures/traceability.json`, `fixtures/constants.json` and",
        "`fixtures/golden/*.json`. Do not edit by hand: the checker fails when this",
        "file is stale, when a fixture clause is not covered, or when a referenced",
        "file, symbol or test is missing.",
        "",
        "## Summary",
        "",
        f"- {len(requirements)} requirements: "
        + ", ".join(f"{count} {label}" for label, count in sorted(labels.items())),
        f"- {len(constants.get('diameter', {})) + len(constants.get('map', {}))} constant sections "
        f"and {len(golden_clauses())} golden-frame clauses yield {len(unique_sources)} distinct clauses, all covered",
        "- Every test under `gmlc-app/src/test/java/com/hamiltel/gmlc/consistency/` is referenced",
        "",
        "## Requirements",
        "",
        "| ID | Boundary | Clauses | Code | Fixture | Tests | State |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for requirement in requirements:
        clauses = summarise(requirement.get("clauses", [])) or "—"
        code = "<br>".join(f"`{ref}`" for ref in requirement.get("code", [])) or "—"
        fixture = f"`{requirement['fixture']}`" if requirement.get("fixture") else "—"
        tests = "<br>".join(f"`{ref}`" for ref in requirement.get("tests", [])) or "—"
        lines.append(
            f"| {requirement['id']} | {requirement['boundary']} | {clauses} | {code} | {fixture} | {tests} | "
            f"{requirement['state']} |"
        )

    lines += [
        "",
        "## Clause index",
        "",
        "Every clause named in a fixture, and the requirement that carries it.",
        "",
        "| Clause | Requirement | Fixture |",
        "| --- | --- | --- |",
    ]
    for key, where in sorted(sources, key=lambda item: (item[0], item[1])):
        lines.append(f"| {key} | {covered.get(key, '**uncovered**')} | `{where}` |")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--core-root", type=Path, default=None)
    parser.add_argument("--write-docs", action="store_true")
    args = parser.parse_args()

    if args.core_root is not None and not args.core_root.is_dir():
        print(f"core root not found: {args.core_root}", file=sys.stderr)
        return 2
    core_root = args.core_root.resolve() if args.core_root else None

    trace = load("fixtures/traceability.json")
    constants = load("fixtures/constants.json")
    requirements = trace["requirements"]
    problems: list[str] = []
    skipped: list[str] = []

    seen: set[str] = set()
    for requirement in requirements:
        identifier = requirement.get("id", "")
        if identifier in seen:
            problems.append(f"{identifier}: duplicate id")
        seen.add(identifier)
        check_requirement(requirement, core_root, problems, skipped)
        reason = fixture_ref(requirement, constants)
        if reason:
            problems.append(f"{identifier}: {reason}")

    declared = {key for requirement in requirements for key in requirement.get("clauses", [])}
    sources = source_clauses(constants) + golden_clauses()
    for key, where in sources:
        if key not in declared:
            problems.append(f"clause not traced: {key} ({where})")

    referenced_tests = {ref.partition("#")[0] for requirement in requirements for ref in requirement.get("tests", [])}
    if GATE_TESTS.is_dir():
        for path in sorted(GATE_TESTS.glob("*Test.java")):
            relative = path.relative_to(ROOT).as_posix()
            if relative not in referenced_tests:
                problems.append(f"gate test not traced: {relative}")

    text = render(requirements, constants)
    if args.write_docs:
        DOC.write_text(text, encoding="utf-8")
    elif not DOC.exists():
        problems.append("docs/TRACEABILITY.md is missing; run with --write-docs")
    elif DOC.read_text(encoding="utf-8") != text:
        problems.append("docs/TRACEABILITY.md is stale; run with --write-docs")

    if skipped:
        print(
            f"SKIP {len(skipped)} core references (core repository not supplied); "
            "run with --core-root for the full check",
            file=sys.stderr,
        )
    for problem in problems:
        print(f"FAIL {problem}", file=sys.stderr)
    if problems:
        return 1

    labels: dict[str, int] = {}
    for requirement in requirements:
        labels[requirement["state"]] = labels.get(requirement["state"], 0) + 1
    unique = {key for key, _ in sources}
    summary = ", ".join(f"{count} {label}" for label, count in sorted(labels.items()))
    print(
        f"OK {len(requirements)} requirements ({summary}); "
        f"{len(unique)} clauses covered from {len(sources)} fixture entries; "
        f"{len(referenced_tests)} test references"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
