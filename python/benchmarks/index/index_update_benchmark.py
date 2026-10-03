import tempfile
from pathlib import Path

from benchmarks import books
from benchmarks.harness import SINGLE_SHOT, BenchmarkState, Iterations, benchmark
from benchmarks.index import index_benchmark_support as support
from stage1.tokenizer import Tokenizer

NEW_BOOKS = 10


class IndexUpdateBenchmark(BenchmarkState):
    name = "benchmarks.index.IndexUpdateBenchmark"
    modes = (SINGLE_SHOT,)
    unit = "ms"
    warmup = Iterations(0)
    measurement = Iterations(3)
    params = {"structure": support.STRUCTURES, "books": [25, 50, 100]}

    def setup_trial(self):
        self.tokenizer = Tokenizer()
        corpus = books.load(self.books + NEW_BOOKS)
        self.existing_books = corpus[:self.books]
        self.new_books = corpus[self.books:]

    def setup_iteration(self):
        self.work_dir = Path(tempfile.mkdtemp(prefix="index_update_"))
        support.reset_mongo(self.structure)
        self.index = support.create(self.structure, self.work_dir, self.tokenizer)
        for book in self.existing_books:
            self.index.index_book(book.id, book.body)
        self.index.flush()

    @benchmark("addNewBooks")
    def add_new_books(self):
        for book in self.new_books:
            self.index.index_book(book.id, book.body)
        self.index.flush()

    def teardown_iteration(self):
        support.dispose(self.structure, self.index, self.work_dir)


if __name__ == "__main__":
    support.run(IndexUpdateBenchmark)
