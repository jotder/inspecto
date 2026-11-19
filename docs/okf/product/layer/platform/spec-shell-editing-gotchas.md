---
type: Reference
title: Shell and editing on Windows - gotchas
description: Git Bash, PowerShell, cmd.exe, heredoc and Python edit traps on this Windows sandbox.
tags: [shell, powershell, bash, windows, gotchas]
timestamp: 2026-11-19T00:00:00Z
item: layer.platform
audience: internal
status: current
verified: 2026-11-19 last-edit
---

# Shell and editing on Windows - gotchas

Moved verbatim from the retired `PROJECT_NOTES.md` (D-IR5, 2026-11-19); the archived copy is `docs/archived-documents/project-notes-2026-11-19.md`. Each entry is cause, then rule.

- 🔴 **WRITING A FILE THROUGH A PYTHON/HEREDOC LAYER CORRUPTED THREE FILES IN ONE SHIFT** (2026-09-16).
  Three separate traps, one root cause — an escape interpreted by the *writer* instead of landing as text:
  (a) `"🔴"` in a Python string is two **lone surrogates**, which cannot be UTF-8 encoded; `open(w)`
  had already **truncated** `docs/BACKLOG.md` to **0 bytes** before the write raised, and the same command
  chain then `git add -A`'d the empty file into a commit. ⚠ **Every doc guard passed on the empty file** — an
  empty file has no violations — and `git add -A` stages destruction as readily as an edit. It was caught only
  because a `grep -c` that should have returned 5 returned 0. (b) A `\\` inside a bash heredoc **collapsed** to
  `\`, producing `replace('\', '/')` — an unclosed character literal. (c) a unicode escape for NUL landed as a **real NUL byte**, which the pre-push guard refused (a NUL makes the whole file invisible to recursive ripgrep).
  ⇒ **Use `Edit` for existing files**, especially large tracked docs; if a script must write one, `wc -c` it
  before staging, and never `git add -A` after a script that touched a doc.

- ⛔ **A `#` comment between PowerShell BACKTICK CONTINUATIONS breaks the parse**, and looks correct in a
  diff. Put such notes ABOVE the call, and parse-check an edited script rather than eyeballing it:
  `[System.Management.Automation.Language.Parser]::ParseFile(path,[ref]$tokens,[ref]$errors)` (2026-09-14).

- ⚠ **`tee` masks the exit code of the command feeding it.** A `package.ps1` run that threw was reported
  as `exit 0` because the wrapper read `tee`'s status. Read the output for the exception; the code is the
  pipe's, not the program's (2026-09-14).

- 🔴 **cmd.exe expands `%VAR%` inside a parenthesised block ONCE, at PARSE time — so N
  `set "X=%X% …"` lines in one `if ( … )` collapse to the LAST one executed.** Measured 2026-09-11:
  `set "OPTS=BASE"` then a block doing `-Dfirst` and `-Dsecond` yields `BASE -Dsecond`; `-Dfirst` is gone.
  This shipped in the generated `serve.bat` (`SERVEBAT-OPTS-1`): six such lines in the
  `if exist inspecto-oidc.jar (…)` branch meant **every Windows Professional/Enterprise bundle booted
  AUTH-FREE** — `-Dauth.mode=oidc` and three of the four OIDC flags dropped — while printing
  `edition: Enterprise`. ✅ Fix = **one statement per line**, chaining conditions
  (`if exist X if not "%Y%"=="" set …`), the form the adjacent Postgres lines already used AND documented.
  ⛔ **Not `setlocal EnableDelayedExpansion`**: these values carry client secrets and keystore passwords,
  and delayed expansion eats `!` inside them. ⚠ `serve.sh` has no such hazard — bash expands at execution.

- 🔴 **`| grep -v spec` to "skip the tests" silently discards 77 % OF THIS REPOSITORY**, because the product
  name **`inspecto` contains the substring `spec`** (i-n-**s-p-e-c**-t-o). Measured 2026-09-10: **3,015 of
  3,923** tracked files have `inspecto` in their path, and **every one** is dropped by that filter — the whole
  client and every Java module. What survives is `docs/`, `providers/asn-parser/`, `compliance/` and `.github/`, so the
  sweep looks like it ran and reports a plausible, tiny answer. ⇒ Exclude test files by a **path-anchored**
  pattern (`grep -v '\.spec\.ts$'`, `grep -v '/src/test/'`) or with ripgrep's `--glob '!*.spec.ts'`, never by
  the bare word. ⚠ This one produced a confident "no `EventSource` anywhere in the client" while the client
  had seven occurrences of it, and it is a *worse* trap than the NUL-byte one because nothing about the output
  looks truncated.

- 🔴 **`grep -r` on this sandbox returns ZERO hits for strings `git grep` finds** (2026-09-10, three times in one shift:
  `source_timezone` in four UI files; two UI callers and a Java test of `/config/suggest/schema`). Each time the
  false negative was one step from filing a shipped surface as missing or a live route as an orphan. **Absence is
  concluded only from `git grep`**; `grep -r` may locate, never negate.

- 🔴 **Python's default text read converts CRLF→LF, so a "restored" file shows `M` with an empty diff** (2026-09-10).
  Open with `newline=''`, match on the file's own EOL, and `git checkout --` a stat-only ghost. Related: the Bash tool
  collapses backslashes inside heredocs AND `python -c`, so a `
` in an `old` string matches nothing and a regex's
  `\b` reaches the file as `\b` — author scripts with the Write tool and assert `count == 1` on every replace; that
  assert is what caught every instance.

- **A find-and-replace-all reporting success is NOT evidence of complete coverage.** Replacing four call
  sites of `userFor(f)` with the pattern `, userFor(f));` hit only three on 2026-08-15: the fourth was a
  **ternary arm** ending in `)` with no trailing semicolon, so the pattern never matched it and the tool
  reported success anyway. The half-applied fix compiled and looked done. **When the surrounding syntax
  varies across call sites, grep the result and count them** before believing the edit.

- **🔴 A heredoc collapses `\b` and `\n` into control characters — it can silently break a REGEX.**
  (2026-09-13 — **three times in one shift: the third was this very note**, whose escapes were eaten
  as it was being written.) Writing a JS rule through `python - <<'PY'` produced
  `re: /^Hsource\.…/` where `^H` is a literal **BACKSPACE byte**, not the word-boundary `\b`. The guard
  loaded, ran, and matched nothing — reading as a clean pass. The second instance put a real newline inside
  a JS string literal and broke the module outright (that one at least crashed). ⛔ Build such literals from
  `chr(92)+'b'`, and **`cat -A` the line** to see what actually landed. ⚠ The general rule this is an
  instance of: **a guard that passes proves nothing until it is proven RED** — mutate the thing it guards
  and watch it fire, or you have written a decoration.

- **⚠ `cmd | head; echo $?` reports the PIPE's status, not the command's.** A crashed Node guard printed
  its stack through `head` and still showed `EXIT=0` (2026-09-13). Redirect to a file and test the command's
  own exit code when the exit code is the thing you are asserting.

- **⚠ `$!` in bash is the SHELL JOB, not the JVM it launched.** `nohup java … & echo $!` then
  `kill $PID` left `ControlApi` serving on :8080 while the wait loop hung (2026-09-13). Take the pid from
  `jps -l | grep ControlApi`.

- **PowerShell splits an unquoted `-Dfoo.bar=baz` native argument AT THE DOT.** A token starting with `-`
  is parsed as a parameter name, which ends at the `.`, so `-Dmdep.includeScope=runtime` reaches the
  process as **two** arguments (`-Dmdep` and `.includeScope=runtime`). Maven then reports the tail as an
  *unknown lifecycle phase*, which reads like a broken POM rather than a quoting bug — it killed
  `tools/run-backend.ps1` on 2026-08-15 (`ad6eb73c`). ⚠ **Quote every `-D` argument, including the ones
  containing no variable**: the ones with `$vars` are usually already quoted for interpolation and so
  survive, which is exactly why the bug hides — six unquoted `-D` JVM properties sat latent in the same
  script's launch step, invisible only because it died earlier. Prove it in one line, don't reason about
  it: `pwsh -NoProfile -Command 'function Show { $args | % { "[$_]" } }; Show -Da.b=c "-Dd.e=f"'`.

- **`powershell -File` invoked from Bash reads the script as ANSI**, so `package.ps1` reports ~40
  phantom parse errors on lines nobody touched (mangled em-dashes). Use **`pwsh`** (PowerShell 7 is
  installed) to RUN it, and `[Parser]::ParseInput` with explicit UTF-8 — never `ParseFile` — to CHECK
  it. 🔴 Before believing any gate that goes red, run it on `git show HEAD:<file>` first: that is what
  showed this was pre-existing rather than a change of mine.

- **⚠ A python-in-bash edit REWRITES LINE ENDINGS on Windows and inflates the diff ~6×.** `.gitattributes`
  is `* text=auto eol=lf`, but Python's `io.open(path, 'w')` does universal-newline translation and emits
  **CRLF** on Windows. Git then normalises to LF in the index, so the committed *content* is right — but a
  file previously stored with CRLF shows as **fully rewritten**: a 302-line change reported as 1783/1491
  on 2026-09-12. ⛔ Nothing is broken and ⛔ do not rewrite history over it; just know the real size is
  `git show --stat --ignore-cr-at-eol`. **Pass `newline=''` when writing** (`io.open(p, 'w', newline='')`)
  to preserve what was there, or use the Edit tool for small changes. ⚠ The tell is a diff far larger
  than the edit, plus git's "CRLF will be replaced by LF" warning at commit time.
