#!/usr/bin/env bash
# Verifica a afinidade e faz a demonstração (20 s sem e 20 s com afinidade).
set -e
cd "$(dirname "$0")"
mvn -q compile exec:java -Dexec.args="--verificar-afinidade"
echo
echo "Abra o htop (ou top e aperte 1) em outro terminal e tecle ENTER para a demonstração."
read -r
mvn -q exec:java -Dexec.args="--demonstrar-afinidade=40 --threads=2"
echo
echo "Saídas guardadas em resultados/verificacao_afinidade.txt e resultados/demonstracao_afinidade.txt"
