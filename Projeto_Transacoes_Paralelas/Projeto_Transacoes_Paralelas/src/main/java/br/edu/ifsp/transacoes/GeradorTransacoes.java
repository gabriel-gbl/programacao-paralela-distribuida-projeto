package br.edu.ifsp.transacoes;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Geração sintética das transações.
 *
 * Cada categoria segue a distribuição estatística que mais se parece com gastos reais
 * (são cinco distribuições diferentes; a comanda pede no mínimo quatro):
 *
 *   Alimentação -> Normal (média R$ 85, desvio R$ 30): compras do dia a dia giram em torno de um
 *                  valor típico, com poucas muito acima ou muito abaixo.
 *   Transporte  -> Exponencial (R$ 4,40 + média de R$ 30): muitas passagens e corridas baratas,
 *                  cada vez menos frequentes conforme o valor sobe.
 *   Eletrônicos -> Log-normal (mediana R$ 900): a maioria abaixo de R$ 2 mil, com uma "cauda longa"
 *                  de compras caras. É a categoria que mais alimenta a detecção de suspeitas.
 *   Saúde       -> Bimodal (mistura de duas normais): 75% consultas e farmácia (perto de R$ 120) e
 *                  25% exames e procedimentos (perto de R$ 1.800). O histograma tem dois picos.
 *   Educação    -> Uniforme (de R$ 250 a R$ 3.000): mensalidades e cursos espalhados numa faixa.
 *
 * Além disso, 0,02% das transações (de qualquer categoria) recebem um valor anômalo entre
 * R$ 8.000 e R$ 60.000, simulando fraudes para a detecção de suspeitas encontrar.
 *
 * A geração acontece UMA vez, em paralelo, ANTES das medições: o benchmark mede só as análises.
 *
 * Ela é DETERMINÍSTICA: a mesma semente gera exatamente os mesmos dados, com qualquer número de
 * threads. Para isso, o conjunto é dividido em blocos de tamanho FIXO (1 milhão) e cada bloco
 * recebe o seu próprio gerador aleatório, criado com split() a partir de um gerador "raiz",
 * sempre na mesma ordem. Nenhuma thread compartilha gerador (um Random compartilhado seria
 * um ponto de disputa entre as threads). Consequência útil: um volume menor é exatamente o
 * começo de um volume maior gerado com a mesma semente.
 */
public final class GeradorTransacoes {
    public static final int TAMANHO_BLOCO = 1_000_000;

    /** Proporção de cada categoria, na ordem de Transacoes.CATEGORIAS (soma = 1). */
    public static final double[] PESO_CATEGORIA = {0.32, 0.23, 0.19, 0.15, 0.11};
    public static final String[] DISTRIBUICAO_CATEGORIA = {"Normal", "Exponencial", "Log-normal", "Bimodal", "Uniforme"};

    /** Fração de transações anômalas (fraudes simuladas). */
    public static final double TAXA_ANOMALIA = 0.0002;

    private GeradorTransacoes() {
    }

    /** Gera 'quantidade' transações usando as threads do executor recebido. */
    public static Transacoes gerar(int quantidade, long semente, ExecutorService executor)
            throws InterruptedException, ExecutionException {
        int[] valores = new int[quantidade];
        byte[] categorias = new byte[quantidade];
        SplittableRandom raiz = new SplittableRandom(semente);

        List<Callable<Void>> tarefas = new ArrayList<>();
        for (long inicio = 0; inicio < quantidade; inicio += TAMANHO_BLOCO) { // long: evita estouro perto do limite do int
            int ini = (int) inicio;
            int fim = (int) Math.min(inicio + TAMANHO_BLOCO, quantidade);
            SplittableRandom aleatorioDoBloco = raiz.split(); // um gerador independente por bloco
            tarefas.add(() -> {
                preencher(valores, categorias, ini, fim, aleatorioDoBloco);
                return null;
            });
        }
        for (Future<Void> futuro : executor.invokeAll(tarefas)) {
            futuro.get(); // repassa qualquer erro que tenha acontecido dentro de uma tarefa
        }
        return new Transacoes(valores, categorias);
    }

    static void preencher(int[] valores, byte[] categorias, int inicio, int fim, SplittableRandom aleatorio) {
        for (int i = inicio; i < fim; i++) {
            int categoria = sortearCategoria(aleatorio);
            double reais = aleatorio.nextDouble() < TAXA_ANOMALIA
                    ? valorAnomalo(aleatorio)
                    : valorDaCategoria(categoria, aleatorio);
            categorias[i] = (byte) categoria;
            valores[i] = (int) Math.round(reais * 100);
        }
    }

    static int sortearCategoria(SplittableRandom aleatorio) {
        double u = aleatorio.nextDouble();
        double acumulado = 0;
        for (int c = 0; c < PESO_CATEGORIA.length - 1; c++) {
            acumulado += PESO_CATEGORIA[c];
            if (u < acumulado) {
                return c;
            }
        }
        return PESO_CATEGORIA.length - 1;
    }

    /** Valor em reais, de acordo com a distribuição da categoria. */
    static double valorDaCategoria(int categoria, SplittableRandom a) {
        return switch (categoria) {
            case Transacoes.ALIMENTACAO -> normalComMinimo(a, 85, 30, 5);
            case Transacoes.TRANSPORTE -> 4.40 + 30 * a.nextExponential();
            case Transacoes.ELETRONICOS -> logNormalComMaximo(a, 900, 0.9, 200_000);
            case Transacoes.SAUDE -> a.nextDouble() < 0.75
                    ? normalComMinimo(a, 120, 40, 10)
                    : normalComMinimo(a, 1_800, 600, 300);
            default -> 250 + 2_750 * a.nextDouble(); // Educação
        };
    }

    /**
     * Normal "truncada": sorteia de novo enquanto o valor ficar abaixo do mínimo.
     * Simplesmente cortar no mínimo (Math.max) criaria muitas transações com exatamente o mesmo
     * valor, o que distorce o menor valor e o histograma.
     */
    static double normalComMinimo(SplittableRandom a, double media, double desvio, double minimo) {
        double x;
        do {
            x = media + desvio * a.nextGaussian();
        } while (x < minimo);
        return x;
    }

    /** Log-normal: e elevado a uma normal. Sorteia de novo acima do máximo (raríssimo). */
    static double logNormalComMaximo(SplittableRandom a, double mediana, double sigma, double maximo) {
        double x;
        do {
            x = mediana * Math.exp(sigma * a.nextGaussian());
        } while (x > maximo);
        return x;
    }

    static double valorAnomalo(SplittableRandom a) {
        return 8_000 + 52_000 * a.nextDouble();
    }
}
