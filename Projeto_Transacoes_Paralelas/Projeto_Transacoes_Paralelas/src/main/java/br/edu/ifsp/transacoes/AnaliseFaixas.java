package br.edu.ifsp.transacoes;

import java.util.Arrays;

/**
 * Análise 5 da comand:
 * distribuição dos valores por faixa (histograma) e desvio padrão.
 *
 * Faixas: até R$ 50 | R$ 50 a 100 | R$ 100 a 500 | R$ 500 a 1 mil | R$ 1 mil a 5 mil |
 *         R$ 5 mil a 10 mil | acima de R$ 10 mil.
 * Cada faixa inclui o limite de cima (ex.: R$ 100,00 exatos cai em "R$ 50 a 100"). Assim, a
 * última faixa usa o mesmo critério da Regra 1 de suspeitas (valor > R$ 10.000): as duas
 * contagens precisam bater, o que permite conferir uma análise com a outra.
 *
 * Desvio padrão (populacional) = raiz( média dos quadrados - quadrado da média ).
 *
 * Estratégia de paralelização: cada tarefa conta as faixas e acumula soma e soma dos quadrados
 * do seu bloco; a redução soma tudo. A soma dos quadrados é guardada em double porque estouraria
 * o long (500 milhões de valores ao quadrado). Por isso esta é a única análise em que o paralelo
 * pode diferir do sequencial nas últimas casas decimais: somar números double em outra ordem
 * arredonda de outro jeito (a soma em ponto flutuante não é associativa). A conferência usa uma
 * tolerância relativa de 1e-9, e a diferença não aparece nas casas mostradas.
 */
public final class AnaliseFaixas implements Analise<AnaliseFaixas.Resultado> {
    /** Limite de cima de cada faixa, em centavos (a última faixa não tem limite). */
    public static final int[] LIMITES_CENTAVOS = {5_000, 10_000, 50_000, 100_000, 500_000, 1_000_000};
    public static final String[] NOMES_FAIXAS = {
        "até R$ 50", "R$ 50 a 100", "R$ 100 a 500", "R$ 500 a 1 mil",
        "R$ 1 mil a 5 mil", "R$ 5 mil a 10 mil", "acima de R$ 10 mil"
    };
    public static final int NUMERO_FAIXAS = LIMITES_CENTAVOS.length + 1;
    public static final double TOLERANCIA_RELATIVA = 1e-9;

    /** Em qual faixa (0 a 6) o valor cai. */
    public static int faixa(int valorCentavos) {
        int f = 0;
        while (f < LIMITES_CENTAVOS.length && valorCentavos > LIMITES_CENTAVOS[f]) {
            f++;
        }
        return f;
    }

    /** Resultado de um bloco (parcial) ou do conjunto inteiro (final). */
    public static final class Resultado {
        final long[] quantidade = new long[NUMERO_FAIXAS];
        final long[] totalCentavos = new long[NUMERO_FAIXAS];
        long n;
        long somaCentavos;
        double somaQuadrados; // soma de (valor em centavos)^2

        public double mediaCentavos() {
            return n == 0 ? 0 : (double) somaCentavos / n;
        }

        public double desvioPadraoCentavos() {
            if (n == 0) {
                return 0;
            }
            double media = mediaCentavos();
            double variancia = somaQuadrados / n - media * media;
            return Math.sqrt(Math.max(0, variancia)); // max evita raiz de -0,0000001 por arredondamento
        }
    }

    @Override
    public String nome() {
        return "faixas";
    }

    @Override
    public String titulo() {
        return "Distribuição por faixa de valor e desvio padrão";
    }

    @Override
    public Resultado processar(Transacoes dados, int inicio, int fim) {
        int[] valores = dados.valorCentavos;
        Resultado r = new Resultado();
        long[] quantidade = r.quantidade;
        long[] total = r.totalCentavos;
        long soma = 0;
        double somaQuadrados = 0;

        for (int i = inicio; i < fim; i++) {
            int valor = valores[i];
            int f = faixa(valor);
            quantidade[f]++;
            total[f] += valor;
            soma += valor;
            somaQuadrados += (double) valor * valor;
        }

        r.n = fim - inicio;
        r.somaCentavos = soma;
        r.somaQuadrados = somaQuadrados;
        return r;
    }

    @Override
    public Resultado juntar(Resultado a, Resultado b) {
        Resultado r = new Resultado();
        for (int f = 0; f < NUMERO_FAIXAS; f++) {
            r.quantidade[f] = a.quantidade[f] + b.quantidade[f];
            r.totalCentavos[f] = a.totalCentavos[f] + b.totalCentavos[f];
        }
        r.n = a.n + b.n;
        r.somaCentavos = a.somaCentavos + b.somaCentavos;
        r.somaQuadrados = a.somaQuadrados + b.somaQuadrados;
        return r;
    }

    @Override
    public boolean mesmoResultado(Resultado a, Resultado b) {
        return Arrays.equals(a.quantidade, b.quantidade)
                && Arrays.equals(a.totalCentavos, b.totalCentavos)
                && a.n == b.n
                && a.somaCentavos == b.somaCentavos
                && quaseIguais(a.somaQuadrados, b.somaQuadrados);
    }

    static boolean quaseIguais(double x, double y) {
        double maior = Math.max(Math.abs(x), Math.abs(y));
        return maior == 0 || Math.abs(x - y) <= TOLERANCIA_RELATIVA * maior;
    }

    @Override
    public String resumo(Resultado r) {
        double n = Math.max(1, r.n);
        StringBuilder texto = new StringBuilder(String.format(Formato.BR,
                "  %-20s %14s %9s %22s", "Faixa", "Quantidade", "% qtd", "Valor total"));
        for (int f = 0; f < NUMERO_FAIXAS; f++) {
            texto.append(String.format(Formato.BR, "%n  %-20s %14s %9s %22s",
                    NOMES_FAIXAS[f], Formato.inteiro(r.quantidade[f]),
                    Formato.porcentagem(r.quantidade[f] / n, 2), Formato.reais(r.totalCentavos[f])));
        }
        texto.append("\n  Média: ").append(Formato.reais(r.mediaCentavos()))
                .append(" | Desvio padrão: ").append(Formato.reais(r.desvioPadraoCentavos()));
        return texto.toString();
    }
}
