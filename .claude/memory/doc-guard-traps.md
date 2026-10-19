---
name: doc-guard-traps
description: "Doc guard gotchas: a citation heading must read 'References' (vocabulary guard), doc-links resolve against git ls-files (git add new docs first), dead-path citations need the absence phrase on the same line, docs-relocate needs a second pass"
metadata:
  type: reference
---

- **"## Sources" / "Source:" fails `tools/check-vocabulary.mjs`** (banned acquisition term). Title citation
  sections **"References"**; write "per …"/"from …". A product-generated key cannot be fixed by deleting it from
  the file.
- **`tools/check-doc-links.mjs` resolves against `git ls-files`, case-exact**: a new doc + its INDEX row is RED
  until the new file is `git add`ed. Links into git-excluded folders always fail — use backticked paths there.
- **`tools/check-doc-citations.mjs` allows a dead path only if the SAME physical line** matches
  `ABSENCE_MARKER` (`dead path|no longer exist|does not exist|never existed|deleted|retired`). A token whose first
  segment is not a real directory is never checked — a useless falsification probe.
- Guards collect subjects from tracked paths only (gitignored `*.local.md` used to break pushes).
- Chain `check-vocabulary && check-doc-links && check-doc-citations && ... && git commit` so a red guard blocks
  the commit, not just the push. The secret guard runs pre-push (too late is CI).
- **Moving docs:** `node tools/docs-relocate.mjs map.csv --write` rewrites links and bare path mentions, but a
  second pass was needed for docs-relative mentions and relative mentions of unmoved files. `inspecto/package.ps1`
  `$docsRequiredEntries` hard-codes doc paths — packaging breaks only at bundle time. Hub `index.md` is generated
  (`tools/render-product-docs.mjs`).
- Guards check links and words, not content: missing rows / conflict markers in prose pass every guard.

Related: [[guard-scope-is-a-silent-exemption]].
