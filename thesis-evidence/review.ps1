<#
    Denmark Road Assistant: applies the review fixes and collects the new evidence.

    Part A puts the changed source files in place. Before any file is replaced,
    a copy is kept next to it as <name>.before-review, and a file is only
    replaced if it is exactly the version the fixes were written against. If
    you changed a file yourself since, it is left alone and listed.
    UNDO_REVIEW_UPDATE.bat puts every original back.

    Part B runs all the tests and the browser checks. Results go into
    thesis-evidence\output-review. Your own accounts and saved analyses are not
    touched: the backend uses a separate test database that is deleted at the end.
#>

$ErrorActionPreference = 'Continue'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $Here
$Pkg  = Join-Path $Here 'review-update'
$Out  = Join-Path $Here 'output-review'
$Logs = Join-Path $Out 'logs'
$VenvPy = Join-Path $Root 'ml-service\.venv\Scripts\python.exe'

function Step { param($m) Write-Host ''; Write-Host "== $m" -ForegroundColor Cyan }
function Ok   { param($m) Write-Host "   OK  $m" -ForegroundColor Green }
function Warn { param($m) Write-Host "   !!  $m" -ForegroundColor Yellow }

function Get-NormalHash {
    param([string]$Path)
    $text = [IO.File]::ReadAllText($Path).Replace("`r", '')
    $bytes = [Text.Encoding]::UTF8.GetBytes($text)
    $sha = [Security.Cryptography.SHA256]::Create()
    return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLower()
}

function Test-PortInUse {
    param([int]$Port)
    try { return ($null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop)) } catch { return $false }
}

function Wait-Http {
    param([string]$Url, [int]$Seconds, $Process)
    $waited = 0
    while ($waited -lt $Seconds) {
        if ($null -ne $Process -and $Process.HasExited) { return $false }
        try {
            $r = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400) { return $true }
        } catch { }
        Start-Sleep -Seconds 2
        $waited += 2
    }
    return $false
}

function Stop-Tree { param($p) if ($null -ne $p -and -not $p.HasExited) { & taskkill /F /T /PID $p.Id 2>$null | Out-Null } }

# ---------------------------------------------------------------------------
if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Path $Out  | Out-Null
New-Item -ItemType Directory -Path $Logs | Out-Null
Start-Transcript -Path (Join-Path $Out 'run-log.txt') | Out-Null

Write-Host ''
Write-Host 'Denmark Road Assistant - review fixes and evidence' -ForegroundColor White
Write-Host 'This takes about 25 to 30 minutes. Please leave this window open.' -ForegroundColor White

foreach ($port in 8000, 8080, 4200, 8099) {
    if (Test-PortInUse -Port $port) {
        Warn "Port $port is already in use. Please stop the running app first (Ctrl+C in the start-all window), then run this again."
        Stop-Transcript | Out-Null
        exit 1
    }
}

# --- A. put the fixed files in place ---------------------------------------
Step 'A  Putting the fixed source files in place (originals kept as *.before-review)'
$manifestPath = Join-Path $Pkg 'manifest.json'
if (Test-Path $manifestPath) { $manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json } else { $manifest = @(); Ok 'No update package here, so the code is tested as it is' }
$applied = 0; $already = 0; $skipped = @()
foreach ($f in $manifest) {
    $dest = Join-Path $Root ($f.path -replace '/', '\')
    $src  = Join-Path $Pkg ('files\' + ($f.path -replace '/', '\'))
    $newHash = $f.newSha
    if (Test-Path $dest) {
        $cur = Get-NormalHash $dest
        if ($cur -eq $newHash) { $already++; continue }
        if (@($f.baseShas) -notcontains $cur) { $skipped += $f.path; continue }
        $backup = "$dest.before-review"
        if (-not (Test-Path $backup)) { Copy-Item $dest $backup }
    } else {
        $dir = Split-Path -Parent $dest
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    }
    Copy-Item $src $dest -Force
    $applied++
}
"applied=$applied already=$already skipped=$($skipped.Count)" | Out-File (Join-Path $Out 'update.txt') -Encoding utf8
$skipped | Out-File (Join-Path $Out 'update.txt') -Append -Encoding utf8
Ok "$applied files updated, $already already up to date"
if ($skipped.Count -gt 0) {
    Warn "These files were changed on this laptop since the fixes were written, so they were NOT replaced:"
    $skipped | ForEach-Object { Warn "   $_" }
    Warn 'Stopping here so nothing is mixed. Tell Claude, and it will merge them.'
    Stop-Transcript | Out-Null
    exit 1
}

$env:NG_CLI_ANALYTICS = 'false'
$env:CI = 'true'

# --- 0. environment and code version ----------------------------------------
Step 'Recording the test machine and the code version'
$envFile = Join-Path $Out 'environment.txt'
"Run started: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')" | Out-File $envFile -Encoding utf8
try {
    $os  = Get-CimInstance Win32_OperatingSystem
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $cs  = Get-CimInstance Win32_ComputerSystem
    "OS: $($os.Caption) $($os.Version)" | Out-File $envFile -Append -Encoding utf8
    "CPU: $($cpu.Name)" | Out-File $envFile -Append -Encoding utf8
    "RAM: $([math]::Round($cs.TotalPhysicalMemory / 1GB, 1)) GB" | Out-File $envFile -Append -Encoding utf8
} catch { }
cmd /c "java -version >> $envFile 2>&1"
cmd /c "echo Node: >> $envFile & node -v >> $envFile 2>&1"
cmd /c "echo Python: >> $envFile & $VenvPy --version >> $envFile 2>&1"
Push-Location $Root
cmd /c "git rev-parse HEAD > $Out\git-commit.txt 2>&1"
cmd /c "git status --short > $Out\git-status.txt 2>&1"
cmd /c "git diff --stat > $Out\git-diffstat.txt 2>&1"
Pop-Location
Ok 'environment.txt and git-*.txt written'

# --- 1-3. automated tests ---------------------------------------------------
Step '1/8  Backend tests (Maven)'
Push-Location (Join-Path $Root 'backend')
cmd /c ".\mvnw.cmd -B test > $Out\backend-tests.log 2>&1"
$beCode = $LASTEXITCODE
Pop-Location
$sf = Join-Path $Out 'surefire'
New-Item -ItemType Directory -Path $sf | Out-Null
Copy-Item (Join-Path $Root 'backend\target\surefire-reports\*.txt') $sf -ErrorAction SilentlyContinue
Copy-Item (Join-Path $Root 'backend\target\surefire-reports\*.xml') $sf -ErrorAction SilentlyContinue
if ($beCode -eq 0) { Ok 'backend tests passed' } else { Warn "backend tests exit code $beCode (see backend-tests.log)" }

Step '2/8  ML service tests (pytest)'
Push-Location (Join-Path $Root 'ml-service')
cmd /c "$VenvPy -m pytest -q --junitxml=$Out\ml-tests.xml > $Out\ml-tests.log 2>&1"
$mlCode = $LASTEXITCODE
Pop-Location
if ($mlCode -eq 0) { Ok 'ML tests passed' } else { Warn "ML tests exit code $mlCode (see ml-tests.log)" }

Step '3/8  Frontend tests and production build'
Push-Location (Join-Path $Root 'frontend')
cmd /c "npx ng test --watch=false > $Out\frontend-tests.log 2>&1"
$feCode = $LASTEXITCODE
cmd /c "npx ng build > $Out\frontend-build.log 2>&1"
$fbCode = $LASTEXITCODE
Pop-Location
if ($feCode -eq 0) { Ok 'frontend tests passed' } else { Warn "frontend tests exit code $feCode" }
if ($fbCode -eq 0) { Ok 'production build succeeded' } else { Warn "production build exit code $fbCode" }

if ($beCode -ne 0) {
    Warn 'The backend tests failed, so the app is not started. Tell Claude; the log is in output-review.'
    "backend tests exit code: $beCode" | Out-File (Join-Path $Out 'SUMMARY.txt') -Encoding utf8
    Stop-Transcript | Out-Null
    exit 1
}

# --- 4. start everything ----------------------------------------------------
Step '4/8  Starting the recording proxy, ML service, backend and frontend'
Push-Location $Here
cmd /c "npm install --no-audit --no-fund > $Logs\npm-install.log 2>&1"
Pop-Location
$env:EVIDENCE_OUT = $Out
$px = Start-Process -FilePath 'node' -ArgumentList @('proxy.cjs') -WorkingDirectory $Here -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $Logs 'proxy.out.log') -RedirectStandardError (Join-Path $Logs 'proxy.err.log')
$null = $px.Handle
if (Wait-Http -Url 'http://127.0.0.1:8099/__stats' -Seconds 30 -Process $px) { Ok 'proxy is up' } else { Warn 'proxy did not start' }

$env:ML_BOOTSTRAP_TRAINING = '0'
$ml = Start-Process -FilePath $VenvPy -ArgumentList @('-m','uvicorn','app.main:app','--host','127.0.0.1','--port','8000') `
        -WorkingDirectory (Join-Path $Root 'ml-service') -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $Logs 'ml.out.log') -RedirectStandardError (Join-Path $Logs 'ml.err.log')
$null = $ml.Handle
if (Wait-Http -Url 'http://127.0.0.1:8000/health' -Seconds 120 -Process $ml) { Ok 'ML service is up' } else { Warn 'ML service did not start (see logs\ml.err.log)' }

$env:SPRING_DATASOURCE_URL = 'jdbc:h2:file:./data/thesis-review;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1'
$env:NOMINATIM_SEARCH_URL = 'http://127.0.0.1:8099/nominatim/search'
$env:OSRM_BASE_URL = 'http://127.0.0.1:8099/osrm/route/v1/driving/'
$env:WEATHER_BASE_URL = 'http://127.0.0.1:8099/openmeteo/v1/forecast'
$env:TRAFFIC_EVENTS_URL = 'http://127.0.0.1:8099/events'
$env:ADMIN_EMAILS = ''
$be = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c','.\mvnw.cmd','-q','spring-boot:run') `
        -WorkingDirectory (Join-Path $Root 'backend') -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $Logs 'backend.out.log') -RedirectStandardError (Join-Path $Logs 'backend.err.log')
$null = $be.Handle
if (Wait-Http -Url 'http://127.0.0.1:8080/api/health' -Seconds 240 -Process $be) { Ok 'backend is up' } else { Warn 'backend did not start (see logs\backend.out.log)' }

$fe = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c','npm','start') `
        -WorkingDirectory (Join-Path $Root 'frontend') -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $Logs 'frontend.out.log') -RedirectStandardError (Join-Path $Logs 'frontend.err.log')
$null = $fe.Handle
if (Wait-Http -Url 'http://localhost:4200' -Seconds 240 -Process $fe) { Ok 'frontend is up' } else { Warn 'frontend did not start (see logs\frontend.out.log)' }

# --- 5-8. browser checks ----------------------------------------------------
Push-Location $Here
Step '5/8  The 24 system test cases again, with the real outside services'
cmd /c "node evidence.cjs main > $Logs\evidence-main.log 2>&1"
if (Select-String -Path (Join-Path $Logs 'evidence-main.log') -Pattern 'FATAL' -Quiet) {
    Warn 'Edge could not be started, downloading a private copy of Chromium and trying again'
    cmd /c "npx playwright-core install chromium > $Logs\chromium-install.log 2>&1"
    $env:PW_CHANNEL = 'none'
    cmd /c "node evidence.cjs main > $Logs\evidence-main.log 2>&1"
}
Get-Content (Join-Path $Logs 'evidence-main.log') | Select-String -Pattern 'pass|FAIL|DONE' | ForEach-Object { Write-Host "   $($_.Line)" }

Step '6/8  New checks: optional details, retraining blocked, delete and export, keyboard, touch, weather sampling, timings'
cmd /c "node evidence2.cjs review > $Logs\evidence-review.log 2>&1"
Get-Content (Join-Path $Logs 'evidence-review.log') | Select-String -Pattern 'pass|FAIL|DONE' | ForEach-Object { Write-Host "   $($_.Line)" }

Step '7/8  Repeat and concurrency timings with the outside services replayed (no load on public servers)'
cmd /c "node evidence2.cjs replay > $Logs\evidence-replay.log 2>&1"
Get-Content (Join-Path $Logs 'evidence-replay.log') | Select-String -Pattern 'pass|FAIL|DONE' | ForEach-Object { Write-Host "   $($_.Line)" }

Step '8/8  Weather service switched off: the seasonal fallback must be labelled as such; then the ML service is stopped'
cmd /c "node evidence2.cjs fallback > $Logs\evidence-fallback.log 2>&1"
Get-Content (Join-Path $Logs 'evidence-fallback.log') | Select-String -Pattern 'pass|FAIL|DONE' | ForEach-Object { Write-Host "   $($_.Line)" }
Stop-Tree $ml
Start-Sleep -Seconds 3
cmd /c "node evidence.cjs mldown > $Logs\evidence-mldown.log 2>&1"
Get-Content (Join-Path $Logs 'evidence-mldown.log') | Select-String -Pattern 'pass|FAIL|DONE' | ForEach-Object { Write-Host "   $($_.Line)" }
try { Invoke-WebRequest -Uri 'http://127.0.0.1:8099/__stats' -UseBasicParsing -TimeoutSec 5 | Select-Object -ExpandProperty Content | Out-File (Join-Path $Out 'proxy-stats.json') -Encoding utf8 } catch { }
Pop-Location

# --- tidy up ----------------------------------------------------------------
Step 'Stopping everything and removing the test database'
Stop-Tree $fe
Stop-Tree $be
Stop-Tree $ml
Stop-Tree $px
Start-Sleep -Seconds 3
Get-ChildItem (Join-Path $Root 'backend\data') -Filter 'thesis-review*' -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

$summary = Join-Path $Out 'SUMMARY.txt'
"Finished: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')" | Out-File $summary -Encoding utf8
"backend tests exit code: $beCode"   | Out-File $summary -Append -Encoding utf8
"ML tests exit code: $mlCode"        | Out-File $summary -Append -Encoding utf8
"frontend tests exit code: $feCode"  | Out-File $summary -Append -Encoding utf8
"frontend build exit code: $fbCode"  | Out-File $summary -Append -Encoding utf8
Ok "All done. Results are in $Out"
Write-Host ''
Write-Host 'You can close this window now and tell Claude it has finished.' -ForegroundColor White
Stop-Transcript | Out-Null
