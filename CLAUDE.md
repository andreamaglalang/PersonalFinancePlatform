# OWLI instructions for Claude Code

Read and follow [AGENTS.md](AGENTS.md) and
[docs/DEVELOPMENT_WORKFLOW.md](docs/DEVELOPMENT_WORKFLOW.md) before any task.
These shared project instructions also govern Codex and future skills/plans.

- `dev` is the permanent development/integration branch and shared source of truth.
- `handoff/owli-20261125` is the original consolidated baseline. Preserve it after
  the initial approved documentation/workflow setup and creation of `dev`.
- Start temporary task branches from the latest synchronized `dev`; merge
  completed and verified work back into `dev` through review.
- `development/owli-stabilization` is temporary, not an integration branch.
- `main` is reserved for verified releases with explicit owner approval.
- Coordinate ownership with Codex, use separate worktrees for concurrent tasks,
  avoid overlapping file edits, and preserve other agents' changes.
- Never force-push, reset/rewrite history, publish secrets, or change application
  code, databases, or runtime settings during branch/workflow setup.
