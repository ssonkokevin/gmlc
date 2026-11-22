#!/usr/bin/env python3
"""Check the GMLC's configuration against config.schema.json.

One source of truth: every environment variable the gateway reads and every
host port either stack publishes is declared in config.schema.json. This script
fails when application.yml, docker-compose.yml, .env.example or the core
compose disagree with it, and it regenerates docs/PORTS.md from the same file.

    python tools/check_config.py                          # gateway only
    python tools/check_config.py --core-root ../5g-core-engine
    python tools/check_config.py --write-docs              # after a register change

The core half is skipped, with a note, when the core repository is not present
(CI checks out one repository; `make verify` runs both).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "config.schema.json"
YML = ROOT / "gmlc-app" / "src" / "main" / "resources" / "application.yml"
COMPOSE = ROOT / "docker-compose.yml"
ENV_EXAMPLE = ROOT / ".env.example"
PORTS_DOC = ROOT / "docs" / "PORTS.md"

VARIABLE = re.compile(r"\$\{([A-Z0-9_]+)(?::([^}]*))?\}")
TYPES = {
    "integer": re.compile(r"^-?\d+$"),
    "number": re.compile(r"^-?\d+(\.\d+)?$"),
    "boolean": re.compile(r"^(true|false)$"),
}


def parse_env_file(text: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        values[key.strip()] = value.strip()
    return values


def compose_services(text: str) -> dict[str, dict]:
    """Just enough YAML: services, their environment and ports."""
    services: dict[str, dict] = {}
    service = None
    section = None
    for line in text.splitlines():
        match = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if match:
            service = match.group(1)
            services[service] = {"environment": {}, "ports": []}
            section = None
            continue
        if service is None:
            continue
        if re.match(r"^    environment:\s*$", line):
            section = "environment"
            continue
        if re.match(r"^    ports:\s*$", line):
            section = "ports"
            continue
        if re.match(r"^    [A-Za-z]", line):
            section = None
        if section == "environment":
            match = re.match(r"^      ([A-Z0-9_]+):\s*(.*)$", line)
            if match:
                services[service]["environment"][match.group(1)] = match.group(2).strip()
            continue
        if section == "ports":
            match = re.match(r'^\s+- "([^"]+)"', line)
            if match:
                services[service]["ports"].append(parse_port(match.group(1)))
    return services


def parse_port(spec: str) -> dict:
    with_default = re.match(r"^\$\{[A-Z0-9_]+:-(\d+)\}:(\d+)(?:/(\w+))?$", spec)
    literal = re.match(r"^(\d+):(\d+)(?:/(\w+))?$", spec)
    match = with_default or literal
    if match is None:
        raise ValueError(f"unparsed port mapping {spec!r}")
    host, container, protocol = match.groups()
    return {
        "port": int(host),
        "container_port": int(container),
        "protocol": protocol or "tcp",
    }


def compose_value(value: str) -> tuple[str, str | None]:
    """Return (kind, default): kind is 'literals', 'pass' or 'alias'."""
    match = re.match(r"^\$\{([A-Z0-9_]+)(?::-([^}]*))?(?::[?+-][^}]*)?\}$", value)
    if match:
        return ("pass", match.group(2))
    return ("literal", value)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--core-root",
        type=Path,
        default=ROOT.parent / "5g-core-engine",
        help="path to the Python core repository (optional)",
    )
    parser.add_argument(
        "--write-docs",
        action="store_true",
        help="regenerate docs/PORTS.md instead of checking it",
    )
    args = parser.parse_args()

    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    declared = {entry["name"]: entry for entry in schema["variables"]}
    problems: list[str] = []

    # --- code ------------------------------------------------------------
    yml_text = YML.read_text(encoding="utf-8")
    references = VARIABLE.findall(yml_text)
    seen: dict[str, str | None] = {}
    for name, default in references:
        seen[name] = default

    for name in sorted(set(seen) - set(declared)):
        problems.append(f"application.yml reads ${{{name}}}, which config.schema.json does not declare")
    for name in sorted(set(declared) - set(seen)):
        problems.append(f"config.schema.json declares {name}, but application.yml never reads it")

    for name, entry in declared.items():
        if name not in seen:
            continue
        default = seen[name] if seen[name] is not None else ""
        if default != entry["default"]:
            problems.append(
                f"{name}: application.yml default is {default!r}, schema says {entry['default']!r}")
        kind = entry["type"]
        if kind in TYPES and default and not TYPES[kind].match(default):
            problems.append(f"{name}: default {default!r} is not {kind}")
        if kind == "enum" and default not in entry["values"]:
            problems.append(f"{name}: default {default!r} is not one of {entry['values']}")
        if entry["secret"] and default:
            problems.append(f"{name}: a secret must not have a committed default")

    # --- compose ---------------------------------------------------------
    services = compose_services(COMPOSE.read_text(encoding="utf-8"))
    environment = services.get("gmlc", {}).get("environment", {})
    allowed_compose = set(declared) | set(schema.get("compose_extra", []))
    overrides = set(schema.get("compose_overrides", []))

    for key in sorted(set(environment) - allowed_compose):
        problems.append(f"docker-compose.yml sets {key}, which config.schema.json does not declare")
    for key in sorted(set(declared) - set(environment)):
        problems.append(f"docker-compose.yml does not pass {key}, so compose cannot configure it")

    for name, entry in declared.items():
        value = environment.get(name)
        if value is None:
            continue
        kind, default = compose_value(value)
        if entry["secret"]:
            if kind != "pass" or default:
                problems.append(
                    f"{name}: a secret must be a pass-through with no default, found {value!r}")
            continue
        if name in overrides:
            continue
        if kind == "literal" and default != entry["default"]:
            problems.append(
                f"{name}: compose literal {default!r} differs from the schema default "
                f"{entry['default']!r}")
        if kind == "pass" and default is not None and default != entry["default"]:
            problems.append(
                f"{name}: compose default {default!r} differs from the schema default "
                f"{entry['default']!r}")

    # --- .env.example ----------------------------------------------------
    env_values = parse_env_file(ENV_EXAMPLE.read_text(encoding="utf-8"))
    allowed_env = set(declared) | set(schema.get("env_extra", []))
    for key in sorted(set(env_values) - allowed_env):
        problems.append(f".env.example documents {key}, which config.schema.json does not declare")
    for name, entry in declared.items():
        if entry["secret"] and env_values.get(name):
            problems.append(f".env.example gives {name} a value; secrets stay empty")

    # --- ports -----------------------------------------------------------
    register = {(port["owner"], port["service"], port["port"],
                 port["container_port"], port["protocol"]) for port in schema["ports"]}
    by_port: dict[tuple[int, str], set[str]] = {}
    for port in schema["ports"]:
        by_port.setdefault((port["port"], port["protocol"]), set()).add(port["service"])
    for (number, protocol), owners in sorted(by_port.items()):
        if len(owners) > 1:
            problems.append(
                f"host port {number}/{protocol} is claimed by {', '.join(sorted(owners))}")

    for owner, path in (("gmlc", COMPOSE), ("core", args.core_root / "docker-compose.yml")):
        if owner == "core" and not args.core_root.is_dir():
            print(f"NOTE core repository not found at {args.core_root}; core ports not checked")
            continue
        if not path.is_file():
            problems.append(f"port register names {path}, which does not exist")
            continue
        found = set()
        for service, body in compose_services(path.read_text(encoding="utf-8")).items():
            for port in body["ports"]:
                found.add((owner, service, port["port"], port["container_port"], port["protocol"]))
        for missing in sorted(register - found):
            if missing[0] == owner:
                problems.append(
                    f"{path.name}: register has {missing[2]}/{missing[4]} on {missing[1]}, "
                    "but compose does not publish it")
        for extra in sorted(found - register):
            problems.append(
                f"{path.name}: publishes {extra[2]}/{extra[4]} on {extra[1]}, "
                "which the register does not list")

    # --- generated documentation -----------------------------------------
    document = render_document(schema)
    if args.write_docs:
        PORTS_DOC.write_text(document, encoding="utf-8", newline="\n")
        print(f"wrote {PORTS_DOC.relative_to(ROOT)}")
    elif not PORTS_DOC.is_file() or PORTS_DOC.read_text(encoding="utf-8") != document:
        problems.append("docs/PORTS.md is stale; run tools/check_config.py --write-docs")

    if problems:
        for problem in problems:
            print(f"FAIL {problem}", file=sys.stderr)
        return 1

    core_count = sum(1 for port in schema["ports"] if port["owner"] == "core")
    print(
        f"OK {len(declared)} variables: {len(seen)} read by code, "
        f"{len(environment)} passed by compose, {len(env_values)} documented; "
        f"{len(schema['ports'])} ports ({len(schema['ports']) - core_count} gateway, "
        f"{core_count} core)")
    return 0


def render_document(schema: dict) -> str:
    lines = [
        "# Ports and environment",
        "",
        "Generated from `config.schema.json` by `tools/check_config.py --write-docs`.",
        "Do not edit by hand.",
        "",
        "## Host ports",
        "",
        "| Host port | Container | Protocol | Service | Stack |",
        "| --- | --- | --- | --- | --- |",
    ]
    for port in sorted(schema["ports"], key=lambda item: (item["owner"], item["port"], item["protocol"])):
        lines.append(
            f"| {port['port']} | {port['container_port']} | {port['protocol']} "
            f"| {port['service']} | {port['owner']} |")

    lines += ["", "## Environment", ""]
    group = None
    for entry in sorted(schema["variables"], key=lambda item: (item["group"], item["name"])):
        if entry["group"] != group:
            group = entry["group"]
            lines += ["", f"### {group}", "",
                      "| Variable | Type | Default | Secret | Meaning |",
                      "| --- | --- | --- | --- | --- |"]
        default = entry["default"] or "*(empty)*"
        if entry["type"] == "enum":
            default = f"{default} ({', '.join(entry['values'])})"
        lines.append(
            f"| `{entry['name']}` | {entry['type']} | {default} "
            f"| {'yes' if entry['secret'] else 'no'} | {entry['clause']} |")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    raise SystemExit(main())
