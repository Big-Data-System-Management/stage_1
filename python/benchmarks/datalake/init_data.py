from benchmarks import books
from benchmarks.datalake.benchmark_paths import PATHS, create_store
from stage1 import gutenberg
from stage1.datalake import CompositeStore

FIRST_BOOK_ID = 1
LAST_BOOK_ID = 250


def main():
    store = CompositeStore(create_store(strategy, path) for strategy, path in PATHS.items())
    for book_id in range(FIRST_BOOK_ID, LAST_BOOK_ID + 1):
        if store.exists(book_id):
            continue
        raw = books.raw_book(book_id)
        book = gutenberg.process(raw) if raw is not None else None
        if book is not None:
            store.store_data(book)
            print(f"Libro {book_id} guardado")


if __name__ == "__main__":
    main()
