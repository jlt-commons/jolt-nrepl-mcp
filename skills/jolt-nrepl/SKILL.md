---
name: jolt-nrepl
description: "REPL-driven Jolt/Clojure development through the jolt-nrepl MCP tools — evaluate in a persistent nREPL session, reload files, run tests, inspect values instead of adding println, discover APIs, and make structural edits. Use whenever working on Jolt or Clojure code in a project that has (or can start) an nREPL server: before editing a file to fix a bug or write a function, and before adding println/log statements to debug."
---

# Jolt nREPL workflow

The `jolt-nrepl` MCP server keeps one nREPL connection to the project's
`jolt nrepl-server`. Vars, loaded namespaces and the current ns persist across
calls. Prefer it over `jolt -e` for anything iterative: no startup cost, and
state carries over.

Pass `cwd` (an absolute path inside the project) on the first call; it is
remembered. The server's `.nrepl-port` is found in `cwd` or any parent.

## Tools

| Tool | Use |
|---|---|
| `nrepl_eval` | Evaluate forms. Each top-level form is shown as `-- form`, then its `out`, `err`, `=> value`; the first error stops the rest (`skipped: N`). Optional `ns`, `pprint`, `timeoutMs`, `maxOutputChars`. |
| `nrepl_load_file` | Reload a file after editing it on disk. |
| `nrepl_lookup` | Arglists, docstring and file:line of a var (`sym`, optional `ns`; aliases work). |
| `nrepl_complete` | Vars/namespaces starting with a `prefix`. |
| `nrepl_test` | Run clojure.test: `ns` (reloaded first) or `vars`. Counts, then each FAIL/ERROR with var, line, testing context, expected, actual. |
| `nrepl_start` / `nrepl_stop` | Start (or reuse) `jolt nrepl-server` for the nearest `deps.edn`; stop one it started. |
| `nrepl_status` | Connection, ns, print caps, ops. `scan: true` lists every nREPL server running on the machine with its directory. |
| `nrepl_interrupt`, `nrepl_disconnect`, `nrepl_connect` | Housekeeping. |

A project with a `deps.edn` and no server (or a stale `.nrepl-port`) gets one
started on the first call; the result says `started nREPL …`. Without a
`deps.edn`, run `nrepl_start` yourself, or find a running server with
`nrepl_status` `scan: true` and `nrepl_connect` to its port.

Printing is capped (`*print-length*` 200, `*print-level*` 50), so `(range)`
returns `(0 1 … ...)` instead of hanging. A trailing `...` means more items
exist: count them or take a slice rather than trusting what is shown.

## Syntax repair

Code is vetted before it is sent:

- A trailing truncation (dropped closing parens) is closed and the result says
  `repaired: auto-closed …`. Check that the placement was what you meant.
- Extra trailing closers are trimmed; a closer that indentation implies (an
  unclosed binding vector with the body indented under it) is inserted.
- Anything else — a mismatched or stray closer mid-text, an unterminated
  string, text that balances but still is not Clojure — is refused with a line
  and column and nothing runs. Fix the text; don't resend it unchanged.

`nrepl_load_file` refuses a broken file the same way, with the position. After
a large edit, check the file with jolt-lsp's `jolt_check`; `fix: true` applies
only the safe repairs.

## Develop in the session: REPL first, then edit

The REPL is where you find the change, not just where you check it. Even when
the fix looks obvious from reading the code, confirm it in the session before
touching the file: the cost is one eval, and it catches the wrong hypothesis
before it is written down.

1. Reproduce first. `nrepl_start` if needed, then in `nrepl_eval` require the
   namespace and call the failing function or test with its real inputs. Look
   at the actual value, not the one you expect.
2. Try the fix in the REPL: redefine the function with `nrepl_eval` (pass `ns`
   to evaluate in its namespace) and re-run the failing case until it is right.
3. Only then write it into the file, and `nrepl_load_file` it (or
   `(require 'my.ns :reload)`). To edit by structure, use jolt-lsp's
   `jolt_replace_form` with `apply: true`, then `nrepl_load_file`.
4. Run tests in the session with `nrepl_test` (`ns: ["my.ns-test"]`, or
   `vars: ["my.ns-test/the-test"]` for one). It reloads the test namespace and
   returns each failure's expected/actual, so there is no output to parse.
   Then the project's test task as the final gate.
5. A long-running form: pass a larger `timeoutMs`. On timeout the eval is
   interrupted when the server supports it; otherwise the connection is dropped
   and the form may still be running. Timed-out forms are never replayed — do
   not assume their side effects did or did not happen; check.

Structural edits, syntax checks, navigation and refactors live in the
jolt-lsp MCP server. Prefer its `jolt_replace_form` over Edit when Edit fails
because whitespace or line breaks differ, or when a form is easier to name
than to copy exactly. Multiple matches are listed with line/col — pick one
with `occurrence` or use `all`.

## Debug by inspection, not println

**Red flag:** about to add `println`, `tap>`, or logging to find a bug.
Instead, capture values with an inline def, evaluated in the REPL only (the
file is not touched):

```clojure
;; nrepl_eval — redefine the fn with a capture
(defn handle [req]
  (def req* req)                    ; capture
  (process (:body req)))

;; trigger it (call it, or run the failing test), then inspect
(keys req*)
(type (:body req*))
```

Then form a hypothesis, test the fixed version in the REPL, and only then edit
the file and reload it. Remove capture defs; never commit them.

More inspection moves:

- After an error: `*e`, `(ex-message *e)`, `(ex-data *e)`, `(ex-cause *e)`.
  Values of earlier evals: `*1`, `*2`, `*3`.
- Redefine a single function with `nrepl_eval` to try a fix without reloading
  the whole namespace (evaluate in that ns with the `ns` argument).
- State held in a running system (Integrant/mount/atoms) does not pick up a
  redefinition captured in a closure: restart that component, or re-run the
  code path, after reloading.

## Discover before writing

Before using an API you are not sure of, ask the running image.
`nrepl_lookup` gives arglists, docstring and file:line in one call
(`sym: "str/split"`, with `ns` for aliases); `nrepl_complete` lists what
starts with a prefix. For anything else, eval:

```clojure
(require '[clojure.repl :as r])
(r/doc clojure.string/split)          ; arglists + docstring
(r/apropos "split")                   ; vars whose names match
(r/find-doc "trailing")               ; search docstrings
(r/dir clojure.string)                ; a namespace's public vars
(meta #'clojure.string/join)          ; :arglists :file :line
(macroexpand-1 '(my-macro ...))       ; what a macro really generates
(some? (resolve 'some.ns/f))          ; does it exist here?
```

Jolt has no `clojure.repl/source`; `nrepl_lookup` gives the var's file and
line, then read the file (jolt's own namespaces live under `jolt-core/` and `stdlib/` in
the jolt repo).

## Jolt is not the JVM

Jolt compiles Clojure to Chez Scheme. No reflection, no `clojure.reflect`, no
Java libraries; `java.*` interop resolves against a hand-written subset of
shims. If a diagnosis depends on JVM behaviour, it is wrong. Check facts in the
REPL rather than assuming them.

Jolt's built-in server handles eval/load-file/completions but not interrupt or
real sessions. For interrupt, add the middleware to the project's `deps.edn`
and restart the server:

```clojure
{:deps {jolt-lang/nrepl {:git/url "https://github.com/jolt-lang/nrepl"
                         :git/tag "v0.4.3"
                         :git/sha "8ada4beeb100a391045ec2d93eb566b476fb46f7"}}
 :nrepl/middleware [nrepl.middleware/default-middleware]}
```
