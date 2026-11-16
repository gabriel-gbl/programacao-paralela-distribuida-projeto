@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ===== VERIFICACAO DA AFINIDADE =====
call mvn.cmd -q compile exec:java -Dexec.args="--verificar-afinidade"
if errorlevel 1 (
  echo ERRO: leia a mensagem acima.
  pause
  exit /b 1
)
echo.
echo ===== DEMONSTRACAO: 20 s SEM afinidade e 20 s COM afinidade =====
echo Abra o Gerenciador de Tarefas ANTES de continuar:
echo Desempenho, CPU, botao direito no grafico, Alterar grafico para, Processadores logicos.
pause
call mvn.cmd -q exec:java -Dexec.args="--demonstrar-afinidade=40 --threads=2"
echo.
echo Saidas guardadas em resultados\verificacao_afinidade.txt e resultados\demonstracao_afinidade.txt
pause
