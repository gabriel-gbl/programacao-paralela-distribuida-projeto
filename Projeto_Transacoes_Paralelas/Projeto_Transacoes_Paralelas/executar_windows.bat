@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem Memoria maxima do Java. 500 milhoes de transacoes ocupam cerca de 2,3 GB.
rem Computador com 8 GB de RAM: use 4g. Com 16 GB ou mais: pode usar 8g.
rem Se nao couber, o programa pula os volumes grandes e registra o motivo no CSV.
set MAVEN_OPTS=-Xmx4g

echo ===== BENCHMARK COMPLETO =====
echo Pode levar de 20 a 40 minutos. Feche outros programas, deixe o notebook
echo na tomada e use o plano de energia de alto desempenho.
echo.
call mvn.cmd -q compile exec:java -Dexec.args="--volumes=100000,1000000,10000000,50000000,100000000,250000000,500000000 --threads=1,2,4,8 --tarefas-por-thread=4 --repeticoes=10"
if errorlevel 1 (
  echo.
  echo ERRO: leia a mensagem acima.
  pause
  exit /b 1
)

echo.
echo ===== GRAFICOS =====
where py >nul 2>nul
if %errorlevel%==0 (
  py gerar_graficos.py
) else (
  python gerar_graficos.py
)
if errorlevel 1 (
  echo Nao foi possivel gerar os graficos. Instale o Python 3 e rode: py -m pip install matplotlib
)
echo.
pause
