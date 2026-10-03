#!/usr/bin/env bash
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
QUICK=${QUICK:-0}
LANGUAGES=${LANGUAGES:-"java python cpp"}
MVN=${MVN:-mvn}
PYTHON=${PYTHON:-}
MONGOD=${MONGOD:-}
MONGO_DBPATH=${MONGO_DBPATH:-}
CPP_BUILD=${CPP_BUILD:-$ROOT/cpp/build}
LOGS=$ROOT/benchmark/logs/$(date +%Y%m%d_%H%M%S)
SUMMARY=$LOGS/summary.txt

BENCHMARKS=(
    datalake.BookDataProcessBenchmark
    datalake.IncrementalProcessingBenchmark
    datalake.LookUpCostBenchmark
    datalake.RecoveryBehaviorBenchmark
    datalake.StorageOverheadBenchmark
    index.IndexBuildBenchmark
    index.IndexUpdateBenchmark
    index.IndexQueryBenchmark
    index.IndexStorageReport
    metadata.MetadataInsertBenchmark
    metadata.MetadataQueryBenchmark
)

log() {
    echo "[$(date +%H:%M:%S)] $*" | tee -a "$SUMMARY"
}

windows_path() {
    if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi
}

snake_case() {
    echo "$1" | sed -E 's/([a-z0-9])([A-Z])/\1_\2/g' | tr '[:upper:]' '[:lower:]'
}

quick_args() {
    local name=$1
    case $name in
        StorageOverheadBenchmark) ;;
        IndexStorageReport) echo "-p books=5" ;;
        Index*) echo "-wi 1 -i 1 -w 1s -r 1s -p books=5" ;;
        Metadata*) echo "-wi 1 -i 1 -w 1s -r 1s -p books=500" ;;
        *) echo "-wi 1 -i 1 -w 1s -r 1s" ;;
    esac
}

mongo_ping() {
    (cd "$ROOT/python" && "$PYTHON" -c "from pymongo import MongoClient; MongoClient(serverSelectionTimeoutMS=1000).admin.command('ping')") > /dev/null 2>&1
}

mongo_shutdown() {
    (cd "$ROOT/python" && "$PYTHON" -c "from pymongo import MongoClient; MongoClient(serverSelectionTimeoutMS=1000).admin.command('shutdown')") > /dev/null 2>&1
}

prepare_java() {
    log "Building Java"
    [ -n "${JAVA_HOME:-}" ] || { log "JAVA_HOME is not set"; return 1; }
    (cd "$ROOT" && "$MVN" -q test-compile) >> "$LOGS/build_java.log" 2>&1 || return 1
    (cd "$ROOT" && "$MVN" -q dependency:build-classpath -Dmdep.outputFile="$(windows_path "$LOGS/classpath.txt")") >> "$LOGS/build_java.log" 2>&1 || return 1
    JAVA_CP="$(windows_path "$ROOT/target/test-classes");$(windows_path "$ROOT/target/classes");$(cat "$LOGS/classpath.txt")"
}

prepare_cpp() {
    log "Building C++"
    { cmake -S "$ROOT/cpp" -B "$CPP_BUILD" -G Ninja && cmake --build "$CPP_BUILD"; } >> "$LOGS/build_cpp.log" 2>&1
}

run_benchmark() {
    local language=$1 qualified=$2 args=$3
    local name=${qualified#*.} package=${qualified%%.*}
    case $language in
        java) (cd "$ROOT" && MSYS2_ARG_CONV_EXCL="*" "$JAVA_HOME/bin/java" -cp "$JAVA_CP" "benchmarks.$qualified" $args) ;;
        python) (cd "$ROOT/python" && PYTHONIOENCODING=utf-8 "$PYTHON" -m "benchmarks.$package.$(snake_case "$name")" $args) ;;
        cpp) (cd "$ROOT" && "$CPP_BUILD/stage1_benchmarks" "$name" $args) ;;
    esac
}

mkdir -p "$LOGS"
log "Benchmark run (QUICK=$QUICK, languages: $LANGUAGES)"

if [ -z "$PYTHON" ]; then
    if [ -x "$ROOT/python/.venv/Scripts/python.exe" ]; then PYTHON=$ROOT/python/.venv/Scripts/python.exe; else PYTHON=$ROOT/python/.venv/bin/python; fi
fi

ACTIVE=()
for language in $LANGUAGES; do
    case $language in
        java) prepare_java && ACTIVE+=(java) || log "ERROR building Java, skipped (see build_java.log)" ;;
        cpp) prepare_cpp && ACTIVE+=(cpp) || log "ERROR building C++, skipped (see build_cpp.log)" ;;
        python) ACTIVE+=(python) ;;
    esac
done

STARTED_MONGO=0
if ! mongo_ping; then
    if [ -n "$MONGOD" ]; then
        log "Starting MongoDB"
        "$MONGOD" --dbpath "$MONGO_DBPATH" --bind_ip 127.0.0.1 >> "$LOGS/mongod.log" 2>&1 &
        for _ in $(seq 1 30); do mongo_ping && break; sleep 1; done
        mongo_ping && STARTED_MONGO=1 || log "WARNING: MongoDB does not start, the MONGO structure will be skipped"
    else
        log "WARNING: MongoDB is not running, the MONGO structure will be skipped"
    fi
fi

if [ ! -d "$ROOT/datalakeBookHierarchy" ]; then
    log "Creating the shared datalakes (InitData)"
    (cd "$ROOT/python" && PYTHONIOENCODING=utf-8 "$PYTHON" -m benchmarks.datalake.init_data) >> "$LOGS/init_data.log" 2>&1
fi

for language in "${ACTIVE[@]}"; do
    rm -f "$ROOT/benchmark/results/$language"/*.csv
done

FAILED=0
for qualified in "${BENCHMARKS[@]}"; do
    name=${qualified#*.}
    args=""
    [ "$QUICK" = 1 ] && args=$(quick_args "$name")
    for language in "${ACTIVE[@]}"; do
        start=$(date +%s)
        log "START  $language $name $args"
        if run_benchmark "$language" "$qualified" "$args" > "$LOGS/${language}_${name}.log" 2>&1; then
            log "OK     $language $name ($(( $(date +%s) - start )) s)"
        else
            FAILED=$((FAILED + 1))
            log "ERROR  $language $name ($(( $(date +%s) - start )) s), see ${language}_${name}.log"
        fi
    done
done

log "Generating plots"
(cd "$ROOT/python" && PYTHONIOENCODING=utf-8 "$PYTHON" -m plots.plot_results) > "$LOGS/plots.log" 2>&1 || log "ERROR generating plots (see plots.log)"

[ "$STARTED_MONGO" = 1 ] && mongo_shutdown && log "MongoDB stopped"
log "Done: $FAILED benchmarks failed. Logs in $LOGS"
[ "$FAILED" = 0 ]
