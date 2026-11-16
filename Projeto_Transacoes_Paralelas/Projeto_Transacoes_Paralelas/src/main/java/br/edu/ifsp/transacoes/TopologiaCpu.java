package br.edu.ifsp.transacoes;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.openhft.affinity.Affinity;

/**
 * informações sobre os processadores da máquina.
 *
 * Responde três perguntas:
 *  1. A afinidade funciona neste sistema? (a OpenHFT só fixa threads de verdade no Windows e no
 *     Linux; no macOS ela não altera nada.)
 *  2. Quais CPUs lógicas pertencem ao mesmo núcleo físico? Com hyperthreading (SMT), cada núcleo
 *     físico aparece como 2 CPUs lógicas que DIVIDEM as mesmas unidades de execução. No Windows,
 *     normalmente as CPUs 0 e 1 são o mesmo núcleo; no Linux, é comum ser 0 e 4 (ou 0 e 8).
 *  3. Em que CPU esta thread está rodando AGORA? A pergunta é feita direto ao sistema operacional
 *     e serve para conferir se a afinidade foi mesmo aplicada. (Não dá para usar
 *     Affinity.getAffinity() para isso: no Windows, a OpenHFT devolve a máscara que ela mesma
 *     guardou ao chamar setAffinity, sem consultar o sistema.)
 */
public final class TopologiaCpu {
    private static final String SO = System.getProperty("os.name", "?");
    public static final boolean WINDOWS = SO.toLowerCase(Locale.ROOT).startsWith("windows");
    public static final boolean LINUX = SO.toLowerCase(Locale.ROOT).startsWith("linux");

    private TopologiaCpu() {
    }

    /** Devolve null se a afinidade pode ser usada; senão, o motivo de não poder. */
    public static String motivoSemAfinidade() {
        if (!WINDOWS && !LINUX) {
            return "a OpenHFT só aplica afinidade no Windows e no Linux (sistema atual: " + SO + ")";
        }
        try {
            if (!Affinity.isJNAAvailable()) {
                return "JNA indisponível (biblioteca nativa usada pela OpenHFT)";
            }
            String implementacao = Affinity.getAffinityImpl().getClass().getSimpleName();
            if (implementacao.startsWith("Null")) {
                return "a OpenHFT não carregou a implementação nativa (" + implementacao + ")";
            }
        } catch (Throwable erro) {
            return "OpenHFT indisponível: " + erro;
        }
        return null;
    }

    /** CPUs lógicas que este processo pode usar, em ordem crescente (0, 1, 2, ...). */
    public static int[] cpusPermitidas() {
        try {
            BitSet mascara = Affinity.getAffinity();
            if (mascara != null && !mascara.isEmpty()) {
                return mascara.stream().toArray();
            }
        } catch (Throwable ignorado) {
            // sem OpenHFT/JNA: considera todas as CPUs
        }
        int[] todas = new int[Runtime.getRuntime().availableProcessors()];
        for (int i = 0; i < todas.length; i++) {
            todas[i] = i;
        }
        return todas;
    }

    /** CPU lógica em que a thread atual está rodando agora, segundo o sistema (-1 = desconhecido). */
    public static int cpuAtual() {
        try {
            if (LINUX) {
                return Affinity.getCpu(); // sched_getcpu() do Linux
            }
            if (WINDOWS) {
                return Kernel32Extra.INSTANCE.GetCurrentProcessorNumber();
            }
        } catch (Throwable ignorado) {
            // sistema não informou
        }
        return -1;
    }

    /** Função do Windows que a JNA não traz pronta: em qual processador a thread está agora. */
    private interface Kernel32Extra extends Library {
        Kernel32Extra INSTANCE = Native.load("kernel32", Kernel32Extra.class);

        int GetCurrentProcessorNumber();
    }

    private static String nomeDoProcessador;

    /** Nome comercial do processador (ou "desconhecido"). Lido uma vez e guardado. */
    public static synchronized String nomeDoProcessador() {
        if (nomeDoProcessador == null) {
            nomeDoProcessador = lerNomeDoProcessador();
        }
        return nomeDoProcessador;
    }

    private static String lerNomeDoProcessador() {
        try {
            if (LINUX) {
                for (String linha : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    if (linha.startsWith("model name")) {
                        return linha.substring(linha.indexOf(':') + 1).trim();
                    }
                }
            } else if (WINDOWS) {
                return Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE,
                        "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "ProcessorNameString").trim();
            }
        } catch (Throwable ignorado) {
            // segue para o padrão
        }
        String identificador = System.getenv("PROCESSOR_IDENTIFIER");
        return identificador != null ? identificador : "desconhecido";
    }

    /** Uma CPU lógica por núcleo físico, na ordem em que devem ser usadas. */
    public static final class Nucleos {
        public final int[] cpus;
        public final String descricao;

        Nucleos(int[] cpus, String descricao) {
            this.cpus = cpus;
            this.descricao = descricao;
        }

        /** Lista informada pelo usuário com --nucleos=... */
        public static Nucleos informados(int[] cpus) {
            return new Nucleos(cpus, cpus.length + " núcleos informados com --nucleos");
        }
    }

    /**
     * Descobre os núcleos físicos e escolhe uma CPU lógica de cada um (a de menor número).
     * Em processadores híbridos (núcleos de desempenho P e de eficiência E), os P vêm primeiro.
     * Devolve null se não conseguir descobrir; nesse caso, use --nucleos=0,2,4,6 (por exemplo).
     */
    public static Nucleos detectarNucleos(int[] permitidas) {
        try {
            if (LINUX) {
                return nucleosLinux(permitidas);
            }
            if (WINDOWS) {
                return nucleosWindows(permitidas);
            }
        } catch (Throwable erro) {
            Console.println("Aviso: não foi possível detectar os núcleos físicos (" + erro + ").");
        }
        return null;
    }

    /** Linux: lê a topologia em /sys/devices/system/cpu/cpuN/topology/. */
    private static Nucleos nucleosLinux(int[] permitidas) throws IOException {
        Set<Integer> permitidasSet = paraConjunto(permitidas);
        // Em processadores Intel híbridos, este arquivo lista as CPUs dos núcleos P.
        Set<Integer> nucleosP = new HashSet<>();
        Path arquivoP = Path.of("/sys/devices/cpu_core/cpus");
        if (Files.exists(arquivoP)) {
            nucleosP.addAll(lerLista(Files.readString(arquivoP).trim()));
        }

        TreeSet<Integer> representantes = new TreeSet<>();
        int maiorGrupo = 1;
        for (int cpu : permitidas) {
            Path topologia = Path.of("/sys/devices/system/cpu/cpu" + cpu + "/topology");
            Path irmasArquivo = Files.exists(topologia.resolve("core_cpus_list"))
                    ? topologia.resolve("core_cpus_list")
                    : topologia.resolve("thread_siblings_list");
            List<Integer> irmas = lerLista(Files.readString(irmasArquivo).trim());
            maiorGrupo = Math.max(maiorGrupo, irmas.size());
            int representante = cpu;
            for (int irma : irmas) {
                if (permitidasSet.contains(irma) && irma < representante) {
                    representante = irma;
                }
            }
            representantes.add(representante);
        }

        List<Integer> ordem = new ArrayList<>();
        int quantidadeP = 0;
        for (int cpu : representantes) { // primeiro os núcleos P (se houver distinção)
            if (nucleosP.contains(cpu)) {
                ordem.add(cpu);
                quantidadeP++;
            }
        }
        for (int cpu : representantes) {
            if (!nucleosP.contains(cpu)) {
                ordem.add(cpu);
            }
        }
        String descricao = representantes.size() + " núcleos físicos, " + permitidas.length + " CPUs lógicas"
                + (maiorGrupo > 1 ? " (até " + maiorGrupo + " por núcleo: hyperthreading/SMT)" : " (sem hyperthreading)");
        if (quantidadeP > 0 && quantidadeP < representantes.size()) {
            descricao += "; híbrido: " + quantidadeP + " núcleos P e " + (representantes.size() - quantidadeP) + " núcleos E (P primeiro)";
        }
        return new Nucleos(paraVetor(ordem), descricao);
    }

    /** Windows: pergunta ao sistema (GetLogicalProcessorInformationEx) quais CPUs formam cada núcleo. */
    private static Nucleos nucleosWindows(int[] permitidas) {
        Set<Integer> permitidasSet = paraConjunto(permitidas);
        WinNT.SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX[] itens = Kernel32Util.getLogicalProcessorInformationEx(
                WinNT.LOGICAL_PROCESSOR_RELATIONSHIP.RelationProcessorCore);

        List<int[]> nucleos = new ArrayList<>(); // {cpu representante, classe de eficiência}
        int maiorGrupo = 1;
        Set<Integer> classes = new HashSet<>();
        for (WinNT.SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX item : itens) {
            if (!(item instanceof WinNT.PROCESSOR_RELATIONSHIP) || ((WinNT.PROCESSOR_RELATIONSHIP) item).groupMask == null) {
                continue;
            }
            WinNT.PROCESSOR_RELATIONSHIP nucleo = (WinNT.PROCESSOR_RELATIONSHIP) item;
            for (WinNT.GROUP_AFFINITY grupo : nucleo.groupMask) {
                if (grupo == null || grupo.group != 0) {
                    continue; // só o grupo 0 (computadores com até 64 CPUs lógicas)
                }
                long mascara = grupo.mask.longValue();
                maiorGrupo = Math.max(maiorGrupo, Long.bitCount(mascara));
                for (int cpu = 0; cpu < 64; cpu++) {
                    if (((mascara >>> cpu) & 1L) == 1L && permitidasSet.contains(cpu)) {
                        int classe = nucleo.efficiencyClass & 0xFF; // maior = núcleo de desempenho (P)
                        nucleos.add(new int[] {cpu, classe});
                        classes.add(classe);
                        break;
                    }
                }
            }
        }
        if (nucleos.isEmpty()) {
            return null;
        }
        // Núcleos P (classe maior) primeiro; dentro da mesma classe, pela numeração.
        nucleos.sort((x, y) -> x[1] != y[1] ? Integer.compare(y[1], x[1]) : Integer.compare(x[0], y[0]));
        int[] cpus = new int[nucleos.size()];
        int quantidadeP = 0;
        int classeMaxima = 0;
        for (int[] n : nucleos) {
            classeMaxima = Math.max(classeMaxima, n[1]);
        }
        for (int i = 0; i < cpus.length; i++) {
            cpus[i] = nucleos.get(i)[0];
            if (nucleos.get(i)[1] == classeMaxima) {
                quantidadeP++;
            }
        }
        String descricao = cpus.length + " núcleos físicos, " + permitidas.length + " CPUs lógicas"
                + (maiorGrupo > 1 ? " (até " + maiorGrupo + " por núcleo: hyperthreading)" : " (sem hyperthreading)");
        if (classes.size() > 1) {
            descricao += "; híbrido: " + quantidadeP + " núcleos P e " + (cpus.length - quantidadeP) + " núcleos E (P primeiro)";
        }
        return new Nucleos(cpus, descricao);
    }

    /** Lê listas do Linux como "0-3,8,10-11". */
    static List<Integer> lerLista(String texto) {
        List<Integer> numeros = new ArrayList<>();
        if (texto.isEmpty()) {
            return numeros;
        }
        for (String parte : texto.split(",")) {
            String[] limites = parte.trim().split("-");
            int de = Integer.parseInt(limites[0].trim());
            int ate = limites.length > 1 ? Integer.parseInt(limites[1].trim()) : de;
            for (int n = de; n <= ate; n++) {
                numeros.add(n);
            }
        }
        return numeros;
    }

    private static Set<Integer> paraConjunto(int[] numeros) {
        Set<Integer> conjunto = new HashSet<>();
        for (int n : numeros) {
            conjunto.add(n);
        }
        return conjunto;
    }

    private static int[] paraVetor(List<Integer> numeros) {
        int[] vetor = new int[numeros.size()];
        for (int i = 0; i < vetor.length; i++) {
            vetor[i] = numeros.get(i);
        }
        return vetor;
    }
}
