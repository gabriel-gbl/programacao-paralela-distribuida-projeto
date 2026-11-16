package br.edu.ifsp.transacoes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** Ponto de entrada: lê os parâmetros da linha de comando e escolhe o que executar. */
public final class Main {

    private Main() {
    }

    /** Todas as análises do projeto, na ordem em que são medidas. */
    public static List<Analise<?>> todasAnalises() {
        return List.of(
                new AnaliseAgregacao(),   // Integrante 1
                new AnaliseSuspeitas(),   // Integrante 1
                new AnaliseCategorias(),  // Integrante 2
                new AnaliseFaixas(),      // Integrante 2
                new AnaliseCompleta());   // experimento extra
    }

    public static void main(String[] args) throws Exception {
        Benchmark.Parametros p = new Benchmark.Parametros();
        String acao = "benchmark";
        int segundosDemonstracao = 40;
        int quantidadeAmostra = 10_000_000;
        boolean threadsInformadas = false;

        for (String argumento : args) {
            String valor = argumento.contains("=") ? argumento.substring(argumento.indexOf('=') + 1) : "";
            if (argumento.equals("--ajuda") || argumento.equals("--help") || argumento.equals("-h")) {
                ajuda();
                return;
            } else if (argumento.equals("--teste")) {
                acao = "teste";
            } else if (argumento.equals("--distribuicoes")) {
                acao = "distribuicoes";
            } else if (argumento.startsWith("--distribuicoes=")) {
                acao = "distribuicoes";
                quantidadeAmostra = inteiro(valor);
            } else if (argumento.equals("--verificar-afinidade")) {
                acao = "verificar";
            } else if (argumento.equals("--demonstrar-afinidade")) {
                acao = "demonstrar";
            } else if (argumento.startsWith("--demonstrar-afinidade=")) {
                acao = "demonstrar";
                segundosDemonstracao = inteiro(valor);
            } else if (argumento.startsWith("--volumes=")) {
                p.volumes = inteiros(valor);
            } else if (argumento.startsWith("--threads=")) {
                p.threads = inteiros(valor);
                threadsInformadas = true;
            } else if (argumento.startsWith("--tarefas-por-thread=")) {
                p.tarefasPorThread = inteiros(valor);
            } else if (argumento.startsWith("--repeticoes=")) {
                p.repeticoes = inteiro(valor);
            } else if (argumento.startsWith("--analises=")) {
                p.analises = analises(valor);
            } else if (argumento.startsWith("--modos=")) {
                p.modos = modos(valor);
            } else if (argumento.equals("--sem-afinidade")) {
                p.modos = EnumSet.of(Benchmark.Modo.EXECUTOR);
            } else if (argumento.startsWith("--nucleos=")) {
                p.cpusNucleos = inteiros(valor);
            } else if (argumento.startsWith("--semente=")) {
                p.semente = Long.parseLong(valor.trim());
            } else if (argumento.startsWith("--saida=")) {
                p.saida = Path.of(valor);
            } else {
                throw new IllegalArgumentException("Parâmetro desconhecido: " + argumento + "  (veja --ajuda)");
            }
        }
        validar(p);

        // A saída de cada ação também é guardada num arquivo de texto, na pasta de resultados.
        Path pasta = p.saida.toAbsolutePath().getParent();
        switch (acao) {
            case "teste" -> {
                Console.abrirArquivo(pasta.resolve("testes.txt"));
                try {
                    Testes.executar();
                } finally {
                    Console.fechar();
                }
            }
            case "distribuicoes" -> AmostraDistribuicoes.exportar(quantidadeAmostra, p.semente,
                    pasta.resolve("distribuicoes.csv"));
            case "verificar" -> {
                Console.abrirArquivo(pasta.resolve("verificacao_afinidade.txt"));
                try {
                    Benchmark.verificarAfinidade(p);
                } finally {
                    Console.fechar();
                }
            }
            case "demonstrar" -> {
                Console.abrirArquivo(pasta.resolve("demonstracao_afinidade.txt"));
                try {
                    Benchmark.demonstrarAfinidade(p, threadsInformadas ? p.threads[0] : 2, segundosDemonstracao);
                } finally {
                    Console.fechar();
                }
            }
            default -> new Benchmark(p).executar();
        }
    }

    private static void validar(Benchmark.Parametros p) {
        for (int v : p.volumes) {
            if (v < 1) {
                throw new IllegalArgumentException("Volumes devem ser maiores que zero");
            }
        }
        for (int t : p.threads) {
            if (t < 1 || t > 256) {
                throw new IllegalArgumentException("Threads devem ficar entre 1 e 256");
            }
        }
        for (int k : p.tarefasPorThread) {
            // O limite alto permite medir o custo de tarefas pequenas demais (experimento de granularidade).
            if (k < 1 || k > 10_000) {
                throw new IllegalArgumentException("Tarefas por thread devem ficar entre 1 e 10.000");
            }
        }
        if (p.repeticoes < 1) {
            throw new IllegalArgumentException("Repetições devem ser >= 1");
        }
        if (p.repeticoes < 5) {
            System.out.println("Aviso: com menos de 5 repetições, diferenças pequenas podem ser só ruído.");
        }
    }

    /** "100000,1.000.000,10_000_000" -> {100000, 1000000, 10000000}. */
    static int[] inteiros(String texto) {
        String[] partes = texto.split(",");
        int[] numeros = new int[partes.length];
        for (int i = 0; i < partes.length; i++) {
            numeros[i] = inteiro(partes[i]);
        }
        return numeros;
    }

    static int inteiro(String texto) {
        long n = Long.parseLong(texto.trim().replace("_", "").replace(".", ""));
        if (n > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Número grande demais: " + texto + " (máximo " + Integer.MAX_VALUE + ")");
        }
        return (int) n;
    }

    private static List<Analise<?>> analises(String texto) {
        if (texto.trim().equalsIgnoreCase("todas")) {
            return todasAnalises();
        }
        List<Analise<?>> escolhidas = new ArrayList<>();
        for (String nome : texto.split(",")) {
            Analise<?> encontrada = null;
            for (Analise<?> a : todasAnalises()) {
                if (a.nome().equalsIgnoreCase(nome.trim())) {
                    encontrada = a;
                }
            }
            if (encontrada == null) {
                throw new IllegalArgumentException("Análise desconhecida: " + nome
                        + " (use agregacao, suspeitas, categorias, faixas, completa ou todas)");
            }
            escolhidas.add(encontrada);
        }
        return escolhidas;
    }

    private static EnumSet<Benchmark.Modo> modos(String texto) {
        EnumSet<Benchmark.Modo> modos = EnumSet.noneOf(Benchmark.Modo.class);
        for (String nome : texto.split(",")) {
            switch (nome.trim().toLowerCase(Locale.ROOT)) {
                case "executor" -> modos.add(Benchmark.Modo.EXECUTOR);
                case "afinidade" -> modos.add(Benchmark.Modo.AFINIDADE);
                case "nucleo", "nucleos", "afinidade-nucleo" -> modos.add(Benchmark.Modo.AFINIDADE_NUCLEO);
                case "sequencial" -> {
                    // o sequencial sempre é medido (é a referência do speedup)
                }
                default -> throw new IllegalArgumentException("Modo desconhecido: " + nome
                        + " (use executor, afinidade, nucleo)");
            }
        }
        return modos;
    }

    private static void ajuda() {
        System.out.println("Uso (com Maven):  mvn -q compile exec:java -Dexec.args=\"PARÂMETROS\"");
        System.out.println();
        System.out.println("O que executar (sem nada: benchmark):");
        System.out.println("  --teste                     confere se os cálculos estão certos (não mede tempo)");
        System.out.println("  --verificar-afinidade       mostra CPUs e núcleos e confere a fixação das threads");
        System.out.println("  --demonstrar-afinidade=40   ocupa as threads 20 s sem e 20 s com afinidade (para o print");
        System.out.println("                              do Gerenciador de Tarefas); usa a 1ª quantidade de --threads (padrão 2)");
        System.out.println("  --distribuicoes=10000000    histograma dos valores por categoria (resultados/distribuicoes.csv),");
        System.out.println("                              usado no gráfico 00_distribuicoes.png");
        System.out.println();
        System.out.println("Parâmetros do benchmark:");
        System.out.println("  --volumes=1000000,10000000             quantidades de transações");
        System.out.println("  --threads=1,2,4,8                      quantidades de threads");
        System.out.println("  --tarefas-por-thread=4                 tarefas por thread, de 1 a 10.000 (ex.: 1,4,64,4096)");
        System.out.println("  --repeticoes=10                        repetições de cada configuração (usa a mediana)");
        System.out.println("  --analises=todas                       ou: agregacao,suspeitas,categorias,faixas,completa");
        System.out.println("  --modos=executor,afinidade,nucleo      modos paralelos (o sequencial sempre roda)");
        System.out.println("  --sem-afinidade                        só sequencial e Executor");
        System.out.println("  --nucleos=0,2,4,6                      CPUs do modo \"1 thread por núcleo\" (se não detectar)");
        System.out.println("  --semente=2026                         semente dos dados sintéticos");
        System.out.println("  --saida=resultados/benchmark.csv       arquivo do resumo (o bruto e o console vão junto)");
    }
}
