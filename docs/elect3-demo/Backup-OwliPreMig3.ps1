<#
.SYNOPSIS
    Fail-closed, uniquely named COPY_ONLY backup of PersonalFinanceDb before OWLI migration 3.

.DESCRIPTION
    SAFE BY DEFAULT: without -Execute it only prints the planned file name and command; nothing touches SQL Server.
    With -Execute (and an exact confirmation phrase) it:
      1. builds a unique name  D:\Final Project\db-backups\PersonalFinanceDb_pre_mig3_<yyyyMMdd-HHmmss>_<8 hex>.bak
      2. refuses if that file already exists or the backup folder is missing
      3. renders mig3_2_backup.template.sql with that literal path into a temporary .sql file (one token, verified)
      4. runs it with sqlcmd -b -I (the template itself refuses unexpected paths, never overwrites (NOINIT), requires
         exactly one copy-only checksummed set, and runs RESTORE VERIFYONLY WITH CHECKSUM)
      5. requires exit code 0, "BACKUP VERIFIED" in the output and the new file on disk
      6. records size and SHA-256 in a manifest next to the backup, and deletes the temporary .sql file
    It never restores, never deletes or overwrites a backup, and prints no credentials (Windows authentication).
#>
[CmdletBinding()]
param([switch] $Execute)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$here      = Split-Path -Parent $MyInvocation.MyCommand.Path
$template  = Join-Path $here 'mig3_2_backup.template.sql'
$backupDir = 'D:\Final Project\db-backups'
$token     = '__OWLI_BACKUP_PATH__'
$stamp     = Get-Date -Format 'yyyyMMdd-HHmmss'
$suffix    = [guid]::NewGuid().ToString('N').Substring(0, 8)
$name      = "PersonalFinanceDb_pre_mig3_${stamp}_${suffix}.bak"
$target    = Join-Path $backupDir $name
$sqlcmdArgs = @('-X', '-S', '(localdb)\MSSQLLocalDB', '-d', 'PersonalFinanceDb', '-E', '-N', '-C', '-I', '-b', '-x', '-l', '30', '-t', '300', '-W', '-s', '|')

if (-not (Test-Path -LiteralPath $template -PathType Leaf)) { throw "Template missing: $template" }
if (-not (Test-Path -LiteralPath $backupDir -PathType Container)) { throw "Backup folder missing: $backupDir (create it under separate approval)." }
if (Test-Path -LiteralPath $target) { throw "Refusing: $target already exists." }
if ($name -notmatch '^PersonalFinanceDb_pre_mig3_\d{8}-\d{6}_[0-9a-f]{8}\.bak$') { throw 'Generated name failed validation.' }

Write-Host "Planned backup file: $target"
Write-Host "Command: sqlcmd $($sqlcmdArgs -join ' ') -i <rendered template> -o <output>"
if (-not $Execute) { Write-Host 'Plan only. Add -Execute to run the backup (requires approval).'; return }

$typed = Read-Host 'This writes ONE new backup file. Type exactly: BACKUP PERSONALFINANCEDB BEFORE MIGRATION 3'
if ($typed -cne 'BACKUP PERSONALFINANCEDB BEFORE MIGRATION 3') { throw 'Not confirmed. Nothing was done.' }

$text = [IO.File]::ReadAllText($template)
if (([regex]::Matches($text, [regex]::Escape($token))).Count -ne 1) { throw 'Template must contain the path token exactly once.' }
$rendered = Join-Path $env:TEMP ("owli_mig3_backup_{0}_{1}.sql" -f $stamp, $suffix)
$output   = Join-Path $backupDir ("{0}.sqlcmd.txt" -f [IO.Path]::GetFileNameWithoutExtension($name))
try {
    [IO.File]::WriteAllText($rendered, $text.Replace($token, $target.Replace("'", "''")), (New-Object Text.UTF8Encoding($false)))
    if (Test-Path -LiteralPath $target) { throw "Refusing: $target appeared before the backup started." }
    & sqlcmd @sqlcmdArgs -i $rendered -o $output
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "sqlcmd exited with $code. See $output. No retry is attempted." }
    if (-not (Select-String -LiteralPath $output -Pattern 'BACKUP VERIFIED' -SimpleMatch -Quiet)) { throw "Verification marker missing. See $output." }
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "Backup file not found: $target" }
    $item = Get-Item -LiteralPath $target
    $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    $manifest = [ordered]@{ file = $item.FullName; sizeBytes = $item.Length; sha256 = $hash; createdLocal = (Get-Date -Format 's');
        database = 'PersonalFinanceDb'; purpose = 'pre AddSavingsGoalContributionLedgerAndRowVersions'; sqlcmdOutput = $output }
    $manifestPath = "$target.manifest.json"
    if (Test-Path -LiteralPath $manifestPath) { throw "Refusing to overwrite $manifestPath" }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Write-Host "BACKUP VERIFIED: $($item.FullName)  size=$($item.Length)  sha256=$hash"
} finally {
    if (Test-Path -LiteralPath $rendered) { Remove-Item -LiteralPath $rendered -Force }
}
