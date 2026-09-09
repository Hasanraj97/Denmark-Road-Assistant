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

function Say  { param($m) Write-Host $m -ForegroundColor White }
function Ok   { param($m) Write-Host "  OK  $m" -ForegroundColor Green }
function Bad  { param($m) Write-Host "  XX  $m" -ForegroundColor Red }

function Stop-Everything {
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
    [void]$Started.Add($p)
    return $p
}

function Wait-ForHttp {
    param([string]$Url, [string]$Name, [int]$Seconds = 90, [string]$LogName)

    Write-Host "  ..  waiting for $Name" -NoNewline
    $waited = 0
    while ($waited -lt $Seconds) {
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
    $log = Join-Path $LogDir "$LogName.log"
    if (Test-Path $log) {
        Write-Host "      Last 25 lines of logs\$LogName.log :"
        Get-Content $log -Tail 25 | ForEach-Object { Write-Host "      $_" }
    }
    exit 1
}

try {
    # --- locate the python virtual environment ----------------------------
    $VenvPy = Join-Path $Root 'ml-service\.venv\Scripts\python.exe'
    if (-not (Test-Path $VenvPy)) {
        Bad 'No Python virtual environment found at ml-service\.venv'
        Write-Host '      Create it first (see README section 2):'
        Write-Host '        cd ml-service'
        Write-Host '        py -3.12 -m venv .venv'
        Write-Host '        .\.venv\Scripts\python.exe -m pip install -r requirements.txt'
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
    Write-Host '     first start trains the model - this takes a few seconds' -ForegroundColor DarkGray
    Start-Service-Logged -WorkDir (Join-Path $Root 'ml-service') `
                         -FileName $VenvPy `
                         -ProcessArgs @('-m','uvicorn','app.main:app','--host','127.0.0.1','--port',"$MlPort") `
                         -LogName 'ml-service' | Out-Null
    Wait-ForHttp -Url "http://127.0.0.1:$MlPort/health" -Name 'ML service' -Seconds 120 -LogName 'ml-service'

    # --- 2. Backend -------------------------------------------------------
    Say "2/3  Backend (port $BackendPort)"
    Start-Service-Logged -WorkDir (Join-Path $Root 'backend') `
                         -FileName 'cmd.exe' `
                         -ProcessArgs @('/c','mvnw.cmd','-q','spring-boot:run') `
                         -LogName 'backend' | Out-Null
    Wait-ForHttp -Url "http://127.0.0.1:$BackendPort/api/health" -Name 'Backend' -Seconds 180 -LogName 'backend'

    # --- 3. Frontend ------------------------------------------------------
    Say "3/3  Frontend (port $FrontPort)"
    Start-Service-Logged -WorkDir (Join-Path $Root 'frontend') `
                         -FileName 'cmd.exe' `
                         -ProcessArgs @('/c','npm','start') `
                         -LogName 'frontend' | Out-Null
    # NOTE: localhost, not 127.0.0.1. The Angular dev server binds to IPv6 only
    # ([::1]:4200), so an IPv4 health check never succeeds even though the
    # server is up. The other two bind IPv4 and keep 127.0.0.1.
    Wait-ForHttp -Url "http://localhost:$FrontPort" -Name 'Frontend' -Seconds 180 -LogName 'frontend'

    Say ''
    Say 'All three services are running.'
    Write-Host ''
    Write-Host "    Open  http://localhost:$FrontPort" -ForegroundColor Cyan
    Write-Host ''
    Write-Host '    logs\ml-service.log   logs\backend.log   logs\frontend.log' -ForegroundColor DarkGray
    Write-Host ''
    Write-Host '    Press Ctrl+C to stop everything.'
    Write-Host ''

    # Wait until interrupted or a service dies.
    while ($true) {
        Start-Sleep -Seconds 2
        foreach ($p in $Started) {
            if ($p.HasExited) {
                Bad "A service exited unexpectedly (pid $($p.Id)). Check the logs\ folder."
                exit 1
            }
        }
    }
}
finally {
    Stop-Everything
}
