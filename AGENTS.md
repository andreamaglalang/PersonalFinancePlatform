# OWLI project instructions

These rules apply to Codex, Claude Code, all repository tasks, and any project
skills, plans, or automation added later. Read [the Git workflow](docs/DEVELOPMENT_WORKFLOW.md)
before starting work. Direct user instructions take precedence.

## Branch policy

- `dev` is the permanent development and integration branch and shared source of
  truth. Start every new task from the latest synchronized `dev` on a temporary,
  appropriately named branch. Do not create a task branch from `main`, the
  preserved handoff, or an old integration branch.
- `handoff/owli-20261125` is the consolidated original baseline and recovery
  reference. Finish the initial approved documentation/workflow setup there,
  publish it, then establish `dev` directly from that exact latest handoff commit.
  After initialization, preserve handoff; do not develop applications on it.
- `main` is stable release code. Merge verified `dev` into `main` only with the
  owner's explicit approval. Never develop directly on `main`.
- `development/owli-stabilization`, `fix/*`, `feature/*`, and `test/*` are temporary
  task branches. Integrate completed, verified work into `dev` through review.
  Do not automatically merge incomplete, failing, or unverified work.

## Every development task

1. Inspect working tree status and ownership; preserve existing changes.
2. Fetch remote state and synchronize local `dev` with `origin/dev` using a safe
   fast-forward. If missing, divergent, or blocked, investigate and report;
   never silently fall back to an older baseline.
3. Create a focused task branch from synchronized `dev`. During concurrent work,
   assign ownership and use separate branches and isolated Git worktrees.
4. Investigate, implement within the assigned scope, and perform relevant
   automated and runtime checks. Report what was actually tested and any limits.
5. Review correctness, regressions, security, and scope, then commit and push.
6. Check for conflicting work, integrate verified changes into `dev` through the
   appropriate review/merge process, and verify integrated behavior. Use the
   updated `dev` as the next task's baseline.

## Safety and coordination

- Never force-push, reset/rewrite existing branch history, discard changes,
  delete branches without authorization, or overwrite another agent's work.
- Avoid simultaneous edits to the same files and duplicate implementations;
  perform independent reviews where appropriate.
- Never publish secrets, private configuration, certificates/private keys,
  database files/backups, real financial data, or personal machine details.
  Keep security ignore rules and local environments intact.
- Branch/workflow setup is documentation and Git metadata work only. Do not edit
  application code, change schemas, touch database contents, run migrations,
  restart services, or alter runtime configuration as part of that setup.
- Skills and plans must respect this workflow. A skill does not authorize
  bypassing branch policy, publishing secrets, or merging into `main`.

For current setup, architecture, issues, and verification, start at
[the documentation index](docs/README.md) and [the handoff guide](docs/handoff/README.md).
Older reports retain historical implementation claims; they do not override
this branch policy or constitute current verification.

## OWLI AI development standards

Follow [AI_DEVELOPMENT.md](docs/AI_DEVELOPMENT.md) for skill selection, academic
requirements, financial safeguards, agent ownership, and evidence labels.
Use only relevant skills; scale planning and tests to task risk. Project policy
supersedes conflicting optional skill workflows and inherited role templates,
subject to mandatory platform/security policy. Phase 0 configuration changes
stay on `chore/ai-agent-skills-setup` until reviewed and explicitly approved for
integration; do not begin the system audit or application work in that phase.
