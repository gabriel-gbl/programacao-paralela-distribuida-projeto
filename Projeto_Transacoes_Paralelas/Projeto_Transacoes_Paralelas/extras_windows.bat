@echo off
chcp 65001 >nul
cd /d "%~dp0"
set MAVEN_OPTS=-Xmx4g

echo ===== EXTRAS - PARTE 1: FREQUENCIA DO PROCESSADOR (uns 2 minutos, com voce olhando) =====
echo 1. Abra o Gerenciador de Tarefas, aba Desempenho, e clique em Memoria.
echo    Tire um print dessa tela (mostra a velocidade e os slots da memoria RAM).
echo 2. Clique em CPU e deixe o Gerenciador visivel ao lado deste terminal.
echo 3. Em cada demonstracao, espere uns 10 segundos e tire um print com o campo
echo    "Velocidade" aparecendo (Win+Shift+S). O programa fala em print da fase 2:
echo    aqui o que interessa e a Velocidade, em qualquer fase.
echo.
pause
echo.
echo ----- Demonstracao A: 1 thread ocupada (15 s sem afinidade + 15 s na CPU 0) -----
call mvn.cmd -q compile exec:java -Dexec.args="--demonstrar-afinidade=30 --threads=1 --saida=resultados/frequencia_1_thread/demo.csv"
if errorlevel 1 goto erro
echo.
echo ----- Demonstracao B: 8 threads ocupadas (15 s sem afinidade + 15 s nas CPUs 0 a 7) -----
pause
call mvn.cmd -q exec:java -Dexec.args="--demonstrar-afinidade=30 --threads=8 --saida=resultados/frequencia_8_threads/demo.csv"
if errorlevel 1 goto erro
echo.
echo ===== EXTRAS - PARTE 2: QUANTAS TAREFAS POR THREAD? (uns 5 minutos) =====
echo FECHE o Gerenciador de Tarefas e nao mexa no computador ate aparecer "Pronto".
pause
call mvn.cmd -q compile exec:java -Dexec.args="--volumes=100000000 --tarefas-por-thread=1,4,64,4096 --modos=executor --saida=resultados/tarefas/tarefas.csv"
if errorlevel 1 goto erro
echo.
echo ===== GRAFICOS DAS TAREFAS =====
where py >nul 2>nul
if %errorlevel%==0 (
  py gerar_graficos.py resultados/tarefas/tarefas.csv graficos_tarefas
) else (
  python gerar_graficos.py resultados/tarefas/tarefas.csv graficos_tarefas
)
echo.
echo Pronto. Compacte num .zip a pasta resultados e a pasta graficos_tarefas
echo e mande junto com os prints.
pause
exit /b 0

:erro
echo.
echo ERRO: leia a mensagem acima.
pause
exit /b 1
