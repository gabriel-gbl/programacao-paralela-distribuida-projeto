#!/usr/bin/env bash
# Testes de corretude (não medem tempo).
set -e
cd "$(dirname "$0")"
mvn -q compile exec:java -Dexec.args="--teste"
echo
echo "Saída guardada em resultados/testes.txt"
