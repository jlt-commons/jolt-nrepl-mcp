# Changelog

## v0.4.3 - 2026-11-22

- Needs jolt 0.8.26 or later (`:jolt/min-version`). From 0.8.26 jolt's nREPL
  server starts evals in `user` and keeps each session's namespace
  (jolt-lang/jolt#1564), so the client no longer sends a namespace of its own
  with every eval or forces `user` on connect.
- Fixed: an eval with an explicit `ns` moved the session into that namespace
  for later evals. It now applies to that call only, following any `in-ns`
  among its forms.
- Fixed: `nrepl_test` reloaded `clojure.test` and `clojure.string` along with
  the test namespaces, because `:reload` covers every namespace in its
  `require`. On jolt 0.8.27 that dropped `clojure.string`'s docstrings, so a
  later `nrepl_lookup` showed none. Only the test namespaces are reloaded now.
- The interrupt middleware example and the tests use jolt-lang/nrepl v0.4.6.
  v0.4.3 saved a one-off `:ns` into the session, so a lookup or test run
  moved it back to `user`.
- The `jolt-nrepl` skill, synced from jolt-skills, has two new sections. One
  covers keeping the session and the files in step before you commit, and
  what happens when a namespace loaded from outside the source roots is
  reloaded. The other covers keeping a running system in a top-level atom so
  the REPL can reach it.
- CI lints with jolt-lsp 0.6.0.

## v0.4.2 - 2026-11-19

- `nrepl_start` takes `aliases`, deps.edn aliases put on the server's
  classpath with `-A` (#4). Without it, a server starts with `:test` when
  deps.edn defines that alias, so `nrepl_test` finds test namespaces kept in
  an alias's `:extra-paths`; `[]` starts with none. If that default keeps the
  server from starting, it starts again without it and says so. Alias names
  are checked before they reach the shell.
- `nrepl_start` reports a server that exits before it comes up at once,
  instead of waiting out `waitMs`.
- `nrepl_test` adds a hint to a "Could not locate" error: the namespace may
  need a deps alias on the server's classpath.
- A `command` that is a path to a file is quoted, so a launcher path with a
  space in it works. A relative path is looked up in the project.
- Autostart only starts `jolt nrepl-server` for a Jolt project: a `deps.edn`
  that mentions jolt or jlt-commons, or has `:tasks`. A JVM project with no
  server gets the missing-port error, saying why, instead of a Jolt server in
  the wrong runtime. `nrepl_start` still starts one anywhere when asked.
- Fixed: against a JVM nREPL server the print caps never applied, because
  the session binds `*print-length*`/`*print-level*` and only the root was
  set. Printing an infinite seq ran the JVM out of heap and wedged the
  server. The caps are now `set!` in the session, with the root as the
  fallback for servers that don't bind them (jolt).
- A live test against a JVM nREPL (`jolt-nrepl-mcp.jvm-test`) runs when the
  `clojure` CLI is on PATH. The README has a JVM Clojure section.
- jolt-lsp dependency bumped from v0.1.0 to v0.4.0. `jolt-lsp.syntax`, the
  only namespace loaded from it, is unchanged between the two tags.

## v0.4.1 - 2026-11-16

- Fixed: a call without `cwd` under Kimi used the plugin's install
  directory as the project and could autostart an nREPL there. A plugin's
  own directory is never a default now; the call asks for `cwd`.
- Fixed: `nrepl_start` while an eval was running took the live server for
  dead, deleted its `.nrepl-port` and started a second server. It is refused
  as busy.
- Fixed: `nrepl_stop` said "stopped" when `kill` failed. A server that is
  already gone has its pid and port files removed and is reported as not
  running; any other failure is an error.

## v0.4.0 - 2026-11-12

- `nrepl_eval` sends each top-level form as its own eval and shows each with
  its own output and value; the first error stops the rest. Before, only the
  last value of a multi-form eval came back.
- `*print-length*`/`*print-level*` get root values on connect (200/50, set
  with `JOLT_NREPL_MCP_PRINT_LENGTH`/`_LEVEL`), so `(range)` returns instead
  of hanging until the timeout. `pprint: true` pretty-prints maps and nested
  data.
- New tools: `nrepl_lookup` (arglists, doc, file:line), `nrepl_complete`, and
  `nrepl_test` (clojure.test with each failure's var, context, expected and
  actual). `nrepl_status` takes `scan: true` to list running nREPL servers.
- Tools start a server when a `deps.edn` project has none or its
  `.nrepl-port` is stale (`JOLT_NREPL_MCP_AUTOSTART=0` turns it off).
- Fixed: a call without `cwd` reconnected to the MCP process's directory
  instead of the project in use.
- Fixed: valid code with an alias-qualified keyword (`::s/x`) was refused as
  a syntax error by `nrepl_eval` and `nrepl_load_file`.
- Timeout errors say what usually causes them.
- Ideas and some code from clojure-mcp (EPL-2.0).

## v0.3.1 - 2026-11-11

- The bencode wire format and MCP request routing are pure Clojure, checked
  by writ specs (`jolt spec`, `jolt plan`); CI runs them. bencode no longer
  uses host string interop for UTF-8, and decoding is a single pass over the
  input. Routing moved to `jolt-nrepl-mcp.protocol`. No change in behaviour.

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
