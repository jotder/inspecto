# Draft — GitHub Support request to purge `refs/pull/*` (SEC-INCIDENT-1)

> **This is a DRAFT for a human to review and send.** No agent files it. It must go from an account
> with owner rights on `jotder/inspecto`, because Support will verify ownership before purging refs.
>
> ⛔ **Never paste a secret value into the ticket.** Reference the *file paths and refs* only. A support
> ticket is another disclosure surface, and the whole point of the request is to stop serving these
> values — not to hand them to one more system. The draft below is written to be sendable verbatim and
> contains no credential.
>
> **Where to send it:** <https://support.github.com/> → *Contact us* → account/repository issue.
> Attach nothing. **Record the ticket number in [`../BACKLOG.md`](../BACKLOG.md) §5** so the next shift
> chases the existing ticket instead of opening a second one.

---

## Why this request is necessary (context for the reviewer, not part of the message)

We removed the secrets from `HEAD`, then rewrote history with `git-filter-repo` and force-pushed. Neither
step ended the exposure:

- All five pull requests were merged **2026-06-21 → 06-24**, i.e. *after* the 2026-06-12 leak commit, so
  every `refs/pull/N/head` pins the pre-rewrite lineage.
- Verified against the live API **after** the force-push: fetching file contents at PR-head SHAs still
  returned the secret-shaped literals.
- **A repo owner cannot delete or rewrite `refs/pull/*`.** There is no client-side fix. Only GitHub can
  purge them.

Rotation at the issuer remains the real remediation and is tracked separately; this request reduces the
window during which the old values remain publicly fetchable.

---

## The message

**Subject:** Purge unreachable objects and pull-request refs after a credential-leak history rewrite — `jotder/inspecto`

Hello,

I'm the owner of `jotder/inspecto`. Credentials were committed to this repository and were public for
about six weeks. I have since removed them from the current tree and rewritten the affected history with
`git-filter-repo`, then force-pushed all branches and re-pointed the affected tags.

The rewrite did not end the exposure, because the pull requests involved were merged *after* the commit
that introduced the credentials. The old lineage is therefore still reachable through pull-request refs —
fetching `refs/pull/<N>/head` still returns the affected files, and reading blobs at those PR-head commit
SHAs still returns the credential values. I have confirmed this against the API after the force-push.

Since `refs/pull/*` is not writable by a repository owner, I cannot remediate this myself. Could you
please:

1. **Purge the unreachable objects** in `jotder/inspecto` — the pre-rewrite commits, trees and blobs that
   no branch or tag reaches any more.
2. **Purge or reset the pull-request refs** (`refs/pull/*`) for PRs #1 through #5, so they no longer pin
   the pre-rewrite lineage.
3. **Clear any cached views** that would still serve those blobs (raw content, the code viewer, the API's
   contents endpoint) at the affected commit SHAs.
4. **Confirm when the purge has completed**, so I can record the remediation date. If any of the above
   cannot be purged, please tell me explicitly which — I need to know what remains reachable in order to
   scope the disclosure correctly.

Affected paths: `inspecto-ui/src/environments/*.ts`.
Affected pull requests: #1, #2, #3, #4, #5.

I am rotating the credentials at the issuer independently and am not relying on this request as the sole
remediation — but the values remain fetchable until the refs are purged, so this is time-sensitive.

Please note this repository is **public**, and I have deliberately not included the credential values or
their commit SHAs in this message. I can supply the specific commit SHAs through whatever private channel
you prefer.

Thank you,
<your name>

---

## After sending

- [ ] Ticket number recorded in [`../BACKLOG.md`](../BACKLOG.md) §5.
- [ ] Response received; note **explicitly** which of the four asks GitHub did and did not action —
      "we purged unreachable objects" is not the same as "the PR refs are gone", and only the latter
      closes the fetchable-secret path.
- [ ] Re-verify independently rather than trusting the reply: attempt
      `git fetch origin 'refs/pull/1/head'` from a clean clone and confirm it fails or no longer carries
      the affected file. A confirmation email is a claim; the fetch is the evidence.
- [ ] Remediation date recorded in the §5 row.

⚠ **Requesting the purge does not close SEC-INCIDENT-1.** The row closes on *confirmed rotation at the
issuer*. This request narrows the exposure; it does not end it, and a successful purge does not undo six
weeks of public availability — anyone who already cloned or forked the repository still holds the values.
