# Phase 0 AI environment setup and verification

The original record below describes initial setup commit
`5b5b2756cb45ea347a8bcc1db6773468c94090ab`. Its no-database-operation statements
apply to that earlier scope. The approved finalization section at the end
records the later dedicated-storage exception and current evidence. Historical
results are preserved rather than silently rewritten.

Reviewed 26 November 2026. Scope: agent environment, skills and shared workflow
only. Branch: `chore/ai-agent-skills-setup`, created from synchronized `dev` at
`6687eb95ab3741a747da36d334aa3420c4ed7c23`. Nothing is approved for integration
into `dev` by this record. See [AI_DEVELOPMENT.md](AI_DEVELOPMENT.md) for shared
project standards and [DEVELOPMENT_WORKFLOW.md](DEVELOPMENT_WORKFLOW.md) for Git.

## Environment

| Check | Result | Evidence/limit |
| --- | --- | --- |
| Claude Code installation | PASS | CLI 2.1.332 |
| Codex installation | PASS | CLI 0.163.0 |
| Claude authentication | PASS | User completed interactive personal-account sign-in; first-party readiness verified through the CLI without recording identity/credentials |
| Codex authentication | PASS | Existing ChatGPT login; provider connectivity diagnostic passed |
| Codex diagnostics | PASS with notes | `codex doctor --summary`: zero failures; optional MCP configuration warning, noninteractive terminal warning, newer CLI available; no unrelated settings changed |
| Project instruction loading | PASS | Claude imports `@AGENTS.md`; read-only tests read shared standards and Git workflow |

Relevant supported locations: root `AGENTS.md` for Codex; root `CLAUDE.md` for
Claude (imports shared instructions); project `.claude/settings.json` for Claude
plugin selection; project `.agents/skills/` for local Codex skills; user
`~/.claude/skills/`, `~/.claude/plugins/`, `~/.agents/skills/`,
`~/.codex/plugins/` for existing skills/plugins. User settings/authentication
remain outside version control. No personal machine paths or account identifiers
are needed in project documentation.

## Authoritative skills and installed versions

| Skill | Verified upstream and maintainer | Installed version/source | Compatibility and scope |
| --- | --- | --- | --- |
| Karpathy engineering guidelines | [multica-ai/andrej-karpathy-skills](https://github.com/multica-ai/andrej-karpathy-skills), plugin author `forrestchang`; community guidelines inspired by Karpathy, not an official Karpathy product | 1.0.0; commit `2c606141936f1eeef17fa3043a72095b4765b9c2` | Claude: existing native user plugin `andrej-karpathy-skills@karpathy-skills` reused. Codex: instruction-only `SKILL.md` installed locally in `.agents/skills/karpathy-guidelines/` using the official skill-installer and `--ref`; native format discovery, cross-agent use of instructions, no claim of an upstream Codex plugin |
| Superpowers | [obra/superpowers](https://github.com/obra/superpowers), Jesse Vincent | 7.0.2 on both agents; commit `22f0b463e7a5e579904dd6211a520321035526f7` | Claude: native project plugin `superpowers@claude-plugins-official`. Codex: native user plugin `superpowers@superpowers-dev`, maintainer marketplace pinned to the same commit; CLI has no project-scope plugin-install flag |
| Impeccable | [pbakaus/impeccable](https://github.com/pbakaus/impeccable), Paul Bakaus | 4.8.0; installed upstream snapshot `687bc381bc9091bf252c0a05303640561014a425` | Existing native standalone skills reused at `~/.claude/skills/impeccable/` and `~/.agents/skills/impeccable/`. Claude's duplicate plugin disabled for this project only. Existing standalone description is expanded locally; body is preserved, not silently replaced |
| Ponytail | [DietrichGebert/ponytail](https://github.com/DietrichGebert/ponytail), Dietrich Gebert | 5.3.3; installed source `96608e078b2d5ea607c5c3b8b5a54d1c1fba6846` | Existing native user plugins reused on both agents; no duplicate installation or telemetry/statusline setup added |

Karpathy is an engineering instruction skill. Superpowers is a plugin framework
and methodology composed of workflow skills. Impeccable is a design skill/plugin
with optional native engine and hooks. Ponytail is an engineering skill/plugin
with optional lifecycle/UI features. The separately installed
`karpathy-llm-wiki` is unrelated and was neither substituted nor modified.

Sources and installation documentation:

- [Karpathy README at the installed commit](https://github.com/multica-ai/andrej-karpathy-skills/blob/2c606141936f1eeef17fa3043a72095b4765b9c2/README.md).
  Older docs use `forrestchang/andrej-karpathy-skills`; the existing marketplace
  points to `multica-ai/andrej-karpathy-skills`. Metadata and pinned skill contents
  agree. Karpathy's Claude and Codex instruction bodies have identical hashes.
- [Superpowers installation README](https://github.com/obra/superpowers/blob/22f0b463e7a5e579904dd6211a520321035526f7/README.md),
  [native Codex marketplace manifest](https://github.com/obra/superpowers/blob/22f0b463e7a5e579904dd6211a520321035526f7/.agents/plugins/marketplace.json),
  [native Codex plugin manifest](https://github.com/obra/superpowers/blob/22f0b463e7a5e579904dd6211a520321035526f7/.codex-plugin/plugin.json).
- [Impeccable installation README](https://github.com/pbakaus/impeccable/blob/687bc381bc9091bf252c0a05303640561014a425/README.md)
  documents both agents' native skill locations and manual-copy alternatives.
- [Ponytail installation README](https://github.com/DietrichGebert/ponytail/blob/96608e078b2d5ea607c5c3b8b5a54d1c1fba6846/README.md)
  documents native Claude/Codex plugins and instruction-only alternatives.
- [Codex skill discovery documentation](https://developers.openai.com/codex/skills/),
  [Claude skill discovery](https://code.claude.com/docs/en/skills), and
  [Claude shared instruction imports](https://code.claude.com/docs/en/memory).

## Installation decisions and conflict resolution

Reused the three useful existing installations rather than reinstalling them.
The new Codex Karpathy download is project-local and excluded through local
`.git/info/exclude`; tracked `.gitignore` is unchanged. No third-party skill
bodies, caches or downloaded packages are proposed for publication.

Inspected the skill-installer script, plugin manifests, requested capabilities
and relevant hooks before use. Claude Superpowers' startup hook reads and emits
local instructions; its native Codex manifest has no hooks or connector.
Impeccable's launcher can download a checksum-verified engine and its live-copy
fallback can bypass approvals: neither engine execution nor live-copy mode was
needed or enabled here. Existing Ponytail includes optional hooks/board/webhook
capabilities; no external delivery, history import or new hook trust was enabled.
Existing user hooks were preserved; read-only smoke tests did not grant write
or bypass permissions to the model.

The recommended official Codex marketplace first returned a 6.3.0 Superpowers
package from distribution snapshot
`981b02c0c44058b595f3b4180002c3ae3c806d40`. Installation reported success, but the
native CLI catalog omitted it; reading its cached files was insufficient proof
of activation. Persisting that reserved official marketplace was rejected by
the CLI. Registered the maintainer's native marketplace at the inspected commit
instead, installed 7.0.2, and removed only the non-discoverable package introduced
in this setup. No pre-existing installation was removed.

Reproducible missing-plugin commands (inspect first; do not rerun when installed):

```sh
claude plugin install superpowers@claude-plugins-official --scope project
codex plugin marketplace add obra/superpowers --ref 22f0b463e7a5e579904dd6211a520321035526f7
codex plugin add superpowers@superpowers-dev
```

For Codex Karpathy, use the bundled `$skill-installer` with repository
`multica-ai/andrej-karpathy-skills`, path `skills/karpathy-guidelines`, pinned
commit above, destination `.agents/skills`, after establishing its local Git
exclusion. The helper supports `--repo`, `--path`, `--ref`, and `--dest`.
This is an instruction-format adaptation, not installation of a Claude runtime
plugin into Codex. New worktrees/machines must repeat the local install if the
repo-scoped skill is absent; ignored files do not follow Git branches or clones.

The existing Claude user instructions assume a fixed role/provider template.
Project instructions now specify flexible ownership, the personal native Claude
account, and no inherited proxy/model routing. The Claude smoke test explicitly
recognized and followed that override. Global instructions and account settings
were preserved. An ignored `.claude/settings.local.json` Impeccable override prevents duplicate
skill and hook activation alongside this host's existing standalone install; it does not disable
approval/security controls or remove the globally installed plugin.

## Verification matrix

| Check | Status | Evidence or explicit limit |
| --- | --- | --- |
| Claude native discovery: four requested skills | PASS | Fresh session catalog contained Karpathy, Superpowers, standalone Impeccable and Ponytail |
| Claude instruction-body loading/applicability | PASS | Read-only test loaded all four and gave concrete principles plus a scoped hypothetical money-rounding workflow |
| Codex Karpathy/Impeccable/Ponytail discovery/loading | PASS | Fresh CLI read-only test catalogued and read the actual skill bodies |
| Codex Superpowers discovery after repair | PASS | Fresh CLI catalog explicitly contained `superpowers:using-superpowers` and `superpowers:verification-before-completion`; both bodies loaded successfully; all other requested skills remained visible |
| Shared policies understood | PASS | Both agents read shared files and identified `dev`, task branches, separate Hangfire console-worker requirement, and Phase 0 restrictions |
| Skill engines/application workflows | NOT TESTED | Deliberately not run; this verifies loading and applicability, not UI detector or application functionality |
| Live simultaneous editing/multi-agent correctness | NOT TESTED | Ownership/worktree/review procedure defined; no concurrent application edits attempted |
| Full application audit/build/runtime/SQL | NOT TESTED | Outside Phase 0 scope |
| CLI GitHub write readiness | BLOCKED | Read-only repository permission check still reports no push permission; no account or credential replacement attempted |
| GitHub plugin publication | See final handoff | Use the connected GitHub plugin to publish only this isolated task branch; verify remote files and hashes before reporting publication |

Smoke tests used the real CLIs and current accounts. Claude: plan permission mode,
Read/Skill tools only, no extra MCP servers, no saved conversation. Codex:
read-only sandbox, no escalation approvals, ephemeral session. Prompts explicitly
prohibited application work, credential inspection, delegation, workflow/launcher
execution and writes. Only sanitized outcomes belong in Git; raw local outputs
are kept outside the repository and are not project artifacts.

To repeat manually, start a fresh session from the project root, inspect `/skills`
in Codex or the skill catalog in Claude, and ask:

> Read AGENTS.md and docs/AI_DEVELOPMENT.md. Without editing files or running
> launchers, load Karpathy guidelines, Superpowers verification-before-completion,
> Impeccable and Ponytail instruction bodies. Report their actual discovery/load
> status, `dev` task-branch policy, the separate Hangfire console-worker requirement,
> and a hypothetical financial-bug workflow. Do not audit or change the application.

Do not use bypass flags to make a test pass. In Claude, `claude auth login` is a
manual personal-account action if login is missing; never paste credentials into
chat. Native discovery and instruction-body loading do not guarantee every
upstream feature, hook or future task will work correctly.

## Proposed repository changes and review boundary

New: `.claude/settings.json` (Superpowers enabled), `docs/AI_DEVELOPMENT.md`,
and this setup record. The host-specific Impeccable override is local-only,
so the shared configuration does not disable another developer's sole install.
Modified: `AGENTS.md`, `CLAUDE.md`, and `docs/README.md` to reference the shared
standards and import existing instructions. Existing workflow, handoff, database,
source-map, issue and verification documents are preserved.

Local-only changes: pinned Karpathy skill, `.claude/settings.local.json` duplicate
Impeccable override, and their exact `.git/info/exclude` entries; native plugin
cache/registry entries added through supported agent CLIs.
No broad global settings replacement, account change, application/runtime
configuration change or permission weakening was performed.

Review these six repository paths before merging this task branch into `dev`.
No changes to `main` or preserved handoff are authorized. Application source,
tracked runtime configurations, schema files, and security ignore rules must
remain byte-identical to the parent `dev` commit. Database, certificates, SQL
container, credentials and services must not be accessed or altered by setup.

Both agents may implement/debug/review later. Assign an owner and disjoint files,
use separate task branches/worktrees for concurrency, share concise evidence,
independently review high-risk financial/auth/DB/sync work, and integrate only
approved verified work through `dev`. ChatGPT coordinates and independently
checks sanitized GitHub context; it cannot see local-only skills/uncommitted work.

After final checks and explicit review approval, merge this branch into `dev`,
then authorize a separate comprehensive system audit. Do not start implementation
or produce the final implementation roadmap as part of Phase 0.

## Readiness and remaining actions

Local Phase 0 technical setup is complete (estimated 100% of scoped technical
checks), including real catalog discovery and instruction-body loading in both
agents. Integration/review remains pending. This does not measure application
completion or guarantee every optional skill engine/hook feature.

No further authentication action is currently required. Start fresh agent
sessions to load the installed plugins and project instructions. Local GitHub
CLI push remains blocked for the current CLI account; connected-plugin write
access is separate and must be checked when publishing. Do not replace local
credentials silently. Optional MCP configuration warnings do not block these
skill checks; leave unrelated integrations/settings alone.

The environment is ready to support a comprehensive system audit after this
setup proposal is reviewed and explicitly approved for integration. No audit,
application implementation, runtime tests, SQL operations or final roadmap were
started. Keep Phase 0 changes isolated until that approval.

## Approved Phase 0 finalization — 26 November 2026

The owner approved the integrated assessment and authorized dedicated local
Hangfire catalog/schema preparation, disabled private future-worker settings,
shared knowledge and instruction completion. No finance database writes or
application changes were authorized or performed.

| Skill | Current version/source | Location and final evidence |
| --- | --- | --- |
| Karpathy LLM Wiki | [Astro-Han/karpathy-llm-wiki](https://github.com/Astro-Han/karpathy-llm-wiki); installed SKILL blob `82cb4e28b9ac8a7779d866091697e317bdd3d146` matches upstream; no semantic version declared | Existing Codex installation retained through `~/.agents/skills/karpathy-llm-wiki`; Claude project `.claude/skills/karpathy-llm-wiki` links the same existing source. Native discovery and body loading PASS in both fresh CLI tests. Project root adaptation is explicit, not an upstream code patch |
| Karpathy Guidelines | 1.0.0; `multica-ai/andrej-karpathy-skills` | Existing Claude plugin and Codex repo-local instruction skill retained; both catalogs still expose it; earlier body-load evidence retained |
| Superpowers | 7.0.2; `obra/superpowers` | Existing Claude project/Codex maintainer plugins unchanged, visible in both catalogs; no reinstallation |
| Ponytail | 5.3.3; `DietrichGebert/ponytail` | Existing native user plugins unchanged and visible; no new hooks/telemetry enabled |
| Impeccable | 4.8.0; `pbakaus/impeccable` | Existing standalone native skills retained/visible; local duplicate-plugin disabling preserved |

The wiki skill is distinct from coding guidelines. Its supported Agent Skills
format loads in both tools; the local-only Claude link avoids copying source.
New machines/worktrees must register the skill if absent and verify discovery;
local links/downloads are not committed. Claude first-party and Codex ChatGPT
accounts completed the read-only tests without further authentication. Installed
CLI versions remain Claude Code 2.1.332 and Codex 0.163.0. No agent upgrade or
unrelated global settings replacement was performed.

[PRODUCT.md](../PRODUCT.md) now centralizes approved requirements;
[backend-integration.md](backend-integration.md) records current source contracts;
[AI_DEVELOPMENT.md](AI_DEVELOPMENT.md) and AGENTS retain the shared methodology.
CLAUDE is reduced to import/shared references and necessary Claude-specific rules.
Default Codex backend and Claude frontend ownership is flexible; both review,
coordinate contracts, test and maintain knowledge. No competing plan or empty
`docs/superpowers/` directory was created.

The shared root is `docs/karpathy-wiki/`, with `raw/`, `wiki/index.md`,
`wiki/log.md` and three focused articles. Both agents actually navigated that
single index to the architecture article, rather than loading the whole wiki.
The installed evidence checker passed with zero fidelity suspects, evidence
errors and unreferenced raw files, using existing Python 3.10.22. The checker's
Python 3.10+ requirement was recorded; system Python was not upgraded.
Private notes and worker configuration have exact tracked ignore protection.

[Worker/storage preparation](worker-config/README.md) records the approved
independent console architecture, selected family/IDs/schedules, HTTP current-user
and Development endpoint concerns, stored-summary questions, job compatibility,
portable SQL tests and future runtime acceptance. The worker remains unimplemented.

Dedicated local storage was absent and created on the verified existing approved
container/volume/SQL instance. The official cached 1.8.25 installer matched release
source; guarded explicit provisioning completed at schema version 9 with 11
expected tables. Target server/job/recurring/queue counts were zero. Both catalogs
authenticated through the mapped local endpoint. Before/after finance schema,
constraint, migration-history and row-count fingerprints matched; container
identity/start time/volume mapping matched. This does not prove every financial
row's content or exclude unrelated concurrent activity.

The actual `.local/worker/appsettings.Worker.Local.json` is ignored, owner-only
(file 600, directory 700), reuses existing local SQL credentials and keeps
`Hangfire.Enabled=false`. A single [tracked template](worker-config/appsettings.Worker.template.json)
contains placeholders only. No worker, processor, recurring registration or
financial job was started. Existing API/MVC/Android runtime settings and security
ignore rules were preserved. No finance migration, container restart, credential
or certificate regeneration, deployment or production access occurred.

Runtime worker lifecycle, job execution/retries, SQL financial race behavior,
rendered clients and cross-platform acceptance remain NOT TESTED. Schema
preparation and instruction discovery are not complete application verification.
The environment/storage preparation is ready for the authorized audit after
final PR review/approval; application completion is not measured by this status.

Publish only reviewed instructions/docs/templates on existing branch/PR #1.
Recheck CLI/plugin permission distinction and remote content before reporting
publication. Never replace accounts or generate divergent duplicate commits.
Keep `dev`, `main` and preserved handoff unchanged pending final review/approval.

Independent read-only Claude review sampled source contracts and storage/worker
claims. It found two low-severity instruction defects (stale handoff setup
wording and an overbroad storage prohibition) plus an HTTP-400 documentation
clarification; all were corrected against approved scope and actual middleware.
The review did not access private settings or rerun SQL, and does not constitute
runtime acceptance or permission to merge.

A targeted independent follow-up confirmed the three review corrections and
reported no concrete blockers. Its remaining non-blocking wording suggestions
were checked against source: job-family versus processor-overlap prohibitions
are now explicit, and the non-Development `/api/dev` 404 precedence is recorded.
No application change or runtime retest was needed for these clarifications.
