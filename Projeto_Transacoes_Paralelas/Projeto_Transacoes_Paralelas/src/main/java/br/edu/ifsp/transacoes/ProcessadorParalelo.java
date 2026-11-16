package br.edu.ifsp.transacoes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * motor do paralelismo de dados: divide, distribui e combina.
 *
 * Funciona para qualquer Analise. Quem decide se as threads têm ou não afinidade é o pool
 * (ExecutorService) recebido: o código aqui é o mesmo nas duas versões paralelas, então a única
 * diferença entre "Executor" e "Executor + Affinity" no experimento é a afinidade.
 */
public final class ProcessadorParalelo {

    private ProcessadorParalelo() {
    }

    /** Versão sequencial: a análise inteira, numa única thread (a que chamou o método). */
    public static <R> R sequencial(Analise<R> analise, Transacoes dados, int quantidade) {
        return analise.processar(dados, 0, quantidade);
    }

    /**
     * Versão paralela.
     *
     * 1. Divide [0, quantidade) em 'numeroTarefas' blocos contíguos e quase iguais.
     * 2. Cria um Callable por bloco; cada um devolve o resultado PARCIAL do seu bloco.
     * 3. invokeAll entrega as tarefas ao pool e espera todas terminarem.
     * 4. Combina os parciais na ordem dos blocos (redução).
     *
     * O número de tarefas não depende da quantidade de transações: nunca se cria uma thread
     * (nem uma tarefa) por transação. Com 4 tarefas por thread, uma thread que termina antes
     * pega o próximo bloco da fila, o que equilibra a carga se alguma thread ficar mais lenta.
     */
    public static <R> R paralelo(Analise<R> analise, Transacoes dados, int quantidade,
                                 ExecutorService pool, int numeroTarefas)
            throws InterruptedException, ExecutionException {
        if (numeroTarefas < 1) {
            throw new IllegalArgumentException("É preciso pelo menos 1 tarefa");
        }
        List<Callable<R>> tarefas = new ArrayList<>(numeroTarefas);
        for (int t = 0; t < numeroTarefas; t++) {
            int inicio = inicioDoBloco(quantidade, numeroTarefas, t);
            int fim = inicioDoBloco(quantidade, numeroTarefas, t + 1);
            tarefas.add(() -> analise.processar(dados, inicio, fim));
        }

        List<Future<R>> futuros = pool.invokeAll(tarefas);

        // get() devolve o resultado da tarefa (ou repassa a exceção, se ela falhou).
        R total = futuros.get(0).get();
        for (int t = 1; t < futuros.size(); t++) {
            total = analise.juntar(total, futuros.get(t).get());
        }
        return total;
    }

    /**
     * Primeiro índice do bloco t quando [0, n) é dividido em k blocos.
     *
     * Exemplo: n = 10, k = 4 -> blocos [0,2) [2,5) [5,7) [7,10).
     * Multiplicar antes de dividir espalha o resto: os blocos diferem em no máximo 1 transação,
     * e nenhuma transação fica de fora ou é contada duas vezes.
     * A conta é feita em long porque n * t estoura o int (ex.: 500 milhões x 32 tarefas).
     */
    public static int inicioDoBloco(int n, int k, int t) {
        return (int) ((long) n * t / k);
    }
}
