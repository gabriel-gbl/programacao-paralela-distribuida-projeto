package br.edu.ifsp.transacoes;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;

/**
 * Testes de corretude (rodar com --teste). Não medem desempenho: conferem se os resultados
 * estão CERTOS.
 *
 */
public final class Testes {
    private static int verificacoes;

    private Testes() {
    }

    public static void executar() throws Exception {
        Console.println("TESTES DE CORRETUDE");
        Console.println();
        Transacoes exemplo = exemploManual();
        ExecutorService pool = FabricaExecutores.semAfinidade(3, "teste");
        try {
            testarAgregacao(exemplo, pool);  // Integrante 1
            testarSuspeitas(exemplo, pool);  // Integrante 1
            testarCategorias(exemplo, pool); // Integrante 2
            testarFaixas(exemplo, pool);     // Integrante 2
            testarGerador();                 // Integrante 3
            testarDivisaoDosBlocos();        // base comum
            testarParaleloIgualSequencial(); // base comum: todas as análises, vários tamanhos e threads
        } finally {
            pool.shutdownNow();
        }
        Console.println();
        Console.println("TODOS OS TESTES DE CÁLCULO PASSARAM (" + verificacoes + " verificações).");
        Console.println();
        testarAfinidade();
    }

    /**
     * 8 transações (valores em centavos):
     *   ID 1: R$ 10,00       Alimentação     ID 5: R$ 4.000,00   Eletrônicos
     *   ID 2: R$ 15.000,00   Transporte      ID 6: R$ 20.000,00  Educação
     *   ID 3: R$ 6.000,00    Eletrônicos     ID 7: R$ 1,00       Alimentação
     *   ID 4: R$ 500,00      Saúde           ID 8: R$ 999,99     Eletrônicos
     */
    static Transacoes exemploManual() {
        int[] valores = {1_000, 1_500_000, 600_000, 50_000, 400_000, 2_000_000, 100, 99_999};
        byte[] categorias = {
            Transacoes.ALIMENTACAO, Transacoes.TRANSPORTE, Transacoes.ELETRONICOS, Transacoes.SAUDE,
            Transacoes.ELETRONICOS, Transacoes.EDUCACAO, Transacoes.ALIMENTACAO, Transacoes.ELETRONICOS
        };
        return new Transacoes(valores, categorias);
    }

    // ------------------------------------------------------------------ Integrante 1

    static void testarAgregacao(Transacoes exemplo, ExecutorService pool) throws Exception {
        AnaliseAgregacao a = new AnaliseAgregacao();
        AnaliseAgregacao.Resultado r = ProcessadorParalelo.sequencial(a, exemplo, exemplo.quantidade);
        confere(r.quantidade == 8, "agregação: quantidade");
        confere(r.somaCentavos == 4_651_099L, "agregação: soma deveria ser R$ 46.510,99");
        confere(Math.abs(r.mediaCentavos() - 581_387.375) < 1e-9, "agregação: média");
        confere(r.maiorCentavos == 2_000_000 && r.indiceMaior == 5, "agregação: maior (ID 6)");
        confere(r.menorCentavos == 100 && r.indiceMenor == 6, "agregação: menor (ID 7)");

        // Empate no maior valor: fica a primeira ocorrência, no sequencial e no paralelo.
        Transacoes empate = new Transacoes(new int[] {5, 9, 9, 1}, new byte[4]);
        confere(ProcessadorParalelo.sequencial(a, empate, 4).indiceMaior == 1, "agregação: empate no sequencial");
        confere(ProcessadorParalelo.paralelo(a, empate, 4, pool, 4).indiceMaior == 1, "agregação: empate no paralelo");

        // Bloco vazio não pode estragar a redução.
        confere(ProcessadorParalelo.paralelo(a, exemplo, 8, pool, 20).somaCentavos == 4_651_099L,
                "agregação: mais tarefas do que transações");
        Console.println("  [Integrante 1] Agregação financeira ........ OK");
    }

    static void testarSuspeitas(Transacoes exemplo, ExecutorService pool) throws Exception {
        AnaliseSuspeitas s = new AnaliseSuspeitas();
        AnaliseSuspeitas.Resultado r = ProcessadorParalelo.sequencial(s, exemplo, exemplo.quantidade);
        confere(r.regraValorAlto == 2, "suspeitas: regra 1 deveria achar IDs 2 e 6");
        confere(r.regraEletronicoCaro == 1, "suspeitas: regra 2 deveria achar o ID 3");
        confere(r.suspeitas == 3, "suspeitas: total sem repetição");
        confere(r.valorSuspeitoCentavos == 4_100_000L, "suspeitas: valor total suspeito");
        confere(r.topQuantidade == 3 && r.topIndice[0] == 5 && r.topIndice[1] == 1 && r.topIndice[2] == 2,
                "suspeitas: ordem do top (IDs 6, 2, 3)");

        // Limites: exatamente R$ 10.000,00 e R$ 5.000,00 NÃO são suspeitos (a regra é "acima de").
        Transacoes limites = new Transacoes(new int[] {1_000_000, 1_000_001, 500_000, 500_001},
                new byte[] {Transacoes.SAUDE, Transacoes.SAUDE, Transacoes.ELETRONICOS, Transacoes.ELETRONICOS});
        AnaliseSuspeitas.Resultado rl = ProcessadorParalelo.sequencial(s, limites, 4);
        confere(rl.regraValorAlto == 1 && rl.regraEletronicoCaro == 1 && rl.suspeitas == 2, "suspeitas: limites");

        // Mais de 10 suspeitas com o mesmo valor: o top fica com os 10 primeiros IDs, em qualquer divisão.
        int[] iguais = new int[15];
        Arrays.fill(iguais, 2_000_000);
        Transacoes muitas = new Transacoes(iguais, new byte[15]);
        AnaliseSuspeitas.Resultado seq = ProcessadorParalelo.sequencial(s, muitas, 15);
        AnaliseSuspeitas.Resultado par = ProcessadorParalelo.paralelo(s, muitas, 15, pool, 12);
        confere(seq.topQuantidade == 10 && seq.topIndice[0] == 0 && seq.topIndice[9] == 9, "suspeitas: top 10 com empates");
        confere(s.mesmoResultado(seq, par), "suspeitas: top 10 paralelo diferente do sequencial");
        Console.println("  [Integrante 1] Detecção de suspeitas ....... OK");
    }

    // ------------------------------------------------------------------ Integrante 2

    static void testarCategorias(Transacoes exemplo, ExecutorService pool) throws Exception {
        AnaliseCategorias c = new AnaliseCategorias();
        AnaliseCategorias.Resultado r = ProcessadorParalelo.sequencial(c, exemplo, exemplo.quantidade);
        confere(Arrays.equals(r.quantidade, new long[] {2, 1, 3, 1, 1}), "categorias: quantidades");
        confere(Arrays.equals(r.totalCentavos, new long[] {1_100, 1_500_000, 1_099_999, 50_000, 2_000_000}),
                "categorias: totais");
        confere(Arrays.equals(r.maiorCentavos, new int[] {1_000, 1_500_000, 600_000, 50_000, 2_000_000}),
                "categorias: maiores");
        confere(Arrays.equals(r.menorCentavos, new int[] {100, 1_500_000, 99_999, 50_000, 2_000_000}),
                "categorias: menores");
        confere(Math.abs(r.mediaCentavos(Transacoes.ELETRONICOS) - 1_099_999 / 3.0) < 1e-9, "categorias: média");
        confere(c.mesmoResultado(r, ProcessadorParalelo.paralelo(c, exemplo, 8, pool, 5)), "categorias: paralelo");
        Console.println("  [Integrante 2] Estatísticas por categoria .. OK");
    }

    static void testarFaixas(Transacoes exemplo, ExecutorService pool) throws Exception {
        confere(AnaliseFaixas.faixa(5_000) == 0 && AnaliseFaixas.faixa(5_001) == 1, "faixas: limite de R$ 50");
        confere(AnaliseFaixas.faixa(1_000_000) == 5 && AnaliseFaixas.faixa(1_000_001) == 6, "faixas: limite de R$ 10 mil");

        AnaliseFaixas f = new AnaliseFaixas();
        AnaliseFaixas.Resultado r = ProcessadorParalelo.sequencial(f, exemplo, exemplo.quantidade);
        confere(Arrays.equals(r.quantidade, new long[] {2, 0, 1, 1, 1, 1, 2}), "faixas: quantidades");
        confere(Arrays.equals(r.totalCentavos, new long[] {1_100, 0, 50_000, 99_999, 400_000, 600_000, 3_500_000}),
                "faixas: totais");

        // A última faixa ("acima de R$ 10 mil") tem que bater com a Regra 1 das suspeitas.
        AnaliseSuspeitas.Resultado s = ProcessadorParalelo.sequencial(new AnaliseSuspeitas(), exemplo, exemplo.quantidade);
        confere(r.quantidade[6] == s.regraValorAlto, "faixas: última faixa x regra 1");

        // Desvio padrão conferido com a fórmula de duas passadas (média primeiro, depois desvios).
        double media = 0;
        for (int v : exemplo.valorCentavos) {
            media += v;
        }
        media /= exemplo.quantidade;
        double somaDesvios = 0;
        for (int v : exemplo.valorCentavos) {
            somaDesvios += (v - media) * (v - media);
        }
        double esperado = Math.sqrt(somaDesvios / exemplo.quantidade);
        confere(Math.abs(r.desvioPadraoCentavos() - esperado) <= 1e-9 * esperado, "faixas: desvio padrão");
        confere(f.mesmoResultado(r, ProcessadorParalelo.paralelo(f, exemplo, 8, pool, 3)), "faixas: paralelo");
        Console.println("  [Integrante 2] Faixas e desvio padrão ...... OK");
    }

    // ------------------------------------------------------------------ Integrante 3

    static void testarGerador() throws Exception {
        ExecutorService uma = FabricaExecutores.semAfinidade(1, "teste-gerador-1");
        ExecutorService quatro = FabricaExecutores.semAfinidade(4, "teste-gerador-4");
        try {
            int n = 2_345_678;
            Transacoes a = GeradorTransacoes.gerar(n, 2026, uma);
            Transacoes b = GeradorTransacoes.gerar(n, 2026, quatro);
            confere(Arrays.equals(a.valorCentavos, b.valorCentavos) && Arrays.equals(a.categoria, b.categoria),
                    "gerador: a mesma semente deve gerar os mesmos dados com 1 ou 4 threads");

            Transacoes menor = GeradorTransacoes.gerar(1_500_000, 2026, quatro);
            confere(Arrays.equals(menor.valorCentavos, Arrays.copyOf(a.valorCentavos, 1_500_000))
                            && Arrays.equals(menor.categoria, Arrays.copyOf(a.categoria, 1_500_000)),
                    "gerador: um volume menor deve ser o começo do volume maior");

            Transacoes outra = GeradorTransacoes.gerar(100_000, 2027, quatro);
            confere(!Arrays.equals(outra.valorCentavos, Arrays.copyOf(a.valorCentavos, 100_000)),
                    "gerador: sementes diferentes devem gerar dados diferentes");

            long[] porCategoria = new long[Transacoes.CATEGORIAS.length];
            boolean todosPositivos = true;
            for (int i = 0; i < n; i++) {
                todosPositivos &= a.valorCentavos[i] > 0;
                porCategoria[a.categoria[i]]++;
            }
            confere(todosPositivos, "gerador: todos os valores devem ser positivos");
            for (int c = 0; c < porCategoria.length; c++) {
                double proporcao = (double) porCategoria[c] / n;
                confere(Math.abs(proporcao - GeradorTransacoes.PESO_CATEGORIA[c]) < 0.005,
                        "gerador: proporção de " + Transacoes.CATEGORIAS[c]);
            }
        } finally {
            uma.shutdownNow();
            quatro.shutdownNow();
        }
        Console.println("  [Integrante 3] Gerador de dados ............ OK");
    }

    // ------------------------------------------------------------------ base comum

    static void testarDivisaoDosBlocos() {
        for (int n : new int[] {0, 1, 7, 10, 1_000_003, 500_000_000}) {
            for (int k : new int[] {1, 2, 3, 4, 8, 32}) {
                confere(ProcessadorParalelo.inicioDoBloco(n, k, 0) == 0, "divisão: primeiro bloco começa no 0");
                confere(ProcessadorParalelo.inicioDoBloco(n, k, k) == n, "divisão: último bloco termina em n");
                int menor = Integer.MAX_VALUE;
                int maior = 0;
                for (int t = 0; t < k; t++) {
                    int tamanho = ProcessadorParalelo.inicioDoBloco(n, k, t + 1) - ProcessadorParalelo.inicioDoBloco(n, k, t);
                    menor = Math.min(menor, tamanho);
                    maior = Math.max(maior, tamanho);
                }
                confere(maior - menor <= 1, "divisão: blocos devem diferir em no máximo 1");
            }
        }
        // Exemplo da comanda: 10.000.000 transações em 4 partes.
        confere(ProcessadorParalelo.inicioDoBloco(10_000_000, 4, 1) == 2_500_000
                && ProcessadorParalelo.inicioDoBloco(10_000_000, 4, 3) == 7_500_000, "divisão: exemplo da comanda");
        Console.println("  [Base comum]   Divisão dos blocos .......... OK");
    }

    static void testarParaleloIgualSequencial() throws Exception {
        int[] quantidadesDeThreads = {1, 2, 3, 4, 8};
        ExecutorService[] pools = new ExecutorService[quantidadesDeThreads.length];
        for (int i = 0; i < pools.length; i++) {
            pools[i] = FabricaExecutores.semAfinidade(quantidadesDeThreads[i], "teste-" + quantidadesDeThreads[i]);
        }
        try {
            Transacoes ultimo = null;
            for (int n : new int[] {0, 1, 3, 17, 1_000, 123_457}) {
                Transacoes dados = GeradorTransacoes.gerar(n, 99, pools[3]);
                for (Analise<?> analise : Main.todasAnalises()) {
                    for (int i = 0; i < pools.length; i++) {
                        for (int tarefasPorThread : new int[] {1, 4}) {
                            conferirParalelo(analise, dados, pools[i], quantidadesDeThreads[i] * tarefasPorThread);
                        }
                    }
                }
                ultimo = dados;
            }
            // A análise completa (uma passada) tem que bater com as quatro análises separadas.
            AnaliseCompleta.Resultado tudo = ProcessadorParalelo.sequencial(new AnaliseCompleta(), ultimo, ultimo.quantidade);
            confere(new AnaliseAgregacao().mesmoResultado(tudo.agregacao,
                    ProcessadorParalelo.sequencial(new AnaliseAgregacao(), ultimo, ultimo.quantidade)), "completa x agregação");
            confere(new AnaliseSuspeitas().mesmoResultado(tudo.suspeitas,
                    ProcessadorParalelo.sequencial(new AnaliseSuspeitas(), ultimo, ultimo.quantidade)), "completa x suspeitas");
            confere(new AnaliseCategorias().mesmoResultado(tudo.categorias,
                    ProcessadorParalelo.sequencial(new AnaliseCategorias(), ultimo, ultimo.quantidade)), "completa x categorias");
            confere(new AnaliseFaixas().mesmoResultado(tudo.faixas,
                    ProcessadorParalelo.sequencial(new AnaliseFaixas(), ultimo, ultimo.quantidade)), "completa x faixas");
        } finally {
            for (ExecutorService pool : pools) {
                pool.shutdownNow();
            }
        }
        Console.println("  [Base comum]   Paralelo = sequencial ....... OK (5 análises, 6 tamanhos, 1 a 8 threads)");
    }

    private static <R> void conferirParalelo(Analise<R> analise, Transacoes dados, ExecutorService pool, int tarefas)
            throws Exception {
        R esperado = ProcessadorParalelo.sequencial(analise, dados, dados.quantidade);
        R obtido = ProcessadorParalelo.paralelo(analise, dados, dados.quantidade, pool, tarefas);
        confere(analise.mesmoResultado(esperado, obtido), analise.nome() + ": paralelo diferente do sequencial com "
                + dados.quantidade + " transações e " + tarefas + " tarefas");
    }

    /** Não é teste de cálculo: confere se a afinidade funciona NESTE computador. */
    static void testarAfinidade() throws Exception {
        String motivo = TopologiaCpu.motivoSemAfinidade();
        if (motivo != null) {
            Console.println("AFINIDADE: indisponível neste computador (" + motivo + ").");
            Console.println("Os testes de cálculo valem; meçam a afinidade num Windows ou Linux.");
            return;
        }
        int[] permitidas = TopologiaCpu.cpusPermitidas();
        int[] cpus = Benchmark.cpusEmSequencia(permitidas, Math.min(2, permitidas.length));
        ExecutorService fixo = FabricaExecutores.comAfinidade(cpus, "teste-afin");
        try {
            FabricaExecutores.Verificacao v = FabricaExecutores.verificar(fixo, cpus.length);
            Transacoes dados = GeradorTransacoes.gerar(200_000, 5, fixo);
            for (Analise<?> analise : Main.todasAnalises()) {
                conferirParalelo(analise, dados, fixo, cpus.length * 4);
            }
            Console.println("AFINIDADE: " + (v.ok ? "OK" : "ATENÇÃO, FALHOU") + " | CPUs pedidas " + Formato.lista(cpus)
                    + " | observadas " + v.cpusObservadas + " | " + v.detalhe);
            Console.println("(Os cálculos com o pool fixado também bateram com o sequencial.)");
        } finally {
            fixo.shutdownNow();
        }
    }

    private static void confere(boolean condicao, String mensagem) {
        verificacoes++;
        if (!condicao) {
            throw new IllegalStateException("TESTE FALHOU: " + mensagem);
        }
    }
}
