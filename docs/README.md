# OWLI documentation index and publication review

Reviewed on 26 November 2026 for `handoff/owli-20261125` in
`andreamaglalang/PersonalFinancePlatform`.

## Start here

Approved requirements are in [PRODUCT.md](../PRODUCT.md); current source-backed
client contracts are in [backend integration](backend-integration.md).
Use the [single knowledge index](karpathy-wiki/wiki/index.md) for targeted reads
and [worker/storage preparation](worker-config/README.md) for the approved future
console architecture and local preparation evidence. These do not certify
worker or cross-client runtime functionality.

For AI-assisted work, read [shared AI development standards](AI_DEVELOPMENT.md)
and the [Phase 0 environment and skill verification](PHASE0_AI_SETUP.md).
These do not certify application functionality or authorize application changes.

Follow the [Git development workflow](DEVELOPMENT_WORKFLOW.md),
[shared agent instructions](../AGENTS.md), and [Claude Code instructions](../CLAUDE.md).
`dev` is the permanent development/integration branch, initialized from the
latest approved handoff documentation commit. Handoff remains the recovery
baseline; `main` is reserved for explicitly approved stable releases.

The [25 November handoff](handoff/README.md) is the current setup entry point.
It supersedes older implementation and verification claims in historical reports.

| Development need | Documentation |
| --- | --- |
| Setup and onboarding | [Current handoff](handoff/README.md) |
| Architecture and source navigation | [Source map](handoff/SOURCE_MAP.md), [architecture reference](developer-guide/ARCHITECTURE.md) |
| Debugging and coordination | [Open issues](handoff/ISSUES.md), [handoff verification](handoff/VERIFICATION.md) |
| Database safety and setup | [Current database guide](handoff/DATABASE.md) |
| Android development | [Setup reference](developer-guide/ANDROID_SETUP.md), [development standards](developer-guide/ANDROID_DEVELOPMENT_STANDARDS.md) |
| Historical implementation decisions | Phase, authorization, budget, and category reports listed below |

For ChatGPT GitHub plugin retrieval, specify repository
`andreamaglalang/PersonalFinancePlatform`, ref `handoff/owli-20261125`, and an
exact path from this index. A lookup using the default branch can return a
different documentation set. Direct file retrieval was verified for all 44
existing files at baseline commit
`5cd1f3edae96abbcef9bdf5f4e0907f7d91b8f88`, with every remote blob SHA matching
the then-local tracked version. This verifies retrieval of that snapshot; it
does not establish search indexing or retrieval without an explicit ref.

## Review findings and publication decisions

- All 44 existing files under `docs/` were already tracked. There were no ignored
  or untracked files under `docs/` in this checkout.
- A content scan checked credential assignments, token and private key formats,
  connection strings, endpoints, account identifiers, private hostnames, and
  personal machine paths. Flagged values were reviewed. No apparent live secrets,
  private machine paths, private database credentials, or real financial records
  were identified in the available files.
- Retained examples use localhost/LocalDB development targets, synthetic test
  addresses, runtime variable references, or explicit secret placeholders.
  Database structure and migration discussions are development documentation;
  they are not database dumps. Existing demo scripts, generated migration SQL,
  and the alternative Android patch remain unchanged and were not executed.
- The 26 exact documentation exclusions below name files absent from both this
  checkout and the available Git history. Their contents could not be inspected.
  They remain ignored and are not approved for publication. No document-specific
  ignore rule could safely be removed on the evidence available in this checkout.
- Every existing ignore pattern is preserved, including secret overlays, private
  configuration, certificates and keys, database files and backups, artifacts,
  and local test output. Only explanatory comments were added to `.gitignore`.
- The initial publication-review commit preserved existing documentation byte
  for byte and added this index. The subsequent workflow setup updates branch
  guidance and agent instructions only; it changes no application code,
  database schema, or runtime configuration.

The review covers the available documentation and current ignore rules. It is
not a credential audit of all repository history. If an excluded document is
recovered, inspect its full contents (including embedded data and metadata for
Word files), sanitize only sensitive values, and remove only its exact ignore
rule after review. Missing files must not be described as published.

## Existing documentation verified through the GitHub plugin

- [`docs/DEVELOPER_SETUP.md`](DEVELOPER_SETUP.md)
- [`docs/Development_Admin_Provisioning.md`](Development_Admin_Provisioning.md)
- [`docs/MonthlyBudgets_Implementation_Report.md`](MonthlyBudgets_Implementation_Report.md)
- [`docs/OWLI_AUTH-5F_Database_Preflight.md`](OWLI_AUTH-5F_Database_Preflight.md)
- [`docs/OWLI_AUTH-5K-B_Diagnostics_Offline_Verification.md`](OWLI_AUTH-5K-B_Diagnostics_Offline_Verification.md)
- [`docs/OWLI_AUTH-5K-D2_Lifecycle_Diagnostics_Offline_Verification.md`](OWLI_AUTH-5K-D2_Lifecycle_Diagnostics_Offline_Verification.md)
- [`docs/OWLI_AUTH-5K-D5_Mock_Demo_Diagnostic_Contract.md`](OWLI_AUTH-5K-D5_Mock_Demo_Diagnostic_Contract.md)
- [`docs/Phase3_Final_Verification.md`](Phase3_Final_Verification.md)
- [`docs/Phase3_Implementation_Report.md`](Phase3_Implementation_Report.md)
- [`docs/Phase4_Connection_Preparation_Report.md`](Phase4_Connection_Preparation_Report.md)
- [`docs/Phase5_Authorization_Verification.md`](Phase5_Authorization_Verification.md)
- [`docs/Phase5_Implementation_Report.md`](Phase5_Implementation_Report.md)
- [`docs/Phase6_Implementation_Report.md`](Phase6_Implementation_Report.md)
- [`docs/Stage3_Step12B1_CategoryUniqueness_Report.md`](Stage3_Step12B1_CategoryUniqueness_Report.md)
- [`docs/developer-guide/ANDROID_DEVELOPMENT_STANDARDS.md`](developer-guide/ANDROID_DEVELOPMENT_STANDARDS.md)
- [`docs/developer-guide/ANDROID_SETUP.md`](developer-guide/ANDROID_SETUP.md)
- [`docs/developer-guide/ARCHITECTURE.md`](developer-guide/ARCHITECTURE.md)
- [`docs/developer-guide/BACKEND_SETUP.md`](developer-guide/BACKEND_SETUP.md)
- [`docs/developer-guide/DATABASE_SETUP.md`](developer-guide/DATABASE_SETUP.md)
- [`docs/developer-guide/DEPLOYMENT.md`](developer-guide/DEPLOYMENT.md)
- [`docs/developer-guide/KNOWN_ISSUES.md`](developer-guide/KNOWN_ISSUES.md)
- [`docs/developer-guide/MVC_SETUP.md`](developer-guide/MVC_SETUP.md)
- [`docs/developer-guide/NEXT_STEPS.md`](developer-guide/NEXT_STEPS.md)
- [`docs/developer-guide/README.md`](developer-guide/README.md)
- [`docs/developer-guide/REQUIREMENTS_STATUS.md`](developer-guide/REQUIREMENTS_STATUS.md)
- [`docs/developer-guide/VERIFICATION.md`](developer-guide/VERIFICATION.md)
- [`docs/developer-guide/WINDOWS_SETUP.md`](developer-guide/WINDOWS_SETUP.md)
- [`docs/developer-guide/config-examples/api.appsettings.Development.example.json`](developer-guide/config-examples/api.appsettings.Development.example.json)
- [`docs/developer-guide/config-examples/owli-dev.env.example.ps1`](developer-guide/config-examples/owli-dev.env.example.ps1)
- [`docs/elect3-demo/DEMO_PREREQUISITES.md`](elect3-demo/DEMO_PREREQUISITES.md)
- [`docs/elect3-demo/Invoke-OwliBackendDemo.ps1`](elect3-demo/Invoke-OwliBackendDemo.ps1)
- [`docs/elect3-demo/MIGRATION3_PLAN.md`](elect3-demo/MIGRATION3_PLAN.md)
- [`docs/elect3-demo/MIGRATION4_REVIEW.md`](elect3-demo/MIGRATION4_REVIEW.md)
- [`docs/elect3-demo/Seed-OwliPresentationData.ps1`](elect3-demo/Seed-OwliPresentationData.ps1)
- [`docs/elect3-demo/mig3_AddSavingsGoalContributionLedgerAndRowVersions.generated.sql`](elect3-demo/mig3_AddSavingsGoalContributionLedgerAndRowVersions.generated.sql)
- [`docs/elect3-demo/mig4_AddBillPaymentReceiptsAndGuards.generated.sql`](elect3-demo/mig4_AddBillPaymentReceiptsAndGuards.generated.sql)
- [`docs/handoff/ANDROID_ALTERNATIVE_WIP.patch`](handoff/ANDROID_ALTERNATIVE_WIP.patch)
- [`docs/handoff/DATABASE.md`](handoff/DATABASE.md)
- [`docs/handoff/ISSUES.md`](handoff/ISSUES.md)
- [`docs/handoff/README.md`](handoff/README.md)
- [`docs/handoff/SOURCE_INVENTORY.json`](handoff/SOURCE_INVENTORY.json)
- [`docs/handoff/SOURCE_MAP.md`](handoff/SOURCE_MAP.md)
- [`docs/handoff/VERIFICATION.md`](handoff/VERIFICATION.md)
- [`docs/handoff/config/development.env.example.ps1`](handoff/config/development.env.example.ps1)

## Intentionally excluded: unavailable and unreviewed

These are the exact retained `.gitignore` targets. None was present locally or
found in history reachable from the available Git refs.

- `docs/CategoryUniqueness_BackupRecovery_ReadinessAssessment.md`
- `docs/CategoryUniqueness_ConstraintExpression_Review.md`
- `docs/CategoryUniqueness_DevelopmentMigration_ExecutionRunbook.md`
- `docs/CategoryUniqueness_DevelopmentMigration_SafetyPlan.md`
- `docs/CategoryUniqueness_GuardedPreflight_Implementation.md`
- `docs/CategoryUniqueness_MetadataVisibility_Correction_Implementation.md`
- `docs/CategoryUniqueness_MetadataVisibility_Failure_Review.md`
- `docs/CategoryUniqueness_OperationalInspection_ArgumentException_Fix.md`
- `docs/CategoryUniqueness_OperationalInspection_MicrosoftSqlClient_Implementation.md`
- `docs/CategoryUniqueness_OperationalInspection_ReadOnly.md`
- `docs/CategoryUniqueness_OperationalInspection_SQLConnection_Fix.md`
- `docs/CategoryUniqueness_OperationalInspection_SQLDiagnostics_Enhancement.md`
- `docs/CategoryUniqueness_PreparationStatus_20261102_2235.md`
- `docs/CategoryUniqueness_RemainingOperationalReviews.md`
- `docs/CategoryUniqueness_SQLConnection_ProbePreparation.md`
- `docs/CategoryUniqueness_SQLConnection_TargetedInvestigation.md`
- `docs/CategoryUniqueness_SQLDiagnostic_FieldCapture_Review.md`
- `docs/CategoryUniqueness_SQLProvider_CompatibilityAssessment.md`
- `docs/CategoryUniqueness_SupplementaryInspection_Preparation.md`
- `docs/CategoryUniqueness_TargetedWriterInventory_20261102.md`
- `docs/DevelopmentDatabaseVerification_ParserRepair.md`
- `docs/DevelopmentDatabaseVerification_SchemaMappingRepair.md`
- `docs/DevelopmentDatabaseVerification_SqlIdentityDiagnostics.md`
- `docs/DevelopmentDatabaseVerification_TransactionBoundaries.md`
- `docs/Initial_Project_Report_Personal_Finance_Platform_v3.1_Implementation_Ready.docx`
- `docs/MonthlyBudgets_DevelopmentDatabaseVerification_Preparation.md`
