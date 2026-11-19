[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
# Build/publish only. Never runs the application, migrations or provisioning.
foreach ($entry in @(
    @{ Project = 'PersonalFinance.Api'; Output = 'api' },
    @{ Project = 'PersonalFinance.AdminMvc'; Output = 'mvc' }
)) {
    $project = Join-Path $repo ($entry.Project + '/' + $entry.Project + '.csproj')
    $output = Join-Path $PSScriptRoot ('out/' + $entry.Output)
    if (Test-Path -LiteralPath $output) { throw "Output already exists; use a fresh release workspace: $output" }
    & dotnet publish $project -c Release --no-restore --self-contained false -p:UseAppHost=false -o $output
    if ($LASTEXITCODE -ne 0) { throw "Publish failed: $project" }
}
# Published applications must never contain local development configuration.
$forbidden = @(Get-ChildItem (Join-Path $PSScriptRoot 'out') -Recurse -File | Where-Object {
    $_.Name -match '^appsettings\.(Development|Local).*\.json$|^secrets\.json$|^\.env' -or
    $_.Extension -in @('.pfx', '.p12', '.key', '.mdf', '.ldf', '.bak')
})
if ($forbidden.Count -gt 0) { throw 'Forbidden local configuration or secret-bearing file in publish output. Do not distribute.' }
Get-ChildItem (Join-Path $PSScriptRoot 'out') -Recurse -File | ForEach-Object {
    $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
    '{0}  {1}' -f $hash.Hash, $_.FullName.Substring($PSScriptRoot.Length + 1)
} | Set-Content (Join-Path $PSScriptRoot 'out/SHA256SUMS.txt') -Encoding utf8
