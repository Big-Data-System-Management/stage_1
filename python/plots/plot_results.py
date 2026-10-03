import argparse
from pathlib import Path

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
import pandas as pd

from stage1.files import REPO_ROOT

LANGUAGES = ["java", "python", "cpp"]
LANGUAGE_LABELS = {"java": "Java", "python": "Python", "cpp": "C++"}
LANGUAGE_COLORS = {"java": "#e76f00", "python": "#3776ab", "cpp": "#2a9d8f"}
X_PARAMS = ["books", "storeStrategy"]
PANEL_PARAMS = ["structure"]
STORAGE_REPORT = "IndexStorageReport"
STORAGE_OVERHEAD = "StorageOverheadBenchmark"
STORAGE_METRICS = {
    "build_ms": "Tiempo de construcción (ms)",
    "disk_bytes": "Tamaño en disco (MB)",
    "content_bytes": "Tamaño del contenido (MB)",
    "retained_heap_bytes": "Memoria retenida (MB)",
}
OVERHEAD_METRICS = {
    "files": "Ficheros",
    "directories": "Directorios",
    "size_bytes": "Tamaño total (MB)",
    "average_file_size_bytes": "Tamaño medio por fichero (KB)",
}


def main():
    parser = argparse.ArgumentParser(description="Genera gráficas comparando los resultados de los benchmarks.")
    parser.add_argument("--results", type=Path, default=REPO_ROOT / "benchmark" / "results")
    parser.add_argument("--output", type=Path, default=REPO_ROOT / "benchmark" / "plots")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    names = sorted({path.stem for language in LANGUAGES for path in (args.results / language).glob("*.csv")})
    if not names:
        print(f"No hay resultados en {args.results}")
    for name in names:
        frames = load(args.results, name)
        if name == STORAGE_REPORT:
            figure = plot_storage_report(frames)
        elif name == STORAGE_OVERHEAD:
            figure = plot_storage_overhead(frames)
        else:
            figure = plot_jmh(name, frames)
        path = args.output / f"{name}.png"
        figure.savefig(path, dpi=130, bbox_inches="tight")
        plt.close(figure)
        print(f"[PLOT] {path}")


def load(results, name):
    frames = {}
    for language in LANGUAGES:
        path = results / language / f"{name}.csv"
        if path.exists():
            frame = pd.read_csv(path)
            frame.columns = [column.removeprefix("Param: ") for column in frame.columns]
            frames[language] = frame
    return frames


def plot_jmh(name, frames):
    combined = pd.concat([frame.assign(language=language) for language, frame in frames.items()], ignore_index=True)
    combined["method"] = combined["Benchmark"].str.rsplit(".", n=1).str[-1]
    x_param = next((param for param in X_PARAMS if param in combined.columns), None)
    panel_params = [param for param in PANEL_PARAMS if param in combined.columns]
    panels = combined[["method", "Mode", *panel_params]].drop_duplicates().to_dict("records")
    columns = min(3, len(panels))
    rows = -(-len(panels) // columns)
    figure, axes = plt.subplots(rows, columns, figsize=(5.2 * columns, 4 * rows), squeeze=False)
    for axis, panel in zip(axes.flat, panels):
        mask = pd.Series(True, index=combined.index)
        for key, value in panel.items():
            mask &= combined[key] == value
        data = combined[mask]
        draw_bars(axis, data, x_param)
        title = f"{panel['method']} ({panel['Mode']})"
        if panel_params:
            title += " · " + " · ".join(str(panel[param]) for param in panel_params)
        axis.set_title(title, fontsize=10)
        axis.set_ylabel(data["Unit"].iloc[0])
    for axis in list(axes.flat)[len(panels):]:
        axis.set_visible(False)
    return finish(figure, name)


def draw_bars(axis, data, x_param):
    categories = list(dict.fromkeys(data[x_param])) if x_param else ["-"]
    languages = [language for language in LANGUAGES if language in set(data["language"])]
    width = 0.8 / max(1, len(languages))
    for index, language in enumerate(languages):
        rows = data[data["language"] == language]
        if x_param:
            rows = rows.set_index(x_param).reindex(categories)
        scores = rows["Score"].to_numpy()
        errors = rows["Score Error (99.9%)"].fillna(0).clip(upper=rows["Score"]).to_numpy()
        positions = [i + (index - (len(languages) - 1) / 2) * width for i in range(len(categories))]
        axis.bar(positions, scores, width, yerr=errors, capsize=3,
                 label=LANGUAGE_LABELS[language], color=LANGUAGE_COLORS[language])
    axis.set_xticks(range(len(categories)), [str(category) for category in categories], fontsize=8)
    if x_param:
        axis.set_xlabel(x_param)
    if needs_log_scale(data["Score"]):
        axis.set_yscale("log")
    axis.grid(axis="y", alpha=0.3)


def finish(figure, title):
    handles = {}
    for axis in figure.axes:
        for handle, label in zip(*axis.get_legend_handles_labels()):
            handles.setdefault(label, handle)
    figure.suptitle(title, fontsize=13)
    figure.tight_layout(rect=(0, 0, 1, 0.94))
    figure.legend(handles.values(), handles.keys(), loc="upper center", ncol=len(handles),
                  bbox_to_anchor=(0.5, 0.97), frameon=False)
    return figure


def needs_log_scale(scores):
    positive = scores[scores > 0]
    return len(positive) > 1 and positive.max() / positive.min() > 50


def plot_storage_report(frames):
    combined = pd.concat([frame.assign(language=language) for language, frame in frames.items()], ignore_index=True)
    structures = list(dict.fromkeys(combined["structure"]))
    figure, axes = plt.subplots(len(STORAGE_METRICS), len(structures),
                                figsize=(5 * len(structures), 3.6 * len(STORAGE_METRICS)), squeeze=False)
    for row, (metric, label) in enumerate(STORAGE_METRICS.items()):
        for column, structure in enumerate(structures):
            data = combined[combined["structure"] == structure].copy()
            data["Score"] = data[metric] if metric == "build_ms" else data[metric] / 1e6
            data["Score Error (99.9%)"] = 0
            axis = axes[row][column]
            draw_bars(axis, data, "books")
            axis.set_title(f"{structure} · {label}", fontsize=10)
            axis.set_ylabel(label)
    return finish(figure, STORAGE_REPORT)


def plot_storage_overhead(frames):
    frame = next(iter(frames.values()))
    figure, axes = plt.subplots(1, len(OVERHEAD_METRICS), figsize=(4.2 * len(OVERHEAD_METRICS), 3.8), squeeze=False)
    for axis, (metric, label) in zip(axes.flat, OVERHEAD_METRICS.items()):
        values = frame[metric]
        if metric == "size_bytes":
            values = values / 1e6
        elif metric == "average_file_size_bytes":
            values = values / 1e3
        axis.bar(frame["strategy"], values, color="#6c757d")
        axis.set_title(label, fontsize=10)
        axis.tick_params(axis="x", labelsize=8, rotation=15)
        axis.grid(axis="y", alpha=0.3)
    figure.suptitle(f"{STORAGE_OVERHEAD} (idéntico en los tres lenguajes)", fontsize=13)
    figure.tight_layout()
    return figure


if __name__ == "__main__":
    main()
