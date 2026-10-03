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
ORDER = {value: index for index, value in enumerate(
    ["JSON", "FOLDER", "MONGO", "TIME_HIERARCHY", "BOOK_HIERARCHY", "ID_RANGE_HIERARCHY"])}
COMPARED_PARAMS = ["structure", "storeStrategy"]
COMPARED_COLORS = {
    "JSON": "#264653", "FOLDER": "#e9c46a", "MONGO": "#e76f51",
    "TIME_HIERARCHY": "#8ecae6", "BOOK_HIERARCHY": "#219ebc", "ID_RANGE_HIERARCHY": "#023047",
}
STORAGE_REPORT = "IndexStorageReport"
STORAGE_OVERHEAD = "StorageOverheadBenchmark"
STORAGE_METRICS = {
    "build_ms": "Build time (ms)",
    "disk_bytes": "Size on disk (MB)",
    "content_bytes": "Content size (MB)",
    "retained_heap_bytes": "Retained memory (MB)",
}
OVERHEAD_METRICS = {
    "files": "Files",
    "directories": "Directories",
    "size_bytes": "Total size (MB)",
    "average_file_size_bytes": "Average file size (KB)",
}


def main():
    parser = argparse.ArgumentParser(description="Plots the benchmark results of every language.")
    parser.add_argument("--results", type=Path, default=REPO_ROOT / "benchmark" / "results")
    parser.add_argument("--output", type=Path, default=REPO_ROOT / "benchmark" / "plots")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    names = sorted({path.stem for language in LANGUAGES for path in (args.results / language).glob("*.csv")})
    if not names:
        print(f"No results in {args.results}")
    for name in names:
        frames = load(args.results, name)
        if name == STORAGE_OVERHEAD:
            save(plot_storage_overhead(frames), args.output / f"{name}.png")
            continue
        if name == STORAGE_REPORT:
            figures = {"languages": plot_storage_report(frames), "structures": plot_storage_report_structures(frames)}
        else:
            figures = {"languages": plot_jmh(name, frames), "structures": plot_jmh_structures(name, frames)}
        for suffix, figure in figures.items():
            if figure is not None:
                save(figure, args.output / f"{name}_{suffix}.png")


def save(figure, path):
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
    combined = combine(frames)
    x_param = next((param for param in X_PARAMS if param in combined.columns), None)
    panel_params = [param for param in PANEL_PARAMS if param in combined.columns]
    panels = combined[["method", "Mode", *panel_params]].drop_duplicates().to_dict("records")
    panels.sort(key=lambda panel: (panel["method"], panel["Mode"], *(ORDER.get(panel[p], 99) for p in panel_params)))
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
    return finish(figure, f"{name}: language comparison")


def combine(frames):
    combined = pd.concat([frame.assign(language=language) for language, frame in frames.items()], ignore_index=True)
    if "Benchmark" in combined.columns:
        combined["method"] = combined["Benchmark"].str.rsplit(".", n=1).str[-1]
    return combined


def plot_jmh_structures(name, frames):
    combined = combine(frames)
    compared = next((param for param in COMPARED_PARAMS if param in combined.columns), None)
    if compared is None:
        return None
    groups = sorted(combined[["method", "Mode"]].drop_duplicates().itertuples(index=False, name=None))
    languages = [language for language in LANGUAGES if language in frames]
    figure, axes = plt.subplots(len(groups), len(languages), figsize=(5.2 * len(languages), 3.8 * len(groups)), squeeze=False)
    for row, (method, mode) in enumerate(groups):
        for column, language in enumerate(languages):
            axis = axes[row][column]
            data = combined[(combined["method"] == method) & (combined["Mode"] == mode) & (combined["language"] == language)]
            draw_structures(axis, data, compared, "Score", "Score Error (99.9%)")
            axis.set_title(f"{LANGUAGE_LABELS[language]} · {method} ({mode})", fontsize=10)
            if not data.empty:
                axis.set_ylabel(data["Unit"].iloc[0])
    return finish(figure, f"{name}: structure comparison")


def plot_storage_report_structures(frames):
    combined = combine(frames)
    languages = [language for language in LANGUAGES if language in frames]
    figure, axes = plt.subplots(len(STORAGE_METRICS), len(languages),
                                figsize=(5.2 * len(languages), 3.6 * len(STORAGE_METRICS)), squeeze=False)
    for row, (metric, label) in enumerate(STORAGE_METRICS.items()):
        for column, language in enumerate(languages):
            data = combined[combined["language"] == language].copy()
            data["value"] = data[metric] if metric == "build_ms" else data[metric] / 1e6
            axis = axes[row][column]
            draw_structures(axis, data, "structure", "value", None)
            axis.set_title(f"{LANGUAGE_LABELS[language]} · {label}", fontsize=10)
            axis.set_ylabel(label)
    return finish(figure, f"{STORAGE_REPORT}: structure comparison")


def draw_structures(axis, data, compared, value, error):
    values = ordered(data[compared])
    if "books" in data.columns:
        books = ordered(data["books"])
        for item in values:
            rows = data[data[compared] == item].set_index("books").reindex(books)
            errors = rows[error].fillna(0).clip(upper=rows[value]) if error else None
            axis.errorbar(books, rows[value], yerr=errors, marker="o", capsize=3, label=item, color=COMPARED_COLORS.get(item))
        axis.set_xticks(books)
        axis.set_xlabel("books")
    else:
        rows = data.set_index(compared).reindex(values)
        errors = rows[error].fillna(0).clip(upper=rows[value]) if error else None
        for index, item in enumerate(values):
            axis.bar(index, rows[value].iloc[index], yerr=None if errors is None else errors.iloc[index], capsize=3,
                     label=item, color=COMPARED_COLORS.get(item))
        axis.set_xticks(range(len(values)), values, fontsize=7)
    if needs_log_scale(data[value]):
        axis.set_yscale("log")
    axis.grid(axis="y", alpha=0.3)


def ordered(values):
    unique = list(dict.fromkeys(values))
    if all(isinstance(value, str) for value in unique):
        return sorted(unique, key=lambda value: ORDER.get(value, 99))
    return sorted(unique)


def draw_bars(axis, data, x_param):
    categories = ordered(data[x_param]) if x_param else ["-"]
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
    height = figure.get_figheight()
    figure.suptitle(title, fontsize=13, y=1 - 0.15 / height)
    figure.tight_layout(rect=(0, 0, 1, 1 - 0.7 / height))
    figure.legend(handles.values(), handles.keys(), loc="upper center", ncol=len(handles),
                  bbox_to_anchor=(0.5, 1 - 0.5 / height), frameon=False)
    return figure


def needs_log_scale(scores):
    positive = scores[scores > 0]
    return len(positive) > 1 and positive.max() / positive.min() > 50


def plot_storage_report(frames):
    combined = combine(frames)
    structures = ordered(combined["structure"])
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
    return finish(figure, f"{STORAGE_REPORT}: language comparison")


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
    figure.suptitle(f"{STORAGE_OVERHEAD} (identical in the three languages)", fontsize=13)
    figure.tight_layout()
    return figure


if __name__ == "__main__":
    main()
