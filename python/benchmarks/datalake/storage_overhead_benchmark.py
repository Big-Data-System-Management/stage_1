import os

from benchmarks.datalake.benchmark_paths import STRATEGIES, path_for_strategy
from benchmarks.harness import write_csv

HEADER = "strategy,files,directories,size_bytes,average_file_size_bytes"


def analyze(root):
    files = directories = total_size = 0
    for directory, subdirectories, names in os.walk(root):
        directories += len(subdirectories)
        for name in names:
            files += 1
            total_size += os.path.getsize(os.path.join(directory, name))
    average = total_size // files if files else 0
    return files, directories, total_size, average


def main():
    rows = []
    for strategy in STRATEGIES:
        path = path_for_strategy(strategy)
        print(f"Estrategia: {strategy} -> Ruta: {path}")
        if not path.exists():
            print("Ruta no encontrada para analizar.\n")
            continue
        files, directories, size, average = analyze(path)
        print("=== MÉTRICAS DE ALMACENAMIENTO ===")
        print(f"Archivos totales     : {files}")
        print(f"Directorios totales  : {directories}")
        print(f"Tamaño en disco (MB) : {size / (1024 * 1024):.2f} MB")
        print(f"Tamaño medio archivo : {average} bytes\n")
        rows.append(",".join(map(str, (strategy, files, directories, size, average))))
    path = write_csv("StorageOverheadBenchmark", HEADER, rows)
    print(f"[BENCHMARK] Resultados guardados en {path}")


if __name__ == "__main__":
    main()
