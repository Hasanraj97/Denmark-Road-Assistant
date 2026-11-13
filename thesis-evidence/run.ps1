<#
    Denmark Road Assistant: collects the evidence for the thesis in one go.

    It runs the three automated test suites, builds the frontend, starts the
    three services, and then drives the app in a hidden Microsoft Edge window to
    take the screenshots, run the system test cases, check accessibility and
    time the ML service. Everything is written to thesis-evidence\output.

    Your own data is not touched: the backend uses a separate test database
    (backend\data\thesis-evidence.*) that is deleted again at the end.

    Start it by double-clicking RUN_ME.bat.
#>

$ErrorActionPreference = 'Continue'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $Here
$Out  = Join-Path $Here 'output'
$Logs = Join-Path $Out 'logs'
$VenvPy = Join-Path $Root 'ml-service\.venv\Scripts\python.exe'

function Step { param($m) Write-Host ''; Write-Host "== $m" -ForegroundColor Cyan }
function Ok   { param($m) Write-Host "   OK  $m" -ForegroundColor Green }
function Warn { param($m) Write-Host "   !!  $m" -ForegroundColor Yellow }

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
Write-Host 'Denmark Road Assistant - thesis evidence' -ForegroundColor White
Write-Host 'This takes about 10 to 15 minutes. Please leave this window open.' -ForegroundColor White

foreach ($port in 8000, 8080, 4200) {
    if (Test-PortInUse -Port $port) {
        Warn "Port $port is already in use. Please stop the running app first (Ctrl+C in the start-all window), then run this again."
        Stop-Transcript | Out-Null
        exit 1
    }
}

$env:NG_CLI_ANALYTICS = 'false'
$env:CI = 'true'

# --- 0. environment ---------------------------------------------------------
Step 'Recording the test machine'
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
cmd /c "echo npm: >> $envFile & npm -v >> $envFile 2>&1"
cmd /c "echo Python: >> $envFile & $VenvPy --version >> $envFile 2>&1"
Ok 'environment.txt written'

# --- 1. backend tests -------------------------------------------------------
Step '1/7  Backend tests (Maven; the first run can take a few minutes)'
Push-Location (Join-Path $Root 'backend')
cmd /c ".\mvnw.cmd -B test > $Out\backend-tests.log 2>&1"
$beCode = $LASTEXITCODE
Pop-Location
$sf = Join-Path $Out 'surefire'
New-Item -ItemType Directory -Path $sf | Out-Null
Copy-Item (Join-Path $Root 'backend\target\surefire-reports\*.txt') $sf -ErrorAction SilentlyContinue
if ($beCode -eq 0) { Ok 'backend tests passed' } else { Warn "backend tests exit code $beCode (see backend-tests.log)" }

# --- 2. ML service tests ----------------------------------------------------
Step '2/7  ML service tests (pytest)'
Push-Location (Join-Path $Root 'ml-service')
cmd /c "$VenvPy -m pytest --version > nul 2>&1"
if ($LASTEXITCODE -ne 0) {
    Warn 'pytest is not installed in the ML environment; installing requirements-dev.txt'
    cmd /c "$VenvPy -m pip install -r requirements-dev.txt > $Logs\pip-dev.log 2>&1"
}
cmd /c "$VenvPy -m pytest -q > $Out\ml-tests.log 2>&1"
$mlCode = $LASTEXITCODE
Pop-Location
if ($mlCode -eq 0) { Ok 'ML tests passed' } else { Warn "ML tests exit code $mlCode (see ml-tests.log)" }

# --- 3. frontend tests and production build --------------------------------
Step '3/7  Frontend tests and production build'
Push-Location (Join-Path $Root 'frontend')
cmd /c "npx ng test --watch=false > $Out\frontend-tests.log 2>&1"
$feCode = $LASTEXITCODE
cmd /c "npx ng build > $Out\frontend-build.log 2>&1"
$fbCode = $LASTEXITCODE
Pop-Location
if ($feCode -eq 0) { Ok 'frontend tests passed' } else { Warn "frontend tests exit code $feCode" }
if ($fbCode -eq 0) { Ok 'production build succeeded' } else { Warn "production build exit code $fbCode" }

# --- 4. browser tooling -----------------------------------------------------
Step '4/7  Installing the screenshot tool (playwright-core and axe-core, small)'
Push-Location $Here
cmd /c "npm install --no-audit --no-fund > $Logs\npm-install.log 2>&1"
Pop-Location
Ok 'done'

# --- 5. start the three services --------------------------------------------
Step '5/7  Starting the ML service, backend and frontend'
$env:SPRING_DATASOURCE_URL = 'jdbc:h2:file:./data/thesis-evidence;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1'

$ml = Start-Process -FilePath $VenvPy -ArgumentList @('-m','uvicorn','app.main:app','--host','127.0.0.1','--port','8000') `
        -WorkingDirectory (Join-Path $Root 'ml-service') -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $Logs 'ml.out.log') -RedirectStandardError (Join-Path $Logs 'ml.err.log')
$null = $ml.Handle
if (Wait-Http -Url 'http://127.0.0.1:8000/health' -Seconds 120 -Process $ml) { Ok 'ML service is up' } else { Warn 'ML service did not start (see logs\ml.err.log)' }

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

# --- 6. screenshots and system tests ----------------------------------------
Step '6/7  Taking screenshots and running the system test cases (a hidden Edge window does the clicking)'
Push-Location $Here
$env:EVIDENCE_OUT = $Out
cmd /c "node evidence.cjs main > $Logs\evidence-main.log 2>&1"
if (Select-String -Path (Join-Path $Logs 'evidence-main.log') -Pattern 'FATAL' -Quiet) {
    Warn 'Edge could not be started, downloading a private copy of Chromium and trying again'
    cmd /c "npx playwright-core install chromium > $Logs\chromium-install.log 2>&1"
    $env:PW_CHANNEL = 'none'
    cmd /c "node evidence.cjs main > $Logs\evidence-main.log 2>&1"
}
Get-Content (Join-Path $Logs 'evidence-main.log') | Select-String -Pattern 'pass|FAIL|DONE' | ForEach-Object { Write-Host "   $($_.Line)" }

# --- 7. failure test: ML service stopped -----------------------------------
Step '7/7  Stopping the ML service on purpose to test the error message'
Stop-Tree $ml
Start-Sleep -Seconds 3
cmd /c "node evidence.cjs mldown > $Logs\evidence-mldown.log 2>&1"
Get-Content (Join-Path $Logs 'evidence-mldown.log') | Select-String -Pattern 'pass|FAIL|DONE' | ForEach-Object { Write-Host "   $($_.Line)" }
Pop-Location

# --- tidy up ----------------------------------------------------------------
Step 'Stopping everything and removing the test database'
Stop-Tree $fe
Stop-Tree $be
Stop-Tree $ml
Start-Sleep -Seconds 3
Get-ChildItem (Join-Path $Root 'backend\data') -Filter 'thesis-evidence*' -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

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
