@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ===== TESTES DE CORRETUDE =====
call mvn.cmd -q compile exec:java -Dexec.args="--teste"
if errorlevel 1 (
  echo.
  echo ERRO: leia a mensagem acima. Confira se o JDK 17+ e o Maven estao instalados.
  pause
  exit /b 1
)
echo.
echo Saida guardada em resultados\testes.txt
pause
