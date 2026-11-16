package br.edu.ifsp.transacoes;

/*
 *   1. Sequencial ................ processar(dados, 0, n): um laço só, do começo ao fim.
 *   2. ExecutorService ........... o conjunto é dividido em blocos; cada tarefa (Callable) chama
 *                                  processar(dados, inicio, fim) no seu bloco e devolve um
 *                                  resultado PARCIAL; no fim, os parciais são combinados com
 *                                  juntar(...) — a "redução de resultados paralelos".
 *   3. ExecutorService + Affinity  igual à versão 2, mas o pool tem threads fixadas em CPUs.
 *
 * Quem divide os dados, cria as tarefas e chama juntar(...) é o ProcessadorParalelo.
 * Cada integrante implementa: o laço (processar), a redução (juntar), a conferência
 * (mesmoResultado) e a apresentação (resumo) da sua análise.
 *
 * @param <R> tipo do resultado (parcial ou final) desta análise
 */
public interface Analise<R> {

    /** Nome curto, sem acento: usado no CSV e no parâmetro --analises. */
    String nome();

    /** Nome para mostrar na tela. */
    String titulo();

    /**
     * Analisa as transações de índice 'inicio' até 'fim - 1'.
     * Deve acumular em variáveis locais e devolver um resultado novo: nada compartilhado entre
     * threads é alterado, por isso não é preciso sincronização.
     */
    R processar(Transacoes dados, int inicio, int fim);

    /** Redução: combina dois resultados parciais ('a' = bloco anterior, 'b' = bloco seguinte). */
    R juntar(R a, R b);

    /** true se os dois resultados são iguais (usado para conferir paralelo x sequencial). */
    boolean mesmoResultado(R a, R b);

    /** Texto com o resultado, para o console. */
    String resumo(R resultado);
}
