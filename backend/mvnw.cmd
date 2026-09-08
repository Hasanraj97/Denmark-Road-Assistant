@ECHO OFF
SETLOCAL

SET WRAPPER_JAR=%~dp0.mvn\wrapper\maven-wrapper.jar
SET WRAPPER_MAIN=org.apache.maven.wrapper.MavenWrapperMain

if not exist "%WRAPPER_JAR%" (
  echo Maven wrapper jar not found: %WRAPPER_JAR%
  exit /b 1
)

java -classpath "%WRAPPER_JAR%" %WRAPPER_MAIN% %*