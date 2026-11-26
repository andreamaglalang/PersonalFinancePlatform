# OWLI instructions for Claude Code

@AGENTS.md

The import above loads the shared project instructions. Follow
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

OWLI uses flexible task ownership, not an inherited fixed role/model roster.
Use the personal native Claude account selected by the user; do not select proxy
aliases or another provider to satisfy a global role template. Keep normal
permissions and approvals. Do not auto-create subagents for simple tasks.
The shared [AI standards](docs/AI_DEVELOPMENT.md) govern skill selection;
[Phase 0 setup](docs/PHASE0_AI_SETUP.md) records installation and verification.
