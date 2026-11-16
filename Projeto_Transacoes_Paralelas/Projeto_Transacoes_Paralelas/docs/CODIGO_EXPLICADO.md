# Guia do código

Este guia explica o que cada arquivo faz e, principalmente, **por que** foi feito assim.
Todos os arquivos ficam em `src/main/java/br/edu/ifsp/transacoes/`.

## A ideia em uma figura

```
            Transacoes (vetores: valorCentavos[], categoria[])
                               |
         ProcessadorParalelo divide [0, n) em blocos
          /                |                \
   tarefa 1            tarefa 2     ...     tarefa k       <- Callable, cada uma chama
   processar(0,a)      processar(a,b)       processar(..,n)    analise.processar(dados, inicio, fim)
       |                   |                    |
   parcial 1           parcial 2            parcial k
          \                |                /
           juntar(juntar(parcial 1, parcial 2), ...)        <- redução
                               |
                        resultado final  ==  resultado sequencial ?   (conferido sempre)
```

A **versão sequencial** é `processar(dados, 0, n)`: o mesmo laço, num bloco só. As duas versões
paralelas usam exatamente o mesmo código. Muda só o pool de threads: sem afinidade ou com as
threads fixadas em CPUs. Assim, a única diferença entre "Executor" e "Executor + Affinity" no
experimento é a afinidade.

---

## Base comum (todos devem saber explicar)

### `Transacoes.java`: o conjunto de dados

- Há um vetor por campo ("structure of arrays"): `int[] valorCentavos` e `byte[] categoria`.
  São 5 bytes por transação; 500 milhões ocupam cerca de 2,3 GB.
- **Por que não um objeto `Transacao` por registro?** Cada objeto Java gasta cerca de 16 bytes só
  de cabeçalho, mais a referência e os campos. Com milhões de objetos, isso multiplica a memória e
  espalha os dados. Com vetores, os valores ficam lado a lado e a leitura é sequencial, o que
  aproveita bem a cache do processador. Além disso, cada análise lê só os vetores de que precisa.
- **Por que centavos em `int`?** Somar inteiros é exato, então o paralelo dá exatamente o mesmo
  total do sequencial. Com `double`, a ordem das somas muda o arredondamento. O `int` cabe até
  R$ 21 milhões por transação.
- O ID da transação é a posição no vetor (na tela, ID = índice + 1).

### `Analise.java`: o contrato de toda análise

| Método | Para quê |
|---|---|
| `processar(dados, inicio, fim)` | Analisa um bloco e devolve um resultado **parcial** novo |
| `juntar(a, b)` | **Redução**: combina dois parciais |
| `mesmoResultado(a, b)` | Confere paralelo × sequencial |
| `resumo(r)` | Texto para o console |

### `ProcessadorParalelo.java`: divide, distribui e combina

- `sequencial(...)` chama `processar(dados, 0, n)` na própria thread.
- `paralelo(...)`:
  1. cria `numeroTarefas` objetos `Callable`, um por bloco;
  2. chama `pool.invokeAll(tarefas)`, que entrega todas e espera todas terminarem;
  3. pega cada resultado com `Future.get()` e combina com `juntar`, na ordem dos blocos.
- `inicioDoBloco(n, k, t) = n * t / k`, calculado em `long`:
  - multiplicar antes de dividir espalha o resto, então os blocos diferem em no máximo 1 transação;
  - nenhuma transação fica de fora ou é contada duas vezes;
  - a conta é feita em `long` porque `500.000.000 × 32` estoura o `int`.
- **Nunca se cria uma thread por transação.** O número de tarefas é `threads × tarefas por thread`
  (padrão 4).
- **Por que 4 tarefas por thread?** A fila do pool é compartilhada: a thread que termina o seu bloco
  pega o próximo. Se uma thread atrasar (o sistema a tirou da CPU, ou ela caiu num núcleo mais
  lento), as outras compensam. Isso é **balanceamento de carga**. Com 1 tarefa por thread, todos
  esperam a mais lenta. Muitas tarefas, por outro lado, aumentam o overhead de criação e de
  redução. O experimento `--tarefas-por-thread=1,4` mede isso.

### `FabricaExecutores.java`: os pools e a verificação da afinidade

- `semAfinidade(t)`: `Executors.newFixedThreadPool(t)` com threads "daemon".
- `comAfinidade(cpus)`: pool com uma thread por CPU da lista. Cada `TrabalhadorFixado` chama
  `Affinity.setAffinity(máscara)` **dentro da própria thread**, antes de começar a pegar tarefas. A
  afinidade é uma propriedade da thread que faz a chamada. A thread fica fixada a vida inteira.
- `verificar(pool, t)`:
  - manda uma tarefa por thread. Um `CountDownLatch` faz cada tarefa esperar as outras começarem,
    o que garante que cada thread pega exatamente uma;
  - cada tarefa trabalha um pouco e pergunta ao **sistema operacional** 20 vezes em que CPU está;
  - se a fixação funcionou, ela só pode aparecer na CPU pedida.

### `TopologiaCpu.java`: CPUs, núcleos e "em que CPU estou?"

- `cpuAtual()` pergunta ao sistema em que CPU a thread está agora:
  - Linux: `Affinity.getCpu()`, que usa `sched_getcpu`;
  - Windows: `GetCurrentProcessorNumber` do `kernel32`, chamado via JNA.
  - **Por que não `Affinity.getAffinity()`?** No Windows, a OpenHFT devolve a máscara que ela mesma
    guardou ao chamar `setAffinity`, sem consultar o sistema. A conferência seria sempre "OK".
- `detectarNucleos(...)` escolhe uma CPU lógica por núcleo físico:
  - Linux: lê `/sys/devices/system/cpu/cpuN/topology/`;
  - Windows: usa `GetLogicalProcessorInformationEx` via JNA;
  - em processadores híbridos, os núcleos de desempenho (P) vêm primeiro.
- `motivoSemAfinidade()`: a OpenHFT só fixa threads de verdade no Windows e no Linux.

### `AnaliseCompleta.java`: experimento extra

- Faz as quatro análises numa única passada ("fusão de laços").
- Usa as **mesmas** regras das análises separadas: os métodos estáticos `regraValorAlto`,
  `regraEletronicoCaro` e `faixa`. O teste confere que o resultado é idêntico.
- **Pergunta que o experimento responde:** ler os dados uma vez só compensa? Com muitos dados, o
  gargalo costuma ser a velocidade da memória RAM, e não o processador.

### `Formato.java` e `Console.java`

- `Formato` escreve números no padrão brasileiro.
- `Console` escreve na tela e, ao mesmo tempo, num arquivo em `resultados/`.

---

## Integrante 1 (Wilker Oliveira dos Santos)

### `AnaliseAgregacao.java`: soma, média, maior e menor

- Acumula em **variáveis locais** (`soma`, `maior`, `menor`). Nada é compartilhado entre threads
  durante o laço, então não precisa de `synchronized` nem de variáveis atômicas.
- Redução:
  - soma e quantidade: somam;
  - maior: o maior dos maiores;
  - menor: o menor dos menores.
- **Empate:** o `>` (e não `>=`) guarda a primeira ocorrência. No `juntar`, ganha o menor índice.
  Assim, o ID do maior e do menor é o mesmo em qualquer divisão.
- É a análise mais leve (1 soma e 2 comparações por transação). Com muitos dados, tende a esbarrar
  na velocidade da memória, o que limita o speedup.

### `AnaliseSuspeitas.java`: transações suspeitas

- Regra 1: valor > R$ 10.000. Regra 2: Eletrônicos e valor > R$ 5.000. Uma transação que cumpre
  as duas conta uma vez só.
- Além de contar, guarda as **10 maiores suspeitas**:
  - `considerar(...)` insere numa lista ordenada de 10 posições;
  - no `juntar`, as duas listas são mescladas e cortadas de novo em 10;
  - empate de valor: ganha o menor ID, então o top 10 é o mesmo em qualquer divisão.
- Menos de 1% das transações entra no `if`, então manter o top 10 quase não custa nada.

## Integrante 2 (Gabriel Ribeiro de Oliveira)

### `AnaliseCategorias.java`: estatísticas por categoria

- Cada tarefa cria os **seus** vetores de 5 posições (quantidade, total, maior e menor por
  categoria).
- **Por que não um vetor compartilhado?**
  - precisaria de sincronização, que é lenta;
  - mesmo com `AtomicLong`, as posições vizinhas ficam na mesma linha de cache (64 bytes), e cada
    núcleo invalidaria a cache dos outros a cada escrita. Isso é o "false sharing".
- Redução: soma posição a posição; maior e menor por comparação.

### `AnaliseFaixas.java`: análise 5 (faixas de valor e desvio padrão)

- `faixa(valor)` devolve de 0 a 6. Cada faixa inclui o limite de cima. A última faixa ("acima de
  R$ 10 mil") usa o mesmo critério da Regra 1, então as duas contagens batem. Isso serve de
  conferência cruzada.
- **Desvio padrão** = raiz(média dos quadrados − quadrado da média).
- A soma dos quadrados fica em `double`, porque estouraria o `long`.
- **Consequência:** é a única análise em que o paralelo pode diferir do sequencial nas últimas
  casas decimais. Somar `double` em outra ordem arredonda diferente: a soma em ponto flutuante não
  é associativa. Por isso a conferência usa tolerância relativa de 1e-9.

## Integrante 3 (Ithalo Cavalcante Gomes)

### `GeradorTransacoes.java`: dados sintéticos

| Categoria | Distribuição | Por que parece real |
|---|---|---|
| Alimentação | Normal (média R$ 85) | gastos do dia a dia em torno de um valor típico |
| Transporte | Exponencial (R$ 4,40 + média R$ 30) | muitas passagens baratas, poucas caras |
| Eletrônicos | Log-normal (mediana R$ 900) | cauda longa de compras caras |
| Saúde | Bimodal (R$ 120 e R$ 1.800) | consultas/farmácia e exames/procedimentos |
| Educação | Uniforme (R$ 250 a R$ 3.000) | mensalidades espalhadas numa faixa |

- 0,02% das transações recebem um valor anômalo (R$ 8 mil a R$ 60 mil), simulando fraudes.
- **Por que sortear de novo, em vez de cortar o valor no mínimo?** Cortar criaria muitas
  transações com o mesmo valor, o que distorce o menor valor e o histograma.
- **A geração é paralela e determinística:**
  - o conjunto é dividido em blocos FIXOS de 1 milhão;
  - cada bloco recebe o seu próprio `SplittableRandom`, criado com `split()` a partir de um gerador
    raiz, sempre na mesma ordem;
  - com isso, a mesma semente gera os mesmos dados com 1 ou 8 threads, e nenhuma thread disputa
    um gerador compartilhado;
  - consequência útil: um volume menor é exatamente o começo do maior.

### `Benchmark.java`: o experimento

O resumo do método está no README (seção "Como as medições são feitas"). O código segue esta
ordem:

1. `volumesQueCabemNaMemoria`;
2. `gerarDados`;
3. `prepararPools`, que cria os pools e verifica a afinidade;
4. `medir`, que faz a referência sequencial, o aquecimento e as rodadas embaralhadas;
5. `imprimirEGravar`, que calcula mediana, mínimo, máximo, desvio, speedup e eficiência e grava o
   CSV.

### `Main.java`, `AmostraDistribuicoes.java` e `gerar_graficos.py`

- `Main` lê os parâmetros (`--ajuda` lista todos) e chama o teste, a verificação, a demonstração,
  a amostra das distribuições ou o benchmark.
- `AmostraDistribuicoes` (opção `--distribuicoes`) gera as 10 milhões primeiras transações e grava
  o histograma de cada categoria em `resultados/distribuicoes.csv`, usado no gráfico 00.
- O script Python lê o CSV, usa só as linhas com status `OK` e gera os gráficos e a tabela. Cada
  modo tem sempre a mesma cor em todos os gráficos. Com `--relatorio`, gera a versão para A4 usada
  no relatório. O gráfico 08 lê o arquivo `_bruto.csv` e mostra cada repetição separadamente.

---

## Modificações que o professor pode pedir

| Pedido | Onde mexer |
|---|---|
| Mudar o limite de suspeita para R$ 8.000 | `AnaliseSuspeitas.LIMITE_GERAL_CENTAVOS = 800_000` |
| Nova regra de suspeita (ex.: Saúde > R$ 3.000) | Criar o método `regraSaudeCara` e usá-lo em `processar` (e em `AnaliseCompleta`) |
| Top 5 em vez de top 10 | `AnaliseSuspeitas.TAMANHO_TOP = 5` |
| Média por categoria só de valores > R$ 100 | `AnaliseCategorias.processar`: um `if (valor > 10_000)` antes de acumular |
| Mudar as faixas | `AnaliseFaixas.LIMITES_CENTAVOS` e `NOMES_FAIXAS` (mesma quantidade + 1) |
| Mais tarefas por thread | Parâmetro `--tarefas-por-thread=8` (sem mudar código) |
| Outra distribuição para uma categoria | `GeradorTransacoes.valorDaCategoria` |
| Fixar as threads em outras CPUs | `--nucleos=...` ou `Benchmark.cpusEmSequencia` |

Depois de qualquer mudança, rode `--teste`. Ele compara o paralelo com o sequencial em vários
tamanhos e quantidades de threads. Os testes com valores fixos (escritos à mão em `Testes.java`)
também precisam ser atualizados se a regra mudar.
