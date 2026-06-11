#!/usr/bin/env python3
"""Generate the Postman and Bruno API collections from the MockServer OpenAPI spec.

The OpenAPI spec (jekyll-www.mock-server.com/mockserver-openapi.yaml) is the single
source of truth: edit request-body examples THERE, then run this generator. Both
collections are derived from it so they never diverge.

    python3 scripts/collections/generate_collections.py

Outputs:
    examples/postman/MockServer.postman_collection.json   (Postman v2.1.0)
    examples/bruno/**                                     (Bruno .bru files)

Depends only on PyYAML (already a project dependency); no network access.
"""
import json
import os
import re
import shutil
import sys

import yaml

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SPEC = os.path.join(REPO, "jekyll-www.mock-server.com", "mockserver-openapi.yaml")
POSTMAN_OUT = os.path.join(REPO, "examples", "postman", "MockServer.postman_collection.json")
BRUNO_DIR = os.path.join(REPO, "examples", "bruno")

COLLECTION_NAME = "MockServer Control Plane"
# Stable Postman collection id so Postman tracks history across regenerations.
POSTMAN_ID = "9d7b6a51-2c84-4f0e-9b2a-7f1c3e5a6b40"
DOCS_URL = "https://www.mock-server.com/mock_server/mockserver_clients.html"

# Example values for path parameters ({name} etc.).
PATH_PARAM_EXAMPLES = {"name": "checkout"}
# Query params to enable (with value) rather than leave disabled — needed for the request to work.
ENABLE_QUERY = {("put", "/mockserver/mode"): {"mode": "SIMULATE"}}


def load_spec():
    with open(SPEC) as f:
        return yaml.safe_load(f)


def media_and_example(op):
    """Return (mediaType, example, requestBodyDescription) or (None, None, None)."""
    rb = op.get("requestBody")
    if not rb:
        return None, None, None
    content = rb.get("content", {})
    if not content:
        return None, None, rb.get("description")
    mt = next(iter(content))
    return mt, content[mt].get("example"), rb.get("description")


def body_language(mt):
    if mt is None:
        return None
    if "json" in mt:
        return "json"
    if "xml" in mt:
        return "xml"
    return "text"


def raw_body(example, lang):
    if example is None:
        return None
    if lang == "json":
        return json.dumps(example, indent=2)
    return example  # xml / text are already strings


def request_docs(op, rb_desc):
    parts = []
    summary = op.get("summary")
    if op.get("description"):
        parts.append(op["description"].strip())
    elif summary:
        parts.append(summary.strip().capitalize() + ".")
    if rb_desc:
        parts.append(rb_desc.strip())
    parts.append(f"Full API reference: {DOCS_URL}")
    return "\n\n".join(parts)


def substitute_path(path):
    return re.sub(r"\{(\w+)\}", lambda m: PATH_PARAM_EXAMPLES.get(m.group(1), m.group(1)), path)


def query_params(op, method, path):
    """Return list of (key, value, enabled) from the spec, enabling only what's needed."""
    enable = ENABLE_QUERY.get((method, path), {})
    out = []
    for p in op.get("parameters", []) or []:
        if p.get("in") != "query":
            continue
        key = p["name"]
        sch = p.get("schema", {})
        val = sch.get("default")
        if val is None and sch.get("enum"):
            val = sch["enum"][0]
        if val is None:
            val = p.get("example", "")
        if key in enable:
            out.append((key, enable[key], True))
        else:
            out.append((key, "" if val is None else str(val), False))
    return out


def iter_ops(spec):
    """Yield (tag, method, path, op) in spec order."""
    for path, methods in spec["paths"].items():
        for method, op in methods.items():
            if method not in ("get", "put", "post", "delete", "patch"):
                continue
            tag = (op.get("tags") or ["other"])[0]
            yield tag, method, path, op


def tag_order(spec):
    order = [t["name"] for t in spec.get("tags", [])]
    return order


# ---------------- Postman ----------------

def build_postman(spec):
    folders = {}
    order = tag_order(spec)
    for tag, method, path, op in iter_ops(spec):
        mt, example, rb_desc = media_and_example(op)
        lang = body_language(mt)
        raw = raw_body(example, lang)
        real_path = substitute_path(path)
        segments = [s for s in real_path.split("/") if s]
        qp = query_params(op, method, path)
        url = {
            "raw": "{{baseUrl}}" + real_path + (
                "?" + "&".join(f"{k}={v}" for k, v, en in qp if en) if any(en for _, _, en in qp) else ""),
            "host": ["{{baseUrl}}"],
            "path": segments,
        }
        if qp:
            url["query"] = [{"key": k, "value": v, **({"disabled": True} if not en else {})}
                            for k, v, en in qp]
        req = {
            "method": method.upper(),
            "header": ([{"key": "Content-Type", "value": mt}] if mt else []),
            "url": url,
            "description": request_docs(op, rb_desc),
        }
        if raw is not None:
            req["body"] = {"mode": "raw", "raw": raw,
                           "options": {"raw": {"language": lang if lang in ("json", "xml") else "text"}}}
        item = {"name": op.get("summary", f"{method.upper()} {path}"), "request": req}
        folders.setdefault(tag, []).append(item)

    items = []
    seen = []
    for tag in order + [t for t in folders if t not in order]:
        if tag in folders and tag not in seen:
            seen.append(tag)
            items.append({"name": tag, "item": folders[tag]})

    return {
        "info": {
            "_postman_id": POSTMAN_ID,
            "name": COLLECTION_NAME,
            "description": (
                "MockServer REST control plane — every endpoint, generated from the "
                "OpenAPI spec (mockserver-openapi.yaml). Set the baseUrl variable to your "
                f"MockServer instance. {DOCS_URL}"),
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "item": items,
        "variable": [{"key": "baseUrl", "value": "http://localhost:1080"}],
    }


# ---------------- Bruno ----------------

def bru_escape_name(name):
    return name.replace("\n", " ")


def build_bruno(spec):
    if os.path.isdir(BRUNO_DIR):
        for entry in os.listdir(BRUNO_DIR):
            p = os.path.join(BRUNO_DIR, entry)
            if entry in ("bruno.json", "environments") or os.path.isdir(p):
                if os.path.isdir(p) and entry != "environments":
                    shutil.rmtree(p)
    os.makedirs(BRUNO_DIR, exist_ok=True)

    # bruno.json
    with open(os.path.join(BRUNO_DIR, "bruno.json"), "w") as f:
        json.dump({"version": "1", "name": COLLECTION_NAME, "type": "collection",
                   "ignore": ["node_modules", ".git"]}, f, indent=2)
        f.write("\n")
    # environment
    env_dir = os.path.join(BRUNO_DIR, "environments")
    os.makedirs(env_dir, exist_ok=True)
    with open(os.path.join(env_dir, "Local.bru"), "w") as f:
        f.write("vars {\n  baseUrl: http://localhost:1080\n}\n")

    order = tag_order(spec)
    folders = {}
    for tag, method, path, op in iter_ops(spec):
        folders.setdefault(tag, []).append((method, path, op))

    folder_seq = 0
    for tag in order + [t for t in folders if t not in order]:
        if tag not in folders:
            continue
        folder_seq += 1
        fdir = os.path.join(BRUNO_DIR, tag)
        os.makedirs(fdir, exist_ok=True)
        with open(os.path.join(fdir, "folder.bru"), "w") as f:
            f.write(f"meta {{\n  name: {tag}\n  seq: {folder_seq}\n}}\n")
        seq = 0
        for method, path, op in folders[tag]:
            seq += 1
            mt, example, rb_desc = media_and_example(op)
            lang = body_language(mt)
            raw = raw_body(example, lang)
            real_path = substitute_path(path)
            qp = query_params(op, method, path)
            enabled_q = [(k, v) for k, v, en in qp if en]
            url = "{{baseUrl}}" + real_path
            if enabled_q:
                url += "?" + "&".join(f"{k}={v}" for k, v in enabled_q)
            body_kind = "none" if raw is None else ("json" if lang == "json" else "text")
            name = bru_escape_name(op.get("summary", f"{method.upper()} {path}"))
            fname = re.sub(r"[\\/:*?\"<>|]", "-", name)[:120]

            lines = [f"meta {{\n  name: {name}\n  type: http\n  seq: {seq}\n}}\n"]
            lines.append(f"{method} {{\n  url: {url}\n  body: {body_kind}\n  auth: none\n}}\n")
            if mt:
                lines.append(f"headers {{\n  Content-Type: {mt}\n}}\n")
            if qp:
                qlines = "".join(
                    (f"  ~{k}: {v}\n" if not en else f"  {k}: {v}\n") for k, v, en in qp)
                lines.append(f"params:query {{\n{qlines}}}\n")
            if raw is not None:
                indented = "\n".join("  " + ln for ln in raw.splitlines())
                lines.append(f"body:{('json' if lang=='json' else 'text')} {{\n{indented}\n}}\n")
            docs = request_docs(op, rb_desc)
            docs_indented = "\n".join("  " + ln for ln in docs.splitlines())
            lines.append(f"docs {{\n{docs_indented}\n}}\n")
            with open(os.path.join(fdir, f"{fname}.bru"), "w") as f:
                f.write("\n".join(lines))


def main():
    spec = load_spec()
    n_ops = sum(1 for _ in iter_ops(spec))
    pm = build_postman(spec)
    os.makedirs(os.path.dirname(POSTMAN_OUT), exist_ok=True)
    with open(POSTMAN_OUT, "w") as f:
        json.dump(pm, f, indent=2)
        f.write("\n")
    build_bruno(spec)
    n_pm = sum(len(fl["item"]) for fl in pm["item"])
    print(f"spec operations: {n_ops}")
    print(f"postman requests: {n_pm} across {len(pm['item'])} folders -> {POSTMAN_OUT}")
    n_bru = sum(len([x for x in os.listdir(os.path.join(BRUNO_DIR, d)) if x.endswith('.bru') and x != 'folder.bru'])
                for d in os.listdir(BRUNO_DIR)
                if os.path.isdir(os.path.join(BRUNO_DIR, d)) and d != "environments")
    print(f"bruno requests: {n_bru} -> {BRUNO_DIR}")
    if not (n_ops == n_pm == n_bru):
        print(f"ERROR: parity mismatch ops={n_ops} postman={n_pm} bruno={n_bru}", file=sys.stderr)
        sys.exit(1)
    print("parity OK")


if __name__ == "__main__":
    main()
