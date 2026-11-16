#!/usr/bin/env bash
# Benchmark completo e gráficos. Pode levar de 20 a 40 minutos.
set -e
cd "$(dirname "$0")"

# Memória máxima do Java: 500 milhões de transações ocupam cerca de 2,3 GB.
# Com 8 GB de RAM use 4g; com 16 GB ou mais pode usar 8g.
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx4g}"

mvn -q compile exec:java -Dexec.args="--volumes=100000,1000000,10000000,50000000,100000000,250000000,500000000 --threads=1,2,4,8 --tarefas-por-thread=4 --repeticoes=10"

echo
python3 gerar_graficos.py || echo "Não foi possível gerar os gráficos. Instale: python3 -m pip install matplotlib"
