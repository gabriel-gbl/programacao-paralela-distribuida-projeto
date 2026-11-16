# Análise paralela de transações financeiras (JCRPGPD)

Projeto da 1ª avaliação de Programação Paralela e Distribuída: análise de grandes volumes de
transações financeiras, comparando a versão **sequencial**, a versão com **ExecutorService** e a
versão com **ExecutorService + afinidade de processador** (biblioteca OpenHFT Affinity).

## O que o programa faz

1. **Gera os dados uma vez**, em memória, antes de qualquer medição. São vetores primitivos
   (`int[]` de valores em centavos e `byte[]` de categorias, 5 bytes por transação). Cada categoria
   segue uma distribuição estatística: Normal, Exponencial, Log-normal, Bimodal e Uniforme. Além
   disso, 0,02% das transações recebem valores anômalos (fraudes simuladas).
2. **Roda as análises** nas três versões:
   - agregação financeira: soma, média, maior e menor;
   - detecção de transações suspeitas: regras + as 10 maiores suspeitas;
   - estatísticas por categoria: quantidade, total, média, maior e menor;
   - análise 5 (do grupo): distribuição por faixa de valor e desvio padrão;
   - experimento extra: as quatro análises numa única passada pelos dados.
3. **Mede** cada configuração várias vezes, confere que o resultado paralelo é igual ao sequencial,
   calcula speedup e eficiência e salva tudo em CSV. O script Python gera os gráficos e uma tabela.

## Requisitos

- **JDK 17 ou mais novo** e **Apache Maven**. Na primeira compilação, o Maven baixa a OpenHFT da
  internet.
- **Python 3 + matplotlib**, só para os gráficos: `py -m pip install matplotlib` (Windows) ou
  `python3 -m pip install matplotlib` (Linux).
- **Windows ou Linux instalado direto no computador.** No macOS o programa roda, mas a OpenHFT não
  aplica afinidade. Em máquina virtual ou WSL, as "CPUs" são virtuais e a afinidade perde o sentido.

## Passo a passo no Windows

Abra a pasta que contém o `pom.xml` e execute, nesta ordem (duplo clique ou pelo terminal):

| Script | O que faz | Quanto demora |
|---|---|---|
| `testar_windows.bat` | Confere todos os cálculos e se a afinidade funciona neste computador | menos de 1 min |
| `afinidade_windows.bat` | Mostra CPUs e núcleos, confere a fixação e faz a demonstração para o print | ~1 min |
| `executar_windows.bat` | Benchmark completo (100 mil a 500 milhões) e gráficos | 20 a 40 min ou mais |
| `extras_windows.bat` | Frequência do processador com 1 e 8 threads (prints) e experimento de tarefas por thread | ~7 min |

O teste deve terminar com `TODOS OS TESTES DE CÁLCULO PASSARAM` e `AFINIDADE: OK`.

Antes do benchmark completo, vale um teste rápido para ver se está tudo certo. No PowerShell:

```powershell
mvn.cmd -q compile exec:java '-Dexec.args=--volumes=1000000,10000000 --repeticoes=3'
```

Para volumes grandes, aumente a memória do Java antes, no mesmo terminal:
`$env:MAVEN_OPTS="-Xmx4g"` (PowerShell) ou `set MAVEN_OPTS=-Xmx4g` (cmd). O `executar_windows.bat`
já faz isso.

Se os acentos saírem errados no terminal, rode `chcp 65001` antes. Os arquivos em `resultados/`
são gravados em UTF-8 e ficam certos de qualquer jeito.

## Passo a passo no Linux

```bash
chmod +x *.sh
./testar_linux.sh
./afinidade_linux.sh
./executar_linux.sh
./extras_linux.sh
```

## Parâmetros

Use-os dentro de `-Dexec.args="..."`:

| Parâmetro | Padrão | Para quê |
|---|---|---|
| `--volumes=1000000,10000000` | 1 mi e 10 mi | Quantidades de transações. Aceita `10.000.000` ou `10_000_000` |
| `--threads=1,2,4,8` | 1,2,4,8 | Quantidades de threads |
| `--tarefas-por-thread=4` | 4 | Tarefas por thread (1 a 10.000). Use `1,4,64,4096` para comparar |
| `--repeticoes=10` | 10 | Repetições de cada configuração (o resultado é a mediana) |
| `--analises=todas` | todas | Ou: `agregacao,suspeitas,categorias,faixas,completa` |
| `--modos=executor,afinidade,nucleo` | os três | Modos paralelos. O sequencial sempre roda, porque é a referência |
| `--sem-afinidade` | | Só sequencial e Executor |
| `--nucleos=0,2,4,6` | detecta | CPUs do modo "1 thread por núcleo físico", se a detecção falhar |
| `--semente=2026` | 2026 | Semente dos dados sintéticos (mesma semente = mesmos dados) |
| `--saida=resultados/benchmark.csv` | | Onde salvar o resumo |
| `--teste` | | Só os testes de corretude |
| `--verificar-afinidade` | | Só a topologia e a conferência da afinidade |
| `--demonstrar-afinidade=40` | | 20 s sem e 20 s com afinidade, para o print do Gerenciador de Tarefas. Usa a 1ª quantidade de `--threads` |
| `--distribuicoes=10000000` | | Histograma dos valores de cada categoria (`resultados/distribuicoes.csv`), usado no gráfico 00 |

**Experimento das tarefas por thread** (justifica a escolha de 4 tarefas por thread). O
`extras_windows.bat` já roda este comando e gera os gráficos em `graficos_tarefas/`:

```powershell
mvn.cmd -q compile exec:java '-Dexec.args=--volumes=100000000 --tarefas-por-thread=1,4,64,4096 --modos=executor --saida=resultados/tarefas/tarefas.csv'
py gerar_graficos.py resultados/tarefas/tarefas.csv graficos_tarefas
```

**Gráficos para o relatório:** `py gerar_graficos.py --relatorio` gera em `graficos_relatorio/` a
versão com letras proporcionais a uma página A4 e sem título na imagem (o título vai na legenda da
figura, como pede a ABNT).

## O que sai

| Arquivo | Conteúdo |
|---|---|
| `resultados/benchmark.csv` | Uma linha por configuração: mediana, mínimo, máximo, média e desvio dos tempos, speedup, eficiência, CPUs usadas e status |
| `resultados/benchmark_bruto.csv` | O tempo de cada repetição, com a posição em que rodou na rodada |
| `resultados/benchmark_console.txt` | A saída completa do console |
| `resultados/tabela_maior_volume.csv` | Tabela pronta para o relatório (gerada pelo Python) |
| `resultados/testes.txt`, `verificacao_afinidade.txt`, `demonstracao_afinidade.txt` | Evidências dos testes e da afinidade |
| `resultados/tarefas/` | Experimento de tarefas por thread (resumo, bruto, console e tabela) |
| `resultados/frequencia_1_thread/`, `frequencia_8_threads/` | Demonstrações usadas para observar a frequência do processador |
| `resultados/distribuicoes.csv` | Histograma dos valores de cada categoria (10 milhões de transações) |
| `graficos/00` a `08` | Distribuições, tempo, speedup, eficiência, com e sem afinidade, escalabilidade, passada única e variabilidade |
| `graficos_tarefas/` | Gráficos do experimento de tarefas por thread (o 06 é o principal) |
| `prints/` | Capturas do Gerenciador de Tarefas (afinidade, frequência e memória) |
| `docs/Relatorio_Tecnico_JCRPGPD.docx` e `.pdf` | Relatório técnico em ABNT |

O status de cada linha do CSV é um destes:

- `OK`: medido normalmente.
- `FALHOU`: a afinidade não se confirmou, então a configuração não foi medida.
- `INDISPONIVEL`: não há afinidade neste sistema.
- `NAO_APLICAVEL`: não se aplica, por exemplo mais threads que núcleos físicos.
- `NAO_EXECUTADO`: o volume não coube na memória.

Os gráficos só usam linhas com `OK`, para que nenhum número seja inventado.

## Como as medições são feitas

Isto serve de base para a metodologia do relatório:

- **Os dados são gerados fora do cronômetro.** Mede-se só a análise. Volumes menores usam o começo
  do conjunto maior: a geração é feita em blocos de tamanho fixo, cada um com o seu gerador
  aleatório (`SplittableRandom.split()`), então é idêntico a gerar só aquele volume.
- **Os pools de threads são criados uma vez e reaproveitados.** Criar threads não entra no tempo.
- **Aquecimento:** antes de medir, cada configuração roda num pedaço menor dos dados, para o JIT
  compilar o código.
- **Rodadas intercaladas:** em cada uma das N rodadas, todas as configurações rodam uma vez, em
  ordem embaralhada. Se o computador esquentar ou outro programa atrapalhar, o efeito se espalha
  por todas as configurações em vez de prejudicar sempre a mesma.
- **Conferência:** todo resultado paralelo é comparado com o sequencial antes de o tempo ser aceito.
- **Métricas:** speedup = mediana do sequencial / mediana da configuração. Eficiência = speedup /
  threads. O CSV também traz mínimo, máximo e desvio padrão: se as faixas de dois modos se
  sobrepõem, a diferença entre eles pode ser só ruído.

## Afinidade: o que observar

- **Verificação independente.** O programa pergunta ao sistema operacional em que CPU cada thread
  está rodando: `GetCurrentProcessorNumber` no Windows e `sched_getcpu` no Linux. A conferência não
  usa `Affinity.getAffinity()`, porque no Windows a OpenHFT devolve a máscara que ela mesma guardou,
  sem consultar o sistema. Se a fixação não se confirmar, aquela configuração não é medida.
- **Dois modos com afinidade:**
  - `Executor+Affinity`: thread *i* na CPU *i* (0, 1, 2, ...), como no exemplo da comanda.
  - `Executor+Affinity-nucleo`: uma thread por **núcleo físico**.
- **Por que dois modos.** Com hyperthreading, cada núcleo físico aparece como 2 CPUs lógicas que
  dividem as mesmas unidades de execução. No Windows, normalmente as CPUs 0 e 1 são o mesmo núcleo.
  Fixar 2 threads nelas pode ser mais lento do que não fixar nada. Comparar os dois modos mostra
  isso na prática. Em processadores híbridos (núcleos P e E), os núcleos P vêm primeiro na lista.
  Para conferir a topologia no Windows: Coreinfo (Sysinternals); no Linux: `lscpu -e`.
- **Evidência para o relatório:** rode `afinidade_windows.bat`. Na segunda fase da demonstração,
  só as CPUs fixadas ficam ocupadas no Gerenciador de Tarefas. Tire o print nesse momento.

## Divisão entre os 3 integrantes

A divisão segue a da comanda (por funcionalidade). Cada análise tem as suas três versões, a sua
redução e os seus testes. A seção "Contribuição Individual" do relatório detalha o que cada um fez.

| Integrante | Arquivos | O que explica na defesa |
|---|---|---|
| 1: Wilker Oliveira dos Santos | `AnaliseAgregacao`, `AnaliseSuspeitas` (+ testes do Integrante 1 em `Testes`) | Laço do bloco, acumuladores locais, redução de soma, maior e menor, regras de suspeita, top 10 |
| 2: Gabriel Ribeiro de Oliveira | `AnaliseCategorias`, `AnaliseFaixas` (+ testes do Integrante 2) | Vetores por tarefa (sem compartilhamento e sem false sharing), redução por categoria, histograma, desvio padrão e soma em ponto flutuante |
| 3: Ithalo Cavalcante Gomes | `GeradorTransacoes`, `AmostraDistribuicoes`, `Benchmark`, `Main`, `gerar_graficos.py`, scripts (+ testes do gerador); executou os experimentos | Distribuições, geração paralela determinística, metodologia de medição, speedup, eficiência, gráficos |
| Todos | `Transacoes`, `Analise`, `ProcessadorParalelo`, `FabricaExecutores`, `TopologiaCpu`, `AnaliseCompleta`, `Formato`, `Console` | Divisão em blocos, ExecutorService, Callable/Future, afinidade e verificação |

## Resultados obtidos

Medidos num notebook com AMD Ryzen 7 5700U (8 núcleos físicos, 16 lógicos), Windows 11 e Java 21,
em 18/10/2026. Detalhes, tabelas e análise completa estão no relatório em `docs/`.

- Com 500 milhões de transações, o speedup com 2 threads ficou entre 1,89 e 2,00, e o melhor
  resultado foi 3,62 (faixas, 4 threads). Com 8 threads, ficou entre 2,97 e 3,54.
- Com 8 núcleos ocupados, o processador caiu de 3,95 GHz para 1,77 GHz (limite de energia de um
  processador de 15 W); isso explica a saturação. A agregação ainda esbarra na memória (~16 GB/s).
- Afinidade nas CPUs 0 e 1 (mesmo núcleo físico) deixou 2 threads de 14,1% a 53,5% mais lentas; uma
  thread por núcleo físico foi de 0,6% a 5,2% mais rápida que sem afinidade.
- 1, 4 e 64 tarefas por thread deram o mesmo tempo; 4.096 tarefas por thread deixaram a agregação
  2,62 vezes mais lenta (cerca de 1 µs por tarefa).

O guia `docs/CODIGO_EXPLICADO.md` explica cada arquivo e como fazer as modificações mais comuns.

## Problemas comuns

| Problema | O que fazer |
|---|---|
| `mvn.cmd` não é reconhecido | Instale o Maven e coloque a pasta `bin` no PATH; reabra o terminal |
| `java`/`javac` não reconhecido | Instale o JDK 17+ (não só o JRE) e reabra o terminal |
| `NAO_EXECUTADO: memória insuficiente` | Aumente `MAVEN_OPTS=-Xmx...` ou use volumes menores. A comanda permite usar o maior volume que o computador aguentar |
| `AFINIDADE: indisponível` ou `FALHOU` | Confira se está em Windows ou Linux nativo (não VM/WSL/macOS) e leia a mensagem |
| Gráfico 04 não gerado | Não há medições com afinidade e status `OK` no CSV |
| Muito demorado | Reduza `--volumes`, `--repeticoes` ou `--analises` |
