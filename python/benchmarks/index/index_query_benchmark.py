import tempfile
from pathlib import Path

from benchmarks import books
from benchmarks.harness import AVERAGE_TIME, BenchmarkState, Iterations, benchmark
from benchmarks.index import index_benchmark_support as support
from benchmarks.java_random import JavaRandom
from stage1.index import utf16_order
from stage1.tokenizer import Tokenizer

FREQUENT_TERMS = 40
RANDOM_TERMS = 40
MISSING_TERMS = 20
QUERIES = FREQUENT_TERMS + RANDOM_TERMS + MISSING_TERMS


class IndexQueryBenchmark(BenchmarkState):
    name = "benchmarks.index.IndexQueryBenchmark"
    modes = (AVERAGE_TIME,)
    unit = "us"
    warmup = Iterations(2, 2)
    measurement = Iterations(5, 2)
    params = {"structure": support.STRUCTURES, "books": [25, 50, 100]}

    def setup_trial(self):
        self.tokenizer = Tokenizer()
        corpus = books.load(self.books)
        self.work_dir = Path(tempfile.mkdtemp(prefix="index_query_"))
        support.reset_mongo(self.structure)
        self.index = support.create(self.structure, self.work_dir, self.tokenizer)
        for book in corpus:
            self.index.index_book(book.id, book.body)
        self.index.flush()
        self.queries = build_queries(corpus, self.tokenizer)

    @benchmark("searchTerms", operations=QUERIES)
    def search_terms(self):
        for query in self.queries:
            self.index.search(query)

    def teardown_trial(self):
        support.dispose(self.structure, self.index, self.work_dir)


def build_queries(corpus, tokenizer):
    document_frequency = {}
    for book in corpus:
        for term in set(tokenizer.tokenize(book.body)):
            document_frequency[term] = document_frequency.get(term, 0) + 1
    by_frequency = sorted(document_frequency, key=lambda term: (-document_frequency[term], utf16_order(term)))
    frequent = by_frequency[:FREQUENT_TERMS]
    others = by_frequency[FREQUENT_TERMS:]
    JavaRandom(42).shuffle(others)
    return frequent + others[:RANDOM_TERMS] + [f"missingterm{i}" for i in range(MISSING_TERMS)]


if __name__ == "__main__":
    support.run(IndexQueryBenchmark)
