# OWLI Git development workflow

This is the shared branch policy for Claude Code and Codex, effective
26 November 2026. It supersedes older branch advice in development guides and
historical reports. It describes the intended setup; creation and publication
must be verified independently before claiming a branch exists on GitHub.

## Branch responsibilities

| Branch | Responsibility |
| --- | --- |
| `main` | Stable, verified releases; no active development; merge from `dev` only after final verification and explicit owner approval |
| `dev` | Permanent central development/integration branch and shared source of truth for both agents |
| `handoff/owli-20261125` | Original consolidated implementation and recovery reference; preserved after documentation updates and `dev` initialization |
| Temporary task branches | Focused development from current `dev`, reviewed and verified before integration back into `dev` |

The handoff includes existing bugs, architectural concerns, and incomplete
features. Establishing `dev` from it makes no claim that those issues are fixed
or that the baseline is release-ready. Old integration branches and `main` must
not be used as the initial `dev` source.

## Historical initialization — completed

The following records the completed initial branch setup. It is not a workflow
to rerun or permission to write to preserved handoff. Current tasks, including
`chore/*` documentation/setup branches, start from synchronized `dev`.

1. Finish, review, commit, and publish approved documentation and project
   instructions on `handoff/owli-20261125`. Verify the remote commit and files.
2. Fetch the latest remote state. Check for local `dev` and remote `origin/dev`.
3. If either exists, inspect its tip, tree, and ancestry against the latest
   handoff before changing anything. Preserve existing work; never overwrite,
   reset, or force-push it. If remote `dev` alone exists, create its local
   tracking branch after inspection. If local `dev` alone exists, inspect it
   before deciding whether a normal push is appropriate.
4. Only if neither exists, create local `dev` directly from the verified latest
   handoff SHA, then push with `git push -u origin dev`. Verify that its initial
   local and remote SHA equals the handoff SHA and that upstream is `origin/dev`.
5. After initialization, preserve handoff and perform subsequent work on
   temporary task branches from `dev`. Do not create the illustrative branches
   below automatically.

If publishing or fetching is blocked, report the blocker. Do not silently use an
older remote handoff or claim `dev` is synchronized or published.

## Standard task workflow

Inspect the working tree first. Fetch remote state, synchronize `dev`, and create
a focused branch. With a clean working tree and established tracking refs:

```sh
git fetch origin
git switch dev
git merge --ff-only origin/dev
git switch -c fix/assigned-task
```

If local `dev` is ahead, divergent, or another agent has changed it, inspect and
coordinate before proceeding. These commands are not permission to discard
uncommitted work or bypass review.

Investigate and implement the assigned task, run relevant automated and runtime
checks, review correctness/regressions/security/scope, then commit and normally
push the task branch. Integrate completed, verified changes into `dev` through
the appropriate review and merge process, checking for conflicts first. Verify
the integrated `dev` remains functional before using it for the next task.
Record exact test results and limits; never automatically merge incomplete,
failing, or unverified changes. Documentation-only work needs documentation and
Git verification rather than application or database execution.

Examples of temporary branches include `development/owli-stabilization`,
`fix/mvc-registration`, `fix/savings-goals`, `fix/recurring-bills`,
`fix/currency-transactions`, `fix/android-mvc-sync`, `feature/hangfire-console`,
`feature/notification-inbox`, and `test/cross-platform-integration`.
`development/owli-stabilization` is not a permanent integration branch.

## Collaboration and safety

Assign explicit task/file ownership. Concurrent Claude Code and Codex sessions
use separate task branches and isolated Git worktrees; avoid editing the same
files simultaneously or implementing the same feature twice. Synchronize
completed work through `dev`, check conflicts, and use independent reviews where
appropriate. Keep commits focused, reviewable, and reversible.

Never force-push, reset/rewrite branch history, discard uncommitted changes,
delete branches without authorization, overwrite another agent's work, or
publish secrets/private configuration. Preserve working configurations,
database contents, certificates, and local development environments. A branch
setup task must not change application code, schemas, or runtime configuration.

[AGENTS.md](../AGENTS.md) governs both agents; [CLAUDE.md](../CLAUDE.md) points
Claude Code to the shared rules. Future repository skills and development plans
must use the same branch strategy. Do not change global agent settings to
implement repository policy.

## Shared knowledge and contract handoff

Before a task, read [approved requirements](../PRODUCT.md), the relevant
[wiki index entry](karpathy-wiki/wiki/index.md), and affected source/contracts.
After significant verified changes, update only affected knowledge with commit
and test evidence before reviewed integration into `dev`. Keep requirements,
observed implementation, proposed changes and runtime verification distinct.
Follow [AI development standards](AI_DEVELOPMENT.md) for flexible backend/frontend
ownership and independent financial/auth/schema/synchronization review.
