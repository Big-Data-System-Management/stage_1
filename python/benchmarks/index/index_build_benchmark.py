import tempfile
from pathlib import Path

from benchmarks import books
from benchmarks.harness import SINGLE_SHOT, BenchmarkState, Iterations, benchmark
from benchmarks.index import index_benchmark_support as support
from stage1.tokenizer import Tokenizer


class IndexBuildBenchmark(BenchmarkState):
    name = "benchmarks.index.IndexBuildBenchmark"
    modes = (SINGLE_SHOT,)
    unit = "ms"
    warmup = Iterations(1)
    measurement = Iterations(3)
    params = {"structure": support.STRUCTURES, "books": [25, 50, 100]}

    def setup_trial(self):
        self.tokenizer = Tokenizer()
        self.corpus = books.load(self.books)

    def setup_iteration(self):
        self.work_dir = Path(tempfile.mkdtemp(prefix="index_build_"))
        support.reset_mongo(self.structure)
        self.index = support.create(self.structure, self.work_dir, self.tokenizer)

    @benchmark("buildIndexFromScratch")
    def build_index_from_scratch(self):
        for book in self.corpus:
            self.index.index_book(book.id, book.body)
        self.index.flush()

    def teardown_iteration(self):
        support.dispose(self.structure, self.index, self.work_dir)


if __name__ == "__main__":
    support.run(IndexBuildBenchmark)
