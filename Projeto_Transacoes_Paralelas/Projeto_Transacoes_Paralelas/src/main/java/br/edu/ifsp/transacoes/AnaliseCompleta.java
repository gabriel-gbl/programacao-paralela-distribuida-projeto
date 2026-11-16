package br.edu.ifsp.transacoes;

/**
 * EXPERIMENTO EXTRA — as quatro análises numa ÚNICA passada pelos dados ("fusão de laços").
 *
 * Rodando separadas, as análises percorrem os vetores quatro vezes (uma por análise). Esta
 * versão percorre uma vez só e atualiza os quatro resultados no mesmo laço. Com volumes
 * grandes, o gargalo costuma ser a velocidade da memória, então ler os dados menos vezes pode
 * valer mais do que usar mais threads. Compare o tempo desta análise com a soma dos tempos das
 * quatro separadas.
 *
 * As regras de suspeita e as faixas são as MESMAS das análises separadas (os métodos estáticos
 * regraValorAlto, regraEletronicoCaro e faixa), e a redução reaproveita o juntar(...) de cada
 * uma. O teste (--teste) confere que o resultado bate com o das análises individuais.
 */
public final class AnaliseCompleta implements Analise<AnaliseCompleta.Resultado> {
    private final AnaliseAgregacao agregacao = new AnaliseAgregacao();
    private final AnaliseSuspeitas suspeitas = new AnaliseSuspeitas();
    private final AnaliseCategorias categorias = new AnaliseCategorias();
    private final AnaliseFaixas faixas = new AnaliseFaixas();

    /** Os quatro resultados juntos. */
    public static final class Resultado {
        final AnaliseAgregacao.Resultado agregacao;
        final AnaliseSuspeitas.Resultado suspeitas;
        final AnaliseCategorias.Resultado categorias;
        final AnaliseFaixas.Resultado faixas;

        Resultado(AnaliseAgregacao.Resultado agregacao, AnaliseSuspeitas.Resultado suspeitas,
                  AnaliseCategorias.Resultado categorias, AnaliseFaixas.Resultado faixas) {
            this.agregacao = agregacao;
            this.suspeitas = suspeitas;
            this.categorias = categorias;
            this.faixas = faixas;
        }
    }

    @Override
    public String nome() {
        return "completa";
    }

    @Override
    public String titulo() {
        return "Todas as análises numa única passada (experimento extra)";
    }

    @Override
    public Resultado processar(Transacoes dados, int inicio, int fim) {
        int[] valores = dados.valorCentavos;
        byte[] cats = dados.categoria;

        // Agregação
        long soma = 0;
        int maior = Integer.MIN_VALUE;
        int indiceMaior = -1;
        int menor = Integer.MAX_VALUE;
        int indiceMenor = -1;
        // Suspeitas
        AnaliseSuspeitas.Resultado sus = new AnaliseSuspeitas.Resultado();
        long regra1 = 0;
        long regra2 = 0;
        long qtdSuspeitas = 0;
        long valorSuspeito = 0;
        // Categorias
        AnaliseCategorias.Resultado cat = new AnaliseCategorias.Resultado();
        long[] qtdCat = cat.quantidade;
        long[] totalCat = cat.totalCentavos;
        int[] maiorCat = cat.maiorCentavos;
        int[] menorCat = cat.menorCentavos;
        // Faixas
        AnaliseFaixas.Resultado fx = new AnaliseFaixas.Resultado();
        long[] qtdFaixa = fx.quantidade;
        long[] totalFaixa = fx.totalCentavos;
        double somaQuadrados = 0;

        for (int i = inicio; i < fim; i++) {
            int valor = valores[i];
            byte c = cats[i];

            soma += valor;
            if (valor > maior) {
                maior = valor;
                indiceMaior = i;
            }
            if (valor < menor) {
                menor = valor;
                indiceMenor = i;
            }

            boolean r1 = AnaliseSuspeitas.regraValorAlto(valor);
            boolean r2 = AnaliseSuspeitas.regraEletronicoCaro(valor, c);
            if (r1 || r2) {
                if (r1) {
                    regra1++;
                }
                if (r2) {
                    regra2++;
                }
                qtdSuspeitas++;
                valorSuspeito += valor;
                sus.considerar(i, valor, c);
            }

            qtdCat[c]++;
            totalCat[c] += valor;
            if (valor > maiorCat[c]) {
                maiorCat[c] = valor;
            }
            if (valor < menorCat[c]) {
                menorCat[c] = valor;
            }

            int f = AnaliseFaixas.faixa(valor);
            qtdFaixa[f]++;
            totalFaixa[f] += valor;
            somaQuadrados += (double) valor * valor;
        }

        long n = fim - inicio;
        AnaliseAgregacao.Resultado agr = new AnaliseAgregacao.Resultado();
        agr.quantidade = n;
        agr.somaCentavos = soma;
        agr.maiorCentavos = maior;
        agr.indiceMaior = indiceMaior;
        agr.menorCentavos = menor;
        agr.indiceMenor = indiceMenor;

        sus.quantidadeAnalisada = n;
        sus.regraValorAlto = regra1;
        sus.regraEletronicoCaro = regra2;
        sus.suspeitas = qtdSuspeitas;
        sus.valorSuspeitoCentavos = valorSuspeito;

        fx.n = n;
        fx.somaCentavos = soma;
        fx.somaQuadrados = somaQuadrados;

        return new Resultado(agr, sus, cat, fx);
    }

    @Override
    public Resultado juntar(Resultado a, Resultado b) {
        return new Resultado(
                agregacao.juntar(a.agregacao, b.agregacao),
                suspeitas.juntar(a.suspeitas, b.suspeitas),
                categorias.juntar(a.categorias, b.categorias),
                faixas.juntar(a.faixas, b.faixas));
    }

    @Override
    public boolean mesmoResultado(Resultado a, Resultado b) {
        return agregacao.mesmoResultado(a.agregacao, b.agregacao)
                && suspeitas.mesmoResultado(a.suspeitas, b.suspeitas)
                && categorias.mesmoResultado(a.categorias, b.categorias)
                && faixas.mesmoResultado(a.faixas, b.faixas);
    }

    @Override
    public String resumo(Resultado r) {
        return "  (os mesmos resultados das quatro análises separadas, calculados numa passada só)\n"
                + "  Soma: " + Formato.reais(r.agregacao.somaCentavos)
                + " | Suspeitas: " + Formato.inteiro(r.suspeitas.suspeitas)
                + " | Desvio padrão: " + Formato.reais(r.faixas.desvioPadraoCentavos());
    }
}
