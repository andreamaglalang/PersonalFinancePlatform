# OWLI shared knowledge conventions

The single knowledge index is [wiki/index.md](wiki/index.md). This directory is
the project root passed to the installed `karpathy-llm-wiki` skill/checker:
`raw/` stores immutable sanitized source evidence; `wiki/` stores compiled
knowledge; `wiki/log.md` is append-only history. Do not create root-level
`raw/`/`wiki/` or a second index. This README defines conventions, not another
navigation index.

Before a task, read the index, relevant article(s) and affected current source.
Approved [product requirements](../../PRODUCT.md) define intended behavior;
source at a stated commit defines implementation; runtime evidence defines
verification. Cached knowledge never overrides changed source. After significant
verified work, capture a new source snapshot, update only affected articles,
mark superseded/disputed claims visibly, update index/log and check evidence.
Keep one plan and acceptance record; do not bulk-ingest or copy the whole repo.

Articles use the skill's Sources/Raw/Updated metadata and one topic directory
level under `wiki/`. Cite immutable raw evidence and original source paths/SHAs.
Preserve raw files after ingestion. New operational results get a new dated
source rather than silently editing old evidence. Mechanical evidence checks
are read-only; the skill's full lint workflow can fix navigation and append its
log, so use it only with write authorization. Run its `scripts/check_evidence.py`
with this directory as the argument, not the repository root. It requires Python
3.10 or newer; the system Python may be older. Reuse a verified installed
interpreter rather than modifying the skill or installing another dependency.
The checker does not replace source freshness review or runtime verification.

Use Confirmed, Suspected, Proposed, Implemented, Fixed, Verified, Blocked and
Not tested deliberately, with PASS/FAIL/BLOCKED/NOT TESTED/NOT APPLICABLE for
checks. Keep expected architecture separate from current implementation.
No credentials, private connection values, financial records, job payloads,
private operational logs or personal machine details belong in raw or wiki.
Private scratch space `.local/karpathy-wiki/` is ignored and non-authoritative.
Only sanitized shared knowledge is committed. GitHub consumers must specify
repository/ref/commit; local skills and ignored notes do not follow clones.
