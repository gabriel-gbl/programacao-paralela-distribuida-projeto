package br.edu.ifsp.transacoes;

/**
 * Análises 1 e 2 da comanda: agregação financeira.
 * Calcula quantidade, soma, média, maior e menor transação.
 *
 * Estratégia de paralelização:
 *  - cada tarefa percorre o seu bloco acumulando em VARIÁVEIS LOCAIS. Nada é compartilhado
 *    entre threads durante o laço, então não há sincronização nem disputa por memória;
 *  - no fim, cada tarefa devolve um Resultado parcial, e a redução combina os parciais:
 *      quantidade e soma -> somam;  maior -> o maior dos maiores;  menor -> o menor dos menores.
 *  - a soma é feita em centavos (long), por isso é EXATA: o paralelo dá exatamente o mesmo
 *    valor do sequencial, com qualquer número de threads.
 *
 */
public final class AnaliseAgregacao implements Analise<AnaliseAgregacao.Resultado> {

    /** Resultado de um bloco (parcial) ou do conjunto inteiro (final). */
    public static final class Resultado {
        long quantidade;
        long somaCentavos;
        int maiorCentavos = Integer.MIN_VALUE;
        int indiceMaior = -1; // -1 = bloco vazio
        int menorCentavos = Integer.MAX_VALUE;
        int indiceMenor = -1;

        public double mediaCentavos() {
            return quantidade == 0 ? 0 : (double) somaCentavos / quantidade;
        }
    }

    @Override
    public String nome() {
        return "agregacao";
    }

    @Override
    public String titulo() {
        return "Agregação financeira (soma, média, maior e menor)";
    }

    @Override
    public Resultado processar(Transacoes dados, int inicio, int fim) {
        int[] valores = dados.valorCentavos;
        long soma = 0;
        int maior = Integer.MIN_VALUE;
        int indiceMaior = -1;
        int menor = Integer.MAX_VALUE;
        int indiceMenor = -1;

        for (int i = inicio; i < fim; i++) {
            int valor = valores[i];
            soma += valor;
            // ">" (e não ">=") mantém a PRIMEIRA ocorrência quando há empate.
            if (valor > maior) {
                maior = valor;
                indiceMaior = i;
            }
            if (valor < menor) {
                menor = valor;
                indiceMenor = i;
            }
        }

        Resultado r = new Resultado();
        r.quantidade = fim - inicio;
        r.somaCentavos = soma;
        r.maiorCentavos = maior;
        r.indiceMaior = indiceMaior;
        r.menorCentavos = menor;
        r.indiceMenor = indiceMenor;
        return r;
    }

    @Override
    public Resultado juntar(Resultado a, Resultado b) {
        if (a.quantidade == 0) {
            return b;
        }
        if (b.quantidade == 0) {
            return a;
        }
        // Em caso de empate, fica o menor índice (igual ao sequencial, que guarda a primeira ocorrência).
        boolean maiorVemDeA = a.maiorCentavos > b.maiorCentavos
                || (a.maiorCentavos == b.maiorCentavos && a.indiceMaior < b.indiceMaior);
        boolean menorVemDeA = a.menorCentavos < b.menorCentavos
                || (a.menorCentavos == b.menorCentavos && a.indiceMenor < b.indiceMenor);

        Resultado r = new Resultado();
        r.quantidade = a.quantidade + b.quantidade;
        r.somaCentavos = a.somaCentavos + b.somaCentavos;
        r.maiorCentavos = maiorVemDeA ? a.maiorCentavos : b.maiorCentavos;
        r.indiceMaior = maiorVemDeA ? a.indiceMaior : b.indiceMaior;
        r.menorCentavos = menorVemDeA ? a.menorCentavos : b.menorCentavos;
        r.indiceMenor = menorVemDeA ? a.indiceMenor : b.indiceMenor;
        return r;
    }

    @Override
    public boolean mesmoResultado(Resultado a, Resultado b) {
        return a.quantidade == b.quantidade
                && a.somaCentavos == b.somaCentavos
                && a.maiorCentavos == b.maiorCentavos
                && a.indiceMaior == b.indiceMaior
                && a.menorCentavos == b.menorCentavos
                && a.indiceMenor == b.indiceMenor;
    }

    @Override
    public String resumo(Resultado r) {
        if (r.quantidade == 0) {
            return "  (nenhuma transação)";
        }
        return "  Quantidade: " + Formato.inteiro(r.quantidade) + "\n"
                + "  Soma: " + Formato.reais(r.somaCentavos) + "\n"
                + "  Média: " + Formato.reais(r.mediaCentavos()) + "\n"
                + "  Maior transação: " + Formato.reais(r.maiorCentavos) + " (ID " + (r.indiceMaior + 1L) + ")\n"
                + "  Menor transação: " + Formato.reais(r.menorCentavos) + " (ID " + (r.indiceMenor + 1L) + ")";
    }
}
