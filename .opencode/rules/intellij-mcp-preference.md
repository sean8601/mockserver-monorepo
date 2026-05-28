# IntelliJ MCP Preference — Prefer IDE Tools Over Bash When Available

## Rule

When the conversation has the IntelliJ MCP toolset available (tools
prefixed `mcp__idea__*`, indicating IntelliJ is open with the
project loaded), **prefer the IDE tools over raw shell** for
anything the user might want to follow visually:

| Task | Preferred IntelliJ MCP tool | Bash fallback (avoid) |
|------|----------------------------|------------------------|
| Run a shell command (build, test, script) | `mcp__idea__execute_terminal_command` | `Bash(...)` |
| Run a saved Run/Debug Configuration | `mcp__idea__execute_run_configuration` | n/a |
| Java compile + error report | `mcp__idea__build_project` | `mvn compile` |
| Read a Java file | `mcp__idea__read_file` (decompiles JARs too) | `Read` |
| Edit a file | `mcp__idea__replace_text_in_file` | `Edit` |
| Open a file in the editor for the user | `mcp__idea__open_file_in_editor` | n/a |
| Find files by glob / regex / text | `mcp__idea__find_files_by_glob` / `search_in_files_by_*` | `find` / `grep` |
| Get per-file errors/warnings | `mcp__idea__get_file_problems` | n/a |
| Check open editor tabs | `mcp__idea__get_all_open_file_paths` | n/a |
| List Maven modules | `mcp__idea__get_project_modules` | parse `mvn -pl ...` |

## Why

1. **Transparency.** Every IntelliJ MCP call lands in a tool window
   the user can watch live: terminal commands in **Terminal**, builds
   in **Build**, Run configs in **Run**, file edits in **Changes** /
   **Local History**, project structure in **Maven** / **Project**.
   Bash output only lands in the agent's transcript, which the user
   sees as a summary at best.
2. **Live errors.** `mcp__idea__build_project` + `get_file_problems`
   surface inspection-level errors immediately, with click-to-source
   navigation in the IDE — much faster than `mvn compile` round-trips.
3. **Refactor safety.** `mcp__idea__rename_refactoring` and IntelliJ's
   built-in refactors apply across the whole project safely, vs.
   `sed`-based replacements that miss type-level references.
4. **Indexed search.** `search_in_files_by_text/regex` uses
   IntelliJ's persistent index — orders of magnitude faster than
   `grep -r` on a large repo.

## Long-Running Commands — Background Pattern

The IntelliJ MCP terminal tool has limits: it caps output at 2000
lines and effectively times out around 2–3 minutes regardless of the
`timeout` parameter. For long builds (`mvn install`, `mvn verify`,
big test suites, Docker builds), **background the command in
IntelliJ's shell** and poll the log file:

```
mcp__idea__execute_terminal_command(
    command="./mvnw verify -fae > .tmp/maven-verify.log 2>&1 &",
    timeout=5000,
    reuseExistingTerminalWindow=true
)
```

The `&` puts the build into the background; the short `timeout`
returns control to the agent almost immediately. The user sees the
process running live in IntelliJ's Terminal window. To check
progress, the agent issues another tool call:

```
mcp__idea__execute_terminal_command(
    command="tail -50 .tmp/maven-verify.log",
    timeout=15000,
    reuseExistingTerminalWindow=true
)
```

Or simply `Read` the log file directly (it's on local disk).

**Even better when a saved Run Configuration exists:**
`mcp__idea__execute_run_configuration("Maven verify")` streams into
IntelliJ's **Run** tool window with module nesting, error markers,
and click-through to source. Trade-off: requires the user to save
the Run Configuration once. Recommend this for repeat workflows.

## When NOT to Use IntelliJ MCP

These cases legitimately stay on Bash / Edit / Read:

1. **No IntelliJ MCP in the toolset.** If `mcp__idea__*` tools aren't
   listed (IntelliJ not open, plugin not installed, opencode runtime
   instead of Claude Code), shell tools are the only option.
2. **Operations outside the project root.** `git`, `gh`, `aws`, `kubectl`,
   anything touching the host or remote systems. IntelliJ's terminal
   can also run these, but there's no IDE-side advantage —  Bash
   `run_in_background` notification is cleaner for non-IDE actions.
3. **IntelliJ MCP is degraded.** If a tool call repeatedly times out
   or returns errors that wouldn't happen via shell (e.g. process
   freeze), fall back to Bash and note it in the response.
4. **Bulk file operations.** `find . -name '*.java' -exec sed ...`
   for codebase-wide changes is sometimes the only practical option.
   Even then, prefer IntelliJ's **Refactor → Migrate Packages and
   Classes** when the change is well-defined (e.g., `javax.*` →
   `jakarta.*`).

## Gotchas

- **Output limits.** `execute_terminal_command` truncates at 2000
  lines. Always redirect long output to a file (`> .tmp/foo.log
  2>&1`) and `tail` or `Read` the file rather than relying on the
  inline tool response.
- **Brave Mode.** Without "Brave Mode" enabled in the IntelliJ MCP
  plugin settings, every `execute_terminal_command` prompts for user
  confirmation. Mention this to the user if they want to enable it
  for smoother flow.
- **Project not imported.** If `mcp__idea__get_project_modules`
  returns only a partial module list (e.g., just a Node sub-project
  without the Java tree), the user needs to right-click the relevant
  `pom.xml` and **Add as Maven Project**. Until that happens,
  `build_project` and `get_file_problems` return misleading
  "success" / "no errors" responses because IntelliJ has no
  classpath for the unimported sources.
- **External file edits.** `Edit` (Claude Code's filesystem tool)
  writes directly to disk; IntelliJ detects external changes after a
  short delay. If the user has the file open with unsaved local
  edits, IntelliJ will prompt to reload — surface this risk before
  bulk Bash-based edits.
- **`mvn install` vs `mvn compile`.** Shade-plugin `-no-dependencies`
  jars are only produced by `install`. If a downstream module depends
  on a no-deps jar (e.g., `mockserver-examples`), `mvn compile` alone
  will fail. Use `mvn install -DskipTests` to populate local m2.

## Why a Rule

The user explicitly chose IntelliJ MCP for **transparency** —
they want to watch progress live in tool windows, not read agent
summaries. Defaulting to Bash defeats the entire purpose of the
setup. This rule makes the IDE-first behaviour explicit so future
sessions don't drift back to shell out of convenience.
