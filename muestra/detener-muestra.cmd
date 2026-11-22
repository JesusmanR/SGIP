@echo off
rem Apaga la muestra local de SGIP. Solo llama a Git Bash; la logica esta en lib\muestra.sh
chcp 65001 >nul
set "BASH=%ProgramFiles%\Git\bin\bash.exe"
if not exist "%BASH%" set "BASH=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not exist "%BASH%" set "BASH=%LocalAppData%\Programs\Git\bin\bash.exe"
if not exist "%BASH%" (
  echo Falta Git para Windows ^(trae Git Bash^). Instalalo y vuelve a abrir este archivo.
  pause
  exit /b 1
)
set CHERE_INVOKING=1
"%BASH%" -l "%~dp0lib\muestra.sh" detener
set "CODIGO=%ERRORLEVEL%"
echo.
pause
exit /b %CODIGO%
