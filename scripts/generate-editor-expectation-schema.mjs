#!/usr/bin/env node
// Generate a single self-contained JSON Schema for MockServer expectation files
// (`*.mockserver.json`) and write it into the VS Code and JetBrains extensions.
//
// WHY: the editor extensions validate expectation files against the SAME schema
// the server validates against, so the editor is never stricter or laxer than
// MockServer itself. The server keeps the schema as ~45 cross-referencing files
// under mockserver-core and assembles them at runtime
// (org.mockserver.validator.jsonschema.JsonSchemaValidator#addReferencesIntoSchema).
// An editor needs ONE self-contained document, so this script performs the same
// assembly ahead of time and bundles the result.
//
// This is the authoritative source for the published schema — do NOT hand-edit
// the generated files. Re-run after changing any mockserver-core schema:
//   node scripts/generate-editor-expectation-schema.mjs
//
// The reference-file list below mirrors JsonSchemaExpectationValidator exactly.
// If that Java list changes, update this list — the self-check at the end fails
// the build if any `#/definitions/X` reference is left unresolved.

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const SCHEMA_DIR = join(
  REPO_ROOT,
  "mockserver/mockserver-core/src/main/resources/org/mockserver/model/schema"
);

// Mirrors org.mockserver.validator.jsonschema.JsonSchemaExpectationValidator
// (main schema = "expectation"; the rest are the referenced definition files).
const MAIN = "expectation";
const REFERENCE_FILES = [
  "requestDefinition",
  "openAPIDefinition",
  "binaryRequestDefinition",
  "dnsRequestDefinition",
  "httpRequest",
  "httpResponse",
  "httpTemplate",
  "httpForward",
  "httpClassCallback",
  "httpObjectCallback",
  "httpOverrideForwardedRequest",
  "httpForwardValidateAction",
  "httpForwardWithFallback",
  "httpError",
  "httpSseResponse",
  "httpLlmResponse",
  "httpWebSocketResponse",
  "grpcStreamResponse",
  "grpcBidiResponse",
  "binaryResponse",
  "dnsResponse",
  "dnsRecord",
  "afterAction",
  "expectationStep",
  "httpChaosProfile",
  "times",
  "timeToLive",
  "stringOrJsonSchema",
  "body",
  "bodyWithContentType",
  "delay",
  "connectionOptions",
  "keyToMultiValue",
  "keyToValue",
  "socketAddress",
  "protocol",
  // NOTE: "draft-07" is deliberately NOT bundled. The source schemas reference
  // it via `#/definitions/draft-07` to mean "any valid JSON schema" (for JSON
  // body matchers). Embedding the meta-schema breaks validators two ways: its
  // `$id` collides with the validator's built-in draft-07, and its internal
  // `"$ref": "#"` self-references would re-root to the bundle. Instead we
  // rewrite those refs to the canonical draft-07 URI below, which VS Code and
  // every standard validator resolve from their built-in meta-schema.
];

const DRAFT_07_URI = "http://json-schema.org/draft-07/schema#";

// Each extension keeps its own copy so it can be packaged into the .vsix / plugin
// .jar without a build-time dependency on mockserver-core's resources.
const OUTPUTS = [
  join(REPO_ROOT, "mockserver-vscode/schemas/mockserver-expectation.schema.json"),
  join(
    REPO_ROOT,
    "mockserver-jetbrains/src/main/resources/schemas/mockserver-expectation.schema.json"
  ),
];

function readSchema(name) {
  return JSON.parse(readFileSync(join(SCHEMA_DIR, `${name}.json`), "utf8"));
}

// Mirror JsonSchemaValidator#addReferencesIntoSchema: collect every reference
// file under `definitions`, and hoist each file's own nested `definitions` up to
// the top level so all `#/definitions/X` references resolve against one document.
const definitions = {};
for (const name of REFERENCE_FILES) {
  const def = readSchema(name);
  definitions[name] = def;
  if (def.definitions && typeof def.definitions === "object") {
    for (const [k, v] of Object.entries(def.definitions)) {
      definitions[k] = v;
    }
  }
}

// The expectation schema is the server's root document; expose it as a named
// definition so the editor schema can accept either a single expectation or an
// array of expectations (the initialization-JSON form).
const expectation = readSchema(MAIN);
// The source expectation.json keeps an empty `definitions: {}` placeholder; its
// real definitions live in the sibling files hoisted above. Fail loudly if that
// ever changes, so nested definitions are not silently dropped here.
if (expectation.definitions && Object.keys(expectation.definitions).length > 0) {
  console.error(
    `expectation.json now declares its own definitions (${Object.keys(expectation.definitions).join(", ")}) — ` +
      "update this generator to hoist them instead of dropping them."
  );
  process.exit(1);
}
delete expectation.definitions; // refs resolve against the bundle's root definitions
definitions[MAIN] = expectation;

const bundled = {
  $schema: "http://json-schema.org/draft-07/schema#",
  $id: "https://www.mock-server.com/schema/mockserver-expectation.schema.json",
  title: "MockServer expectation(s)",
  description:
    "A MockServer expectation, or an array of expectations (initialization JSON). " +
    "Generated from mockserver-core — do not hand-edit.",
  oneOf: [
    { $ref: "#/definitions/expectation" },
    {
      type: "array",
      items: { $ref: "#/definitions/expectation" },
      minItems: 1,
    },
  ],
  definitions,
};

// Rewrite `#/definitions/draft-07` → canonical draft-07 URI (see NOTE above).
function rewriteDraft07Refs(node) {
  if (Array.isArray(node)) {
    node.forEach(rewriteDraft07Refs);
  } else if (node && typeof node === "object") {
    if (node.$ref === "#/definitions/draft-07") {
      node.$ref = DRAFT_07_URI;
    }
    for (const value of Object.values(node)) rewriteDraft07Refs(value);
  }
}
rewriteDraft07Refs(bundled);

// Self-check: every internal `#/definitions/X` reference must resolve. This is
// the guard that catches drift if the Java reference list changes but this one
// is not updated.
const refs = new Set();
JSON.stringify(bundled, (key, value) => {
  if (key === "$ref" && typeof value === "string" && value.startsWith("#/definitions/")) {
    refs.add(value.slice("#/definitions/".length));
  }
  return value;
});
const missing = [...refs].filter((name) => !(name in definitions));
if (missing.length > 0) {
  console.error(
    `Unresolved schema references (add the source file to REFERENCE_FILES): ${missing.join(", ")}`
  );
  process.exit(1);
}

const json = JSON.stringify(bundled, null, 2) + "\n";
for (const out of OUTPUTS) {
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, json);
  console.log(`wrote ${out.replace(REPO_ROOT + "/", "")} (${refs.size} refs resolved)`);
}
