#!/usr/bin/env bash
# Experimentos extras: frequência do processador (demonstrações com 1 e 8 threads) e
# quantidade de tarefas por thread (granularidade). Equivalente ao extras_windows.bat.
set -e
cd "$(dirname "$0")"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx4g}"

echo "===== PARTE 1: FREQUÊNCIA DO PROCESSADOR ====="
echo "Em outro terminal, acompanhe a frequência com: watch -n1 \"grep MHz /proc/cpuinfo\""
read -r -p "Pressione Enter para começar a demonstração com 1 thread..."
mvn -q compile exec:java -Dexec.args="--demonstrar-afinidade=30 --threads=1 --saida=resultados/frequencia_1_thread/demo.csv"
read -r -p "Pressione Enter para a demonstração com 8 threads..."
mvn -q exec:java -Dexec.args="--demonstrar-afinidade=30 --threads=8 --saida=resultados/frequencia_8_threads/demo.csv"

echo
echo "===== PARTE 2: QUANTAS TAREFAS POR THREAD? (uns 5 minutos) ====="
read -r -p "Feche os outros programas e pressione Enter. Não mexa no computador até o fim..."
mvn -q compile exec:java -Dexec.args="--volumes=100000000 --tarefas-por-thread=1,4,64,4096 --modos=executor --saida=resultados/tarefas/tarefas.csv"
python3 gerar_graficos.py resultados/tarefas/tarefas.csv graficos_tarefas || echo "Não foi possível gerar os gráficos. Instale: python3 -m pip install matplotlib"
echo "Pronto."
