import shutil
import tempfile
from pathlib import Path

from benchmarks import harness
from benchmarks.harness import SINGLE_SHOT, BenchmarkState, Iterations, benchmark
from benchmarks.metadata import synthetic_metadata
from stage1.metadata import SqliteMetadataRepository


class MetadataInsertBenchmark(BenchmarkState):
    name = "benchmarks.metadata.MetadataInsertBenchmark"
    modes = (SINGLE_SHOT,)
    unit = "ms"
    warmup = Iterations(1)
    measurement = Iterations(3)
    params = {"books": [500, 5000, 50000]}

    def setup_trial(self):
        self.metadata = synthetic_metadata.generate(self.books)

    def setup_iteration(self):
        self.database_dir = Path(tempfile.mkdtemp(prefix="metadata_insert_"))
        self.repository = SqliteMetadataRepository(self.database_dir / "metadata.db")

    @benchmark("insertAllInOneTransaction")
    def insert_all_in_one_transaction(self):
        self.repository.save_all(self.metadata)

    @benchmark("insertOneTransactionPerBook")
    def insert_one_transaction_per_book(self):
        for book in self.metadata:
            self.repository.save(book)

    def teardown_iteration(self):
        inserted = self.repository.count()
        self.repository.close()
        shutil.rmtree(self.database_dir)
        if inserted != self.books:
            raise RuntimeError(f"Se insertaron {inserted} libros de {self.books}")


if __name__ == "__main__":
    harness.run(MetadataInsertBenchmark)
