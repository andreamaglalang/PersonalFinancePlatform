<#
.SYNOPSIS
    One-time OWLI presentation data for finance.test1@example.com through the existing authenticated API.

.DESCRIPTION
    Approved dataset: 7 categories, 42 transactions (27 in November 2026, 15 in September 2026), 5 November budgets.
    All amounts PHP. Uses only existing endpoints; no SQL, no schema or source changes, nothing deleted or updated.

    Modes:
      (default)  Plan only: validates the dataset offline and prints it. Sends nothing.
      -Preview   Signs in and shows, read-only, what already exists and what would be created. Writes nothing.
      -Execute   Signs in, refuses any account other than finance.test1@example.com (base PHP), checks database
                 responsiveness, asks for a typed confirmation, then creates only what is missing and verifies the
                 November and September report totals.

    Safety: deterministic ClientIds (re-running never duplicates a transaction); categories and budgets are created only
    when missing; retries a timeout/5xx at most twice with the same request; stops on any other failure; never prints
    passwords or tokens; always signs out (revokes only this session's refresh-token family).
    Windows PowerShell 5.1 compatible. Run interactively in your own terminal.

.EXAMPLE
    .\Seed-OwliPresentationData.ps1             # plan only
    .\Seed-OwliPresentationData.ps1 -Preview    # sign in, read-only preview
    .\Seed-OwliPresentationData.ps1 -Execute    # sign in, confirm, write missing records, verify
#>
[CmdletBinding()]
param(
    [switch] $Preview,
    [switch] $Execute,
    [string] $BaseUrl = 'https://localhost:7027/'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$TargetEmail = 'finance.test1@example.com'
$Confirmation = 'SEED OWLI PRESENTATION DATA'
$Today = [datetime]::new(2026, 11, 20)

# ---------------------------------------------------------------- Approved dataset
$Categories = @(
    @{ Name = 'Allowance';      Type = 'Income';  Color = '#2E7D6B' },
    @{ Name = 'Groceries';      Type = 'Expense'; Color = '#4CAF50' },
    @{ Name = 'Transportation'; Type = 'Expense'; Color = '#1E88E5' },
    @{ Name = 'Utilities';      Type = 'Expense'; Color = '#F4A100' },
    @{ Name = 'Subscriptions';  Type = 'Expense'; Color = '#8E5CC9' },
    @{ Name = 'Shopping';       Type = 'Expense'; Color = '#E5677D' },
    @{ Name = 'Entertainment';  Type = 'Expense'; Color = '#E0559A' }
)
$Reused = @{ 'Salary' = 'Income'; 'Food & Dining' = 'Expense' }

function Tx($key, $date, $type, $category, $amount, $description) {
    [pscustomobject]@{ Key = $key; Date = $date; Type = $type; Category = $category; Amount = [decimal]$amount; Description = $description }
}
$Transactions = @(
    # November 2026 (27)
    (Tx 'nov-01-allowance'    '2026-11-01' 'Income'  'Allowance'      '5000.00'  'Monthly allowance'),
    (Tx 'nov-02-groceries'    '2026-11-02' 'Expense' 'Groceries'      '1450.50'  'Weekly groceries'),
    (Tx 'nov-03-beep'         '2026-11-03' 'Expense' 'Transportation' '800.00'   'Beep card reload'),
    (Tx 'nov-04-lunch'        '2026-11-04' 'Expense' 'Food & Dining'  '285.00'   'Lunch with classmates'),
    (Tx 'nov-05-salary'       '2026-11-05' 'Income'  'Salary'         '15000.00' 'Salary (1st half of November)'),
    (Tx 'nov-05-netflix'      '2026-11-05' 'Expense' 'Subscriptions'  '549.00'   'Netflix subscription'),
    (Tx 'nov-05-spotify'      '2026-11-05' 'Expense' 'Subscriptions'  '194.00'   'Spotify Premium'),
    (Tx 'nov-06-electricity'  '2026-11-06' 'Expense' 'Utilities'      '3150.00'  'Electricity bill'),
    (Tx 'nov-06-water'        '2026-11-06' 'Expense' 'Utilities'      '650.00'   'Water bill'),
    (Tx 'nov-07-dinner'       '2026-11-07' 'Expense' 'Food & Dining'  '450.00'   'Dinner with friends'),
    (Tx 'nov-08-grab'         '2026-11-08' 'Expense' 'Transportation' '420.00'   'Grab rides'),
    (Tx 'nov-08-icloud'       '2026-11-08' 'Expense' 'Subscriptions'  '149.00'   'iCloud storage'),
    (Tx 'nov-09-groceries'    '2026-11-09' 'Expense' 'Groceries'      '1325.75'  'Weekly groceries'),
    (Tx 'nov-09-shoes'        '2026-11-09' 'Expense' 'Shopping'       '1299.00'  'Running shoes'),
    (Tx 'nov-11-coffee'       '2026-11-11' 'Expense' 'Food & Dining'  '180.00'   'Coffee and snacks'),
    (Tx 'nov-12-internet'     '2026-11-12' 'Expense' 'Utilities'      '1699.00'  'Home internet'),
    (Tx 'nov-13-supplies'     '2026-11-13' 'Expense' 'Shopping'       '850.00'   'School supplies'),
    (Tx 'nov-14-family'       '2026-11-14' 'Expense' 'Food & Dining'  '520.00'   'Family dinner'),
    (Tx 'nov-15-gasoline'     '2026-11-15' 'Expense' 'Transportation' '1500.00'  'Gasoline'),
    (Tx 'nov-15-movie'        '2026-11-15' 'Expense' 'Entertainment'  '760.00'   'Movie night'),
    (Tx 'nov-16-groceries'    '2026-11-16' 'Expense' 'Groceries'      '1180.25'  'Weekly groceries'),
    (Tx 'nov-18-meeting'      '2026-11-18' 'Expense' 'Food & Dining'  '365.00'   'Lunch meeting'),
    (Tx 'nov-18-fares'        '2026-11-18' 'Expense' 'Transportation' '380.00'   'Jeepney and bus fares'),
    (Tx 'nov-19-topup'        '2026-11-19' 'Expense' 'Groceries'      '581.50'   'Grocery top-up'),
    (Tx 'nov-19-karaoke'      '2026-11-19' 'Expense' 'Entertainment'  '490.00'   'Karaoke with friends'),
    (Tx 'nov-20-salary'       '2026-11-20' 'Income'  'Salary'         '15000.00' 'Salary (2nd half of November)'),
    (Tx 'nov-20-family-lunch' '2026-11-20' 'Expense' 'Food & Dining'  '620.00'   'Weekend family lunch'),
    # September 2026 (15)
    (Tx 'sep-01-allowance'    '2026-09-01' 'Income'  'Allowance'      '5000.00'  'Monthly allowance'),
    (Tx 'sep-04-transport'    '2026-09-04' 'Expense' 'Transportation' '2880.00'  'Fuel and fares'),
    (Tx 'sep-05-salary'       '2026-09-05' 'Income'  'Salary'         '15000.00' 'Salary (1st half of September)'),
    (Tx 'sep-05-netflix'      '2026-09-05' 'Expense' 'Subscriptions'  '549.00'   'Netflix subscription'),
    (Tx 'sep-05-spotify'      '2026-09-05' 'Expense' 'Subscriptions'  '194.00'   'Spotify Premium'),
    (Tx 'sep-06-groceries'    '2026-09-06' 'Expense' 'Groceries'      '2250.00'  'Monthly grocery run'),
    (Tx 'sep-07-electricity'  '2026-09-07' 'Expense' 'Utilities'      '2980.00'  'Electricity bill'),
    (Tx 'sep-07-water'        '2026-09-07' 'Expense' 'Utilities'      '641.00'   'Water bill'),
    (Tx 'sep-08-icloud'       '2026-09-08' 'Expense' 'Subscriptions'  '149.00'   'iCloud storage'),
    (Tx 'sep-12-internet'     '2026-09-12' 'Expense' 'Utilities'      '1699.00'  'Home internet'),
    (Tx 'sep-13-birthday'     '2026-09-13' 'Expense' 'Food & Dining'  '2050.00'  'Birthday dinner'),
    (Tx 'sep-20-salary'       '2026-09-20' 'Income'  'Salary'         '15000.00' 'Salary (2nd half of September)'),
    (Tx 'sep-20-groceries'    '2026-09-20' 'Expense' 'Groceries'      '1870.00'  'Grocery restock'),
    (Tx 'sep-21-shopping'     '2026-09-21' 'Expense' 'Shopping'       '1450.00'  'New backpack'),
    (Tx 'sep-27-entertainment' '2026-09-27' 'Expense' 'Entertainment' '1288.00'  'Weekend outing')
)
$Budgets = @(
    @{ Category = 'Groceries';      Limit = [decimal]'6000.00' },
    @{ Category = 'Food & Dining';  Limit = [decimal]'2700.00' },
    @{ Category = 'Transportation'; Limit = [decimal]'4000.00' },
    @{ Category = 'Shopping';       Limit = [decimal]'2000.00' },
    @{ Category = 'Entertainment';  Limit = [decimal]'2000.00' }
)
$BudgetMonth = '2026-11-01'
$Expected = @{
    '2026-11' = @{ Income = [decimal]'35000.00'; Expense = [decimal]'19848.00'; Count = 27 }
    '2026-09' = @{ Income = [decimal]'35000.00'; Expense = [decimal]'18000.00'; Count = 15 }
}

# Deterministic RFC 4122 v5-style GUID from the record key: the same key always gives the same ClientId.
function ClientIdFor([string] $key) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { [byte[]] $h = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes("owli-presentation-2026|$key")) } finally { $sha.Dispose() }
    [byte[]] $b = $h[0..15]
    $b[7] = ($b[7] -band 0x0F) -bor 0x50
    $b[8] = ($b[8] -band 0x3F) -bor 0x80
    return New-Object Guid (, $b)
}

# ---------------------------------------------------------------- Offline validation (every mode)
function Assert-Dataset {
    $names = @($Categories | ForEach-Object { $_.Name }) + @($Reused.Keys)
    if (@($names | Sort-Object -Unique).Count -ne $names.Count) { throw 'Dataset: duplicate category name.' }
    $keys = @($Transactions | ForEach-Object { $_.Key })
    if (@($keys | Sort-Object -Unique).Count -ne $keys.Count) { throw 'Dataset: duplicate record key.' }
    $ids = @($Transactions | ForEach-Object { (ClientIdFor $_.Key).ToString() })
    if (@($ids | Sort-Object -Unique).Count -ne $ids.Count) { throw 'Dataset: ClientId collision.' }
    foreach ($t in $Transactions) {
        $catType = $null
        foreach ($c in $Categories) { if ($c.Name -eq $t.Category) { $catType = $c.Type } }
        if (-not $catType -and $Reused.ContainsKey($t.Category)) { $catType = $Reused[$t.Category] }
        if ($catType -ne $t.Type) { throw "Dataset: $($t.Key) category/type mismatch." }
        if ($t.Amount -le 0 -or [decimal]::Round($t.Amount, 2) -ne $t.Amount) { throw "Dataset: $($t.Key) amount invalid." }
        if ([datetime]::ParseExact($t.Date, 'yyyy-MM-dd', $null) -gt $Today) { throw "Dataset: $($t.Key) is in the future." }
        if ($t.Description.Length -gt 200) { throw "Dataset: $($t.Key) description too long." }
    }
    foreach ($m in $Expected.Keys) {
        $rows = @($Transactions | Where-Object { $_.Date.StartsWith($m) })
        $inc = [decimal]0; $exp = [decimal]0
        foreach ($r in $rows) { if ($r.Type -eq 'Income') { $inc += $r.Amount } else { $exp += $r.Amount } }
        if ($rows.Count -ne $Expected[$m].Count -or $inc -ne $Expected[$m].Income -or $exp -ne $Expected[$m].Expense) {
            throw "Dataset: $m totals differ from the approved plan ($($rows.Count) rows, income $inc, expenses $exp)."
        }
    }
    if ($Transactions.Count -ne 42 -or $Categories.Count -ne 7 -or $Budgets.Count -ne 5) { throw 'Dataset: record counts differ from the approved plan.' }
}

function Write-Plan {
    'OWLI presentation dataset for {0} (all PHP)' -f $TargetEmail
    '  Categories to create if missing: {0}' -f (($Categories | ForEach-Object { "$($_.Name) ($($_.Type))" }) -join ', ')
    '  Reused existing categories: {0}' -f ($Reused.Keys -join ', ')
    foreach ($m in @('2026-11', '2026-09')) {
        '  {0}: {1} transactions, income {2:N2}, expenses {3:N2}' -f $m, $Expected[$m].Count, $Expected[$m].Income, $Expected[$m].Expense
    }
    '  November budgets (80%): {0}' -f (($Budgets | ForEach-Object { '{0} {1:N2}' -f $_.Category, $_.Limit }) -join '; ')
    '  Writes if nothing exists yet: {0} categories + {1} transactions + {2} budgets = {3}' -f $Categories.Count, $Transactions.Count, $Budgets.Count, ($Categories.Count + $Transactions.Count + $Budgets.Count)
}

# ---------------------------------------------------------------- HTTP (no bodies, tokens or passwords are printed)
$script:Token = $null
$script:Refresh = $null
function Invoke-Api([string] $Method, [string] $Path, $Body = $null, [switch] $Retry) {
    $uri = $BaseUrl.TrimEnd('/') + '/' + $Path.TrimStart('/')
    $headers = @{ Accept = 'application/json' }
    if ($script:Token) { $headers['Authorization'] = 'Bearer ' + $script:Token }
    $attempts = if ($Retry) { 3 } else { 1 }
    for ($i = 1; $i -le $attempts; $i++) {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        try {
            $req = @{ Method = $Method; Uri = $uri; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 60 }
            if ($null -ne $Body) { $req['Body'] = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 5 -Compress)); $req['ContentType'] = 'application/json; charset=utf-8' }
            $r = Invoke-WebRequest @req
            $json = $null; if ($r.Content) { $json = $r.Content | ConvertFrom-Json }
            return [pscustomobject]@{ Status = [int]$r.StatusCode; Json = $json; Ms = $sw.ElapsedMilliseconds }
        } catch {
            $status = 0
            if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
            $transient = ($status -eq 0 -or $status -ge 500)
            if ($transient -and $i -lt $attempts) { Write-Host ("  {0} {1}: transient failure (HTTP {2}); retrying the same request" -f $Method, $Path.Split('?')[0], $status); Start-Sleep -Seconds (3 * $i); continue }
            return [pscustomobject]@{ Status = $status; Json = $null; Ms = $sw.ElapsedMilliseconds }
        }
    }
}
function Require($r, [int[]] $ok, [string] $what) {
    if ($ok -notcontains $r.Status) { throw "STOP: $what failed (HTTP $($r.Status)). Nothing further was sent." }
}
function Get-All([string] $path, [int] $pageSize) {
    $all = New-Object System.Collections.ArrayList; $page = 1
    while ($true) {
        $sep = if ($path.Contains('?')) { '&' } else { '?' }
        $r = Invoke-Api 'GET' ("{0}{1}page={2}&pageSize={3}" -f $path, $sep, $page, $pageSize) -Retry
        Require $r @(200) "GET $($path.Split('?')[0])"
        foreach ($it in @($r.Json.items)) { [void]$all.Add($it) }
        if (@($r.Json.items).Count -eq 0 -or $all.Count -ge [long]$r.Json.totalCount) { break }
        $page++; if ($page -gt 50) { throw 'STOP: unexpected paging.' }
    }
    return ,$all
}
function Get-Report([string] $month) {
    $r = Invoke-Api 'GET' "api/reports/monthly?month=$month" -Retry
    Require $r @(200) "GET report $month"
    return $r.Json
}

function Connect-Member {
    $email = Read-Host 'Member email (finance.test1@example.com)'
    if ($email.Trim() -ine $TargetEmail) { throw "STOP: this script only runs for $TargetEmail." }
    $secure = Read-Host 'Password' -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
        $r = Invoke-Api 'POST' 'api/auth/login' @{ email = $email.Trim(); password = $plain }
    } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr); $plain = $null }
    Require $r @(200) 'Sign-in'
    $script:Token = $r.Json.accessToken
    $script:Refresh = $r.Json.refreshToken
    $me = Invoke-Api 'GET' 'api/auth/me' -Retry
    Require $me @(200) 'GET api/auth/me'
    if ([string]$me.Json.email -ine $TargetEmail) { throw 'STOP: signed-in account is not the designated test account.' }
    if ([string]$me.Json.baseCurrency -ne 'PHP') { throw 'STOP: account base currency is not PHP.' }
    Write-Host ("Signed in as the designated test account (base {0}, roles: {1})." -f $me.Json.baseCurrency, (@($me.Json.roles) -join ', '))
}
function Disconnect-Member {
    if ($script:Refresh) {
        $r = Invoke-Api 'POST' 'api/auth/logout' @{ refreshToken = $script:Refresh }
        if ($r.Status -in 200, 204) { Write-Host 'Signed out.' } else { Write-Warning "Sign-out not confirmed (HTTP $($r.Status))." }
    }
    $script:Token = $null; $script:Refresh = $null
}

# ---------------------------------------------------------------- Main
Assert-Dataset
Write-Plan
"  ClientIds are deterministic, e.g. nov-05-salary -> $(ClientIdFor 'nov-05-salary')"
if (-not $Preview -and -not $Execute) { 'Plan only: nothing was sent. Use -Preview (read-only) or -Execute.'; return }

try {
    Connect-Member

    # Database stability: three timed read-only requests must all succeed promptly.
    foreach ($i in 1..3) {
        $r = Invoke-Api 'GET' 'api/categories?page=1&pageSize=1'
        Require $r @(200) 'Database stability check'
        if ($r.Ms -gt 10000) { throw "STOP: database response too slow ($($r.Ms) ms)." }
        Write-Host ("  stability check {0}: OK in {1} ms" -f $i, $r.Ms)
    }

    # Current state (read-only).
    $cats = Get-All 'api/categories?includeInactive=true' 100
    $existing = @{}; foreach ($c in $cats) { $existing[[string]$c.name] = $c }
    foreach ($n in $Reused.Keys) { if (-not $existing.ContainsKey($n) -or -not $existing[$n].isActive -or $existing[$n].type -ne $Reused[$n]) { throw "STOP: required existing category '$n' missing, inactive or of the wrong type." } }
    $toCreate = @($Categories | Where-Object { -not $existing.ContainsKey($_.Name) })
    foreach ($c in $Categories) { if ($existing.ContainsKey($c.Name) -and ((-not $existing[$c.Name].isActive) -or $existing[$c.Name].type -ne $c.Type)) { throw "STOP: category '$($c.Name)' exists but is inactive or of the wrong type." } }
    $txNow = Get-All 'api/transactions?fromDate=2026-09-01&toDate=2026-11-30' 100
    $present = @{}; foreach ($t in $txNow) { $present[[string]$t.clientId] = $t }
    $txToCreate = @($Transactions | Where-Object { -not $present.ContainsKey((ClientIdFor $_.Key).ToString()) })
    $bNow = Get-All "api/budgets?periodMonth=$BudgetMonth" 100
    $bByCat = @{}; foreach ($b in $bNow) { $bByCat[[string]$b.categoryName] = $b }
    $bToCreate = @($Budgets | Where-Object { -not $bByCat.ContainsKey($_.Category) })
    $before = @{ '2026-11' = Get-Report '2026-11-01'; '2026-09' = Get-Report '2026-09-01' }

    ''
    'Preview against the server (read-only):'
    '  categories to create: {0} ({1})' -f $toCreate.Count, (($toCreate | ForEach-Object { $_.Name }) -join ', ')
    '  transactions to create: {0} of {1} (already present: {2})' -f $txToCreate.Count, $Transactions.Count, ($Transactions.Count - $txToCreate.Count)
    '  budgets to create: {0} of {1}' -f $bToCreate.Count, $Budgets.Count
    foreach ($m in @('2026-11', '2026-09')) {
        $b4 = $before[$m]
        '  {0} now: income {1:N2}, expenses {2:N2}, {3} transactions' -f $m, [decimal]$b4.totalIncome, [decimal]$b4.totalExpenses, $b4.transactionCount
    }
    if ($Preview -and -not $Execute) { 'Preview only: nothing was written.'; return }

    $typed = Read-Host "This WRITES $($toCreate.Count + $txToCreate.Count + $bToCreate.Count) records to $TargetEmail. Type exactly: $Confirmation"
    if ($typed -cne $Confirmation) { 'Not confirmed. Nothing was written.'; return }

    # 1. Categories.
    foreach ($c in $toCreate) {
        $r = Invoke-Api 'POST' 'api/categories' @{ name = $c.Name; type = $c.Type; colorHEX = $c.Color; isActive = $true }
        Require $r @(200, 201) "Create category $($c.Name)"
        $existing[$c.Name] = $r.Json
        Write-Host "  category created: $($c.Name)"
    }
    # 2. Transactions (deterministic ClientId; a replay of an already-recorded ClientId returns the same record).
    $created = 0; $replayed = 0
    foreach ($t in $txToCreate) {
        $body = [ordered]@{ clientId = (ClientIdFor $t.Key).ToString(); type = $t.Type; categoryId = [int]$existing[$t.Category].categoryId
            amount = $t.Amount; currencyCode = 'PHP'; transactionDate = $t.Date; description = $t.Description }
        $r = Invoke-Api 'POST' 'api/transactions' $body -Retry
        Require $r @(200, 201) "Create transaction $($t.Key)"
        if ($r.Status -eq 201) { $created++ } else { $replayed++ }
    }
    Write-Host "  transactions: $created created, $replayed already recorded (replayed)"
    # 3. Budgets.
    foreach ($b in $bToCreate) {
        $r = Invoke-Api 'POST' 'api/budgets' @{ categoryId = [int]$existing[$b.Category].categoryId; periodMonth = $BudgetMonth; limitAmount = $b.Limit; alertThresholdPct = 80 }
        Require $r @(200, 201) "Create budget $($b.Category)"
        Write-Host ("  budget created: {0} {1:N2} -> status {2}" -f $b.Category, $b.Limit, $r.Json.status)
    }

    # 4. Verification: after = before + whatever this run actually created for that month.
    ''
    'Verification (server reports):'
    $ok = $true
    foreach ($m in @('2026-11', '2026-09')) {
        $add = @($txToCreate | Where-Object { $_.Date.StartsWith($m) })
        $inc = [decimal]0; $exp = [decimal]0; foreach ($a in $add) { if ($a.Type -eq 'Income') { $inc += $a.Amount } else { $exp += $a.Amount } }
        $after = Get-Report "$m-01"
        $wantInc = [decimal]$before[$m].totalIncome + $inc; $wantExp = [decimal]$before[$m].totalExpenses + $exp
        $wantCount = [int]$before[$m].transactionCount + $add.Count
        $match = ([decimal]$after.totalIncome -eq $wantInc -and [decimal]$after.totalExpenses -eq $wantExp -and [int]$after.transactionCount -eq $wantCount -and [decimal]$after.netCashFlow -eq ($wantInc - $wantExp))
        if (-not $match) { $ok = $false }
        '  {0}: income {1:N2}, expenses {2:N2}, net {3:N2}, {4} transactions, {5} budgets -> {6}' -f $m, [decimal]$after.totalIncome, [decimal]$after.totalExpenses, [decimal]$after.netCashFlow, $after.transactionCount, @($after.budgets).Count, $(if ($match) { 'MATCHES' } else { 'MISMATCH' })
    }
    if ($ok) { 'SEED COMPLETE: server totals match the plan.' } else { throw 'STOP: server totals do not match the expected values. Do not re-run; report this.' }
}
finally { Disconnect-Member }
