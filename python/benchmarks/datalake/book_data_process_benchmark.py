import shutil
import tempfile
from pathlib import Path

from benchmarks import books, harness
from benchmarks.datalake.benchmark_paths import STRATEGIES, create_store
from benchmarks.harness import SINGLE_SHOT, BenchmarkState, Iterations, benchmark
from stage1 import gutenberg

TOTAL_BOOKS = 100
MAX_BOOK_ID_TO_TRY = 300


class BookDataProcessBenchmark(BenchmarkState):
    name = "benchmarks.datalake.BookDataProcessBenchmark"
    modes = (SINGLE_SHOT,)
    unit = "ms"
    warmup = Iterations(2)
    measurement = Iterations(5)
    params = {"storeStrategy": STRATEGIES}

    def setup_trial(self):
        self.raw_books = []
        self.parsed_books = []
        for book_id in range(1, MAX_BOOK_ID_TO_TRY + 1):
            if len(self.raw_books) >= TOTAL_BOOKS:
                break
            raw = books.raw_book(book_id)
            book = gutenberg.process(raw) if raw is not None else None
            if book is None:
                continue
            self.raw_books.append(raw)
            self.parsed_books.append(book)
        if len(self.raw_books) < TOTAL_BOOKS:
            raise RuntimeError(f"Solo hay {len(self.raw_books)} libros válidos, se necesitan {TOTAL_BOOKS}")

    def setup_iteration(self):
        self.datalake = Path(tempfile.mkdtemp(prefix=f"datalake_bench_{self.storeStrategy.lower()}_"))
        self.store = create_store(self.storeStrategy, self.datalake)
        self.stored_books = 0

    def teardown_iteration(self):
        try:
            if self.stored_books != TOTAL_BOOKS:
                raise RuntimeError(f"Se guardaron {self.stored_books} libros de {TOTAL_BOOKS}")
        finally:
            shutil.rmtree(self.datalake)

    @benchmark("measureSplitAndStore", operations=TOTAL_BOOKS)
    def measure_split_and_store(self):
        for raw in self.raw_books:
            book = gutenberg.process(raw)
            if book is not None:
                self._store(book)

    @benchmark("measureStoreOnly", operations=TOTAL_BOOKS)
    def measure_store_only(self):
        for book in self.parsed_books:
            self._store(book)

    def _store(self, book):
        self.store.store_data(book)
        self.stored_books += 1


if __name__ == "__main__":
    harness.run(BookDataProcessBenchmark)
