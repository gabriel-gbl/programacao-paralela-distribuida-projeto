package br.edu.ifsp.transacoes;

/**
 *Análise 3 da comanda: detecção de transações suspeitas.
 *
 * Critérios definidos pelo grupo:
 *   Regra 1: valor acima de R$ 10.000,00 (qualquer categoria);
 *   Regra 2: categoria Eletrônicos e valor acima de R$ 5.000,00.
 * Uma transação que cumpre as duas regras conta uma vez só no total de suspeitas.
 * Além de contar, a análise IDENTIFICA as 10 maiores suspeitas (ID, valor e categoria).
 *
 * Estratégia de paralelização:
 *  - cada tarefa conta as suspeitas do seu bloco e guarda o "top 10" do bloco;
 *  - redução: contagens e somas se somam; as duas listas top 10 são mescladas e cortadas de
 *    novo em 10. Empates de valor são decididos pelo menor ID, então o resultado não depende
 *    de como os dados foram divididos entre as tarefas.
 *
 * As regras ficam em métodos estáticos para a AnaliseCompleta usar exatamente as mesmas.
 */
public final class AnaliseSuspeitas implements Analise<AnaliseSuspeitas.Resultado> {
    public static final int LIMITE_GERAL_CENTAVOS = 1_000_000;     // R$ 10.000,00
    public static final int LIMITE_ELETRONICOS_CENTAVOS = 500_000; // R$ 5.000,00
    public static final int TAMANHO_TOP = 10;

    /** Regra 1: valor acima de R$ 10.000,00. */
    public static boolean regraValorAlto(int valorCentavos) {
        return valorCentavos > LIMITE_GERAL_CENTAVOS;
    }

    /** Regra 2: Eletrônicos acima de R$ 5.000,00. */
    public static boolean regraEletronicoCaro(int valorCentavos, int categoria) {
        return categoria == Transacoes.ELETRONICOS && valorCentavos > LIMITE_ELETRONICOS_CENTAVOS;
    }

    /** Resultado de um bloco (parcial) ou do conjunto inteiro (final). */
    public static final class Resultado {
        long quantidadeAnalisada;
        long regraValorAlto;
        long regraEletronicoCaro;
        long suspeitas;            // sem contar duas vezes quem cumpre as duas regras
        long valorSuspeitoCentavos;

        // Top 10, do maior para o menor valor (empate -> menor índice primeiro).
        final int[] topIndice = new int[TAMANHO_TOP];
        final int[] topValor = new int[TAMANHO_TOP];
        final byte[] topCategoria = new byte[TAMANHO_TOP];
        int topQuantidade;

        /** Coloca a transação no top 10, se ela for grande o bastante. */
        void considerar(int indice, int valor, byte categoria) {
            int posicao = topQuantidade;
            while (posicao > 0 && vemAntes(valor, indice, topValor[posicao - 1], topIndice[posicao - 1])) {
                posicao--;
            }
            if (posicao >= TAMANHO_TOP) {
                return; // menor que todas as 10 que já estão na lista
            }
            // Empurra para a direita quem está depois da posição (o último sai se a lista estiver cheia).
            int ultimo = Math.min(topQuantidade, TAMANHO_TOP - 1);
            for (int k = ultimo; k > posicao; k--) {
                topIndice[k] = topIndice[k - 1];
                topValor[k] = topValor[k - 1];
                topCategoria[k] = topCategoria[k - 1];
            }
            topIndice[posicao] = indice;
            topValor[posicao] = valor;
            topCategoria[posicao] = categoria;
            if (topQuantidade < TAMANHO_TOP) {
                topQuantidade++;
            }
        }

        private static boolean vemAntes(int valor1, int indice1, int valor2, int indice2) {
            return valor1 > valor2 || (valor1 == valor2 && indice1 < indice2);
        }
    }

    @Override
    public String nome() {
        return "suspeitas";
    }

    @Override
    public String titulo() {
        return "Detecção de transações suspeitas";
    }

    @Override
    public Resultado processar(Transacoes dados, int inicio, int fim) {
        int[] valores = dados.valorCentavos;
        byte[] categorias = dados.categoria;
        Resultado r = new Resultado();
        long regra1 = 0;
        long regra2 = 0;
        long suspeitas = 0;
        long valorSuspeito = 0;

        for (int i = inicio; i < fim; i++) {
            int valor = valores[i];
            byte categoria = categorias[i];
            boolean r1 = regraValorAlto(valor);
            boolean r2 = regraEletronicoCaro(valor, categoria);
            if (r1 || r2) { // raro: menos de 1% das transações entra aqui
                if (r1) {
                    regra1++;
                }
                if (r2) {
                    regra2++;
                }
                suspeitas++;
                valorSuspeito += valor;
                r.considerar(i, valor, categoria);
            }
        }

        r.quantidadeAnalisada = fim - inicio;
        r.regraValorAlto = regra1;
        r.regraEletronicoCaro = regra2;
        r.suspeitas = suspeitas;
        r.valorSuspeitoCentavos = valorSuspeito;
        return r;
    }

    @Override
    public Resultado juntar(Resultado a, Resultado b) {
        Resultado r = new Resultado();
        r.quantidadeAnalisada = a.quantidadeAnalisada + b.quantidadeAnalisada;
        r.regraValorAlto = a.regraValorAlto + b.regraValorAlto;
        r.regraEletronicoCaro = a.regraEletronicoCaro + b.regraEletronicoCaro;
        r.suspeitas = a.suspeitas + b.suspeitas;
        r.valorSuspeitoCentavos = a.valorSuspeitoCentavos + b.valorSuspeitoCentavos;
        for (int k = 0; k < a.topQuantidade; k++) {
            r.considerar(a.topIndice[k], a.topValor[k], a.topCategoria[k]);
        }
        for (int k = 0; k < b.topQuantidade; k++) {
            r.considerar(b.topIndice[k], b.topValor[k], b.topCategoria[k]);
        }
        return r;
    }

    @Override
    public boolean mesmoResultado(Resultado a, Resultado b) {
        if (a.quantidadeAnalisada != b.quantidadeAnalisada
                || a.regraValorAlto != b.regraValorAlto
                || a.regraEletronicoCaro != b.regraEletronicoCaro
                || a.suspeitas != b.suspeitas
                || a.valorSuspeitoCentavos != b.valorSuspeitoCentavos
                || a.topQuantidade != b.topQuantidade) {
            return false;
        }
        for (int k = 0; k < a.topQuantidade; k++) {
            if (a.topIndice[k] != b.topIndice[k] || a.topValor[k] != b.topValor[k]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String resumo(Resultado r) {
        double total = Math.max(1, r.quantidadeAnalisada);
        StringBuilder texto = new StringBuilder();
        texto.append("  Regra 1 (valor > R$ 10.000): ").append(Formato.inteiro(r.regraValorAlto))
                .append(" (").append(Formato.porcentagem(r.regraValorAlto / total, 3)).append(")\n");
        texto.append("  Regra 2 (Eletrônicos > R$ 5.000): ").append(Formato.inteiro(r.regraEletronicoCaro))
                .append(" (").append(Formato.porcentagem(r.regraEletronicoCaro / total, 3)).append(")\n");
        texto.append("  Suspeitas (sem repetir): ").append(Formato.inteiro(r.suspeitas))
                .append(" (").append(Formato.porcentagem(r.suspeitas / total, 3)).append(")")
                .append(" | valor total: ").append(Formato.reais(r.valorSuspeitoCentavos)).append('\n');
        texto.append("  ").append(r.topQuantidade).append(" maiores suspeitas:");
        for (int k = 0; k < r.topQuantidade; k++) {
            texto.append(String.format(Formato.BR, "%n    %2d. ID %-12s %16s  %s", k + 1,
                    Formato.inteiro(r.topIndice[k] + 1L), Formato.reais(r.topValor[k]),
                    Transacoes.CATEGORIAS[r.topCategoria[k]]));
        }
        return texto.toString();
    }
}
