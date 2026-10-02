import shutil
import tempfile
from pathlib import Path

from benchmarks import harness
from benchmarks.harness import AVERAGE_TIME, BenchmarkState, Iterations, benchmark
from benchmarks.java_random import JavaRandom
from benchmarks.metadata import synthetic_metadata
from stage1.metadata import SqliteMetadataRepository

QUERIES = 100
QUERY_SEED = 7


class MetadataQueryBenchmark(BenchmarkState):
    name = "benchmarks.metadata.MetadataQueryBenchmark"
    modes = (AVERAGE_TIME,)
    unit = "us"
    warmup = Iterations(2, 2)
    measurement = Iterations(5, 2)
    params = {"books": [500, 5000, 50000]}

    def setup_trial(self):
        self.database_dir = Path(tempfile.mkdtemp(prefix="metadata_query_"))
        self.repository = SqliteMetadataRepository(self.database_dir / "metadata.db")
        self.repository.save_all(synthetic_metadata.generate(self.books))
        random = JavaRandom(QUERY_SEED)
        self.book_ids, self.titles, self.authors = [], [], []
        for _ in range(QUERIES):
            book_id = 1 + random.next_int(self.books)
            self.book_ids.append(book_id)
            self.titles.append(synthetic_metadata.title(book_id))
            self.authors.append(synthetic_metadata.author(random.next_int(synthetic_metadata.author_count(self.books))))

    @benchmark("findPathById", operations=QUERIES)
    def find_path_by_id(self):
        for book_id in self.book_ids:
            book = self.repository.find_by_id(book_id)
            _ = None if book is None else book.body_path

    @benchmark("findPathByTitle", operations=QUERIES)
    def find_path_by_title(self):
        for title in self.titles:
            self.repository.find_by_title(title)

    @benchmark("findBooksByAuthor", operations=QUERIES)
    def find_books_by_author(self):
        for author in self.authors:
            self.repository.find_by_author(author)

    def teardown_trial(self):
        self.repository.close()
        shutil.rmtree(self.database_dir)


if __name__ == "__main__":
    harness.run(MetadataQueryBenchmark)
