import gc
import os
import sys
import tempfile
import time
from pathlib import Path

from pymongo import MongoClient

from benchmarks import books
from benchmarks.harness import parse_options, write_csv
from benchmarks.index import index_benchmark_support as support
from stage1.tokenizer import Tokenizer

DEFAULT_BOOK_COUNTS = [25, 50, 100]
BLOCK_SIZE = 4096
HEADER = "structure,books,terms,build_ms,files,directories,content_bytes,disk_bytes,retained_heap_bytes"


def main():
    tokenizer = Tokenizer()
    structures = support.available_structures()
    if len(structures) < len(support.STRUCTURES):
        print("[BENCHMARK] MongoDB no está arrancado: se omite la estructura MONGO.")
    book_counts = sorted(parse_options(sys.argv[1:])["params"].get("books", DEFAULT_BOOK_COUNTS))
    all_books = books.load(book_counts[-1])
    rows = []
    for count in book_counts:
        corpus = all_books[:count]
        terms = vocabulary_size(corpus, tokenizer)
        for structure in structures:
            row = measure(structure, corpus, terms, tokenizer)
            rows.append(row)
            print_row(row)
    path = write_csv("IndexStorageReport", HEADER, [",".join(map(str, row)) for row in rows])
    print(f"[BENCHMARK] Resultados guardados en {path}")


def measure(structure, corpus, terms, tokenizer):
    work_dir = Path(tempfile.mkdtemp(prefix="index_storage_"))
    support.reset_mongo(structure)
    index = None
    try:
        gc.collect()
        start = time.perf_counter_ns()
        index = support.create(structure, work_dir, tokenizer)
        for book in corpus:
            index.index_book(book.id, book.body)
        index.flush()
        build_millis = (time.perf_counter_ns() - start) // 1_000_000
        gc.collect()
        retained = retained_bytes(index)
        index.search("whale")
        disk = mongo_disk_usage() if structure == "MONGO" else file_disk_usage(work_dir)
        return (structure, len(corpus), terms, build_millis, *disk, retained)
    finally:
        support.dispose(structure, index, work_dir)


def vocabulary_size(corpus, tokenizer):
    vocabulary = set()
    for book in corpus:
        vocabulary.update(tokenizer.tokenize(book.body))
    return len(vocabulary)


def retained_bytes(index):
    data = getattr(index, "postings", None)
    if data is None:
        data = index.pending_postings
    seen = set()
    stack = [data]
    total = 0
    while stack:
        item = stack.pop()
        if id(item) in seen:
            continue
        seen.add(id(item))
        total += sys.getsizeof(item)
        if isinstance(item, dict):
            stack.extend(item.keys())
            stack.extend(item.values())
        elif isinstance(item, (set, frozenset, list, tuple)):
            stack.extend(item)
    return total


def file_disk_usage(root):
    files = directories = content_bytes = disk_bytes = 0
    for directory, subdirectories, names in os.walk(root):
        directories += len(subdirectories)
        for name in names:
            size = os.path.getsize(os.path.join(directory, name))
            files += 1
            content_bytes += size
            disk_bytes += max(1, (size + BLOCK_SIZE - 1) // BLOCK_SIZE) * BLOCK_SIZE
    return files, directories, content_bytes, disk_bytes


def mongo_disk_usage():
    with MongoClient(support.MONGO_URI) as client:
        client.admin.command("fsync")
        stats = next(client[support.MONGO_DATABASE][support.MONGO_COLLECTION].aggregate(
            [{"$collStats": {"storageStats": {}}}]))
        storage = stats["storageStats"]
        return storage["count"], 0, storage["size"], storage["storageSize"] + storage["totalIndexSize"]


def print_row(row):
    structure, count, terms, build_millis, files, _, content_bytes, disk_bytes, retained = row
    unit = "documentos" if structure == "MONGO" else "ficheros"
    print(f"[BENCHMARK] {structure:<6} {count:3d} libros | {terms:7,d} términos | {build_millis:8,d} ms | "
          f"{files:7,d} {unit:<10} | {content_bytes / 1e6:8,.2f} MB contenido | "
          f"{disk_bytes / 1e6:8,.2f} MB en disco | {retained / 1e6:8,.2f} MB heap")


if __name__ == "__main__":
    main()
