package br.edu.ifsp.transacoes;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.openhft.affinity.Affinity;

/**
 * cria os pools de threads das versões paralelas e confere a afinidade.
 *
 *  - semAfinidade: pool comum; o sistema operacional escolhe (e troca quando quiser) a CPU de
 *    cada thread.
 *  - comAfinidade: a thread número i do pool fica presa na CPU cpus[i] durante a vida inteira.
 *    A fixação é feita DENTRO da própria thread, logo que ela começa, porque a afinidade é uma
 *    propriedade da thread que faz a chamada (sched_setaffinity no Linux,
 *    SetThreadAffinityMask no Windows).
 *
 * O código das análises é o mesmo nos dois casos: só o pool muda.
 */
public final class FabricaExecutores {
    private static volatile long sumidouro; // evita que o JIT elimine o laço de "ocupar"

    private FabricaExecutores() {
    }

    /** Pool com 'threads' threads, sem afinidade. */
    public static ExecutorService semAfinidade(int threads, String nome) {
        AtomicInteger contador = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, laco -> {
            Thread thread = new Thread(laco, nome + "-" + contador.getAndIncrement());
            thread.setDaemon(true); // não impede o programa de terminar
            return thread;
        });
    }

    /** Pool com cpus.length threads; a thread i fica fixada na CPU cpus[i]. */
    public static ExecutorService comAfinidade(int[] cpus, String nome) {
        AtomicInteger contador = new AtomicInteger();
        return Executors.newFixedThreadPool(cpus.length, laco -> {
            int ordem = contador.getAndIncrement();
            return new TrabalhadorFixado(laco, cpus[ordem % cpus.length], nome + "-" + ordem);
        });
    }

    /** Thread do pool que se fixa numa CPU antes de começar a pegar tarefas da fila. */
    static final class TrabalhadorFixado extends Thread {
        final int cpu;
        volatile boolean fixada;
        volatile String erro;

        TrabalhadorFixado(Runnable lacoDoPool, int cpu, String nome) {
            super(lacoDoPool, nome);
            this.cpu = cpu;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                BitSet mascara = new BitSet();
                mascara.set(cpu);
                Affinity.setAffinity(mascara);
                fixada = true;
            } catch (Throwable falha) {
                erro = falha.toString();
            }
            super.run(); // executa o laço normal do pool: pegar tarefas da fila e executá-las
        }
    }

    /** O que a verificação encontrou. */
    public static final class Verificacao {
        /** Todas as threads foram fixadas e rodaram só na CPU pedida. */
        public final boolean ok;
        /** O sistema informou a CPU atual (se false, a fixação não pôde ser confirmada). */
        public final boolean verificavel;
        public final String cpusObservadas;
        public final String detalhe;

        Verificacao(boolean ok, boolean verificavel, String cpusObservadas, String detalhe) {
            this.ok = ok;
            this.verificavel = verificavel;
            this.cpusObservadas = cpusObservadas;
            this.detalhe = detalhe;
        }
    }

    private record Amostra(String thread, int cpuPedida, boolean fixada, String erro, Set<Integer> cpusVistas) {
    }

    /**
     * Confere, perguntando ao SISTEMA OPERACIONAL, em que CPU cada thread do pool roda.
     *
     * Manda uma tarefa por thread. Cada tarefa espera as outras começarem (assim cada thread
     * pega exatamente uma), trabalha um pouco e pergunta ao sistema 20 vezes em que CPU está.
     * Se a afinidade funcionou, a thread só pode aparecer na CPU em que foi fixada.
     */
    public static Verificacao verificar(ExecutorService pool, int threads)
            throws InterruptedException, ExecutionException {
        CountDownLatch todasComecaram = new CountDownLatch(threads);
        List<Callable<Amostra>> tarefas = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tarefas.add(() -> {
                todasComecaram.countDown();
                todasComecaram.await(10, TimeUnit.SECONDS);
                Thread atual = Thread.currentThread();
                if (!(atual instanceof TrabalhadorFixado)) {
                    return new Amostra(atual.getName(), -1, false, "pool sem afinidade", Set.of());
                }
                TrabalhadorFixado trabalhador = (TrabalhadorFixado) atual;
                Set<Integer> vistas = new TreeSet<>();
                for (int k = 0; k < 20; k++) {
                    ocupar(k);
                    vistas.add(TopologiaCpu.cpuAtual());
                }
                return new Amostra(trabalhador.getName(), trabalhador.cpu, trabalhador.fixada, trabalhador.erro, vistas);
            });
        }

        List<Amostra> amostras = new ArrayList<>();
        for (Future<Amostra> futuro : pool.invokeAll(tarefas)) {
            amostras.add(futuro.get());
        }
        amostras.sort((a, b) -> Integer.compare(numeroDaThread(a.thread()), numeroDaThread(b.thread())));

        boolean ok = true;
        boolean verificavel = true;
        StringBuilder observadas = new StringBuilder();
        StringBuilder problemas = new StringBuilder();
        for (Amostra a : amostras) {
            if (observadas.length() > 0) {
                observadas.append(',');
            }
            observadas.append(a.cpusVistas().size() == 1
                    ? String.valueOf(a.cpusVistas().iterator().next())
                    : a.cpusVistas().toString().replace(", ", "|"));
            if (!a.fixada()) {
                ok = false;
                problemas.append(a.thread()).append(" não foi fixada (").append(a.erro()).append("). ");
            } else if (a.cpusVistas().contains(-1)) {
                verificavel = false;
            } else if (!a.cpusVistas().equals(Set.of(a.cpuPedida()))) {
                ok = false;
                problemas.append(a.thread()).append(" pediu a CPU ").append(a.cpuPedida())
                        .append(" mas rodou em ").append(a.cpusVistas()).append(". ");
            }
        }
        String detalhe = problemas.length() > 0 ? problemas.toString().trim()
                : verificavel ? "todas as threads rodaram só na CPU pedida"
                : "o sistema não informou a CPU atual; fixação não confirmada";
        return new Verificacao(ok, verificavel, observadas.toString(), detalhe);
    }

    /** Trabalho inútil, só para a thread ficar ocupada por um instante. */
    private static void ocupar(long semente) {
        long x = semente;
        for (int i = 0; i < 200_000; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
        }
        sumidouro += x;
    }

    private static int numeroDaThread(String nome) {
        try {
            return Integer.parseInt(nome.substring(nome.lastIndexOf('-') + 1));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }
}
