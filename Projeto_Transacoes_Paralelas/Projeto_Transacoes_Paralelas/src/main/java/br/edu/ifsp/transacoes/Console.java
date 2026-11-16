package br.edu.ifsp.transacoes;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
    saída completa de cada execução fica guardada em resultados/ para o relatório.
 */
public final class Console {
    private static BufferedWriter arquivo;

    private Console() {
    }

    public static void abrirArquivo(Path caminho) throws IOException {
        Path pasta = caminho.toAbsolutePath().getParent();
        if (pasta != null) {
            Files.createDirectories(pasta);
        }
        arquivo = Files.newBufferedWriter(caminho, StandardCharsets.UTF_8);
    }

    public static void println(String texto) {
        System.out.println(texto);
        if (arquivo != null) {
            try {
                arquivo.write(texto);
                arquivo.newLine();
                arquivo.flush();
            } catch (IOException erro) {
                throw new UncheckedIOException(erro);
            }
        }
    }

    public static void println() {
        println("");
    }

    /** printf com números no padrão brasileiro. Use %n para quebrar a linha. */
    public static void printf(String formato, Object... valores) {
        String texto = String.format(Formato.BR, formato, valores);
        if (texto.endsWith(System.lineSeparator())) {
            texto = texto.substring(0, texto.length() - System.lineSeparator().length());
        } else if (texto.endsWith("\n")) {
            texto = texto.substring(0, texto.length() - 1);
        }
        println(texto);
    }

    public static void fechar() throws IOException {
        if (arquivo != null) {
            arquivo.close();
            arquivo = null;
        }
    }
}
