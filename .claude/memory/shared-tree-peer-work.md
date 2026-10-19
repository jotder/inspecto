---
name: shared-tree-peer-work
description: "The checkout is shared by concurrent sessions: a red gate may be a peer's uncommitted work, merges can drop it, by-path staging sweeps their hunks, and origin/master moves under you — fetch first, verify in a sibling worktree, stage by hunk"
metadata:
  type: feedback
---

- **Fetch first:** `git fetch && git log --oneline HEAD..origin/master` before planning — a whole task was once
  rebuilt that another shift had already pushed. A cited SHA you cannot find: `git log --all | grep <sha>` /
  `git branch -a --contains` before calling the note bogus. `origin/master` is shared by all worktrees and moves
  when a peer pushes.
- **Red reactor? `git status --porcelain` first** — separate my files from peers'. If the tree holds others' work,
  verify in isolation: `git diff -- <mine> > my.patch`, pin `SHA=$(git rev-parse HEAD)`,
  `git worktree add --detach C:\sandbox\.wt-<name> $SHA`, apply, confirm `git status` shows only mine, build there.
- **Never `git merge`/pull with a peer's uncommitted work in the tree:** a wrapper stashes and may not pop it back.
  Recover via `git fsck --lost-found` dangling `WIP on master` commits + `git stash apply --index <sha>` (never
  pop); no `git gc` until the owner commits.
- **Stage shared docs by HUNK** (`BACKLOG.md`, `INDEX.md`, `PROJECT_NOTES.md`): read `git diff -- <file>`, build a
  patch of my hunks, `git apply --cached`; check `git diff --cached`. Never `git add -A`. Acknowledge peer hunks that
  rode a commit in the next message.
- **A peer may commit my dirty tree** under its message: re-run `git log --oneline -3` before every commit; never
  amend/reset a shared HEAD — fix provenance in a follow-up `docs:` commit. Commit verified work promptly.
- Before fixing a shared breakage, read `origin/master..` and lanes' status — four lanes once fixed the same red.
- Lane agents' worktrees branch from `origin/master`, not local HEAD: push (or tell them the base) before launching
  lanes, and have each lane report the commit it verified against.
- The ~130 local `claude/*` / `worktree-agent-*` branches are stale leftovers, not unpushed work.

Related: [[worktree-location-traps]], [[shell-edit-traps]].
