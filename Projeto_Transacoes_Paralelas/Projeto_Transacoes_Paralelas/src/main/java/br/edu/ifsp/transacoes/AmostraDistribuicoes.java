package br.edu.ifsp.transacoes;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/**
 * Exporta o histograma dos valores de cada categoria, usado no gráfico
 * 00_distribuicoes.png (mostra as cinco distribuições estatísticas do gerador).
 *
 * Usa o mesmo gerador e a mesma semente do benchmark. Como a geração é determinística, a amostra
 * é exatamente o começo dos dados usados nas medições.
 *
 * Faixas de R$ 5 até R$ 4.000; o que passa disso vai para a última linha ("cauda"). O script de
 * gráficos junta faixas vizinhas quando a categoria se espalha por valores maiores.
 */
public final class AmostraDistribuicoes {
    static final int LARGURA_CENTAVOS = 500;    // R$ 5
    static final int LIMITE_CENTAVOS = 400_000; // R$ 4.000

    private AmostraDistribuicoes() {
    }

    public static void exportar(int quantidade, long semente, Path arquivo) throws Exception {
        ExecutorService pool = FabricaExecutores.semAfinidade(Runtime.getRuntime().availableProcessors(), "amostra");
        Transacoes dados;
        try {
            dados = GeradorTransacoes.gerar(quantidade, semente, pool);
        } finally {
            pool.shutdownNow();
        }

        int faixas = LIMITE_CENTAVOS / LARGURA_CENTAVOS; // 800 faixas + 1 de cauda
        long[][] contagem = new long[Transacoes.CATEGORIAS.length][faixas + 1];
        for (int i = 0; i < dados.quantidade; i++) {
            int valor = dados.valorCentavos[i];
            int faixa = valor >= LIMITE_CENTAVOS ? faixas : valor / LARGURA_CENTAVOS;
            contagem[dados.categoria[i]][faixa]++;
        }

        Path pasta = arquivo.toAbsolutePath().getParent();
        if (pasta != null) {
            Files.createDirectories(pasta);
        }
        try (BufferedWriter saida = Files.newBufferedWriter(arquivo, StandardCharsets.UTF_8)) {
            saida.write('﻿'); // BOM: o Excel reconhece os acentos
            saida.write("categoria;distribuicao;inicio_reais;fim_reais;quantidade\n");
            for (int c = 0; c < contagem.length; c++) {
                for (int f = 0; f <= faixas; f++) {
                    String fim = f == faixas ? "" : String.valueOf((f + 1) * LARGURA_CENTAVOS / 100);
                    saida.write(Transacoes.CATEGORIAS[c] + ";" + GeradorTransacoes.DISTRIBUICAO_CATEGORIA[c] + ";"
                            + (f * LARGURA_CENTAVOS / 100) + ";" + fim + ";" + contagem[c][f] + "\n");
                }
            }
        }
        Console.println("Histograma de " + Formato.inteiro(quantidade) + " transações salvo em "
                + arquivo.toAbsolutePath());
    }
}
