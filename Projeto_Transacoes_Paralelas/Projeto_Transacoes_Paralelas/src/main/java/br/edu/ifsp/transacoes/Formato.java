package br.edu.ifsp.transacoes;

import java.text.NumberFormat;
import java.util.Locale;

/** formatação de números no padrão brasileiro (1.234,56). */
public final class Formato {
    public static final Locale BR = Locale.forLanguageTag("pt-BR");

    private Formato() {
    }

    /** Valor em centavos como moeda: 123456 -> "R$ 1.234,56". */
    public static String reais(double centavos) {
        // O Java usa um espaço "não separável" depois do R$; trocamos por espaço comum para o
        // texto não sair com caractere estranho no console do Windows.
        return NumberFormat.getCurrencyInstance(BR).format(centavos / 100.0).replace(' ', ' ');
    }

    /** Inteiro com separador de milhar: 1000000 -> "1.000.000". */
    public static String inteiro(long numero) {
        return NumberFormat.getIntegerInstance(BR).format(numero);
    }

    /** Número com 'casas' casas decimais e vírgula: 3.14159 -> "3,14". */
    public static String decimal(double numero, int casas) {
        return String.format(BR, "%." + casas + "f", numero);
    }

    /** Fração como porcentagem: 0.1234 -> "12,34%". */
    public static String porcentagem(double fracao, int casas) {
        return decimal(fracao * 100, casas) + "%";
    }

    /** Lista de CPUs: {0, 1, 2} -> "0,1,2". */
    public static String lista(int[] numeros) {
        StringBuilder texto = new StringBuilder();
        for (int i = 0; i < numeros.length; i++) {
            if (i > 0) {
                texto.append(',');
            }
            texto.append(numeros[i]);
        }
        return texto.toString();
    }
}
