# Sanitized agent discovery evidence

> Source: Bounded read-only native Claude Code and Codex CLI smoke tests
> Collected: 2026-11-26
> Published: 2026-11-26

{
  "claude_cli": "2.1.332",
  "codex_cli": "0.163.0",
  "claude_native_wiki_discovered": true,
  "codex_native_wiki_discovered": true,
  "both_wiki_instruction_bodies_loaded": true,
  "other_four_skills_visible": true,
  "single_index": "docs/karpathy-wiki/wiki/index.md",
  "knowledge_root": "docs/karpathy-wiki/",
  "backend_owner": "Codex",
  "frontend_owner": "Claude",
  "selected_job_family": "FinanceJobs",
  "worker_implemented": false,
  "phase0_activation_allowed": false,
  "merge_approval_required": true
}

Each agent read the single index and only the architecture article. Neither test
performed writes, launcher execution, ingestion, database access or delegation.
This verifies instruction discovery/loading and applicable policy awareness;
not complete engine, application or worker operation.
