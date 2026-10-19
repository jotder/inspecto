---
name: shell-edit-traps
description: "Scripted edits that destroy or mangle files here: heredoc backslash collapse, long heredoc truncation, open(w) truncation, perl -i UTF-8 double-encode, pathspec defaults, unchained resolver lines"
metadata:
  type: feedback
---

Default: use the **Edit tool** for text edits (cannot truncate; bad anchor fails loudly). Scripts only for true bulk.

- **Heredoc backslash collapse:** inside `python - <<'EOF'` in the Bash tool, `"a\nb"` became a literal newline.
  Build escapes with `BS = chr(92)`; `sed -n` the edited lines back. A Java text block (`"""`) closes a Python
  triple-quoted string — write the script to a scratchpad file instead.
- **Heredocs over ~8 KB get truncated** ("unexpected EOF looking for matching quote"): Write the script/payload to
  the scratchpad and run it by path.
- **`open(P, "w")` truncates before writing** — a UnicodeEncodeError (lone surrogate emoji escape) zeroed tracked
  files three times. Encode first, write a `.tmp`, `os.replace`. Never type emoji as surrogate pairs.
- **`perl -0pi` with `\x{...}` or any non-ASCII replacement double-encodes the WHOLE file** (only warning: "Wide
  character in print"). Repair: decode utf-8, `.encode('latin-1').decode('utf-8')`. Check
  `git diff | grep '^-' | grep -c 'â'`.
- **Quotes in `${2:-'*.java'}` defaults are literal** — git grep returned 0 for everything. Pass pathspecs inline or
  as an array. Exclude tests by path, not by content after `-h`.
- **A failed assert does not stop the next shell line:** chain resolver `&& git cherry-pick --continue`; grep
  `^<<<<<<<` first. Replace by EXACT old text (`s.count(old)==1`), never "to the next bullet"; compare
  `git diff --stat` line counts after scripted doc edits.
- Bulk FILE/OLD/NEW block application: an insertion's OLD survives in its NEW, so "OLD absent = applied" loops and
  duplicates.
- A PowerShell `#` comment between backtick continuations breaks the parse.

Related: [[shared-tree-peer-work]].
