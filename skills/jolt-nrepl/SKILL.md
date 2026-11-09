---
name: jolt-nrepl
description: REPL-driven Jolt/Clojure development through the jolt-nrepl MCP tools — evaluate in a persistent nREPL session, reload files, run tests, inspect values instead of adding println, discover APIs, and make structural edits. Use whenever working on Jolt or Clojure code in a project that has (or can start) an nREPL server, and before adding println/log statements to debug.
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
| `nrepl_eval` | Evaluate forms. Returns `out`, `err`, `=> value`, `ns`. Optional `ns`, `timeoutMs`, `maxOutputChars`. |
| `nrepl_load_file` | Reload a file after editing it on disk. |
| `clj_replace` | Structural edit: replace the form that *reads* equal to `old`, regardless of whitespace. `load: true` reloads the file afterwards. |
| `clj_check` | Syntax check without evaluating; `fix: true` writes a safe repair to the file. |
| `nrepl_start` / `nrepl_stop` | Start (or reuse) `jolt nrepl-server` for the nearest `deps.edn`; stop one it started. |
| `nrepl_status`, `nrepl_interrupt`, `nrepl_disconnect`, `nrepl_connect` | Housekeeping. |

If `nrepl_eval` says there is no `.nrepl-port`, call `nrepl_start` with the
project dir. If the port is stale (connection refused), `nrepl_start` replaces it.

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
a large edit, `clj_check` the file; `fix: true` applies only the safe repairs.

## Develop in the session

1. Try the expression in `nrepl_eval` first, see the value, then write it into
   the file.
2. After editing a file, `nrepl_load_file` it (or `(require 'my.ns :reload)`).
   `clj_replace` with `load: true` edits and reloads in one call.
3. Run tests in the session:
   `(require 'my.ns-test :reload) (clojure.test/run-tests 'my.ns-test)`.
   For a single test: `(clojure.test/test-vars [#'my.ns-test/the-test])`.
4. A long-running form: pass a larger `timeoutMs`. On timeout the eval is
   interrupted when the server supports it; otherwise the connection is dropped
   and the form may still be running. Timed-out forms are never replayed — do
   not assume their side effects did or did not happen; check.

Prefer `clj_replace` over Edit when Edit fails because whitespace or line
breaks differ, or when a form is easier to name than to copy exactly. Write
continuation lines of `new` relative to column 1; they are re-indented to the
match. Multiple matches are listed with line/col — pick one with `occurrence`
or use `all`.

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

Before using an API you are not sure of, ask the running image:

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

Jolt has no `clojure.repl/source`; use the var's `:file`/`:line` metadata and
read the file (jolt's own namespaces live under `jolt-core/` and `stdlib/` in
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
