"""Gera os gráficos e as tabelas do relatório a partir dos CSVs criados pelo Java.

Uso:  py gerar_graficos.py                                  (Windows)
      python3 gerar_graficos.py                             (Linux)
      py gerar_graficos.py resultados/tarefas/tarefas.csv graficos_tarefas
          (outro arquivo de resumo e outra pasta para as imagens)
      py gerar_graficos.py --relatorio
          (versão para o relatório em A4: letras maiores em relação à figura e sem título na
           imagem, porque no relatório o título vai na legenda "Figura N - ...")

Lê resultados/benchmark.csv e usa SÓ as medições com status OK (as configurações que não puderam
ser medidas, como afinidade indisponível, não viram números inventados). Gera em graficos/:

  00_distribuicoes.png            histograma dos valores de cada categoria (se existir
                                  resultados/distribuicoes.csv, criado com --distribuicoes)
  01_tempo_por_threads.png        tempo (mediana, com barras de mínimo a máximo) x threads, maior volume
  02_speedup_por_threads.png      speedup x threads, com a linha do speedup ideal, maior volume
  03_eficiencia_por_threads.png   eficiência x threads, maior volume
  04_com_e_sem_afinidade.png      tempos lado a lado: Executor x Executor + Affinity (questão central)
  05_speedup_por_volume.png       speedup x tamanho do conjunto de dados (escalabilidade)
  06_tarefas_por_thread.png       tempo relativo x tarefas por thread (se mais de uma foi medida)
  07_uma_passada.png              4 análises separadas x todas numa passada só (se houver a "completa")
  08_variabilidade.png            speedup de CADA repetição (lê o arquivo _bruto.csv)

e a tabela tabela_maior_volume.csv (para colar no relatório), na pasta do CSV.
"""
import csv
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # gera arquivos de imagem sem precisar abrir janela
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.ticker import FixedLocator, FuncFormatter  # noqa: E402

BASE = Path(__file__).resolve().parent
RELATORIO = "--relatorio" in sys.argv
ARGUMENTOS = [a for a in sys.argv[1:] if not a.startswith("--")]
FONTE = Path(ARGUMENTOS[0]) if ARGUMENTOS else BASE / "resultados" / "benchmark.csv"
PASTA = Path(ARGUMENTOS[1]) if len(ARGUMENTOS) > 1 else BASE / ("graficos_relatorio" if RELATORIO else "graficos")
BRUTO = FONTE.with_name(FONTE.stem + "_bruto.csv")
DISTRIBUICOES = FONTE.with_name("distribuicoes.csv")

# ---------------------------------------------------------------- aparência
# Tela: figuras grandes, com título. Relatório: figura com a largura da página A4 (16 cm), letras
# legíveis impressas e sem título (o título vai na legenda da figura, como pede a ABNT).
if RELATORIO:
    PAINEL_L, PAINEL_A, DPI = 2.2, 1.95, 300
    LETRA, LETRA_TITULO, MARCADOR, LINHA, PONTO = 7, 8, 4.5, 1.4, 9
else:
    PAINEL_L, PAINEL_A, DPI = 4.6, 3.5, 160
    LETRA, LETRA_TITULO, MARCADOR, LINHA, PONTO = 10, 11, 8, 2, 22

# Cores fixas por MODO: a mesma cor significa sempre o mesmo modo, em todos os gráficos.
SUPERFICIE = "#fcfcfb"
TINTA = "#0b0b0b"
TINTA_2 = "#52514e"
APAGADO = "#898781"
GRADE = "#e1e0d9"
EIXO = "#c3c2b7"
COR_MODO = {
    "Executor": "#2a78d6",                  # azul
    "Executor+Affinity": "#eb6834",         # laranja
    "Executor+Affinity-nucleo": "#1baf7a",  # verde-água
}
NOME_MODO = {
    "Executor": "ExecutorService",
    "Executor+Affinity": "Executor + Affinity (CPUs em sequência)" if RELATORIO else "Executor + Affinity",
    "Executor+Affinity-nucleo": "Executor + Affinity (1 por núcleo físico)",
}
MODOS = list(COR_MODO)
# Rampa de um só tom (claro -> escuro) para coisas ORDENADAS: quantidade de threads ou de tarefas.
RAMPA = ["#86b6ef", "#3987e5", "#1c5cab", "#0d366b"]
NOME_ANALISE = {
    "agregacao": "Agregação",
    "suspeitas": "Suspeitas",
    "categorias": "Categorias",
    "faixas": "Faixas e desvio padrão",
    "completa": "Completa (1 passada)",
}

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.size": LETRA,
    "figure.facecolor": SUPERFICIE,
    "axes.facecolor": SUPERFICIE,
    "savefig.facecolor": SUPERFICIE,
    "axes.edgecolor": EIXO,
    "axes.linewidth": 0.8 if RELATORIO else 1,
    "axes.labelcolor": TINTA_2,
    "axes.titlecolor": TINTA,
    "axes.titlesize": LETRA_TITULO,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "xtick.color": APAGADO,
    "ytick.color": APAGADO,
    "xtick.labelcolor": TINTA_2,
    "ytick.labelcolor": TINTA_2,
    "legend.frameon": False,
    "legend.labelcolor": TINTA_2,
    "legend.fontsize": LETRA,
    "lines.linewidth": LINHA,
    "lines.solid_capstyle": "round",
    "lines.solid_joinstyle": "round",
    "text.parse_math": False,  # "R$ ... R$" não vira fórmula matemática
})


def numero(texto):
    return float(texto.replace(".", "").replace(",", ".")) if texto else None


def numero_bruto(texto):
    """O CSV bruto usa vírgula decimal e não tem separador de milhar."""
    return float(texto.replace(",", "."))


def br(valor, casas=1):
    """1234.5 -> '1.234,5' (padrão brasileiro)."""
    texto = f"{valor:,.{casas}f}"
    return texto.replace(",", "X").replace(".", ",").replace("X", ".")


def br_eixo(valor, _posicao=None):
    """Rótulo de eixo: 0 -> '0', 1.25 -> '1,25', 1.5 -> '1,5', 2500 -> '2.500'."""
    if abs(valor) >= 10:
        return br(valor, 0)
    texto = f"{valor:.2f}".rstrip("0").rstrip(".")
    return "0" if texto in ("-0", "") else texto.replace(".", ",")


def volume_curto(v):
    if v >= 1_000_000:
        return f"{br(v / 1_000_000, 0 if v % 1_000_000 == 0 else 1)} mi"
    if v >= 1_000:
        return f"{br(v / 1_000, 0)} mil"
    return str(v)


def ler_csv():
    if not FONTE.exists():
        raise SystemExit(f"Arquivo não encontrado: {FONTE}\nRode o benchmark em Java antes de gerar os gráficos.")
    with FONTE.open(encoding="utf-8-sig", newline="") as arquivo:
        linhas = list(csv.DictReader(arquivo, delimiter=";"))
    validas = []
    for linha in linhas:
        if linha["status"] != "OK" or not linha["mediana_ms"]:
            continue
        validas.append({
            "volume": int(linha["volume"]),
            "analise": linha["analise"],
            "modo": linha["configuracao"],
            "threads": int(linha["threads"]),
            "k": int(linha["tarefas_por_thread"]),
            "mediana": numero(linha["mediana_ms"]),
            "minimo": numero(linha["minimo_ms"]),
            "maximo": numero(linha["maximo_ms"]),
            "speedup": numero(linha["speedup"]),
            "eficiencia": numero(linha["eficiencia_percentual"]),
        })
    if not validas:
        raise SystemExit("Não há medições com status OK no CSV.")
    return validas


DADOS = ler_csv()
ANALISES = [a for a in NOME_ANALISE if any(d["analise"] == a for d in DADOS)]
ANALISES += sorted({d["analise"] for d in DADOS} - set(ANALISES))
VOLUMES = sorted({d["volume"] for d in DADOS})
MAIOR = VOLUMES[-1]
VALORES_K = sorted({d["k"] for d in DADOS if d["modo"] != "Sequencial"})
K = 4 if 4 in VALORES_K else (VALORES_K[-1] if VALORES_K else 1)  # tarefas por thread "principal"
THREADS = sorted({d["threads"] for d in DADOS if d["modo"] != "Sequencial"})
PASTA.mkdir(parents=True, exist_ok=True)


def filtrar(**criterios):
    return [d for d in DADOS if all(d[c] == v for c, v in criterios.items())]


def serie(analise, modo, volume=MAIOR, k=K):
    return sorted(filtrar(analise=analise, modo=modo, volume=volume, k=k), key=lambda d: d["threads"])


def sequencial(analise, volume=MAIOR):
    s = filtrar(analise=analise, modo="Sequencial", volume=volume)
    return s[0] if s else None


def painel(n):
    """Uma figura com um painel por análise e sempre um espaço sobrando para a legenda."""
    colunas = min(3, n + 1)
    linhas = (n + 1 + colunas - 1) // colunas
    sobra = linhas * colunas - n
    extra = 0 if RELATORIO else 0.8  # espaço do título (só na versão de tela)
    fig, eixos = plt.subplots(linhas, colunas, figsize=(PAINEL_L * colunas, PAINEL_A * linhas + extra), squeeze=False)
    eixos = [e for linha in eixos for e in linha]
    for e in eixos[n:]:
        e.axis("off")
    return fig, eixos[:n], (eixos[n] if sobra else None)


def estilo(ax, titulo, ylabel=None, xlabel="Threads", eixo_threads=True):
    ax.set_title(titulo, loc="left", pad=6 if RELATORIO else 8)
    ax.grid(axis="y", color=GRADE, linewidth=0.6 if RELATORIO else 0.8)
    ax.set_axisbelow(True)
    if ylabel:
        ax.set_ylabel(ylabel)
    if xlabel:
        ax.set_xlabel(xlabel)
    if eixo_threads and THREADS:
        ax.xaxis.set_major_locator(FixedLocator(THREADS))
        ax.xaxis.set_major_formatter(FuncFormatter(lambda x, _: f"{int(x)}"))
    ax.yaxis.set_major_formatter(FuncFormatter(br_eixo))


def linha_modo(ax, xs, ys, modo, rotulo=None):
    ax.plot(xs, ys, color=COR_MODO[modo], marker="o", markersize=MARCADOR,
            markeredgecolor=SUPERFICIE, markeredgewidth=1.2 if RELATORIO else 2,
            label=rotulo or NOME_MODO[modo], zorder=3)


def tracejada(ax, y, rotulo):
    ax.axhline(y, color=APAGADO, linewidth=LINHA * 0.75, linestyle=(0, (4, 3)), label=rotulo, zorder=1)


def legenda(fig, area, handles, rotulos, titulo=None):
    alvo = area if area is not None else fig
    kwargs = {"loc": "center"} if area is not None else {"loc": "lower center", "ncol": len(rotulos)}
    leg = alvo.legend(handles, rotulos, title=titulo, **kwargs)
    if titulo:
        leg.get_title().set_color(TINTA_2)


def salvar(fig, nome, titulo, subtitulo):
    if RELATORIO:
        fig.tight_layout(pad=0.4)
    else:
        altura = fig.get_figheight()  # posições em polegadas a partir do topo, para qualquer altura
        fig.suptitle(titulo, x=0.01, ha="left", va="top", fontsize=13, color=TINTA, y=1 - 0.12 / altura)
        fig.text(0.01, 1 - 0.42 / altura, subtitulo, ha="left", va="top", fontsize=9.5, color=TINTA_2)
        fig.tight_layout(rect=(0, 0, 1, 1 - 0.75 / altura))
    fig.savefig(PASTA / nome, dpi=DPI)
    plt.close(fig)
    print(f"  {PASTA.name}/{nome}")


def coletar_legenda(eixos):
    handles, rotulos = [], []
    for ax in eixos:
        for h, r in zip(*ax.get_legend_handles_labels()):
            if r not in rotulos:
                handles.append(h)
                rotulos.append(r)
    return handles, rotulos


SUB = f"{volume_curto(MAIOR)} de transações | {K} tarefas por thread | mediana das repetições"

# ---------------------------------------------------------------- 00 distribuições
if DISTRIBUICOES.exists():
    with DISTRIBUICOES.open(encoding="utf-8-sig", newline="") as arquivo:
        faixas = list(csv.DictReader(arquivo, delimiter=";"))
    categorias = list(dict.fromkeys(f["categoria"] for f in faixas))
    # Escala de cada painel: as categorias de valores baixos ganham um zoom (até R$ 300, faixas de
    # R$ 5); as demais vão até R$ 4.000, com faixas de R$ 20 (4 faixas do CSV somadas).
    ZOOM = {"Alimentação": 300, "Transporte": 300}
    fig, eixos, area = painel(len(categorias))
    total_amostra = 0
    cauda_eletronicos = 0.0
    for ax, categoria in zip(eixos, categorias):
        linhas = [f for f in faixas if f["categoria"] == categoria]
        total = sum(int(f["quantidade"]) for f in linhas)
        total_amostra += total
        normais = [f for f in linhas if f["fim_reais"]]
        cauda = total - sum(int(f["quantidade"]) for f in normais)
        if categoria.startswith("Eletr"):
            cauda_eletronicos = 100 * cauda / total
        limite = ZOOM.get(categoria, 4000)
        largura_csv = int(normais[0]["fim_reais"]) - int(normais[0]["inicio_reais"])
        juntar = 1 if categoria in ZOOM else max(1, 20 // largura_csv)
        inicios, alturas = [], []
        for n in range(0, len(normais), juntar):
            grupo = normais[n:n + juntar]
            if int(grupo[0]["inicio_reais"]) >= limite:
                break
            inicios.append(int(grupo[0]["inicio_reais"]))
            alturas.append(100 * sum(int(f["quantidade"]) for f in grupo) / total)
        ax.bar(inicios, alturas, width=largura_csv * juntar, align="edge", color=COR_MODO["Executor"],
               linewidth=0, zorder=2)
        ax.set_xlim(0, limite)
        marcas = [0, 100, 200, 300] if categoria in ZOOM else [0, 1000, 2000, 3000, 4000]
        ax.xaxis.set_major_locator(FixedLocator(marcas))
        ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: br(v, 0)))
        estilo(ax, f"{categoria}: {linhas[0]['distribuicao']}",
               f"% por faixa de R$ {largura_csv * juntar}", xlabel="Valor (R$)", eixo_threads=False)
    if area is not None:
        area.text(0.0, 0.5,
                  f"Amostra: as {br(total_amostra / 1e6, 0)} milhões primeiras\n"
                  "transações (mesma semente do\n"
                  "benchmark).\n\n"
                  "Fora da escala: "
                  f"{br(cauda_eletronicos, 1)}% de\n"
                  "Eletrônicos fica acima de R$ 4 mil\n"
                  "(cauda da log-normal), e as\n"
                  "anomalias (0,02% de cada\n"
                  "categoria) ficam entre R$ 8 mil\n"
                  "e R$ 60 mil.",
                  ha="left", va="center", color=TINTA_2, fontsize=LETRA, linespacing=1.45)
    salvar(fig, "00_distribuicoes.png", "Distribuição dos valores em cada categoria",
           "Cinco distribuições estatísticas diferentes (a comanda pede no mínimo quatro)")

# ---------------------------------------------------------------- 01 tempo
fig, eixos, area = painel(len(ANALISES))
for ax, analise in zip(eixos, ANALISES):
    for modo in MODOS:
        s = serie(analise, modo)
        if s:
            xs = [d["threads"] for d in s]
            ys = [d["mediana"] for d in s]
            erro = [[y - d["minimo"] for y, d in zip(ys, s)], [d["maximo"] - y for y, d in zip(ys, s)]]
            ax.errorbar(xs, ys, yerr=erro, fmt="none", ecolor=COR_MODO[modo], elinewidth=0.8 if RELATORIO else 1,
                        capsize=2 if RELATORIO else 3, alpha=0.6, zorder=2)
            linha_modo(ax, xs, ys, modo)
    seq = sequencial(analise)
    if seq:
        tracejada(ax, seq["mediana"], "Sequencial")
    ax.set_ylim(bottom=0)
    estilo(ax, NOME_ANALISE.get(analise, analise), "Tempo (ms)")
handles, rotulos = coletar_legenda(eixos)
legenda(fig, area, handles, rotulos)
salvar(fig, "01_tempo_por_threads.png", "Tempo de processamento por quantidade de threads",
       SUB + " | barras: menor e maior tempo")

# ---------------------------------------------------------------- 02 speedup
fig, eixos, area = painel(len(ANALISES))
for ax, analise in zip(eixos, ANALISES):
    if THREADS:
        ax.plot(THREADS, THREADS, color=APAGADO, linewidth=LINHA * 0.75, linestyle=(0, (4, 3)),
                label="Speedup ideal", zorder=1)
    for modo in MODOS:
        s = serie(analise, modo)
        if s:
            linha_modo(ax, [d["threads"] for d in s], [d["speedup"] for d in s], modo)
    ax.set_ylim(bottom=0)
    estilo(ax, NOME_ANALISE.get(analise, analise), "Speedup (vezes)")
handles, rotulos = coletar_legenda(eixos)
legenda(fig, area, handles, rotulos)
salvar(fig, "02_speedup_por_threads.png", "Speedup = tempo sequencial / tempo paralelo", SUB)

# ---------------------------------------------------------------- 03 eficiência
fig, eixos, area = painel(len(ANALISES))
for ax, analise in zip(eixos, ANALISES):
    tracejada(ax, 100, "Eficiência ideal (100%)")
    for modo in MODOS:
        s = serie(analise, modo)
        if s:
            linha_modo(ax, [d["threads"] for d in s], [d["eficiencia"] for d in s], modo)
    ax.set_ylim(0, max(110, ax.get_ylim()[1]))
    estilo(ax, NOME_ANALISE.get(analise, analise), "Eficiência (%)")
handles, rotulos = coletar_legenda(eixos)
legenda(fig, area, handles, rotulos)
salvar(fig, "03_eficiencia_por_threads.png", "Eficiência = speedup / número de threads", SUB)

# ---------------------------------------------------------------- 04 com x sem afinidade
modos_presentes = [m for m in MODOS if filtrar(modo=m, volume=MAIOR, k=K)]
if len(modos_presentes) > 1:
    fig, eixos, area = painel(len(ANALISES))
    largura = 0.6 / len(modos_presentes)  # barras finas; o resto da faixa fica como respiro
    for ax, analise in zip(eixos, ANALISES):
        for i, modo in enumerate(modos_presentes):
            s = {d["threads"]: d for d in serie(analise, modo)}
            xs = [j + (i - (len(modos_presentes) - 1) / 2) * largura for j, t in enumerate(THREADS) if t in s]
            ys = [s[t]["mediana"] for t in THREADS if t in s]
            erro = [[s[t]["mediana"] - s[t]["minimo"] for t in THREADS if t in s],
                    [s[t]["maximo"] - s[t]["mediana"] for t in THREADS if t in s]]
            ax.bar(xs, ys, width=largura * 0.92, color=COR_MODO[modo], label=NOME_MODO[modo], zorder=2,
                   yerr=erro, error_kw={"ecolor": TINTA_2, "elinewidth": 0.7 if RELATORIO else 1,
                                        "capsize": 1.5 if RELATORIO else 2.5})
        ax.set_xticks(range(len(THREADS)))
        ax.set_xticklabels([str(t) for t in THREADS])
        estilo(ax, NOME_ANALISE.get(analise, analise), "Tempo (ms)", eixo_threads=False)
    handles, rotulos = coletar_legenda(eixos)
    legenda(fig, area, handles, rotulos)
    salvar(fig, "04_com_e_sem_afinidade.png", "Com e sem afinidade de processador",
           SUB + " | barras finas: menor e maior tempo. Se elas se sobrepõem, a diferença pode ser só ruído")
else:
    print("  (sem medições de afinidade com status OK: gráfico 04 não gerado)")

# ---------------------------------------------------------------- 05 speedup x volume
if len(VOLUMES) > 1:
    fig, eixos, area = painel(len(ANALISES))
    for ax, analise in zip(eixos, ANALISES):
        for i, t in enumerate(THREADS):
            s = sorted(filtrar(analise=analise, modo="Executor", threads=t, k=K), key=lambda d: d["volume"])
            if s:
                ax.plot([d["volume"] for d in s], [d["speedup"] for d in s], color=RAMPA[min(i, len(RAMPA) - 1)],
                        marker="o", markersize=MARCADOR * 0.9, markeredgecolor=SUPERFICIE,
                        markeredgewidth=1.2 if RELATORIO else 2,
                        label=f"{t} thread" + ("s" if t > 1 else ""), zorder=3)
        tracejada(ax, 1, "Igual ao sequencial")
        ax.set_xscale("log")
        ax.xaxis.set_major_locator(FixedLocator(VOLUMES))
        ax.xaxis.set_minor_locator(FixedLocator([]))
        ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: volume_curto(int(round(v)))))
        ax.tick_params(axis="x", labelrotation=45)
        ax.set_ylim(bottom=0)
        estilo(ax, NOME_ANALISE.get(analise, analise), "Speedup (vezes)", xlabel="Transações", eixo_threads=False)
    handles, rotulos = coletar_legenda(eixos)
    legenda(fig, area, handles, rotulos, titulo="ExecutorService")
    salvar(fig, "05_speedup_por_volume.png", "Escalabilidade: speedup conforme cresce o conjunto de dados",
           f"ExecutorService sem afinidade | {K} tarefas por thread | mediana das repetições")
else:
    print("  (só um volume medido: gráfico 05 não gerado)")

# ---------------------------------------------------------------- 06 tarefas por thread
# Tempo de cada quantidade de tarefas dividido pelo tempo com K tarefas por thread, na MESMA
# quantidade de threads. 1 = igual; acima de 1 = mais lento. Comparar tempos (e não speedups) evita
# misturar o efeito das tarefas com o tempo sequencial.
if len(VALORES_K) > 1:
    fig, eixos, area = painel(len(ANALISES))
    for ax, analise in zip(eixos, ANALISES):
        for i, t in enumerate(THREADS):
            pontos = {d["k"]: d["mediana"] for d in filtrar(analise=analise, modo="Executor", threads=t, volume=MAIOR)}
            if K not in pontos:
                continue
            ks = [k for k in VALORES_K if k in pontos]
            ax.plot(ks, [pontos[k] / pontos[K] for k in ks], color=RAMPA[min(i, len(RAMPA) - 1)], marker="o",
                    markersize=MARCADOR * 0.9, markeredgecolor=SUPERFICIE, markeredgewidth=1.2 if RELATORIO else 2,
                    label=f"{t} thread" + ("s" if t > 1 else ""), zorder=3)
        tracejada(ax, 1, f"Igual a {K} tarefas por thread")
        ax.set_xscale("log", base=2)
        ax.xaxis.set_major_locator(FixedLocator(VALORES_K))
        ax.xaxis.set_minor_locator(FixedLocator([]))
        ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: br(v, 0)))
        ax.set_ylim(bottom=0)
        estilo(ax, NOME_ANALISE.get(analise, analise), "Tempo relativo", xlabel="Tarefas por thread",
               eixo_threads=False)
    handles, rotulos = coletar_legenda(eixos)
    legenda(fig, area, handles, rotulos, titulo="ExecutorService")
    salvar(fig, "06_tarefas_por_thread.png", "Quantas tarefas por thread?",
           f"{volume_curto(MAIOR)} de transações | tempo dividido pelo tempo com {K} tarefas por thread "
           "(acima de 1 = mais lento)")
else:
    print("  (só uma quantidade de tarefas por thread: gráfico 06 não gerado; use --tarefas-por-thread=1,4)")

# ---------------------------------------------------------------- 07 uma passada x quatro
separadas = ["agregacao", "suspeitas", "categorias", "faixas"]
if "completa" in ANALISES and all(a in ANALISES for a in separadas):
    configuracoes = [("Sequencial", 1)] + [("Executor", t) for t in THREADS]
    rotulos_x, soma, completa = [], [], []
    for modo, t in configuracoes:
        partes = [filtrar(analise=a, modo=modo, threads=t, volume=MAIOR, **({} if modo == "Sequencial" else {"k": K}))
                  for a in separadas]
        alvo = filtrar(analise="completa", modo=modo, threads=t, volume=MAIOR, **({} if modo == "Sequencial" else {"k": K}))
        if all(partes) and alvo:
            rotulos_x.append("Sequencial" if modo == "Sequencial" else f"{t} thread" + ("s" if t > 1 else ""))
            soma.append(sum(p[0]["mediana"] for p in partes))
            completa.append(alvo[0]["mediana"])
    if rotulos_x:
        fig, ax = plt.subplots(figsize=(6.3, 2.9) if RELATORIO else (8, 4.6))
        xs = list(range(len(rotulos_x)))
        ax.bar([x - 0.155 for x in xs], soma, width=0.29, color=APAGADO, label="4 análises separadas (soma dos tempos)", zorder=2)
        ax.bar([x + 0.155 for x in xs], completa, width=0.29, color=COR_MODO["Executor"], label="Todas numa passada só", zorder=2)
        for x, s_, c_ in zip(xs, soma, completa):
            ax.annotate(f"{br(s_ / c_, 2)}×", (x + 0.155, c_), textcoords="offset points", xytext=(0, 3),
                        ha="center", fontsize=LETRA - 1, color=TINTA_2)
        ax.set_xticks(xs)
        ax.set_xticklabels(rotulos_x)
        estilo(ax, "", "Tempo (ms)", xlabel="", eixo_threads=False)
        ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.12), ncol=2)
        salvar(fig, "07_uma_passada.png", "Ler os dados uma vez só compensa?",
               f"{volume_curto(MAIOR)} de transações | rótulo: quantas vezes a passada única é mais rápida")

# ---------------------------------------------------------------- 08 variabilidade
# Um ponto por repetição: speedup = mediana do sequencial / tempo daquela repetição. Mostra o que a
# mediana esconde, como configurações em que as repetições se dividem em dois grupos.
if BRUTO.exists():
    with BRUTO.open(encoding="utf-8-sig", newline="") as arquivo:
        repeticoes = [r for r in csv.DictReader(arquivo, delimiter=";") if int(r["volume"]) == MAIOR]
    fig, eixos, area = painel(len(ANALISES))
    largura = 0.24
    for ax, analise in zip(eixos, ANALISES):
        seq = sequencial(analise)
        if not seq:
            continue
        for i, modo in enumerate(MODOS):
            for j, t in enumerate(THREADS):
                tempos = sorted(numero_bruto(r["tempo_ms"]) for r in repeticoes
                                if r["analise"] == analise and r["configuracao"] == modo and int(r["threads"]) == t
                                and int(r["tarefas_por_thread"]) == K)
                if not tempos:
                    continue
                centro = j + (i - 1) * largura
                deslocamentos = [((n % 5) - 2) * largura * 0.12 for n in range(len(tempos))]
                ax.scatter([centro + d for d in deslocamentos], [seq["mediana"] / x for x in tempos], s=PONTO,
                           color=COR_MODO[modo], alpha=0.75, linewidths=0, zorder=3,
                           label=NOME_MODO[modo])
        ax.set_xticks(range(len(THREADS)))
        ax.set_xticklabels([str(t) for t in THREADS])
        ax.set_ylim(bottom=0)
        estilo(ax, NOME_ANALISE.get(analise, analise), "Speedup", eixo_threads=False)
    handles, rotulos = coletar_legenda(eixos)
    legenda(fig, area, handles, rotulos)
    salvar(fig, "08_variabilidade.png", "Cada repetição separadamente",
           f"{volume_curto(MAIOR)} de transações | um ponto por repetição | speedup = mediana do sequencial / tempo")
else:
    print(f"  (sem {BRUTO.name}: gráfico 08 não gerado)")

# ---------------------------------------------------------------- tabela para o relatório
tabela = FONTE.parent / "tabela_maior_volume.csv"
with tabela.open("w", encoding="utf-8-sig", newline="") as arquivo:
    w = csv.writer(arquivo, delimiter=";")
    w.writerow(["Análise", "Configuração", "Threads", "Tarefas", "Tempo (ms)", "Mínimo (ms)", "Máximo (ms)",
                "Speedup", "Eficiência (%)"])
    for analise in ANALISES:
        linhas = [d for d in filtrar(analise=analise, volume=MAIOR) if d["modo"] == "Sequencial" or d["k"] == K]
        ordem = {"Sequencial": 0, **{m: i + 1 for i, m in enumerate(MODOS)}}
        for d in sorted(linhas, key=lambda d: (ordem.get(d["modo"], 9), d["threads"])):
            w.writerow([NOME_ANALISE.get(analise, analise), d["modo"], d["threads"],
                        1 if d["modo"] == "Sequencial" else d["threads"] * d["k"],
                        br(d["mediana"], 3), br(d["minimo"], 3), br(d["maximo"], 3),
                        br(d["speedup"], 2), br(d["eficiencia"], 1)])
print(f"  {tabela.parent.name}/{tabela.name}")
print(f"Pronto. Gráficos em: {PASTA}")
