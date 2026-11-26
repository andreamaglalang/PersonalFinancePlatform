$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot)
try {
    # Explicit allowlist: default entrypoints use synthetic in-memory/offline checks.
    # Never add SQL/live flags, server startup, deployment or provisioning commands here.
    # SqlIntegrationTests runs only its offline guard checks unless OWLI_SQL_TEST_SERVER is set.
    foreach ($name in @('FinanceTests', 'AuthenticationTests', 'ApiSecurityTests',
        'CategoryUniquenessTests', 'SchemaValidation', 'AdminProvisioningTests', 'AdminMvc.UiTests', 'SqlIntegrationTests')) {
        dotnet run --project "tests/PersonalFinance.$name"
        if ($LASTEXITCODE -ne 0) { throw "$name failed with exit code $LASTEXITCODE" }
    }
} finally { Pop-Location }
