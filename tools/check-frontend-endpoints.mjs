#!/usr/bin/env node
/*
 * Checks that every HTTP path the frontend uses exists in api/openapi.yaml,
 * with the method the call actually sends. The console (React) and the legacy
 * static dashboard are both scanned. This is the path half of the
 * frontend-to-backend contract; generated response types are tracked separately
 * in docs/CONSISTENCY-AUDIT.md.
 *
 * Run from anywhere: node tools/check-frontend-endpoints.mjs
 */

import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const files = [
  'gmlc-app/console/src/api.ts',
  'gmlc-app/src/main/resources/static/dashboard.js',
];

const VERBS = new Set(['get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace']);

function normalise(value) {
  let result = value.split('?')[0].split('#')[0];
  // Adjacent interpolations without a slash form one path segment, e.g.
  // `/x/${id}${suffix}`; collapse the whole run to a single `{}`.
  result = result.replace(/(?:\$\{[^}]*\}|\{[^}]*\})+/g, '{}');
  result = result.replace(/\/+$/, '');
  return result === '' ? '/' : result;
}

function readSpec() {
  const lines = readFileSync(path.join(root, 'api/openapi.yaml'), 'utf8').split(/\r?\n/);
  const spec = new Map();
  let current = null;
  for (const line of lines) {
    const pathMatch = /^ {2}(\/\S*):\s*$/.exec(line);
    if (pathMatch) {
      current = normalise(pathMatch[1]);
      if (!spec.has(current)) spec.set(current, new Set());
      continue;
    }
    if (/^\S/.test(line)) {
      current = null;
      continue;
    }
    if (!current) continue;
    const verbMatch = /^ {4}([a-z]+):\s*$/.exec(line);
    if (verbMatch && VERBS.has(verbMatch[1])) {
      spec.get(current).add(verbMatch[1].toUpperCase());
    }
  }
  return spec;
}

function scan(file) {
  const lines = readFileSync(path.join(root, file), 'utf8').split(/\r?\n/);
  const uses = [];
  lines.forEach((line, index) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) return;
    const literal = /['"`](\/(?:v1|mlp)[^'"`]*)/g;
    let match;
    while ((match = literal.exec(line)) !== null) {
      let raw = match[1];
      const after = line.slice(match.index + match[0].length);
      if (raw.endsWith('/') && /^['"`]\s*\+/.test(after)) {
        raw += '{}';
      }
      const window = lines.slice(index, index + 3).join('\n');
      const methodMatch = /method:\s*['"](GET|POST|PUT|PATCH|DELETE)['"]/i.exec(window);
      uses.push({
        file,
        line: index + 1,
        raw,
        path: normalise(raw),
        method: methodMatch ? methodMatch[1].toUpperCase() : 'GET',
      });
    }
  });
  return uses;
}

const spec = readSpec();
const uses = files.flatMap(scan);

const problems = [];
for (const use of uses) {
  const methods = spec.get(use.path);
  if (!methods) {
    problems.push(`${use.file}:${use.line} ${use.method} ${use.raw} -> no such path in api/openapi.yaml`);
  } else if (!methods.has(use.method)) {
    problems.push(
      `${use.file}:${use.line} ${use.method} ${use.raw} -> path exists but declares ${[...methods].join(', ')}`,
    );
  }
}

const unique = new Map();
for (const use of uses) {
  const key = `${use.method} ${use.path}`;
  unique.set(key, (unique.get(key) ?? 0) + 1);
}

for (const key of [...unique.keys()].sort()) {
  console.log(`  ${key}`);
}

if (problems.length > 0) {
  console.error('');
  for (const problem of problems) console.error(`FAIL ${problem}`);
  process.exit(1);
}

console.log(
  `OK ${uses.length} calls resolve to ${unique.size} declared operations (${files.length} files)`,
);
