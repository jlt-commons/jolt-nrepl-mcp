# jolt-nrepl-mcp

An MCP server, written in Jolt, that lets a coding agent work in a persistent
nREPL session: evaluate, reload files, run tests, check and repair syntax, and
make structural edits to Clojure source. Ships as a Claude Code plugin (and a
Kimi Code plugin) with a REPL-workflow skill.

It has no dependencies beyond `jolt` itself: JSON, sockets and bencode are
built in or local, so it starts in about 0.2s and needs no network.

## Tools

| Tool | Purpose |
|---|---|
| `nrepl_eval` | Evaluate code; returns out/err/value/ns. The ns carries over between evals. |
| `nrepl_load_file` | Load a project file (checked for syntax first). |
| `clj_check` | Diagnose delimiter/reader problems with line/col; `fix` writes a safe repair. |
| `clj_replace` | Replace the form that reads equal to `old`, ignoring formatting; `load` reloads after. |
| `nrepl_start` / `nrepl_stop` | Start `jolt nrepl-server` for the project in the background, or stop it. |
| `nrepl_connect`, `nrepl_status`, `nrepl_interrupt`, `nrepl_disconnect` | Connection management. |

`cwd` is optional on every tool: it defaults to the last project used, then to
the directory the agent launched the server from. `.nrepl-port` is looked up in
`cwd` and its parents.

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
                         :git/tag "v0.4.3"
                         :git/sha "8ada4beeb100a391045ec2d93eb566b476fb46f7"}}
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
tar xzf jolt-nrepl-mcp-v0.2.0-aarch64-macos.tar.gz
xattr -d com.apple.quarantine jolt-nrepl-mcp-v0.2.0-aarch64-macos/jolt-nrepl-mcp  # macOS only
claude mcp add --scope user jolt-nrepl -- "$PWD/jolt-nrepl-mcp-v0.2.0-aarch64-macos/jolt-nrepl-mcp"
```

`jolt dist` builds the binary at `target/jolt-nrepl-mcp`; `jolt smoke` runs it
through a real nREPL session.

Kimi Code: `/plugins install /path/to/jolt-nrepl-mcp`.

`bin/jolt-nrepl-mcp` runs the server from this directory and passes the
caller's directory through `JOLT_NREPL_MCP_CWD`. Set `JOLT` to use a different
launcher (a clone's `bin/jolt`, say).

## Development

```bash
jolt -M:test        # or: jolt test
jolt -M:test jolt-nrepl-mcp.client-test   # one namespace
```

The suite starts real `jolt nrepl-server` processes, one with the default
middleware (fetched on first run), and drives the stdio server end to end.

## Credits

- Base protocol and client design from kimi-nrepl-plugin.
- Delimiter diagnosis and repair ported from samizdat (`samizdat.lisp`), which
  ports parts of vis's parse-diagnose (Blockether, Apache-2.0).
- `src/parinferish` is vendored from oakes/parinferish (public domain), with one
  jolt port change marked inline.
- `clj_replace` and the debugging/discovery workflow follow
  humorless/clj-native-agent (MIT); `skills/clj-refactor` is taken from it.

EPL-2.0.
