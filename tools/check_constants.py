#!/usr/bin/env python3
"""Check the Python side of fixtures/constants.json.

The Java gateway is checked by SharedConstantsTest. This script is the other
half: it imports the Python core's constants and fails when they disagree with
the fixture. Run it in CI with both repositories checked out side by side:

    python tools/check_constants.py --core-root ../5g-core-engine

Entries without a "python" key belong to Java only and are skipped. Known
Python-side divergences are listed in the fixture and printed as warnings; they
become enforced entries when the owning boundary is fixed.
"""

from __future__ import annotations

import argparse
import importlib
import json
import re
import sys
from pathlib import Path

SECTIONS = [
    "diameter.vendorId",
    "diameter.applicationIds",
    "diameter.commands",
    "diameter.avps",
    "diameter.enumerations",
    "diameter.resultCodes",
    "map.opcodes",
    "map.ssn",
    "map.sccpMessageTypes",
]

INT_LITERAL = re.compile(r"^(0[xX][0-9a-fA-F]+|\d+)$")


def fixture() -> dict:
    path = Path(__file__).resolve().parents[1] / "fixtures" / "constants.json"
    return json.loads(path.read_text(encoding="utf-8"))


def section(root: dict, dotted: str):
    node = root
    for part in dotted.split("."):
        node = node[part]
    return node


def resolve(core_root: Path, spec: str):
    if spec.startswith("file:"):
        relative, name = spec[len("file:") :].split("#", 1)
        text = (core_root / relative).read_text(encoding="utf-8")
        match = re.search(
            r"^" + re.escape(name) + r"\s*=\s*([0-9A-Fa-fxX]+)\b", text, re.MULTILINE
        )
        if match is None:
            raise KeyError(f"{name} not found in {relative}")
        return int(match.group(1), 0)

    module_name, _, name = spec.rpartition(".")
    if not module_name:
        raise KeyError(f"{spec} is not a module.NAME path")
    if str(core_root) not in sys.path:
        sys.path.insert(0, str(core_root))
    module = importlib.import_module(module_name)
    return getattr(module, name)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--core-root",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "5g-core-engine",
        help="path to the Python core repository",
    )
    args = parser.parse_args()

    if not args.core_root.is_dir():
        print(f"core root not found: {args.core_root}", file=sys.stderr)
        return 2

    root = fixture()
    problems: list[str] = []
    checked = 0

    for dotted in SECTIONS:
        for entry in section(root, dotted):
            if "python" not in entry:
                continue
            checked += 1
            where = f"{dotted}[{entry['name']}]"
            try:
                actual = int(resolve(args.core_root, entry["python"]))
            except Exception as error:  # noqa: BLE001 - report and keep checking
                problems.append(f"{where}: cannot resolve {entry['python']} ({error})")
                continue
            if actual != int(entry["value"]):
                problems.append(
                    f"{where}: {entry['python']} is {actual}, fixture says "
                    f"{entry['value']} per {entry['spec']}"
                )

    for divergence in root.get("knownDivergences", []):
        print(
            f"WARN known divergence [{divergence['boundary']}] {divergence['what']} "
            f"({divergence.get('pythonWhere') or divergence.get('javaWhere')})",
            file=sys.stderr,
        )

    if problems:
        for problem in problems:
            print(f"FAIL {problem}", file=sys.stderr)
        return 1

    print(f"OK {checked} Python constants match fixtures/constants.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
