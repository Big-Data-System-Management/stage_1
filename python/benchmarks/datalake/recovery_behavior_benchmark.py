import shutil
import tempfile
from pathlib import Path

from benchmarks import harness
from benchmarks.datalake.benchmark_paths import STRATEGIES, create_store
from benchmarks.harness import SINGLE_SHOT, BenchmarkState, benchmark
from stage1.files import write_text
from stage1.model import Book


class RecoveryBehaviorBenchmark(BenchmarkState):
    name = "benchmarks.datalake.RecoveryBehaviorBenchmark"
    modes = (SINGLE_SHOT,)
    unit = "ms"
    params = {"storeStrategy": STRATEGIES}

    def setup_invocation(self):
        self.datalake = Path(tempfile.mkdtemp(prefix=f"dl_recovery_{self.storeStrategy.lower()}_"))
        for book_id in range(1, 501):
            self._write(book_id, {f"{book_id}.header.txt": f"Header {book_id}", f"{book_id}.body.txt": f"Body {book_id}"})
        for book_id in range(501, 551):
            self._write(book_id, {f"{book_id}.header.txt": f"Header {book_id}"})
        for book_id in range(551, 601):
            self._write(book_id, {f"{book_id}.header.txt.tmp": f"Temporal inconcluso {book_id}"})

    def teardown_invocation(self):
        shutil.rmtree(self.datalake, ignore_errors=True)

    @benchmark("measureRecoveryTimeAfterCrash")
    def measure_recovery_time_after_crash(self):
        store = create_store(self.storeStrategy, self.datalake)
        if store.exists(505):
            raise RuntimeError("Recovery failure: an incomplete book was indexed.")
        return store

    @benchmark("measurePipelineResumeAndRepair")
    def measure_pipeline_resume_and_repair(self):
        store = create_store(self.storeStrategy, self.datalake)
        for book_id in range(501, 551):
            store.store_data(Book(book_id, f"Header reparado {book_id}", f"Body completado {book_id}"))
        return store

    def _write(self, book_id, files):
        directory = self._target_directory(book_id)
        directory.mkdir(parents=True, exist_ok=True)
        for name, content in files.items():
            write_text(directory / name, content)

    def _target_directory(self, book_id):
        if self.storeStrategy == "BOOK_HIERARCHY":
            return self.datalake / str(book_id)
        if self.storeStrategy == "ID_RANGE_HIERARCHY":
            batch = book_id // 1000
            return self.datalake / f"batch_{batch * 1000}_to_{(batch + 1) * 1000 - 1}"
        if self.storeStrategy == "TIME_HIERARCHY":
            return self.datalake / "20260929" / "17"
        raise ValueError(f"Invalid strategy: {self.storeStrategy}")


if __name__ == "__main__":
    harness.main(RecoveryBehaviorBenchmark)
