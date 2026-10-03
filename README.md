# Stage 1 — Building the Data Layer

Search Engine Project · Big Data · Grado en Ciencia e Ingeniería de Datos · Universidad de Las Palmas de Gran Canaria

This repository implements the **data layer** of a search engine built from scratch over books from [Project Gutenberg](https://www.gutenberg.org/):

- a **datalake** that stores the header and body of every downloaded book,
- **datamarts** with the book metadata (SQLite) and an **inverted index**,
- a minimal **control layer** that coordinates downloading and indexing without duplicating or losing work,
- **benchmarks** of three datalake structures, three inverted-index structures and the metadata store, implemented in **Java, Python and C++** with identical outputs.

**Group:** Big Data System & Management

**Members:**

- Adrián Rodríguez Pérez
- Pablo Castillo Garcerá
- Pablo Martín Perdomo
- Joel López Guillén

---

## Table of contents

1. [Repository layout](#repository-layout)
2. [Architecture](#architecture)
3. [Requirements](#requirements)
4. [Sample dataset](#sample-dataset)
5. [Running the pipeline (Java)](#running-the-pipeline-java)
6. [Running the tests](#running-the-tests)
7. [Benchmarks](#benchmarks)
8. [Cross-language equivalence](#cross-language-equivalence)
9. [Known differences and caveats](#known-differences-and-caveats)

---

## Repository layout

```
stage_1/
├── pom.xml                      Java project (Maven, Java 25)
├── src/main/java/               Java pipeline: crawler, processor, datalake, metadata, index, control layer, search
├── src/main/resources/          stopwords.txt (shared by the three languages)
├── src/test/java/               JUnit tests and Java benchmarks (JMH)
├── python/                      Python port of the compared components + benchmarks + plotting script
│   ├── stage1/                  Gutenberg processor, datalake, tokenizer, inverted indexes, metadata
│   ├── benchmarks/              Benchmarks (JMH-like harness)
│   ├── plots/                   plot_results.py
│   └── tests/                   unittest tests
├── cpp/                         C++20 port of the compared components (CMake)
│   ├── src/                     Library stage1
│   ├── benchmarks/              stage1_benchmarks executable
│   └── tests/                   stage1_tests executable
├── benchmark/
│   ├── run_all.sh               Builds and runs every benchmark in the three languages, then plots
│   ├── raw/                     (generated) cache of downloaded Gutenberg books, pg<ID>.txt
│   ├── results/{java,python,cpp}/  (generated) CSV results
│   ├── plots/                   (generated) PNG plots
│   └── logs/                    (generated) one log per benchmark run
└── sample_dataset/              15 Gutenberg books to test everything offline
```

Folders marked as *generated* are git-ignored, as are `datalake/`, `datamarts/`, `control/` and the shared benchmark datalakes `datalake*Hierarchy/`.

---

## Architecture

```
          ┌──────────────┐   download    ┌──────────────────────┐   split    ┌──────────────┐
          │ Control layer│ ────────────▶ │ GutenbergCrawler     │ ─────────▶ │ Processor    │
          │ control/*.txt│               │ (rate limited, gzip) │            │ header/body  │
          └──────┬───────┘               └──────────────────────┘            └──────┬───────┘
                 │ index in batches of 10                                           │ store
                 ▼                                                                  ▼
   ┌─────────────────────────────┐   tokenize body    ┌──────────────────────────────────────┐
   │ Datamarts                   │ ◀───────────────── │ Datalake                             │
   │  · metadata.db (SQLite)     │   parse header     │  datalake/YYYYMMDD/HH/<ID>.body.txt  │
   │  · inverted_index.json      │                    │  datalake/YYYYMMDD/HH/<ID>.header.txt│
   └─────────────────────────────┘                    └──────────────────────────────────────┘
```

### Pipeline (`Main`)

1. The **control layer** (`ControlLayer`, `ControlRegistry`) keeps `control/downloaded_books.txt` and `control/indexed_books.txt`. A book is recorded only **after** its work is finished, so an interrupted run resumes without duplicating or losing books.
2. If at least 10 books are downloaded but not indexed, it indexes them as a batch. Otherwise it downloads the next book ID (1 to 1000) that is not downloaded yet. When no IDs remain, it indexes whatever is pending and stops.
3. **Download** (`GutenbergCrawler`, `GutenbergBookDownloader`): `https://www.gutenberg.org/cache/epub/<ID>/pg<ID>.txt`, with a random 0.77–1 s delay between requests, transparent gzip decoding and back-off on HTTP 403/429/5xx.
4. **Split** (`GutenbergBookProcessor`): header = text before the `*** START OF THE/THIS PROJECT GUTENBERG EBOOK …` line; body = text between that line and `*** END OF …`. Matching is case-insensitive and accepts `***START`, `EBOOK` / `E-BOOK`.
5. **Datalake** (`DatalakeLocalStoreTimeHierarchy`): `datalake/YYYYMMDD/HH/<ID>.header.txt` and `<ID>.body.txt`, written atomically (temporary file + rename).
6. **Metadata** (`MetadataExtractor`, `SqliteMetadataRepository`): `Title`, `Author` (falling back to `Editor`, `Translator`, `Compiler`) and `Language` are parsed from the header and stored in `datamarts/metadata.db` together with the body path.
7. **Inverted index** (`MonolithicJsonIndex`): `datamarts/inverted_index.json`.

### Datalake structures (benchmarked)

| Structure | Layout |
|---|---|
| `TIME_HIERARCHY` | `YYYYMMDD/HH/<ID>.body.txt` (used by the pipeline) |
| `BOOK_HIERARCHY` | `<ID>/<ID>.body.txt` |
| `ID_RANGE_HIERARCHY` | `batch_1000_to_1999/<ID>.body.txt` |

On start-up every store scans its folder and keeps the IDs of complete books (with a `.body.txt`) in memory. Header-only books and abandoned `.tmp` files are ignored, so they are downloaded again.

### Inverted-index structures (benchmarked)

| Structure | Storage |
|---|---|
| `JSON` | One file `inverted_index.json`, terms sorted, compact: `{"adventure":[5,12],"island":[12]}` |
| `FOLDER` | One file per term: `inverted_index/A/adventure.txt` with one book ID per line. The folder is the upper-case first letter. Windows reserved names (`con`, `prn`, `aux`, `nul`, `com1`–`com9`, `lpt1`–`lpt9`) get a `_` prefix. |
| `MONGO` | MongoDB collection `{term, postings}` with a unique index on `term`. Updates use `bulkWrite` upserts with `$addToSet` + `$each`. |

### Tokenizer

Identical rules in the three languages, applied to the book body:

1. Unicode NFD normalisation and removal of combining marks (`Café` → `Cafe`).
2. Lower-casing, folding the final sigma `ς` into `σ`.
3. Tokens are maximal runs of Unicode letters and digits (`[\p{L}\p{N}]+`).
4. Tokens shorter than 2 or longer than 50 code points, and the stopwords in `src/main/resources/stopwords.txt`, are discarded.

### Search

`Search` looks up a single term in `datamarts/inverted_index.json` and prints the metadata of every matching book.

---

## Requirements

| Component | Needed for | Version used |
|---|---|---|
| JDK | Java pipeline, tests, benchmarks | 25 |
| Maven | Java build | 3.9 (the one bundled with IntelliJ IDEA works) |
| Python | Python port, benchmarks, plots | 3.12 |
| C++ toolchain | C++ port | GCC 16 (C++20), CMake ≥ 3.20, Ninja, pkg-config |
| C++ libraries | C++ port | mongo-c-driver ≥ 2.0 (`mongoc2`), utf8proc, SQLite 3, libcurl, zlib, nlohmann-json |
| MongoDB server | `MONGO` index structure (optional) | 8.x on `localhost:27017` |
| Internet | Downloading books not present in `benchmark/raw/` | — |

Everything was measured on **Windows 11**. The code also has POSIX paths for Linux and macOS, but those platforms have not been tested.

### Python environment

```bash
python -m venv python/.venv
# Windows:      python\.venv\Scripts\activate
# Linux/macOS:  source python/.venv/bin/activate
pip install -r python/requirements.txt
```

### C++ toolchain

**Windows (MSYS2).** Install [MSYS2](https://www.msys2.org/), open the **UCRT64** shell and run:

```bash
pacman -S mingw-w64-ucrt-x86_64-gcc mingw-w64-ucrt-x86_64-cmake mingw-w64-ucrt-x86_64-ninja \
          mingw-w64-ucrt-x86_64-pkgconf mingw-w64-ucrt-x86_64-mongo-c-driver \
          mingw-w64-ucrt-x86_64-utf8proc mingw-w64-ucrt-x86_64-sqlite3 mingw-w64-ucrt-x86_64-curl \
          mingw-w64-ucrt-x86_64-zlib mingw-w64-ucrt-x86_64-nlohmann-json
```

**macOS (Homebrew).** `brew install cmake ninja pkg-config mongo-c-driver utf8proc sqlite curl nlohmann-json`

**Debian/Ubuntu.** `sudo apt install build-essential cmake ninja-build pkg-config libutf8proc-dev libsqlite3-dev libcurl4-openssl-dev zlib1g-dev nlohmann-json3-dev`. The packaged mongo-c-driver is usually 1.x; version 2.x has to be [built from source](https://www.mongodb.com/docs/languages/c/c-driver/current/install-from-source/).

Build:

```bash
cmake -S cpp -B cpp/build -G Ninja
cmake --build cpp/build
```

### MongoDB (optional)

Start a local server before running anything that uses the `MONGO` structure:

```bash
mongod --dbpath <some-folder> --bind_ip 127.0.0.1
```

If MongoDB is not running, the benchmarks skip the `MONGO` structure and the Java MongoDB tests are skipped.

---

## Sample dataset

`sample_dataset/` contains the first 15 books of Project Gutenberg (8.4 MB, all with valid start and end markers), exactly as downloaded:

| ID | Title | Author |
|---|---|---|
| 1 | The Declaration of Independence of the United States of America | Thomas Jefferson |
| 2 | The United States Bill of Rights | United States |
| 3 | John F. Kennedy's Inaugural Address | John F. Kennedy |
| 4 | Lincoln's Gettysburg Address | Abraham Lincoln |
| 5 | The United States Constitution | United States |
| 6 | Give Me Liberty or Give Me Death | Patrick Henry |
| 7 | The Mayflower Compact | Unknown |
| 8 | Abraham Lincoln's Second Inaugural Address | Abraham Lincoln |
| 9 | Abraham Lincoln's First Inaugural Address | Abraham Lincoln |
| 10 | The King James Version of the Bible | Unknown |
| 11 | Alice's Adventures in Wonderland | Lewis Carroll |
| 12 | Through the Looking-Glass | Lewis Carroll |
| 13 | The Hunting of the Snark: An Agony in Eight Fits | Lewis Carroll |
| 14 | The 1990 CIA World Factbook | United States. Central Intelligence Agency |
| 15 | Moby-Dick; or, The Whale | Herman Melville |

### Quick test of the whole pipeline (offline, a few seconds)

Passing a folder to `Main` makes the pipeline read the books from it instead of downloading them. Everything else (split, datalake, metadata, inverted index and control layer) runs exactly as in the online mode:

```bash
mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt

# Linux/macOS
java -cp "target/classes:$(cat cp.txt)" Main sample_dataset
java -cp "target/classes:$(cat cp.txt)" Search whale

# Windows PowerShell
java -cp "target/classes;$(Get-Content cp.txt)" Main sample_dataset
java -cp "target/classes;$(Get-Content cp.txt)" Search whale
```

```
[CONTROL] Indexed 10 books: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
...
[CONTROL] Pipeline finished: 15 books indexed.

2 books contain "whale"
  [10] The King James Version of the Bible | Unknown | English | datalake\20261003\16\10.body.txt
  [15] Moby-Dick; or, The Whale | Herman Melville | English | datalake\20261003\16\15.body.txt
```

Running `Main sample_dataset` a second time does nothing, because the control layer already records the 15 books as downloaded and indexed. To run the pipeline again from scratch, delete `datalake/`, `datamarts/` and `control/`.

### Using the sample in the benchmarks

The benchmarks of the three languages read books from the cache `benchmark/raw/pg<ID>.txt` and only download the ones that are missing. Copying the sample there makes the quick benchmark run work offline for the index and metadata benchmarks:

```bash
mkdir -p benchmark/raw
cp sample_dataset/*.txt benchmark/raw/
```

The full benchmarks need up to 250 books. The missing ones are downloaded automatically (about one per second) and cached for later runs. IDs that do not exist in Gutenberg are remembered with a `pg<ID>.missing` marker.

---

## Running the pipeline (Java)

Build once and save the dependency classpath:

```bash
mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt
```

Run the pipeline. Without arguments it downloads books 1–1000 from Gutenberg, which takes a while because of the delay between requests. It can be stopped at any moment and resumes where it left off:

```bash
# Linux/macOS
java -cp "target/classes:$(cat cp.txt)" Main
# Windows PowerShell
java -cp "target/classes;$(Get-Content cp.txt)" Main
```

With a folder as argument (`Main sample_dataset`), it reads the `pg<ID>.txt` files of that folder instead of downloading them. It processes the IDs between the lowest and the highest found (see [Sample dataset](#sample-dataset)).

Outputs:

```
datalake/YYYYMMDD/HH/<ID>.header.txt, <ID>.body.txt
datamarts/metadata.db
datamarts/inverted_index.json
control/downloaded_books.txt, control/indexed_books.txt
```

Search a term, either as an argument or interactively (an empty line exits):

```bash
java -cp "target/classes:$(cat cp.txt)" Search whale      # Linux/macOS
java -cp "target/classes;$(Get-Content cp.txt)" Search whale   # Windows
```

Each result is printed as:

```
<N> books contain "<term>"
  [<book_id>] <title> | <author> | <language> | <path of the body in the datalake>
```

The project can also be opened in IntelliJ IDEA and `Main` / `Search` run from the IDE.

---

## Running the tests

```bash
mvn test                                                    # Java (JUnit 5)
cd python && python -m unittest discover -s tests -t .      # Python
./cpp/build/stage1_tests                                    # C++
```

The Java MongoDB tests run only when a MongoDB server is available on `localhost:27017`.

---

## Benchmarks

### What is measured

All benchmarks exist with the same name, parameters, iterations and output format in Java (JMH), Python and C++. Python and C++ use a small harness that reproduces JMH's modes and CSV output.

| Benchmark | Measures | Parameters |
|---|---|---|
| `BookDataProcessBenchmark` | Write throughput: split + store 100 books, and store only | 3 datalake structures |
| `IncrementalProcessingBenchmark` | Cost of rebuilding the in-memory index of a datalake (cold start) and of checking whether a book exists | 3 datalake structures |
| `LookUpCostBenchmark` | Time to read the header and body of a random book (IDs 1–200) | 3 datalake structures |
| `RecoveryBehaviorBenchmark` | Recovery after a crash: 500 complete books, 50 header-only, 50 abandoned `.tmp`; resume and repair | 3 datalake structures |
| `StorageOverheadBenchmark` | Files, directories and bytes of each shared datalake | 3 datalake structures |
| `IndexBuildBenchmark` | Indexing speed: build the index from scratch | 3 index structures × 25/50/100 books |
| `IndexUpdateBenchmark` | Update cost: add 10 new books to an existing index | 3 index structures × 25/50/100 books |
| `IndexQueryBenchmark` | Query time: 40 frequent, 40 random and 20 missing terms | 3 index structures × 25/50/100 books |
| `IndexStorageReport` | Build time, number of terms, files, content bytes, disk bytes (4 KB blocks) and retained memory | 3 index structures × 25/50/100 books |
| `MetadataInsertBenchmark` | Insertion speed: all books in one transaction vs one transaction per book | 500/5,000/50,000 books |
| `MetadataQueryBenchmark` | Path by ID, path by title, books by author (100 queries each) | 500/5,000/50,000 books |

The metadata benchmarks use synthetic metadata generated with a fixed seed. The three languages reproduce Java's `java.util.Random`, so the data and the queries are identical.

The datalake benchmarks that read existing data use the shared datalakes `datalakeTimeHierarchy/`, `datalakeBookHierarchy/` and `datalakeIdRangeHierarchy/` (books 1–250). `run_all.sh` creates them if they do not exist.

### Running everything

`benchmark/run_all.sh` builds Java and C++, starts MongoDB if needed, runs every benchmark in the three languages, generates the plots and stops MongoDB. On Windows, run it from the **MSYS2 UCRT64** shell.

```bash
export JAVA_HOME=/path/to/jdk-25
export MVN=mvn                                   # or the full path to mvn / mvn.cmd
export MONGOD=/path/to/mongod                    # optional: started and stopped by the script
export MONGO_DBPATH=/path/to/mongodb-data        # required if MONGOD is set

QUICK=1 ./benchmark/run_all.sh                   # quick check: 1 short iteration, 5 books
./benchmark/run_all.sh                           # full run with the real parameters
```

The quick check takes about 12 minutes once the books are cached. The first time, it also downloads the books needed by the datalake benchmarks (about 230, a few minutes).

Other variables: `LANGUAGES="java python cpp"` to choose languages, `PYTHON` for the Python interpreter (default `python/.venv`), `CPP_BUILD` for the C++ build folder (default `cpp/build`).

- Each benchmark runs in Java, then Python, then C++ before moving to the next one. This way any slow drift of the machine during the run does not always penalise the same language.
- A failing benchmark is logged and the run continues.
- `benchmark/logs/<timestamp>/summary.txt` lists every benchmark with its status and duration. There is one log per benchmark and language in the same folder.

The full run took about **9 hours** on our laptop. Most of it is the `FOLDER` index, which creates about 360,000 small files for 100 books, around one hour per language for the build benchmark and another for the update benchmark. For reliable numbers, use a machine that is plugged in, does not go to sleep and is otherwise idle.

Real-time antivirus scanning adds a cost to every file created, so on Windows it can noticeably slow down the `FOLDER` index benchmarks. Our results were obtained with Microsoft Defender's default real-time scanning enabled.

### Running a single benchmark

All three accept the same JMH-style options:

| Option | Meaning |
|---|---|
| `-wi N` / `-i N` | Warm-up / measurement iterations |
| `-w 1s` / `-r 1s` | Duration of each warm-up / measurement iteration (time-based modes) |
| `-p name=v1,v2` | Override a parameter, e.g. `-p books=25` or `-p structure=JSON,MONGO` |

```bash
# Java (classpath built with test-compile, see above)
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/test-classes:target/classes:$(cat cp.txt)" benchmarks.index.IndexBuildBenchmark -p books=25

# Python (from the python/ folder)
python -m benchmarks.index.index_build_benchmark -p books=25

# C++
./cpp/build/stage1_benchmarks IndexBuildBenchmark -p books=25
```

Python modules follow the snake case of the Java class name (`benchmarks.<package>.<snake_case_name>`). Running `stage1_benchmarks` without arguments lists every C++ benchmark. The shared datalakes can be created with `benchmarks.datalake.InitData` (Java), `python -m benchmarks.datalake.init_data` or `stage1_benchmarks InitData`.

### Results and plots

Every benchmark writes `benchmark/results/<language>/<BenchmarkName>.csv`. JMH benchmarks use JMH's CSV format (`Benchmark, Mode, Threads, Samples, Score, Score Error (99.9%), Unit, Param: …`) in the three languages. `IndexStorageReport` and `StorageOverheadBenchmark` write their own columns.

```bash
cd python && python -m plots.plot_results
```

This creates in `benchmark/plots/`:

- `<Benchmark>_languages.png`: Java vs Python vs C++ for each structure.
- `<Benchmark>_structures.png`: the structures compared within each language (log scale when values differ by more than 50×).

Error bars are the 99.9 % confidence interval reported by JMH. With only 3 measurement iterations this interval is wide, because Student's t is 12.9 for 2 degrees of freedom.

---

## Cross-language equivalence

The guide requires the same dataset, preprocessing rules and outputs in every language. The Python and C++ ports reproduce the Java behaviour exactly:

- The same Gutenberg marker rules (ASCII case-insensitive), Java's definition of whitespace for trimming, the same gzip handling and replacement of invalid UTF-8.
- The same tokenizer rules and stopword file.
- `inverted_index.json` with keys sorted in Java's `String` order (UTF-16 code units) and Gson's escaping.
- Folder names using full Unicode upper-casing (`ß` → `SS`) and the same reserved-name prefix.
- Metadata fields parsed with the semantics of Java's regex (line terminators, continuation lines, `Unknown` fallback). C++ implements them without regex.
- `java.util.Random` reproduced bit for bit for synthetic metadata and for the random query terms.

This was verified by building everything with the first 100 valid books in the three languages and comparing the outputs byte by byte. The datalake, the tokens of every book, `inverted_index.json`, the 358,582 files of the folder index, the MongoDB collection and the metadata were identical. A stress text with every Unicode code point also produced identical tokens for all characters assigned up to Unicode 15.

---

## Known differences and caveats

- **Final sigma.** Java decides between `σ` and `ς` with its own word-break iterator, which other languages cannot reproduce. The tokenizer therefore folds `ς` into `σ` in all three languages, so `ΟΔΟΣ` and `οδος` produce the same term.
- **Unicode version.** Java 25 uses Unicode 16, Python 3.12 Unicode 15 and utf8proc 2.11 Unicode 17. Only characters added in those versions are classified differently. None appear in the books used.
- **Retained memory** (`IndexStorageReport`) is measured differently in each language, so it is comparable between structures but not between languages. Java reports the growth of the heap after garbage collection, Python the deep size of the in-memory index, and C++ the growth of the process private bytes.
- **SQLite statements.** Python's `sqlite3` module caches prepared statements, while Java (JDBC) and C++ prepare them on every query. This favours Python slightly in `MetadataQueryBenchmark`.
- **`LookUpCostBenchmark`** picks book IDs with an unseeded random generator, so each run requests a different sequence. The average over thousands of lookups is stable.
- **One commit per book.** `insertOneTransactionPerBook` is dominated by the disk synchronisation of every SQLite commit (about 4 ms each with the default journal mode), so its cost is almost the same in the three languages.
