@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script (minimal)
@REM ----------------------------------------------------------------------------

@echo off
setlocal

set DIR=%~dp0
set WRAPPER_PROPS=%DIR%.mvn\wrapper\maven-wrapper.properties
set WRAPPER_JAR=%DIR%.mvn\wrapper\maven-wrapper.jar

if not exist "%WRAPPER_PROPS%" (
    echo ERROR: %WRAPPER_PROPS% not found. 1>&2
    exit /b 1
)

if not exist "%WRAPPER_JAR%" (
    for /f "tokens=2 delims==" %%U in ('findstr /b "wrapperUrl=" "%WRAPPER_PROPS%"') do set WRAPPER_URL=%%U
    echo Downloading maven-wrapper.jar from %WRAPPER_URL% ...
    powershell -Command "Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%'"
)

set JAVA_EXE=java
if defined JAVA_HOME set JAVA_EXE=%JAVA_HOME%\bin\java

"%JAVA_EXE%" -classpath "%WRAPPER_JAR%" "-Dmaven.multiModuleProjectDirectory=%DIR%" org.apache.maven.wrapper.MavenWrapperMain %*
