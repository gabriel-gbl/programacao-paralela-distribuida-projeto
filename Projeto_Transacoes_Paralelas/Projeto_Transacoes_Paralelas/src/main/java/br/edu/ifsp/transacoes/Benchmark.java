package br.edu.ifsp.transacoes;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;

/**
 * Módulo de experimentação (benchmark).
 *
 * Como as medições são feitas:
 *  1. Os dados são gerados UMA vez (o maior volume), fora do cronômetro. Os volumes menores usam
 *     o começo do mesmo conjunto (pela geração em blocos, é idêntico a gerar só aquele volume).
 *  2. Os pools de threads são criados UMA vez e reaproveitados: criar threads não entra no tempo
 *     das análises. Os pools com afinidade são conferidos pelo sistema operacional antes de medir;
 *     se a fixação não se confirmar, aquela configuração não é medida (status FALHOU no CSV).
 *  3. Para cada volume e análise: calcula o resultado sequencial de referência, aquece o JIT e
 *     faz R rodadas. Em cada rodada, TODAS as configurações rodam uma vez, em ordem embaralhada.
 *     Assim, se o computador esquentar ou outro programa atrapalhar no meio, o efeito se espalha
 *     por todas as configurações em vez de cair sempre na mesma.
 *  4. Todo resultado paralelo é conferido com o sequencial antes de o tempo ser aceito.
 *  5. Resume com mediana, mínimo, máximo, média e desvio padrão dos tempos;
 *     speedup = mediana do sequencial / mediana da configuração;
 *     eficiência = speedup / número de threads.
 */
public final class Benchmark {

    /** Os modos comparados no experimento. */
    public enum Modo {
        SEQUENCIAL("Sequencial", "SEQUENCIAL"),
        EXECUTOR("Executor", "EXECUTOR"),
        AFINIDADE("Executor+Affinity", "EXECUTOR + AFFINITY"),
        AFINIDADE_NUCLEO("Executor+Affinity-nucleo", "EXECUTOR + AFFINITY (1 thread por núcleo físico)");

        public final String rotulo; // usado no CSV
        public final String titulo; // usado no console

        Modo(String rotulo, String titulo) {
            this.rotulo = rotulo;
            this.titulo = titulo;
        }
    }

    /** Parâmetros do experimento (preenchidos a partir da linha de comando, em Main). */
    public static final class Parametros {
        public int[] volumes = {1_000_000, 10_000_000};
        public int[] threads = {1, 2, 4, 8};
        public int[] tarefasPorThread = {4};
        public int repeticoes = 10;
        public List<Analise<?>> analises = Main.todasAnalises();
        public EnumSet<Modo> modos = EnumSet.of(Modo.EXECUTOR, Modo.AFINIDADE, Modo.AFINIDADE_NUCLEO);
        public int[] cpusNucleos; // --nucleos=... (null = detectar sozinho)
        public long semente = 2026;
        public Path saida = Path.of("resultados", "benchmark.csv");
    }

    /** Um pool preparado para um modo e um número de threads. */
    static final class Pool {
        final Modo modo;
        final int threads;
        final int[] cpus;               // CPUs pedidas (null = sem afinidade)
        final ExecutorService executor; // null = esta configuração não pode ser medida
        final String status;            // OK, NAO_VERIFICADO, FALHOU, INDISPONIVEL ou NAO_APLICAVEL
        final String detalhe;

        Pool(Modo modo, int threads, int[] cpus, ExecutorService executor, String status, String detalhe) {
            this.modo = modo;
            this.threads = threads;
            this.cpus = cpus;
            this.executor = executor;
            this.status = status;
            this.detalhe = detalhe;
        }
    }

    /** Uma linha do experimento (modo + threads + tarefas por thread) com os tempos medidos. */
    static final class Configuracao {
        final Modo modo;
        final int threads;
        final int tarefasPorThread;
        final Pool pool; // null no modo sequencial
        final List<Double> tempos = new ArrayList<>();

        Configuracao(Modo modo, int threads, int tarefasPorThread, Pool pool) {
            this.modo = modo;
            this.threads = threads;
            this.tarefasPorThread = tarefasPorThread;
            this.pool = pool;
        }

        int tarefas() {
            return modo == Modo.SEQUENCIAL ? 1 : threads * tarefasPorThread;
        }

        boolean executavel() {
            return pool == null || pool.executor != null;
        }

        String descricao() {
            return modo.rotulo + " (" + threads + " threads, " + tarefas() + " tarefas)";
        }
    }

    private final Parametros p;
    private final List<Pool> pools = new ArrayList<>();
    private BufferedWriter csv;
    private BufferedWriter bruto;

    public Benchmark(Parametros parametros) {
        this.p = parametros;
    }

    // ===================================================================== execução principal

    public void executar() throws Exception {
        Path pasta = p.saida.toAbsolutePath().getParent();
        Files.createDirectories(pasta);
        String base = nomeSemExtensao(p.saida);
        Console.abrirArquivo(pasta.resolve(base + "_console.txt"));
        try {
            cabecalho();
            csv = abrirCsv(p.saida, "data_hora;sistema;processador;cpus_logicas;volume;analise;configuracao;threads;"
                    + "tarefas_por_thread;tarefas;cpus;repeticoes;mediana_ms;minimo_ms;maximo_ms;media_ms;desvio_ms;"
                    + "speedup;eficiencia_percentual;status;observacao");
            bruto = abrirCsv(pasta.resolve(base + "_bruto.csv"),
                    "volume;analise;configuracao;threads;tarefas_por_thread;rodada;posicao_na_rodada;tempo_ms");

            int[] volumes = volumesQueCabemNaMemoria();
            if (volumes.length == 0) {
                Console.println("Nenhum volume cabe na memória. Aumente MAVEN_OPTS=-Xmx... ou use volumes menores.");
                return;
            }
            Transacoes dados = gerarDados(volumes[volumes.length - 1], p.semente);
            prepararPools();

            for (int volume : volumes) {
                for (Analise<?> analise : p.analises) {
                    medir(analise, dados, volume);
                }
            }
            Console.println();
            Console.println("Resumo salvo em:  " + p.saida.toAbsolutePath());
            Console.println("Tempos de cada repetição: " + pasta.resolve(base + "_bruto.csv"));
            Console.println("Saída do console: " + pasta.resolve(base + "_console.txt"));
            Console.println("Gráficos: rode  py gerar_graficos.py  (Windows) ou  python3 gerar_graficos.py  (Linux).");
        } finally {
            encerrarPools();
            if (csv != null) {
                csv.close();
            }
            if (bruto != null) {
                bruto.close();
            }
            Console.fechar();
        }
    }

    /** --verificar-afinidade: mostra a topologia e confere a fixação das threads, sem medir. */
    public static void verificarAfinidade(Parametros parametros) throws Exception {
        Benchmark b = new Benchmark(parametros);
        try {
            b.cabecalho();
            b.prepararPools();
        } finally {
            b.encerrarPools();
        }
    }

    /**
     * --demonstrar-afinidade: deixa as threads ocupadas, primeiro SEM e depois COM afinidade, para
     * fotografar o Gerenciador de Tarefas (Windows) ou o htop (Linux) e mostrar no relatório que
     * só as CPUs fixadas trabalham na segunda fase.
     */
    public static void demonstrarAfinidade(Parametros parametros, int threads, int segundos) throws Exception {
        Benchmark b = new Benchmark(parametros);
        b.cabecalho();
        String motivo = TopologiaCpu.motivoSemAfinidade();
        if (motivo != null) {
            Console.println("Afinidade indisponível: " + motivo);
            return;
        }
        int[] cpus = cpusEmSequencia(TopologiaCpu.cpusPermitidas(), threads);
        Transacoes dados = gerarDados(20_000_000, parametros.semente);
        Analise<?> analise = new AnaliseCompleta();
        int tarefas = threads * 4;

        if (TopologiaCpu.WINDOWS) {
            Console.println("Abra o Gerenciador de Tarefas > Desempenho > CPU, clique com o botão direito no gráfico");
            Console.println("e escolha Alterar gráfico para > Processadores lógicos. Tire o print no final da fase 2.");
        } else {
            Console.println("Abra o htop (ou top e aperte 1) em outro terminal para ver cada CPU.");
        }
        int metade = Math.max(5, segundos / 2);

        ExecutorService semAfinidade = FabricaExecutores.semAfinidade(threads, "demo-livre");
        try {
            Console.println();
            Console.printf("FASE 1: %d threads SEM afinidade por %d s (o sistema espalha e troca as threads de CPU)...", threads, metade);
            ocupar(analise, dados, semAfinidade, tarefas, metade);
        } finally {
            semAfinidade.shutdownNow();
        }

        ExecutorService comAfinidade = FabricaExecutores.comAfinidade(cpus, "demo-fixa");
        try {
            FabricaExecutores.Verificacao v = FabricaExecutores.verificar(comAfinidade, threads);
            Console.println();
            Console.printf("FASE 2: %d threads COM afinidade nas CPUs %s por %d s (verificação: %s; observadas: %s)...",
                    threads, Formato.lista(cpus), metade, v.ok ? "OK" : "FALHOU", v.cpusObservadas);
            ocupar(analise, dados, comAfinidade, tarefas, metade);
        } finally {
            comAfinidade.shutdownNow();
        }
        Console.println("Fim da demonstração.");
    }

    private static <R> void ocupar(Analise<R> analise, Transacoes dados, ExecutorService pool, int tarefas, int segundos)
            throws Exception {
        long inicio = System.nanoTime();
        long fim = inicio + segundos * 1_000_000_000L;
        long proximoAviso = inicio + 5_000_000_000L;
        int execucoes = 0;
        while (System.nanoTime() < fim) {
            ProcessadorParalelo.paralelo(analise, dados, dados.quantidade, pool, tarefas);
            execucoes++;
            if (System.nanoTime() >= proximoAviso) {
                Console.printf("  %d s: %d execuções", (System.nanoTime() - inicio) / 1_000_000_000L, execucoes);
                proximoAviso += 5_000_000_000L;
            }
        }
    }

    // ===================================================================== preparação

    private void cabecalho() {
        Runtime rt = Runtime.getRuntime();
        Console.println("==============================================================");
        Console.println(" ANÁLISE PARALELA DE TRANSAÇÕES FINANCEIRAS");
        Console.println("==============================================================");
        Console.println("Data: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        Console.println("Sistema: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");
        Console.println("Processador: " + TopologiaCpu.nomeDoProcessador());
        Console.println("CPUs lógicas: " + rt.availableProcessors()
                + " | Memória máxima do Java: " + gb(rt.maxMemory()) + " GB"
                + " | Java " + System.getProperty("java.version"));
        Console.println("Volumes: " + listaDeVolumes(p.volumes) + " | Threads: " + Formato.lista(p.threads)
                + " | Tarefas por thread: " + Formato.lista(p.tarefasPorThread) + " | Repetições: " + p.repeticoes);
        StringBuilder nomes = new StringBuilder();
        for (Analise<?> a : p.analises) {
            nomes.append(nomes.length() > 0 ? ", " : "").append(a.nome());
        }
        StringBuilder modos = new StringBuilder("Sequencial");
        for (Modo m : p.modos) {
            modos.append(", ").append(m.rotulo);
        }
        Console.println("Análises: " + nomes + " | Modos: " + modos);
        Console.println();
    }

    /** Volumes que cabem na memória do Java; os demais são registrados como NAO_EXECUTADO. */
    private int[] volumesQueCabemNaMemoria() throws IOException {
        long maximo = Runtime.getRuntime().maxMemory();
        long limite = (long) (maximo * 0.85) - 64L * 1024 * 1024; // folga para o resto do programa
        int[] ordenados = p.volumes.clone();
        Arrays.sort(ordenados);
        List<Integer> cabem = new ArrayList<>();
        for (int volume : ordenados) {
            long precisa = Transacoes.memoriaNecessaria(volume);
            if (precisa <= limite) {
                cabem.add(volume);
            } else {
                String motivo = "memória insuficiente: os dados precisam de ~" + gb(precisa)
                        + " GB e o Java tem no máximo " + gb(maximo) + " GB (aumente MAVEN_OPTS=-Xmx...)";
                Console.println("Volume " + Formato.inteiro(volume) + " NÃO será executado: " + motivo);
                for (Analise<?> analise : p.analises) {
                    linhaCsv(volume, analise.nome(), Modo.SEQUENCIAL, 1, 1, 1, "", 0,
                            null, 0, "NAO_EXECUTADO", motivo);
                }
            }
        }
        int[] resultado = new int[cabem.size()];
        for (int i = 0; i < resultado.length; i++) {
            resultado[i] = cabem.get(i);
        }
        return resultado;
    }

    private static Transacoes gerarDados(int quantidade, long semente) throws Exception {
        Console.println("Gerando " + Formato.inteiro(quantidade) + " transações (uma vez, em paralelo, fora das medições)...");
        ExecutorService gerador = FabricaExecutores.semAfinidade(Runtime.getRuntime().availableProcessors(), "gerador");
        long inicio = System.nanoTime();
        try {
            Transacoes dados = GeradorTransacoes.gerar(quantidade, semente, gerador);
            Console.println("Dados prontos em " + Formato.decimal((System.nanoTime() - inicio) / 1e9, 1) + " s ("
                    + tamanho(Transacoes.memoriaNecessaria(quantidade)) + " na memória).");
            Console.println();
            return dados;
        } catch (OutOfMemoryError erro) {
            throw new IllegalStateException("Faltou memória para gerar " + Formato.inteiro(quantidade)
                    + " transações. Use volumes menores ou aumente MAVEN_OPTS=-Xmx...", erro);
        } finally {
            gerador.shutdownNow();
        }
    }

    /** Cria (uma vez) os pools de cada modo e número de threads e confere os que têm afinidade. */
    private void prepararPools() throws Exception {
        int[] permitidas = TopologiaCpu.cpusPermitidas();
        String semAfinidade = TopologiaCpu.motivoSemAfinidade();
        boolean querAfinidade = p.modos.contains(Modo.AFINIDADE) || p.modos.contains(Modo.AFINIDADE_NUCLEO);

        Console.println("CPUs lógicas permitidas para o processo: " + Formato.lista(permitidas));
        if (querAfinidade) {
            Console.println("Afinidade: " + (semAfinidade == null
                    ? "disponível (OpenHFT); a conferência pergunta ao sistema operacional em que CPU cada thread roda"
                    : "INDISPONÍVEL: " + semAfinidade));
        }
        TopologiaCpu.Nucleos nucleos = null;
        if (p.modos.contains(Modo.AFINIDADE_NUCLEO) && semAfinidade == null) {
            nucleos = p.cpusNucleos != null
                    ? TopologiaCpu.Nucleos.informados(p.cpusNucleos)
                    : TopologiaCpu.detectarNucleos(permitidas);
            if (nucleos != null) {
                Console.println("Núcleos físicos: " + nucleos.descricao);
                Console.println("CPUs do modo \"1 thread por núcleo físico\": " + Formato.lista(nucleos.cpus));
            }
        }

        for (int t : p.threads) {
            if (p.modos.contains(Modo.EXECUTOR)) {
                pools.add(new Pool(Modo.EXECUTOR, t, null,
                        FabricaExecutores.semAfinidade(t, "exec-" + t + "t"), "OK", "sem afinidade"));
            }
            if (p.modos.contains(Modo.AFINIDADE)) {
                if (semAfinidade != null) {
                    pools.add(new Pool(Modo.AFINIDADE, t, null, null, "INDISPONIVEL", semAfinidade));
                } else {
                    String observacao = t > permitidas.length
                            ? "mais threads que CPUs lógicas: até " + ((t + permitidas.length - 1) / permitidas.length) + " threads por CPU"
                            : "";
                    pools.add(criarPoolFixado(Modo.AFINIDADE, t, cpusEmSequencia(permitidas, t), observacao));
                }
            }
            if (p.modos.contains(Modo.AFINIDADE_NUCLEO)) {
                if (semAfinidade != null) {
                    pools.add(new Pool(Modo.AFINIDADE_NUCLEO, t, null, null, "INDISPONIVEL", semAfinidade));
                } else if (nucleos == null) {
                    pools.add(new Pool(Modo.AFINIDADE_NUCLEO, t, null, null, "INDISPONIVEL",
                            "núcleos físicos não detectados; informe a lista com --nucleos=0,2,4,6 (exemplo)"));
                } else if (t > nucleos.cpus.length) {
                    pools.add(new Pool(Modo.AFINIDADE_NUCLEO, t, null, null, "NAO_APLICAVEL",
                            t + " threads é mais do que os " + nucleos.cpus.length + " núcleos físicos"));
                } else if (p.modos.contains(Modo.AFINIDADE)
                        && Arrays.equals(Arrays.copyOf(nucleos.cpus, t), cpusEmSequencia(permitidas, t))) {
                    pools.add(new Pool(Modo.AFINIDADE_NUCLEO, t, Arrays.copyOf(nucleos.cpus, t), null, "NAO_APLICAVEL",
                            "mesmas CPUs do modo Executor+Affinity (aqui as CPUs em sequência já são núcleos diferentes)"));
                } else {
                    pools.add(criarPoolFixado(Modo.AFINIDADE_NUCLEO, t, Arrays.copyOf(nucleos.cpus, t), ""));
                }
            }
        }

        if (querAfinidade) {
            Console.println();
            Console.println("Verificação da afinidade (CPU em que cada thread realmente rodou, segundo o sistema operacional):");
            for (Pool pool : pools) {
                if (pool.modo == Modo.EXECUTOR) {
                    continue;
                }
                String cpus = pool.cpus == null ? "-" : Formato.lista(pool.cpus);
                Console.printf("  %-26s %2d thread%s | pedidas: %-16s | %s: %s", pool.modo.rotulo, pool.threads,
                        pool.threads == 1 ? " " : "s", cpus, pool.status, pool.detalhe);
            }
        }
        Console.println();
    }

    private static Pool criarPoolFixado(Modo modo, int threads, int[] cpus, String observacao) throws Exception {
        String nome = (modo == Modo.AFINIDADE ? "afin-" : "nucleo-") + threads + "t";
        ExecutorService executor = FabricaExecutores.comAfinidade(cpus, nome);
        FabricaExecutores.Verificacao v = FabricaExecutores.verificar(executor, threads);
        String extra = observacao.isEmpty() ? "" : " (" + observacao + ")";
        if (v.ok && v.verificavel) {
            return new Pool(modo, threads, cpus, executor, "OK", "observadas " + v.cpusObservadas + extra);
        }
        if (v.ok) {
            return new Pool(modo, threads, cpus, executor, "NAO_VERIFICADO", v.detalhe + extra);
        }
        executor.shutdownNow();
        return new Pool(modo, threads, cpus, null, "FALHOU", v.detalhe);
    }

    /** Thread i -> i-ésima CPU permitida (0, 1, 2, ...), como no exemplo da comanda. */
    static int[] cpusEmSequencia(int[] permitidas, int threads) {
        int[] cpus = new int[threads];
        for (int i = 0; i < threads; i++) {
            cpus[i] = permitidas[i % permitidas.length];
        }
        return cpus;
    }

    private void encerrarPools() {
        for (Pool pool : pools) {
            if (pool.executor != null) {
                pool.executor.shutdownNow();
            }
        }
        pools.clear();
    }

    // ===================================================================== medição

    private <R> void medir(Analise<R> analise, Transacoes dados, int volume) throws Exception {
        // Referência: resultado sequencial (fora do cronômetro). Também aquece o JIT e a cache.
        R referencia = ProcessadorParalelo.sequencial(analise, dados, volume);

        List<Configuracao> configuracoes = new ArrayList<>();
        Configuracao sequencial = new Configuracao(Modo.SEQUENCIAL, 1, 1, null);
        configuracoes.add(sequencial);
        for (Modo modo : new Modo[] {Modo.EXECUTOR, Modo.AFINIDADE, Modo.AFINIDADE_NUCLEO}) {
            for (int tarefasPorThread : p.tarefasPorThread) {
                for (Pool pool : pools) {
                    if (pool.modo == modo) {
                        configuracoes.add(new Configuracao(modo, pool.threads, tarefasPorThread, pool));
                    }
                }
            }
        }
        List<Configuracao> executaveis = new ArrayList<>();
        for (Configuracao c : configuracoes) {
            if (c.executavel()) {
                executaveis.add(c);
            }
        }

        // Aquecimento: cada configuração roda 2 vezes num pedaço menor, sem medir.
        int volumeAquecimento = Math.min(volume, 2_000_000);
        for (int vez = 0; vez < 2; vez++) {
            for (Configuracao c : executaveis) {
                rodar(analise, dados, volumeAquecimento, c);
            }
        }

        // Rodadas: todas as configurações uma vez por rodada, em ordem embaralhada.
        Random embaralhador = new Random(p.semente * 31 + volume * 17L + analise.nome().hashCode());
        for (int rodada = 1; rodada <= p.repeticoes; rodada++) {
            List<Configuracao> ordem = new ArrayList<>(executaveis);
            Collections.shuffle(ordem, embaralhador);
            for (int posicao = 0; posicao < ordem.size(); posicao++) {
                Configuracao c = ordem.get(posicao);
                long inicio = System.nanoTime();
                R resultado = rodar(analise, dados, volume, c);
                double ms = (System.nanoTime() - inicio) / 1_000_000.0;
                if (!analise.mesmoResultado(referencia, resultado)) {
                    throw new IllegalStateException("ERRO: " + c.descricao() + " deu resultado diferente do sequencial na análise "
                            + analise.nome() + " com " + volume + " transações");
                }
                c.tempos.add(ms);
                bruto.write(volume + ";" + analise.nome() + ";" + c.modo.rotulo + ";" + c.threads + ";"
                        + c.tarefasPorThread + ";" + rodada + ";" + (posicao + 1) + ";" + decimalCsv(ms) + "\n");
            }
        }
        bruto.flush();

        imprimirEGravar(analise, volume, configuracoes, sequencial);
        Console.println("Resultado da análise (" + Formato.inteiro(volume) + " transações):");
        Console.println(analise.resumo(referencia));
        Console.println();
    }

    private static <R> R rodar(Analise<R> analise, Transacoes dados, int volume, Configuracao c) throws Exception {
        if (c.modo == Modo.SEQUENCIAL) {
            return ProcessadorParalelo.sequencial(analise, dados, volume);
        }
        return ProcessadorParalelo.paralelo(analise, dados, volume, c.pool.executor, c.tarefas());
    }

    // ===================================================================== resultados

    /** Estatísticas dos tempos de uma configuração. */
    static final class Estatisticas {
        final double mediana;
        final double minimo;
        final double maximo;
        final double media;
        final double desvio; // desvio padrão amostral

        Estatisticas(List<Double> tempos) {
            double[] t = new double[tempos.size()];
            for (int i = 0; i < t.length; i++) {
                t[i] = tempos.get(i);
            }
            Arrays.sort(t);
            int meio = t.length / 2;
            mediana = t.length % 2 == 1 ? t[meio] : (t[meio - 1] + t[meio]) / 2;
            minimo = t[0];
            maximo = t[t.length - 1];
            double soma = 0;
            for (double x : t) {
                soma += x;
            }
            media = soma / t.length;
            double quadrados = 0;
            for (double x : t) {
                quadrados += (x - media) * (x - media);
            }
            desvio = t.length > 1 ? Math.sqrt(quadrados / (t.length - 1)) : 0;
        }
    }

    private <R> void imprimirEGravar(Analise<R> analise, int volume, List<Configuracao> configuracoes,
                                     Configuracao sequencial) throws IOException {
        Estatisticas base = new Estatisticas(sequencial.tempos);

        Console.println("==============================================================");
        Console.println(" ANÁLISE: " + analise.titulo());
        Console.println(" Quantidade de transações: " + Formato.inteiro(volume)
                + " | tempos = mediana de " + p.repeticoes + " repetições");
        Console.println("==============================================================");

        Modo modoAtual = null;
        for (Configuracao c : configuracoes) {
            if (c.modo != modoAtual) {
                modoAtual = c.modo;
                Console.println(titulo(c.modo.titulo));
            }
            String cpus = c.pool != null && c.pool.cpus != null ? Formato.lista(c.pool.cpus) : "";
            if (!c.executavel()) {
                Console.printf("Threads: %d | %s: %s", c.threads, c.pool.status, c.pool.detalhe);
                Console.println();
                linhaCsv(volume, analise.nome(), c.modo, c.threads, c.tarefasPorThread, c.tarefas(), cpus,
                        0, null, 0, c.pool.status, c.pool.detalhe);
                continue;
            }
            Estatisticas e = new Estatisticas(c.tempos);
            double speedup = base.mediana / e.mediana;
            double eficiencia = speedup / c.threads * 100;
            if (c.modo != Modo.SEQUENCIAL) {
                Console.printf("Threads: %d | Tarefas: %d", c.threads, c.tarefas());
                if (!cpus.isEmpty()) {
                    Console.println("CPUs: " + cpus);
                }
            }
            Console.printf("Tempo: %.3f ms  (mín %.3f | máx %.3f | desvio %.3f)", e.mediana, e.minimo, e.maximo, e.desvio);
            if (c.modo != Modo.SEQUENCIAL) {
                Console.printf("Speedup: %.2f", speedup);
                Console.printf("Eficiência: %.1f%%", eficiencia);
            }
            Console.println();
            String status = c.pool == null ? "OK" : c.pool.status;
            String observacao = c.pool == null ? "referência para o speedup" : c.pool.detalhe;
            linhaCsv(volume, analise.nome(), c.modo, c.threads, c.tarefasPorThread, c.tarefas(), cpus,
                    c.tempos.size(), e, c.modo == Modo.SEQUENCIAL ? 1 : speedup, status, observacao);
        }
        csv.flush();
    }

    private void linhaCsv(int volume, String analise, Modo modo, int threads, int tarefasPorThread, int tarefas,
                          String cpus, int repeticoes, Estatisticas e, double speedup, String status,
                          String observacao) throws IOException {
        String tempos = e == null ? ";;;;" : decimalCsv(e.mediana) + ";" + decimalCsv(e.minimo) + ";"
                + decimalCsv(e.maximo) + ";" + decimalCsv(e.media) + ";" + decimalCsv(e.desvio);
        String metricas = e == null ? ";" : decimalCsv(speedup) + ";" + decimalCsv(speedup / threads * 100);
        // As CPUs vão separadas por espaço ("0 1"): com vírgula, o Excel leria "0,1" como o número 0,1.
        csv.write(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ";"
                + texto(System.getProperty("os.name")) + ";" + texto(TopologiaCpu.nomeDoProcessador()) + ";"
                + Runtime.getRuntime().availableProcessors() + ";" + volume + ";" + analise + ";" + modo.rotulo + ";"
                + threads + ";" + tarefasPorThread + ";" + tarefas + ";" + cpus.replace(',', ' ') + ";" + repeticoes + ";"
                + tempos + ";" + metricas + ";" + status + ";" + texto(observacao) + "\n");
    }

    // ===================================================================== utilidades

    private static BufferedWriter abrirCsv(Path caminho, String cabecalho) throws IOException {
        Path pasta = caminho.toAbsolutePath().getParent();
        if (pasta != null) {
            Files.createDirectories(pasta);
        }
        BufferedWriter w = Files.newBufferedWriter(caminho, StandardCharsets.UTF_8);
        w.write('﻿'); // marca UTF-8 (BOM): faz o Excel mostrar os acentos certos
        w.write(cabecalho + "\n");
        return w;
    }

    /** Número com vírgula decimal (padrão do Excel brasileiro). */
    private static String decimalCsv(double x) {
        return Formato.decimal(x, 4);
    }

    /** Tira ';' e quebras de linha de um texto que vai para o CSV. */
    private static String texto(String s) {
        return s == null ? "" : s.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }

    private static String titulo(String texto) {
        int largura = 62;
        int tracos = Math.max(4, largura - texto.length() - 2);
        String esquerda = "-".repeat(tracos / 2);
        String direita = "-".repeat(tracos - tracos / 2);
        return esquerda + " " + texto + " " + direita;
    }

    private static String gb(long bytes) {
        return Formato.decimal(bytes / (1024.0 * 1024 * 1024), 1);
    }

    /** "25 MB" ou "2,3 GB". */
    private static String tamanho(long bytes) {
        double mb = bytes / (1024.0 * 1024);
        return mb < 1024 ? Formato.decimal(mb, 0) + " MB" : gb(bytes) + " GB";
    }

    private static String listaDeVolumes(int[] volumes) {
        StringBuilder texto = new StringBuilder();
        for (int v : volumes) {
            texto.append(texto.length() > 0 ? ", " : "").append(Formato.inteiro(v));
        }
        return texto.toString();
    }

    private static String nomeSemExtensao(Path caminho) {
        String nome = caminho.getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        return ponto > 0 ? nome.substring(0, ponto) : nome;
    }
}
