from benchmarks import harness
from benchmarks.datalake.benchmark_paths import STRATEGIES, create_store, path_for_strategy
from benchmarks.harness import AVERAGE_TIME, THROUGHPUT, BenchmarkState, Iterations, benchmark


class IncrementalProcessingBenchmark(BenchmarkState):
    name = "benchmarks.datalake.IncrementalProcessingBenchmark"
    modes = (AVERAGE_TIME, THROUGHPUT)
    unit = "ms"
    warmup = Iterations(3, 2)
    measurement = Iterations(5, 3)
    params = {"storeStrategy": STRATEGIES, "existingBookId": [15], "nonExistingBookId": [999999]}

    def setup_trial(self):
        self.store = self._create_store()
        print(f"\n[SETUP] Evaluando índices sobre DataLake REAL ({self.storeStrategy})...")

    def _create_store(self):
        return create_store(self.storeStrategy, path_for_strategy(self.storeStrategy))

    @benchmark("measureColdIndexingOverhead")
    def measure_cold_indexing_overhead(self):
        return self._create_store().exists(self.existingBookId)

    @benchmark("measureExistsHit", unit="ns")
    def measure_exists_hit(self):
        return self.store.exists(self.existingBookId)

    @benchmark("measureExistsMiss", unit="ns")
    def measure_exists_miss(self):
        return self.store.exists(self.nonExistingBookId)


if __name__ == "__main__":
    harness.run(IncrementalProcessingBenchmark)
