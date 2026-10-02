import itertools
import math
import statistics
import time
from dataclasses import dataclass

from stage1.files import REPO_ROOT, write_text

RESULTS_DIR = REPO_ROOT / "benchmark" / "results" / "python"
UNIT_NANOS = {"s": 1_000_000_000, "ms": 1_000_000, "us": 1_000, "ns": 1}
STUDENT_T_999 = {
    1: 636.619, 2: 31.599, 3: 12.924, 4: 8.610, 5: 6.869, 6: 5.959, 7: 5.408, 8: 5.041, 9: 4.781,
    10: 4.587, 11: 4.437, 12: 4.318, 13: 4.221, 14: 4.140, 15: 4.073, 16: 4.015, 17: 3.965, 18: 3.922,
    19: 3.883, 20: 3.850, 25: 3.725, 30: 3.646,
}
SINGLE_SHOT = "ss"
AVERAGE_TIME = "avgt"
THROUGHPUT = "thrpt"
MAX_BATCH_NANOS = 1_000_000


@dataclass(frozen=True)
class Iterations:
    count: int
    seconds: float = 10.0


def benchmark(name, operations=1, unit=None):
    def mark(method):
        method.benchmark_name = name
        method.operations = operations
        method.unit = unit
        return method
    return mark


class BenchmarkState:
    name = ""
    modes = (AVERAGE_TIME,)
    unit = "ms"
    warmup = Iterations(5)
    measurement = Iterations(5)
    params = {}

    def setup_trial(self):
        pass

    def teardown_trial(self):
        pass

    def setup_iteration(self):
        pass

    def teardown_iteration(self):
        pass

    def setup_invocation(self):
        pass

    def teardown_invocation(self):
        pass


@dataclass(frozen=True)
class Result:
    benchmark: str
    mode: str
    samples: int
    score: float
    error: float
    unit: str
    params: dict


def run(state_class, params=None):
    params = dict(state_class.params if params is None else params)
    methods = sorted(
        (getattr(state_class, attribute) for attribute in dir(state_class)
         if hasattr(getattr(state_class, attribute), "benchmark_name")),
        key=lambda method: method.benchmark_name,
    )
    results = []
    for method in methods:
        for mode in state_class.modes:
            for values in itertools.product(*params.values()):
                results.append(_run_trial(state_class, method, mode, dict(zip(params, values))))
    path = write_jmh_csv(state_class.name.rsplit(".", 1)[-1], results, sorted(params))
    print(f"[BENCHMARK] Resultados guardados en {path}")
    return results


def _run_trial(state_class, method, mode, values):
    unit = method.unit or state_class.unit
    label = _unit_label(mode, unit)
    benchmark_name = f"{state_class.name}.{method.benchmark_name}"
    print(f"# Benchmark: {benchmark_name} ({mode}) {values}")
    state = state_class()
    for key, value in values.items():
        setattr(state, key, value)
    state.setup_trial()
    scores = []
    try:
        for phase, iterations in (("Warmup", state_class.warmup), ("Iteration", state_class.measurement)):
            for i in range(iterations.count):
                state.setup_iteration()
                try:
                    score = _measure(state, method, mode, unit, iterations)
                finally:
                    state.teardown_iteration()
                print(f"{phase} {i + 1}: {score:.3f} {label}")
                if phase == "Iteration":
                    scores.append(score)
    finally:
        state.teardown_trial()
    return Result(benchmark_name, mode, len(scores), _mean(scores), _error(scores), label, values)


def _measure(state, method, mode, unit, iterations):
    if mode == SINGLE_SHOT:
        state.setup_invocation()
        try:
            start = time.perf_counter_ns()
            method(state)
            elapsed = time.perf_counter_ns() - start
        finally:
            state.teardown_invocation()
        return elapsed / method.operations / UNIT_NANOS[unit]
    budget = iterations.seconds * 1_000_000_000
    calls, batch, elapsed = 0, 1, 0
    start = time.perf_counter_ns()
    while elapsed < budget:
        batch_start = time.perf_counter_ns()
        for _ in range(batch):
            method(state)
        now = time.perf_counter_ns()
        calls += batch
        elapsed = now - start
        if now - batch_start < MAX_BATCH_NANOS:
            batch *= 2
    operations = calls * method.operations
    if mode == THROUGHPUT:
        return operations / (elapsed / UNIT_NANOS[unit])
    return elapsed / operations / UNIT_NANOS[unit]


def _unit_label(mode, unit):
    return f"ops/{unit}" if mode == THROUGHPUT else f"{unit}/op"


def _mean(scores):
    return statistics.fmean(scores) if scores else math.nan


def _error(scores):
    if len(scores) < 2:
        return math.nan
    degrees = len(scores) - 1
    t = STUDENT_T_999.get(degrees) or STUDENT_T_999[max(k for k in STUDENT_T_999 if k <= degrees)]
    return t * statistics.stdev(scores) / math.sqrt(len(scores))


def _number(value):
    return "NaN" if math.isnan(value) else f"{value:.6f}"


def write_jmh_csv(simple_name, results, param_names):
    header = ['"Benchmark"', '"Mode"', '"Threads"', '"Samples"', '"Score"', '"Score Error (99.9%)"', '"Unit"']
    header += [f'"Param: {name}"' for name in param_names]
    rows = [
        [f'"{r.benchmark}"', f'"{r.mode}"', "1", str(r.samples), _number(r.score), _number(r.error), f'"{r.unit}"']
        + [str(r.params[name]) for name in param_names]
        for r in results
    ]
    return write_csv(simple_name, ",".join(header), [",".join(row) for row in rows])


def write_csv(simple_name, header, rows):
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    path = RESULTS_DIR / f"{simple_name}.csv"
    write_text(path, "".join(line + "\n" for line in [header, *rows]))
    return path
