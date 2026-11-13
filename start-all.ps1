<#
    Start all three Denmark Road Assistant services, in order, and stop them all
    cleanly when you press Ctrl+C.

        .\start-all.ps1

    Each service is started only once the one before it is answering, so you
    never get the confusing situation where the frontend loads but the backend
    is not ready yet.

    If PowerShell refuses to run this file, it is the execution policy, not the
    script. Either run it once as:
        powershell -ExecutionPolicy Bypass -File .\start-all.ps1
    or allow local scripts for your user:
        Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
#>

$ErrorActionPreference = 'Stop'

$Root        = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir      = Join-Path $Root 'logs'
$MlPort      = 8000
$BackendPort = 8080
$FrontPort   = 4200

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }

$Started = New-Object System.Collections.ArrayList
$LogNameOf = @{}   # process id -> its log name, so a crash can show the right log

function Say  { param($m) Write-Host $m -ForegroundColor White }
function Ok   { param($m) Write-Host "  OK  $m" -ForegroundColor Green }
function Bad  { param($m) Write-Host "  XX  $m" -ForegroundColor Red }

function Stop-Everything {
    # A check failed before anything was started: nothing to stop, and saying
    # "Shutting down..." would only be confusing.
    if ($Started.Count -eq 0) { return }
    Write-Host ''
    Write-Host 'Shutting down...' -ForegroundColor White
    foreach ($p in $Started) {
        if ($null -ne $p -and -not $p.HasExited) {
            # Kill the whole tree: Maven and ng both spawn children that would
            # otherwise survive and keep the port bound.
            & taskkill /F /T /PID $p.Id 2>$null | Out-Null
        }
    }
    Start-Sleep -Seconds 1
    Write-Host "Stopped. Logs are in logs\"
}

function Test-PortInUse {
    param([int]$Port)
    try {
        $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop
        return ($null -ne $c)
    } catch {
        return $false
    }
}

function Require-PortFree {
    param([int]$Port, [string]$Name)
    if (Test-PortInUse -Port $Port) {
        Bad "Port $Port is already in use, so $Name cannot start."
        Write-Host "      Something is already listening there - most likely a copy of this"
        Write-Host "      project you started earlier and did not stop."
        Write-Host ''
        Write-Host "      Find it with:   Get-NetTCPConnection -LocalPort $Port -State Listen"
        Write-Host "      Then stop it:   Stop-Process -Id <the OwningProcess it shows> -Force"
        exit 1
    }
}

function Start-Service-Logged {
    # NOTE: this parameter must NOT be called $Args. That is a reserved automatic
    # variable in PowerShell - it holds whatever was passed to the enclosing
    # function - so a parameter of that name never receives the caller's value and
    # Start-Process fails with "argument is null, empty, or contains a null value".
    param([string]$WorkDir, [string]$FileName, [string[]]$ProcessArgs, [string]$LogName)

    $out = Join-Path $LogDir "$LogName.log"
    $err = Join-Path $LogDir "$LogName.err.log"

    $p = Start-Process -FilePath $FileName -ArgumentList $ProcessArgs `
                       -WorkingDirectory $WorkDir -NoNewWindow -PassThru `
                       -RedirectStandardOutput $out -RedirectStandardError $err
    # Touching .Handle now keeps it open, so ExitCode can still be read after the
    # process has died. Without this, Windows PowerShell 5.1 reports a blank exit
    # code for a process started with -PassThru.
    $null = $p.Handle
    [void]$Started.Add($p)
    $LogNameOf[$p.Id] = $LogName
    return $p
}

function Show-ServiceLog {
    # Shows BOTH logs. Python and uvicorn write their messages - including every
    # crash - to standard ERROR, so the useful text lands in <name>.err.log. The
    # script used to print only <name>.log, which for the ML service is always
    # empty, so a crash showed up as a blank box with no explanation.
    param([string]$LogName)
    $shown = $false
    foreach ($file in @("$LogName.err.log", "$LogName.log")) {
        $path = Join-Path $LogDir $file
        if ((Test-Path $path) -and ((Get-Item $path).Length -gt 0)) {
            Write-Host "      Last 25 lines of logs\$file :" -ForegroundColor DarkGray
            Get-Content $path -Tail 25 | ForEach-Object { Write-Host "      $_" }
            $shown = $true
        }
    }
    if (-not $shown) {
        Write-Host "      (both logs\$LogName.log and logs\$LogName.err.log are empty)"
    }
}

function Wait-ForHttp {
    param([string]$Url, [string]$Name, [int]$Seconds = 90, [string]$LogName, $Process)

    Write-Host "  ..  waiting for $Name" -NoNewline
    $waited = 0
    while ($waited -lt $Seconds) {
        # If the program has already died there is nothing to wait for. Before
        # this check, a service that crashed in under a second still made you
        # sit through the full timeout before seeing anything.
        if ($null -ne $Process -and $Process.HasExited) {
            Write-Host ''
            Bad "$Name stopped before it was ready (exit code $($Process.ExitCode))."
            Show-ServiceLog -LogName $LogName
            exit 1
        }
        try {
            $r = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400) {
                Write-Host ''
                Ok "$Name is up  ($Url)"
                return
            }
        } catch { }
        Start-Sleep -Seconds 2
        $waited += 2
        Write-Host '.' -NoNewline
    }
    Write-Host ''
    Bad "$Name did not become healthy within $Seconds seconds."
    Show-ServiceLog -LogName $LogName
    exit 1
}

function Test-PythonEnvironment {
    # Actually RUNS the project's Python and imports what the ML service needs.
    #
    # A virtual environment is not self-contained: it is a thin layer over the
    # Python it was created with. If that Python is uninstalled or upgraded, the
    # .venv folder is still there - so a "does the file exist" check passes - but
    # it can no longer start, and fails with "No Python at '...python.exe'".
    # That is exactly what happened when Python 3.12 was removed from this machine.
    param([string]$Python)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # native stderr must not become a terminating error
    try {
        # Windows PowerShell wraps every line a program writes to stderr in an
        # error record. Printing the record shows PowerShell's own noise (and a
        # blank line comes out as "System.Management.Automation.RemoteException"),
        # so take the original line back out of it and drop the empty ones.
        $lines = & $Python -c "import fastapi, uvicorn, sklearn, pandas, numpy, joblib" 2>&1 |
                 ForEach-Object {
                     if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" }
                 } |
                 Where-Object { "$_".Trim() -ne '' }
        $ok = ($LASTEXITCODE -eq 0)
        $output = $lines -join "`n"
    } catch {
        $output = $_.Exception.Message
        $ok = $false
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ Ok = $ok; Output = "$output".Trim() }
}

try {
    # --- locate the python virtual environment ----------------------------
    $VenvPy = Join-Path $Root 'ml-service\.venv\Scripts\python.exe'
    # -3.14 rather than a bare "py": the bare form picks the NEWEST Python
    # installed, and a release newer than 3.14 has no ready-made packages for
    # the pinned numpy, so pip would try to compile it and fail.
    $rebuildSteps = @(
        '        cd ml-service',
        '        Remove-Item -Recurse -Force .venv      (only if a .venv folder exists)',
        '        py -3.14 -m venv .venv                 (or -3.13 / -3.12 / -3.11 - "py -0p" lists yours)',
        '        .\.venv\Scripts\python.exe -m pip install -r requirements.txt',
        '        cd ..'
    )

    if (-not (Test-Path $VenvPy)) {
        Bad 'No Python environment found at ml-service\.venv'
        Write-Host '      Create it once (any Python 3.11 to 3.14 works):'
        $rebuildSteps | ForEach-Object { Write-Host $_ }
        exit 1
    }

    $pyCheck = Test-PythonEnvironment -Python $VenvPy
    if (-not $pyCheck.Ok) {
        Bad 'The Python environment in ml-service\.venv cannot run.'
        Write-Host ''
        # Two wordings of the same problem: an environment made by Python 3.12
        # or older says "No Python at", one made by 3.13 or newer says "did not
        # find executable at".
        if ($pyCheck.Output -match 'No Python at|did not find executable at') {
            Write-Host '      It was built with a version of Python that is no longer installed'
            Write-Host '      on this computer (for example it was uninstalled or upgraded).'
            Write-Host '      An environment cannot be repaired after that - rebuild it:'
        } elseif ($pyCheck.Output -match 'Application Control policy') {
            Write-Host '      Windows Smart App Control blocked one of its library files. This'
            Write-Host '      usually happens only the first time a new file is seen, while'
            Write-Host '      Windows checks it. Wait a minute and run .\start-all.ps1 again.'
            Write-Host '      If it keeps happening, rebuild the environment:'
        } else {
            Write-Host '      Its packages may be missing or damaged. Rebuild it:'
        }
        $rebuildSteps | ForEach-Object { Write-Host $_ }
        Write-Host ''
        Write-Host '      Python said:' -ForegroundColor DarkGray
        ($pyCheck.Output -split "`r?`n" | Select-Object -Last 4) | ForEach-Object { Write-Host "        $_" -ForegroundColor DarkGray }
        exit 1
    }

    if (-not (Test-Path (Join-Path $Root 'frontend\node_modules'))) {
        Bad 'frontend\node_modules is missing.'
        Write-Host '      Run:  cd frontend; npm ci'
        exit 1
    }

    Say ''
    Say 'Denmark Road Assistant'
    Say '======================'
    Say ''

    Require-PortFree -Port $MlPort      -Name 'the ML service'
    Require-PortFree -Port $BackendPort -Name 'the backend'
    Require-PortFree -Port $FrontPort   -Name 'the frontend'

    # --- 1. ML service ----------------------------------------------------
    Say "1/3  ML service (port $MlPort)"
    # Training is a deliberate step. The service only trains a model on start-up
    # when ML_BOOTSTRAP_TRAINING=1, and this script sets that only when no model
    # exists yet (a fresh clone). An existing model is loaded and checksum-checked.
    if (Test-Path (Join-Path $Root 'ml-service\models\road_condition_model.joblib')) {
        $env:ML_BOOTSTRAP_TRAINING = '0'
    } else {
        $env:ML_BOOTSTRAP_TRAINING = '1'
        Write-Host '     no model yet: training the first one (one-time setup, a few seconds)' -ForegroundColor DarkGray
    }
    $mlProc = Start-Service-Logged -WorkDir (Join-Path $Root 'ml-service') `
                         -FileName $VenvPy `
                         -ProcessArgs @('-m','uvicorn','app.main:app','--host','127.0.0.1','--port',"$MlPort") `
                         -LogName 'ml-service'
    Wait-ForHttp -Url "http://127.0.0.1:$MlPort/health" -Name 'ML service' -Seconds 120 -LogName 'ml-service' -Process $mlProc

    # --- 2. Backend -------------------------------------------------------
    # '.\mvnw.cmd', not 'mvnw.cmd'. cmd.exe resolves a bare name against PATH,
    # and the wrapper lives in the project folder rather than on PATH, so the
    # bare form fails with "'mvnw.cmd' is not recognized" even though
    # -WorkingDirectory points at backend\.
    Say "2/3  Backend (port $BackendPort)"
    $beProc = Start-Service-Logged -WorkDir (Join-Path $Root 'backend') `
                         -FileName 'cmd.exe' `
                         -ProcessArgs @('/c','.\mvnw.cmd','-q','spring-boot:run') `
                         -LogName 'backend'
    Wait-ForHttp -Url "http://127.0.0.1:$BackendPort/api/health" -Name 'Backend' -Seconds 180 -LogName 'backend' -Process $beProc

    # --- 3. Frontend ------------------------------------------------------
    Say "3/3  Frontend (port $FrontPort)"
    $feProc = Start-Service-Logged -WorkDir (Join-Path $Root 'frontend') `
                         -FileName 'cmd.exe' `
                         -ProcessArgs @('/c','npm','start') `
                         -LogName 'frontend'
    # NOTE: localhost, not 127.0.0.1. The Angular dev server binds to IPv6 only
    # ([::1]:4200), so an IPv4 health check never succeeds even though the
    # server is up. The other two bind IPv4 and keep 127.0.0.1.
    Wait-ForHttp -Url "http://localhost:$FrontPort" -Name 'Frontend' -Seconds 180 -LogName 'frontend' -Process $feProc

    Say ''
    Say 'All three services are running.'
    Write-Host ''
    Write-Host "    Open  http://localhost:$FrontPort" -ForegroundColor Cyan
    Write-Host ''
    Write-Host '    Logs: logs\<service>.log and logs\<service>.err.log  (ml-service, backend, frontend)' -ForegroundColor DarkGray
    Write-Host ''
    Write-Host '    Press Ctrl+C to stop everything.'
    Write-Host ''

    # Wait until interrupted or a service dies.
    while ($true) {
        Start-Sleep -Seconds 2
        foreach ($p in $Started) {
            if ($p.HasExited) {
                $name = $LogNameOf[$p.Id]
                Bad "$name exited unexpectedly (exit code $($p.ExitCode))."
                Show-ServiceLog -LogName $name
                exit 1
            }
        }
    }
}
finally {
    Stop-Everything
}
