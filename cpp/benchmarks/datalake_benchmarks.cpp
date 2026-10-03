#include "benchmarks.hpp"

#include <cstdio>
#include <iostream>
#include <random>
#include <stdexcept>

#include "books.hpp"
#include "datalake.hpp"
#include "files.hpp"
#include "gutenberg.hpp"

namespace stage1::benchmarks {

namespace fs = std::filesystem;

namespace {

const std::vector<std::string> STRATEGIES = {"TIME_HIERARCHY", "BOOK_HIERARCHY", "ID_RANGE_HIERARCHY"};
constexpr int TOTAL_BOOKS = 100;
constexpr int MAX_BOOK_ID_TO_TRY = 300;
constexpr int MIN_LOOKUP_ID = 1;
constexpr int MAX_LOOKUP_ID = 200;
constexpr int FIRST_INIT_ID = 1;
constexpr int LAST_INIT_ID = 250;

fs::path path_for_strategy(const std::string& strategy) {
    if (strategy == "TIME_HIERARCHY") return files::repo_root() / "datalakeTimeHierarchy";
    if (strategy == "BOOK_HIERARCHY") return files::repo_root() / "datalakeBookHierarchy";
    if (strategy == "ID_RANGE_HIERARCHY") return files::repo_root() / "datalakeIdRangeHierarchy";
    throw std::invalid_argument("Unsupported strategy: " + strategy);
}

std::unique_ptr<Store> create_store(const std::string& strategy, const fs::path& path) {
    if (strategy == "TIME_HIERARCHY") return std::make_unique<DatalakeLocalStoreTimeHierarchy>(path);
    if (strategy == "BOOK_HIERARCHY") return std::make_unique<DatalakeLocalStoreBookHierarchy>(path);
    if (strategy == "ID_RANGE_HIERARCHY") return std::make_unique<DatalakeLocalStoreIdRangeHierarchy>(path);
    throw std::invalid_argument("Unknown strategy: " + strategy);
}

std::string lowercase(std::string text) {
    for (char& c : text) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return text;
}

class BookDataProcessState : public State {
public:
    explicit BookDataProcessState(std::string strategy) : strategy_(std::move(strategy)) {}

    void setup_trial() override {
        for (int book_id = 1; book_id <= MAX_BOOK_ID_TO_TRY && static_cast<int>(raw_books_.size()) < TOTAL_BOOKS; ++book_id) {
            auto raw = raw_book(book_id);
            if (!raw) continue;
            auto book = gutenberg::process(*raw);
            if (!book) continue;
            raw_books_.push_back(std::move(*raw));
            parsed_books_.push_back(std::move(*book));
        }
        if (static_cast<int>(raw_books_.size()) < TOTAL_BOOKS)
            throw std::runtime_error("Only " + std::to_string(raw_books_.size()) + " valid books, needed: " + std::to_string(TOTAL_BOOKS));
    }

    void setup_iteration() override {
        datalake_ = temp_directory("datalake_bench_" + lowercase(strategy_) + "_");
        store_ = create_store(strategy_, datalake_);
        stored_books_ = 0;
    }

    void teardown_iteration() override {
        store_.reset();
        fs::remove_all(datalake_);
        if (stored_books_ != TOTAL_BOOKS)
            throw std::runtime_error("Stored " + std::to_string(stored_books_) + " books of " + std::to_string(TOTAL_BOOKS));
    }

    void measure_split_and_store() {
        for (const RawBook& raw : raw_books_)
            if (auto book = gutenberg::process(raw)) store(*book);
    }

    void measure_store_only() {
        for (const Book& book : parsed_books_) store(book);
    }

private:
    void store(const Book& book) {
        consume(store_->store_data(book));
        ++stored_books_;
    }

    std::string strategy_;
    std::vector<RawBook> raw_books_;
    std::vector<Book> parsed_books_;
    fs::path datalake_;
    std::unique_ptr<Store> store_;
    int stored_books_ = 0;
};

class IncrementalProcessingState : public State {
public:
    IncrementalProcessingState(std::string strategy, int existing_book_id, int non_existing_book_id)
        : strategy_(std::move(strategy)), existing_book_id_(existing_book_id), non_existing_book_id_(non_existing_book_id) {}

    void setup_trial() override {
        store_ = create_store(strategy_, path_for_strategy(strategy_));
        std::cout << "\n[SETUP] Evaluating indexes on the REAL datalake (" << strategy_ << ")...\n";
    }

    void measure_cold_indexing_overhead() {
        auto store = create_store(strategy_, path_for_strategy(strategy_));
        consume(store->exists(existing_book_id_));
    }

    void measure_exists_hit() { consume(store_->exists(existing_book_id_)); }

    void measure_exists_miss() { consume(store_->exists(non_existing_book_id_)); }

private:
    std::string strategy_;
    int existing_book_id_;
    int non_existing_book_id_;
    std::unique_ptr<Store> store_;
};

class LookUpCostState : public State {
public:
    explicit LookUpCostState(std::string strategy) : strategy_(std::move(strategy)) {}

    void setup_trial() override {
        fs::path target = path_for_strategy(strategy_);
        store_ = create_store(strategy_, target);
        std::cout << "\n[SETUP] Using the REAL datalake at: " << files::to_string(target) << "\n";
    }

    void measure_header_and_body_lookup() { consume(store_->get_book(distribution_(random_))); }

private:
    std::string strategy_;
    std::unique_ptr<Store> store_;
    std::mt19937 random_{std::random_device{}()};
    std::uniform_int_distribution<int> distribution_{MIN_LOOKUP_ID, MAX_LOOKUP_ID};
};

class RecoveryBehaviorState : public State {
public:
    explicit RecoveryBehaviorState(std::string strategy) : strategy_(std::move(strategy)) {}

    void setup_invocation() override {
        datalake_ = temp_directory("dl_recovery_" + lowercase(strategy_) + "_");
        for (int id = 1; id <= 500; ++id) {
            write(id, std::to_string(id) + ".header.txt", "Header " + std::to_string(id));
            write(id, std::to_string(id) + ".body.txt", "Body " + std::to_string(id));
        }
        for (int id = 501; id <= 550; ++id) write(id, std::to_string(id) + ".header.txt", "Header " + std::to_string(id));
        for (int id = 551; id <= 600; ++id) write(id, std::to_string(id) + ".header.txt.tmp", "Temporal inconcluso " + std::to_string(id));
    }

    void teardown_invocation() override {
        std::error_code error;
        fs::remove_all(datalake_, error);
    }

    void measure_recovery_time_after_crash() {
        auto store = create_store(strategy_, datalake_);
        if (store->exists(505)) throw std::runtime_error("Recovery failure: an incomplete book was indexed.");
        consume(store);
    }

    void measure_pipeline_resume_and_repair() {
        auto store = create_store(strategy_, datalake_);
        for (int id = 501; id <= 550; ++id)
            store->store_data({id, "Header reparado " + std::to_string(id), "Body completado " + std::to_string(id)});
        consume(store);
    }

private:
    void write(int id, const std::string& name, const std::string& content) {
        fs::path directory = target_directory(id);
        fs::create_directories(directory);
        files::write(directory / name, content);
    }

    fs::path target_directory(int id) const {
        if (strategy_ == "BOOK_HIERARCHY") return datalake_ / std::to_string(id);
        if (strategy_ == "ID_RANGE_HIERARCHY") {
            int batch = id / 1000;
            return datalake_ / ("batch_" + std::to_string(batch * 1000) + "_to_" + std::to_string((batch + 1) * 1000 - 1));
        }
        if (strategy_ == "TIME_HIERARCHY") return datalake_ / "20260929" / "17";
        throw std::invalid_argument("Invalid strategy: " + strategy_);
    }

    std::string strategy_;
    fs::path datalake_;
};

template <class S>
std::function<std::unique_ptr<State>(const ParamValues&)> strategy_state() {
    return [](const ParamValues& values) { return std::make_unique<S>(values.at("storeStrategy")); };
}

}

Definition book_data_process_benchmark() {
    return {"benchmarks.datalake.BookDataProcessBenchmark", {Mode::SingleShot}, "ms", {2}, {5},
            {{"storeStrategy", STRATEGIES}}, strategy_state<BookDataProcessState>(),
            {method("measureSplitAndStore", &BookDataProcessState::measure_split_and_store, TOTAL_BOOKS),
             method("measureStoreOnly", &BookDataProcessState::measure_store_only, TOTAL_BOOKS)}};
}

Definition incremental_processing_benchmark() {
    return {"benchmarks.datalake.IncrementalProcessingBenchmark", {Mode::AverageTime, Mode::Throughput}, "ms", {3, 2}, {5, 3},
            {{"storeStrategy", STRATEGIES}, {"existingBookId", {"15"}}, {"nonExistingBookId", {"999999"}}},
            [](const ParamValues& values) {
                return std::make_unique<IncrementalProcessingState>(values.at("storeStrategy"), std::stoi(values.at("existingBookId")),
                                                                    std::stoi(values.at("nonExistingBookId")));
            },
            {method("measureColdIndexingOverhead", &IncrementalProcessingState::measure_cold_indexing_overhead),
             method("measureExistsHit", &IncrementalProcessingState::measure_exists_hit, 1, "ns"),
             method("measureExistsMiss", &IncrementalProcessingState::measure_exists_miss, 1, "ns")}};
}

Definition look_up_cost_benchmark() {
    return {"benchmarks.datalake.LookUpCostBenchmark", {Mode::AverageTime}, "us", {3}, {5},
            {{"storeStrategy", STRATEGIES}}, strategy_state<LookUpCostState>(),
            {method("measureHeaderAndBodyLookup", &LookUpCostState::measure_header_and_body_lookup)}};
}

Definition recovery_behavior_benchmark() {
    return {"benchmarks.datalake.RecoveryBehaviorBenchmark", {Mode::SingleShot}, "ms", {5}, {5},
            {{"storeStrategy", STRATEGIES}}, strategy_state<RecoveryBehaviorState>(),
            {method("measureRecoveryTimeAfterCrash", &RecoveryBehaviorState::measure_recovery_time_after_crash),
             method("measurePipelineResumeAndRepair", &RecoveryBehaviorState::measure_pipeline_resume_and_repair)}};
}

void storage_overhead_benchmark() {
    std::vector<std::string> rows;
    for (const std::string& strategy : STRATEGIES) {
        fs::path path = path_for_strategy(strategy);
        std::cout << "Strategy: " << strategy << " -> Path: " << files::to_string(path) << "\n";
        if (!files::exists(path)) {
            std::cout << "Path not found, skipping.\n\n";
            continue;
        }
        long long file_count = 0, directories = 0, size = 0;
        files::walk(path, [&](const files::WalkEntry& entry) {
            if (entry.is_directory) {
                ++directories;
            } else {
                ++file_count;
                size += static_cast<long long>(entry.size);
            }
        });
        long long average = file_count > 0 ? size / file_count : 0;
        std::printf("=== STORAGE METRICS ===\nTotal files          : %lld\nTotal directories    : %lld\n"
                    "Size on disk (MB)    : %.2f MB\nAverage file size    : %lld bytes\n\n",
                    file_count, directories, size / (1024.0 * 1024.0), average);
        std::fflush(stdout);
        rows.push_back(strategy + "," + std::to_string(file_count) + "," + std::to_string(directories) + ","
                       + std::to_string(size) + "," + std::to_string(average));
    }
    auto path = write_csv("StorageOverheadBenchmark", "strategy,files,directories,size_bytes,average_file_size_bytes", rows);
    std::cout << "[BENCHMARK] Results saved to " << files::to_string(path) << "\n";
}

void init_data() {
    std::vector<std::unique_ptr<Store>> stores;
    for (const std::string& strategy : STRATEGIES) stores.push_back(create_store(strategy, path_for_strategy(strategy)));
    CompositeStore store(std::move(stores));
    for (int book_id = FIRST_INIT_ID; book_id <= LAST_INIT_ID; ++book_id) {
        if (store.exists(book_id)) continue;
        auto raw = raw_book(book_id);
        if (!raw) continue;
        if (auto book = gutenberg::process(*raw)) {
            store.store_data(*book);
            std::cout << "Book " << book_id << " stored\n";
        }
    }
}

fs::path temp_directory(const std::string& prefix) {
    static std::mt19937_64 random{std::random_device{}()};
    while (true) {
        fs::path candidate = fs::temp_directory_path() / (prefix + std::to_string(random()));
        if (fs::create_directory(candidate)) return candidate;
    }
}

}
