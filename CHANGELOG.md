# Changelog

## v0.3.0 - 2026-11-11

- Syntax diagnosis/repair and the structural replace matcher moved to
  jolt-lsp v0.1.0 (`jolt-lsp.syntax`, `jolt-lsp.replace`, vendored
  parinferish), which is now a dependency.
- Removed `clj_check` and `clj_replace`: use jolt-lsp's MCP tools
  `jolt_check` and `jolt_replace_form` (then `nrepl_load_file`). Eval and
  load-file input is still vetted and repaired as before.

## v0.2.2 - 2026-11-09

- The server instructions, `nrepl_eval`'s description and the `jolt-nrepl`
  skill now put the REPL first: reproduce the failing case and try the fix in
  the session, then edit the file. In testing, an agent fixing a bug edited the
  file first and only used the REPL to check it.

## v0.2.1 - 2026-11-09

- The server sends MCP `instructions` on initialize, so a client puts a short
  "use these tools for Clojure/Jolt work" note in the agent's prompt.
- The plugin launcher runs an installed `jolt-nrepl-mcp` binary from PATH when
  there is one, and the checkout otherwise. `JOLT_NREPL_MCP_SOURCE=1` forces the
  checkout.

## v0.2.0 - 2026-11-09

First release.

- MCP server over stdio for a persistent nREPL session: `nrepl_eval`,
  `nrepl_load_file`, `nrepl_start`/`nrepl_stop`, `nrepl_connect`,
  `nrepl_status`, `nrepl_interrupt`, `nrepl_disconnect`.
- `clj_check` diagnoses and repairs delimiter problems; eval and load-file run
  the same check first and report line and column.
- `clj_replace` replaces a form by structure, not text, and can reload the file.
- The current namespace carries over between evals. A load-file does not change it.
- Claude Code and Kimi plugin manifests, with the `jolt-nrepl` and
  `clj-refactor` skills.
- Standalone binaries for macOS (arm64) and Linux (x86_64).
