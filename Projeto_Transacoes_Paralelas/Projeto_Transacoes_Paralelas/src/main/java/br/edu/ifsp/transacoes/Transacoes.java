package br.edu.ifsp.transacoes;

/**
 * o conjunto de transações, guardado por colunas (structure of arrays).
 *
 * Em vez de criar um objeto para cada transação (cada objeto Java gasta uns 16 bytes só de
 * cabeçalho, mais a referência e os campos), guardamos UM VETOR POR CAMPO:
 *
 *   valorCentavos[i] -> valor da transação i, em centavos (int = 4 bytes)
 *   categoria[i]     -> categoria da transação i, de 0 a 4 (byte = 1 byte)
 *
 * São 5 bytes por transação: 500 milhões ocupam cerca de 2,3 GB. O ID da transação é a
 * própria posição no vetor (na tela mostramos ID = índice + 1).
 *
 * Vantagens para o desempenho: os valores ficam um ao lado do outro na memória, então a leitura
 * é sequencial e aproveita bem a cache e a pré-busca (prefetch) do processador; e cada análise
 * lê só os vetores de que precisa (a agregação, por exemplo, nem toca no vetor de categorias).
 *
 * Valor em centavos (inteiro) e não em double: somas de inteiros são exatas, então o resultado
 * paralelo é idêntico ao sequencial. O int cabe até R$ 21 milhões por transação, bem acima do
 * maior valor gerado.
 */
public final class Transacoes {
    public static final int ALIMENTACAO = 0;
    public static final int TRANSPORTE = 1;
    public static final int ELETRONICOS = 2;
    public static final int SAUDE = 3;
    public static final int EDUCACAO = 4;
    public static final String[] CATEGORIAS = {"Alimentação", "Transporte", "Eletrônicos", "Saúde", "Educação"};

    /** 4 bytes do valor + 1 byte da categoria. */
    public static final int BYTES_POR_TRANSACAO = Integer.BYTES + Byte.BYTES;

    public final int quantidade;
    public final int[] valorCentavos;
    public final byte[] categoria;

    public Transacoes(int[] valorCentavos, byte[] categoria) {
        if (valorCentavos.length != categoria.length) {
            throw new IllegalArgumentException("Os vetores de valor e categoria precisam ter o mesmo tamanho");
        }
        this.quantidade = valorCentavos.length;
        this.valorCentavos = valorCentavos;
        this.categoria = categoria;
    }

    /** Memória aproximada, em bytes, para guardar n transações. */
    public static long memoriaNecessaria(long n) {
        return n * BYTES_POR_TRANSACAO;
    }
}
