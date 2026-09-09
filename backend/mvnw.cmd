@REM ---------------------------------------------------------------------------
@REM Apache Maven Wrapper startup script for Windows, matching wrapper 3.3.4 in
@REM "only-script" mode.
@REM
@REM WHY THIS FILE WAS REWRITTEN
@REM The previous mvnw.cmd was a 288-byte hand-written stub that ran
@REM     java -classpath .mvn\wrapper\maven-wrapper.jar org.apache...MavenWrapperMain
@REM but .mvn\wrapper\maven-wrapper.jar does not exist and never did. The
@REM maven-wrapper.properties in this project sets distributionType=only-script,
@REM which means there IS no jar - the script downloads and unpacks Maven itself.
@REM So every Windows user following the README from cmd.exe or PowerShell hit
@REM     "Maven wrapper jar not found"
@REM while the same command worked fine from Git Bash, because mvnw (the shell
@REM script) is the real, complete 3.3.4 wrapper.
@REM
@REM This file now does what the shell script does:
@REM   1. read distributionUrl from .mvn\wrapper\maven-wrapper.properties
@REM   2. work out MAVEN_HOME as
@REM        %USERPROFILE%\.m2\wrapper\dists\<dist-name>\<hash-of-url>
@REM      where the hash is Java's String.hashCode in lowercase hex - the same
@REM      value the shell script computes, so both share one download
@REM   3. download and unpack Maven there if it is not already present
@REM   4. hand over to mvn.cmd
@REM ---------------------------------------------------------------------------
@ECHO OFF
SETLOCAL EnableDelayedExpansion

SET "WRAPPER_PROPERTIES=%~dp0.mvn\wrapper\maven-wrapper.properties"

IF NOT EXIST "%WRAPPER_PROPERTIES%" (
  ECHO Cannot find %WRAPPER_PROPERTIES% 1>&2
  EXIT /B 1
)

@REM --- read distributionUrl -------------------------------------------------
SET "DISTRIBUTION_URL="
FOR /F "usebackq tokens=1,* delims==" %%A IN ("%WRAPPER_PROPERTIES%") DO (
  IF /I "%%A"=="distributionUrl" SET "DISTRIBUTION_URL=%%B"
)

IF NOT DEFINED DISTRIBUTION_URL (
  ECHO Cannot read distributionUrl from %WRAPPER_PROPERTIES% 1>&2
  EXIT /B 1
)

@REM Strip stray whitespace and un-escape the ':' that .properties files escape.
SET "DISTRIBUTION_URL=%DISTRIBUTION_URL: =%"
SET "DISTRIBUTION_URL=%DISTRIBUTION_URL:\:=:%"

@REM --- where Maven lives ----------------------------------------------------
IF NOT DEFINED MAVEN_USER_HOME SET "MAVEN_USER_HOME=%USERPROFILE%\.m2"

FOR /F "usebackq delims=" %%H IN (`powershell -NoProfile -NonInteractive -Command ^
  "$u = $env:DISTRIBUTION_URL;" ^
  "$h = [uint32]0;" ^
  "foreach ($c in $u.ToCharArray()) { $h = ([uint64]$h * 31 + [uint32][char]$c) %% 4294967296 };" ^
  "'{0:x}' -f $h"`) DO SET "DIST_HASH=%%H"

FOR %%F IN ("%DISTRIBUTION_URL%") DO SET "DIST_FILE=%%~nxF"
SET "DIST_NAME=%DIST_FILE:.zip=%"
SET "DIST_NAME=%DIST_NAME:-bin=%"

SET "MAVEN_HOME=%MAVEN_USER_HOME%\wrapper\dists\%DIST_NAME%\%DIST_HASH%"

@REM --- download and unpack if needed ---------------------------------------
IF NOT EXIST "%MAVEN_HOME%\bin\mvn.cmd" (
  ECHO Downloading Apache Maven from %DISTRIBUTION_URL%
  ECHO This happens once. It may take a minute.

  powershell -NoProfile -NonInteractive -Command ^
    "$ErrorActionPreference = 'Stop';" ^
    "$url = $env:DISTRIBUTION_URL;" ^
    "$home2 = $env:MAVEN_HOME;" ^
    "$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('mvnw-' + [System.Guid]::NewGuid().ToString('N'));" ^
    "New-Item -ItemType Directory -Path $tmp -Force | Out-Null;" ^
    "$zip = Join-Path $tmp 'maven.zip';" ^
    "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12;" ^
    "Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing;" ^
    "Expand-Archive -Path $zip -DestinationPath $tmp -Force;" ^
    "$inner = Get-ChildItem -Path $tmp -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin\mvn.cmd') } | Select-Object -First 1;" ^
    "if ($null -eq $inner) { Write-Error 'Downloaded archive did not contain bin\mvn.cmd'; exit 1 };" ^
    "$parent = Split-Path -Parent $home2;" ^
    "New-Item -ItemType Directory -Path $parent -Force | Out-Null;" ^
    "if (Test-Path $home2) { Remove-Item -Recurse -Force $home2 };" ^
    "Move-Item -Path $inner.FullName -Destination $home2;" ^
    "Remove-Item -Recurse -Force $tmp"

  IF ERRORLEVEL 1 (
    ECHO. 1>&2
    ECHO Failed to download Maven. 1>&2
    ECHO Check your internet connection, or install Maven yourself and use 'mvn' instead. 1>&2
    EXIT /B 1
  )
)

IF NOT EXIST "%MAVEN_HOME%\bin\mvn.cmd" (
  ECHO Maven was not found at %MAVEN_HOME% 1>&2
  EXIT /B 1
)

@REM --- run it --------------------------------------------------------------
CALL "%MAVEN_HOME%\bin\mvn.cmd" %*
EXIT /B %ERRORLEVEL%
