package br.edu.ifsp.transacoes;

import java.util.Arrays;

/**
 * Análise 4 da comanda: estatísticas por categoria.
 * Para cada categoria: quantidade, valor total, valor médio, maior e menor transação.
 *
 * Estratégia de paralelização:
 *  - cada tarefa usa os SEUS vetores de 5 posições (uma por categoria), criados dentro dela.
 *    Se todas as threads somassem no mesmo vetor compartilhado, seria preciso sincronizar
 *    (lento) e, mesmo com variáveis atômicas, posições vizinhas na mesma linha de cache fariam
 *    os núcleos invalidarem a cache uns dos outros o tempo todo ("false sharing");
 *  - redução: quantidades e totais somam posição a posição; maior e menor de cada categoria
 *    são comparados. Tudo em centavos inteiros, então o resultado é exato.
 */
public final class AnaliseCategorias implements Analise<AnaliseCategorias.Resultado> {
    private static final int N = Transacoes.CATEGORIAS.length;

    /** Resultado de um bloco (parcial) ou do conjunto inteiro (final). */
    public static final class Resultado {
        final long[] quantidade = new long[N];
        final long[] totalCentavos = new long[N];
        final int[] maiorCentavos = new int[N];
        final int[] menorCentavos = new int[N];

        Resultado() {
            Arrays.fill(maiorCentavos, Integer.MIN_VALUE);
            Arrays.fill(menorCentavos, Integer.MAX_VALUE);
        }

        public double mediaCentavos(int categoria) {
            return quantidade[categoria] == 0 ? 0 : (double) totalCentavos[categoria] / quantidade[categoria];
        }
    }

    @Override
    public String nome() {
        return "categorias";
    }

    @Override
    public String titulo() {
        return "Estatísticas por categoria";
    }

    @Override
    public Resultado processar(Transacoes dados, int inicio, int fim) {
        int[] valores = dados.valorCentavos;
        byte[] categorias = dados.categoria;
        Resultado r = new Resultado(); // vetores exclusivos desta tarefa
        long[] quantidade = r.quantidade;
        long[] total = r.totalCentavos;
        int[] maior = r.maiorCentavos;
        int[] menor = r.menorCentavos;

        for (int i = inicio; i < fim; i++) {
            int c = categorias[i];
            int valor = valores[i];
            quantidade[c]++;
            total[c] += valor;
            if (valor > maior[c]) {
                maior[c] = valor;
            }
            if (valor < menor[c]) {
                menor[c] = valor;
            }
        }
        return r;
    }

    @Override
    public Resultado juntar(Resultado a, Resultado b) {
        Resultado r = new Resultado();
        for (int c = 0; c < N; c++) {
            r.quantidade[c] = a.quantidade[c] + b.quantidade[c];
            r.totalCentavos[c] = a.totalCentavos[c] + b.totalCentavos[c];
            r.maiorCentavos[c] = Math.max(a.maiorCentavos[c], b.maiorCentavos[c]);
            r.menorCentavos[c] = Math.min(a.menorCentavos[c], b.menorCentavos[c]);
        }
        return r;
    }

    @Override
    public boolean mesmoResultado(Resultado a, Resultado b) {
        return Arrays.equals(a.quantidade, b.quantidade)
                && Arrays.equals(a.totalCentavos, b.totalCentavos)
                && Arrays.equals(a.maiorCentavos, b.maiorCentavos)
                && Arrays.equals(a.menorCentavos, b.menorCentavos);
    }

    @Override
    public String resumo(Resultado r) {
        long totalGeral = 0;
        for (long q : r.quantidade) {
            totalGeral += q;
        }
        StringBuilder texto = new StringBuilder(String.format(Formato.BR,
                "  %-12s %14s %8s %22s %13s %15s %12s",
                "Categoria", "Quantidade", "% qtd", "Valor total", "Valor médio", "Maior", "Menor"));
        for (int c = 0; c < N; c++) {
            boolean vazia = r.quantidade[c] == 0;
            texto.append(String.format(Formato.BR, "%n  %-12s %14s %8s %22s %13s %15s %12s",
                    Transacoes.CATEGORIAS[c],
                    Formato.inteiro(r.quantidade[c]),
                    Formato.porcentagem(totalGeral == 0 ? 0 : (double) r.quantidade[c] / totalGeral, 1),
                    Formato.reais(r.totalCentavos[c]),
                    vazia ? "-" : Formato.reais(r.mediaCentavos(c)),
                    vazia ? "-" : Formato.reais(r.maiorCentavos[c]),
                    vazia ? "-" : Formato.reais(r.menorCentavos[c])));
        }
        return texto.toString();
    }
}
