<#
.SYNOPSIS
    OWLI backend demonstration steps (authentication, reports, budget alerts, monthly summaries, Frankfurter FX).

.DESCRIPTION
    SAFE BY DEFAULT: without -Execute this script only prints the plan and sends nothing.
    With -Execute it runs ONE selected step against an already running OWLI API, asking for confirmation first.
    Steps that write data (budget, expense, job runs) require typing an exact confirmation phrase.

    It never prints passwords, access tokens or refresh tokens, never disables TLS certificate validation,
    and signs out (revokes the refresh token) at the end of each run.

    Windows PowerShell 5.1 compatible. Run interactively in your own terminal.

.EXAMPLE
    .\Invoke-OwliBackendDemo.ps1                      # print the plan only
    .\Invoke-OwliBackendDemo.ps1 -Step Reports -Execute
    .\Invoke-OwliBackendDemo.ps1 -Step BudgetAlerts -Execute -CategoryId 6 -Month 2026-11
#>
[CmdletBinding()]
param(
    [ValidateSet('Plan', 'Auth', 'Reports', 'BudgetAlerts', 'MonthlySummaries', 'Fx')]
    [string] $Step = 'Plan',
    [switch] $Execute,
    [string] $BaseUrl = 'https://localhost:7027/',
    [string] $Month = (Get-Date -Format 'yyyy-MM'),
    [int] $CategoryId = 0,
    [decimal] $BudgetLimit = 2.00,
    [decimal] $ExpenseAmount = 1.00
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Write-Plan {
    @"
OWLI backend demo plan (nothing is sent without -Execute)
  Auth             Sign in (hidden password prompt), GET api/auth/me, sign out.            Writes: 1 refresh-token row.
  Reports          GET api/reports/monthly?month=$Month-01 for the signed-in member.      Writes: refresh token only.
  BudgetAlerts     Member: POST api/budgets (limit $BudgetLimit, 80%) + POST api/transactions ($ExpenseAmount PHP).
                   Administrator: POST api/dev/jobs/budget-alerts/run (twice: expect created 1, then 0).
                   Member: GET api/notifications.                                          Writes: budget, expense, 1 notification.
  MonthlySummaries Administrator: POST api/dev/jobs/monthly-summaries/run (twice: expect created N, then 0).
                   Member: GET api/notifications.                                          Writes: summaries + notifications.
  Fx               Member: POST api/transactions 1.00 USD; shows FxRate, FxRateDate, AmountBase.
                                                                                           Writes: 1 expense, 1 cached ExchangeRates row.
Prerequisites: API running from the backend worktree in Development (docs/elect3-demo/DEMO_PREREQUISITES.md),
trusted HTTPS certificate on this machine, internet access for Fx, an Administrator account for job steps.
"@
}

function Invoke-Owli([string] $Method, [string] $Path, $Body = $null, [string] $Token = $null) {
    $headers = @{ Accept = 'application/json' }
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $request = @{ Method = $Method; Uri = ($BaseUrl.TrimEnd('/') + '/' + $Path.TrimStart('/')); Headers = $headers; UseBasicParsing = $true }
    if ($null -ne $Body) { $request['Body'] = ($Body | ConvertTo-Json -Depth 6); $request['ContentType'] = 'application/json; charset=utf-8' }
    try { return Invoke-RestMethod @request }
    catch {
        $status = $null
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        throw "Request $Method $Path failed (HTTP $status). No details are printed to avoid leaking data."
    }
}

function Connect-Owli([string] $Label) {
    $email = Read-Host "$Label email"
    $secure = Read-Host "$Label password" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
        $auth = Invoke-Owli 'POST' 'api/auth/login' @{ email = $email; password = $plain }
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr); $plain = $null
    }
    Write-Host "$Label signed in (roles: $($auth.user.roles -join ', '))."
    return $auth
}

function Disconnect-Owli($auth) {
    if ($auth) { try { Invoke-Owli 'POST' 'api/auth/logout' @{ refreshToken = $auth.refreshToken } | Out-Null; Write-Host 'Signed out.' } catch { Write-Warning 'Sign-out not confirmed.' } }
}

function Confirm-Write([string] $Phrase) {
    $typed = Read-Host "This step WRITES data. Type exactly: $Phrase"
    if ($typed -cne $Phrase) { throw 'Not confirmed. Nothing was written.' }
}

function Get-Notifications($auth) {
    $page = Invoke-Owli 'GET' 'api/notifications?page=1&pageSize=10' -Token $auth.accessToken
    Write-Host "Notifications: $($page.totalCount)"
    $page.items | Select-Object type, message, createdAtUtc | Format-Table -AutoSize | Out-String | Write-Host
}

# Used only by the MonthlySummaries step: returns the exact HTTP status plus the job name and created count.
# Normal TLS validation; errors report only the status code (no tokens, bodies or notification text).
function Invoke-OwliJobRun([string] $Job, [string] $Token) {
    $uri = $BaseUrl.TrimEnd('/') + '/api/dev/jobs/' + $Job + '/run'
    try {
        $response = Invoke-WebRequest -Method POST -Uri $uri -UseBasicParsing `
            -Headers @{ Accept = 'application/json'; Authorization = "Bearer $Token" }
    } catch {
        $status = $null
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        throw "Job run request failed (HTTP $status). No details are printed to avoid leaking data."
    }
    $body = $null
    try { $body = $response.Content | ConvertFrom-Json } catch { throw "Job run returned HTTP $([int]$response.StatusCode) with an unreadable body." }
    $created = $null
    if ($null -ne $body -and $body.PSObject.Properties.Name -contains 'created') { $created = [int]$body.created }
    $jobName = $null
    if ($null -ne $body -and $body.PSObject.Properties.Name -contains 'job') { $jobName = [string]$body.job }
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Job = $jobName; Created = $created }
}

if ($Step -eq 'Plan' -or -not $Execute) { Write-Plan; if ($Step -ne 'Plan') { Write-Host "Add -Execute to run step '$Step'." }; return }
if ($Month -notmatch '^\d{4}-\d{2}$') { throw 'Month must be YYYY-MM.' }
$go = Read-Host "Run step '$Step' against $BaseUrl ? (yes/no)"
if ($go -ne 'yes') { Write-Host 'Cancelled.'; return }

$member = $null; $admin = $null
try {
    switch ($Step) {
        'Auth' {
            $member = Connect-Owli 'Member'
            $me = Invoke-Owli 'GET' 'api/auth/me' -Token $member.accessToken
            Write-Host "api/auth/me OK. Base currency: $($me.baseCurrency)."
        }
        'Reports' {
            $member = Connect-Owli 'Member'
            $r = Invoke-Owli 'GET' "api/reports/monthly?month=$Month-01" -Token $member.accessToken
            Write-Host ("Report {0} ({1}): income {2}, expenses {3}, net {4}, {5} transactions, {6} budgets." -f `
                $r.month, $r.baseCurrency, $r.totalIncome, $r.totalExpenses, $r.netCashFlow, $r.transactionCount, @($r.budgets).Count)
        }
        'BudgetAlerts' {
            if ($CategoryId -le 0) { throw 'Pass -CategoryId (an active Expense category of the member).' }
            Confirm-Write 'CREATE DEMO BUDGET AND EXPENSE'
            $member = Connect-Owli 'Member'
            $b = Invoke-Owli 'POST' 'api/budgets' @{ categoryId = $CategoryId; periodMonth = "$Month-01"; limitAmount = $BudgetLimit; alertThresholdPct = 80 } $member.accessToken
            Write-Host "Budget created: limit $($b.limitAmount), status $($b.status)."
            $t = Invoke-Owli 'POST' 'api/transactions' @{ clientId = [guid]::NewGuid(); categoryId = $CategoryId; type = 'Expense'; amount = $ExpenseAmount;
                currencyCode = 'PHP'; transactionDate = (Get-Date -Format 'yyyy-MM-dd'); description = 'OWLI budget alert demo' } $member.accessToken
            Write-Host "Expense recorded: $($t.amount) $($t.currencyCode)."
            $admin = Connect-Owli 'Administrator'
            Confirm-Write 'RUN BUDGET ALERTS JOB'
            foreach ($i in 1, 2) { $run = Invoke-Owli 'POST' 'api/dev/jobs/budget-alerts/run' -Token $admin.accessToken; Write-Host "Run ${i}: created $($run.created)" }
            Get-Notifications $member
        }
        'MonthlySummaries' {
            $admin = Connect-Owli 'Administrator'
            Confirm-Write 'RUN MONTHLY SUMMARIES JOB'
            # Run 1 must be HTTP 200 with created = 1; Run 2 must be HTTP 200 with created = 0 (idempotent re-run).
            # Any other result throws before the next run and before member sign-in; the outer finally signs the Administrator out.
            $runNumber = 0
            foreach ($expected in 1, 0) {
                $runNumber++
                $run = Invoke-OwliJobRun 'monthly-summaries' $admin.accessToken
                Write-Host "Run ${runNumber}: HTTP $($run.Status), created $($run.Created) (expected HTTP 200, created $expected)"
                if ($run.Status -ne 200 -or $run.Job -ne 'monthly-summaries' -or $null -eq $run.Created -or $run.Created -ne $expected) {
                    throw "Run ${runNumber} returned an unexpected result. Stopping before any further step."
                }
            }
            $member = Connect-Owli 'Member'
            Get-Notifications $member
        }
        'Fx' {
            if ($CategoryId -le 0) { throw 'Pass -CategoryId (an active Expense category of the member).' }
            Confirm-Write 'CREATE USD TEST EXPENSE'
            $member = Connect-Owli 'Member'
            $t = Invoke-Owli 'POST' 'api/transactions' @{ clientId = [guid]::NewGuid(); categoryId = $CategoryId; type = 'Expense'; amount = 1.00;
                currencyCode = 'USD'; transactionDate = (Get-Date -Format 'yyyy-MM-dd'); description = 'OWLI FX demo' } $member.accessToken
            Write-Host "FX expense: $($t.amount) $($t.currencyCode) at rate $($t.fxRate) (rate date $($t.fxRateDate)) = $($t.amountBase) base."
        }
    }
} finally {
    Disconnect-Owli $member; Disconnect-Owli $admin
}
