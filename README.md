# jolt-nrepl-mcp

An MCP server, written in Jolt, that lets a coding agent work in a persistent
nREPL session: evaluate, reload files, run tests, check and repair syntax, and
make structural edits to Clojure source. Ships as a Claude Code plugin (and a
Kimi Code plugin) with a REPL-workflow skill.

Its one dependency is jolt-lsp, for syntax repair of eval input; only that
dependency-free namespace is loaded, so it still starts in about 0.3s.
JSON, sockets and bencode are built in or local. Run it next to the jolt-lsp
MCP server for navigation, diagnostics and refactors.

## Tools

| Tool | Purpose |
|---|---|
| `nrepl_eval` | Evaluate code; each top-level form is shown with its own out/err/value. The ns carries over between evals. `pprint` pretty-prints maps and nested data. |
| `nrepl_load_file` | Load a project file (checked for syntax first). |
| `nrepl_lookup` | A var's arglists, docstring and file:line, resolved in a namespace. |
| `nrepl_complete` | Completions for a prefix from the running image. |
| `nrepl_test` | Run clojure.test namespaces or single vars; counts plus each failure with context, expected and actual. |
| `nrepl_start` / `nrepl_stop` | Start `jolt nrepl-server` for the project in the background, or stop it. `aliases` puts deps.edn aliases on its classpath (`-A`); `command` picks the launcher. |
| `nrepl_status` | Connection, ns, print caps and ops; `scan: true` lists the nREPL servers running on the machine. |
| `nrepl_connect`, `nrepl_interrupt`, `nrepl_disconnect` | Connection management. |

`cwd` is optional on every tool: it defaults to the last project used, then to
the directory the agent launched the server from. `.nrepl-port` is looked up in
`cwd` and its parents. When there is none (or it is stale) and the project is a
Jolt project, the tools start a server with `nrepl_start` first and say so;
`JOLT_NREPL_MCP_AUTOSTART=0` turns that off. A Jolt project is one whose
`deps.edn` mentions `jolt` or `jlt-commons` (a `:jolt/min-version` key, a dep,
even a comment) or has `:tasks`. Any other project gets the missing-port error
and no server, since `jolt nrepl-server` would be the wrong runtime for it;
`nrepl_start` still starts one there when asked.

A server starts with the `:test` alias (`-A:test`) when `deps.edn` defines
one, so test namespaces kept in an alias's `:extra-paths` load and
`nrepl_test` finds them. Pass `aliases` to `nrepl_start` to choose others, such
as `["test" "dev"]`, or `[]` for none. If the default `:test` alias keeps the
server from starting (a dep that can't resolve offline, say), it starts again
without it and the reply says so; an alias you name keeps the error. A
`command` that passes `-A` itself gets no default. Aliases apply when a server
starts; to change them on a running one, `nrepl_stop` it first. A server that
exits before it comes up is reported at once, with the log tail. `command` runs another
launcher, such as a pinned `jolt` binary; a path to a file is quoted, so a
space in it is fine.

### JVM Clojure

The tools also work against a JVM nREPL server (`nrepl/nrepl`): eval, load,
lookup, complete, test and interrupt. Start that server yourself (for example
`clojure -M:nrepl`) so the project has a live `.nrepl-port`, and the tools
connect to it. A JVM project's `deps.edn` doesn't count as a Jolt project, so
the tools never start a Jolt server in its place.

### Evaluation

Code is split into top-level forms and each is sent as its own eval, so every
form gets its own output and value (jolt's servers only return the last value
of a multi-form eval). The first error stops the rest and the result says how
many forms were skipped. Code the client can't read on its own, like
`::alias/kw`, goes as one eval and the server reads it.

On connect the client gives `*print-length*` and `*print-level*` values (200
and 50) if the server has none, so printing an infinite seq returns instead of
hanging until the timeout. They are set in the session when the server binds
them per session (a JVM nREPL does), and as root values otherwise (jolt). Set `JOLT_NREPL_MCP_PRINT_LENGTH` /
`JOLT_NREPL_MCP_PRINT_LEVEL` to change them, or to 0 to leave them unset.
`nrepl_lookup` and `nrepl_test` print their own results without the caps.

`nrepl_lookup` uses the server's `lookup` op when it has one (jolt-lang/nrepl's
middleware) and an eval of the var's metadata otherwise. `nrepl_test` rebinds
`clojure.test/report` to collect results, so it works on any server.

### Syntax repair

Code sent to `nrepl_eval` is vetted first (ported from samizdat):

- a trailing truncation is closed, extra trailing closers are trimmed, and a
  closer that indentation implies is inserted (parinfer indent mode,
  insert-only) — each repair is reported, and only accepted if the result reads;
- a mid-text mismatch, stray closer, unterminated string or unreadable token is
  refused with line and column, and nothing is sent to the server.

### Timeouts

`timeoutMs` defaults to 120000. On timeout the client sends `interrupt` when the
server supports it and keeps the session if the interrupt is confirmed.
Otherwise it drops the connection so late output can't leak into the next
eval; the form may still be running and is never replayed. A second eval while
one is running is refused as busy; `nrepl_interrupt` and `nrepl_disconnect`
still work.

Jolt's built-in server has no `interrupt` op. Add the middleware for it:

```clojure
{:deps {jolt-lang/nrepl {:git/url "https://github.com/jolt-lang/nrepl"
                         :git/tag "v0.4.6"
                         :git/sha "29c92b7a30824230cb7930064a50a4fb89490db7"}}
 :nrepl/middleware [nrepl.middleware/default-middleware]}
```

## Install

Claude Code:

```bash
claude plugin marketplace add /path/to/jolt-nrepl-mcp
claude plugin install jolt-nrepl@jolt-nrepl-mcp
```

Or just the MCP server, from a checkout:

```bash
claude mcp add --scope user jolt-nrepl -- /path/to/jolt-nrepl-mcp/bin/jolt-nrepl-mcp
```

or as a standalone binary from the
[releases](https://github.com/jlt-commons/jolt-nrepl-mcp/releases) (macOS arm64,
Linux x86_64), which doesn't need a checkout or `jolt` to run (`nrepl_start`
still runs `jolt nrepl-server`):

```bash
tar xzf jolt-nrepl-mcp-v0.4.2-aarch64-macos.tar.gz
xattr -d com.apple.quarantine jolt-nrepl-mcp-v0.4.2-aarch64-macos/jolt-nrepl-mcp  # macOS only
claude mcp add --scope user jolt-nrepl -- "$PWD/jolt-nrepl-mcp-v0.4.2-aarch64-macos/jolt-nrepl-mcp"
```

`jolt dist` builds the binary at `target/jolt-nrepl-mcp`; `jolt smoke` runs it
through a real nREPL session.

Kimi Code: `/plugins install /path/to/jolt-nrepl-mcp`.

`bin/jolt-nrepl-mcp` (the plugin's launcher) runs an installed release binary
from PATH when there is one, and otherwise runs this checkout with jolt;
`JOLT_NREPL_MCP_SOURCE=1` forces the checkout. It passes the caller's directory
through `JOLT_NREPL_MCP_CWD`. Set `JOLT` to use a different
launcher (a clone's `bin/jolt`, say).

## Development

```bash
jolt test      # Clojure tests (jolt -M:test jolt-nrepl-mcp.client-test for one)
jolt spec      # writ specs
jolt plan      # print the writ state graphs, signatures and laws
jolt dist      # standalone binary at target/jolt-nrepl-mcp
jolt smoke     # drive the built binary through a real nREPL session
```

The writ specs cover the pure core: the bencode wire format
(`jolt-nrepl-mcp.bencode`) and MCP request routing
(`jolt-nrepl-mcp.protocol`); `jolt-nrepl-mcp.mcp` does the JSON parsing and
tool calls around them.

The suite starts real `jolt nrepl-server` processes, one with the default
middleware (fetched on first run), and drives the stdio server end to end.
`jolt-nrepl-mcp.jvm-test` runs against a JVM nREPL when the `clojure` CLI is on
PATH, and skips otherwise.

## Credits

- Base protocol and client design from kimi-nrepl-plugin.
- Delimiter diagnosis and repair (`jolt-lsp.syntax`) live in
  [jolt-lsp](https://github.com/jlt-commons/jolt-lsp), which this server
  depends on; structural edits and syntax checks are jolt-lsp's MCP tools. They were ported from samizdat (`samizdat.lisp`), which ports
  parts of vis's parse-diagnose (Blockether, Apache-2.0), and use parinferish
  (oakes/parinferish, public domain) vendored there.
- Per-form eval output, capped printing, the lookup/test helpers and port
  discovery (`jolt-nrepl-mcp.discover`) follow
  [clojure-mcp](https://github.com/bhauman/clojure-mcp) (Bruce Hauman,
  EPL-2.0); the lsof parsing is ported from its `nrepl_ports` tool.
- The debugging/discovery workflow follows
  humorless/clj-native-agent (MIT); `skills/clj-refactor` is taken from it.

EPL-2.0.
