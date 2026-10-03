import random

from benchmarks import harness
from benchmarks.datalake.benchmark_paths import STRATEGIES, create_store, path_for_strategy
from benchmarks.harness import AVERAGE_TIME, BenchmarkState, Iterations, benchmark

MIN_BOOK_ID = 1
MAX_BOOK_ID = 200


class LookUpCostBenchmark(BenchmarkState):
    name = "benchmarks.datalake.LookUpCostBenchmark"
    modes = (AVERAGE_TIME,)
    unit = "us"
    warmup = Iterations(3)
    measurement = Iterations(5)
    params = {"storeStrategy": STRATEGIES}

    def setup_trial(self):
        target_path = path_for_strategy(self.storeStrategy)
        self.store = create_store(self.storeStrategy, target_path)
        print(f"\n[SETUP] Using the REAL datalake at: {target_path}")

    @benchmark("measureHeaderAndBodyLookup")
    def measure_header_and_body_lookup(self):
        return self.store.get_book(random.randint(MIN_BOOK_ID, MAX_BOOK_ID))


if __name__ == "__main__":
    harness.main(LookUpCostBenchmark)
