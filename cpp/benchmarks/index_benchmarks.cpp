#include "benchmarks.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <iostream>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>

#include <mongoc/mongoc.h>

#ifdef _WIN32
#include <windows.h>
#include <psapi.h>
#endif

#include "books.hpp"
#include "files.hpp"
#include "index.hpp"
#include "java_random.hpp"
#include "utf8.hpp"

namespace stage1::benchmarks {

namespace fs = std::filesystem;

namespace {

const std::string MONGO_URI = "mongodb://localhost:27017/?serverSelectionTimeoutMS=1000";
const std::string MONGO_DATABASE = "index_benchmark";
const std::string MONGO_COLLECTION = "inverted_index";
const std::vector<std::string> STRUCTURES = {"JSON", "FOLDER", "MONGO"};
const std::vector<std::string> BOOK_COUNTS = {"25", "50", "100"};
constexpr int NEW_BOOKS = 10;
constexpr int FREQUENT_TERMS = 40;
constexpr int RANDOM_TERMS = 40;
constexpr int MISSING_TERMS = 20;
constexpr int QUERIES = FREQUENT_TERMS + RANDOM_TERMS + MISSING_TERMS;
constexpr long long BLOCK_SIZE = 4096;

struct MongoClient {
    mongoc_client_t* client;
    MongoClient() {
        ensure_mongo_initialized();
        client = mongoc_client_new(MONGO_URI.c_str());
    }
    ~MongoClient() { mongoc_client_destroy(client); }
    MongoClient(const MongoClient&) = delete;
    MongoClient& operator=(const MongoClient&) = delete;

    bool command(const char* database, bson_t* command, bson_t* reply) {
        bson_error_t error;
        bool ok = mongoc_client_command_simple(client, database, command, nullptr, reply, &error);
        bson_destroy(command);
        return ok;
    }
};

bool is_mongo_available() {
    MongoClient mongo;
    bson_t reply;
    bool ok = mongo.command("admin", BCON_NEW("ping", BCON_INT32(1)), &reply);
    bson_destroy(&reply);
    return ok;
}

std::vector<std::string> available_structures() {
    std::vector<std::string> structures;
    for (const std::string& structure : STRUCTURES)
        if (structure != "MONGO" || is_mongo_available()) structures.push_back(structure);
    return structures;
}

void drop_mongo_database() {
    MongoClient mongo;
    mongoc_database_t* database = mongoc_client_get_database(mongo.client, MONGO_DATABASE.c_str());
    bson_error_t error;
    mongoc_database_drop(database, &error);
    mongoc_database_destroy(database);
}

void reset_mongo(const std::string& structure) {
    if (structure != "MONGO") return;
    if (!is_mongo_available()) throw std::runtime_error("MongoDB no está arrancado en localhost:27017");
    drop_mongo_database();
}

std::unique_ptr<InvertedIndex> create_index(const std::string& structure, const fs::path& work_dir, const Tokenizer& tokenizer) {
    if (structure == "JSON") return std::make_unique<MonolithicJsonIndex>(work_dir / "inverted_index.json", tokenizer);
    if (structure == "FOLDER") return std::make_unique<HierarchicalFolderIndex>(work_dir / "inverted_index", tokenizer);
    if (structure == "MONGO") return std::make_unique<MongoInvertedIndex>(MONGO_URI, MONGO_DATABASE, MONGO_COLLECTION, tokenizer);
    throw std::invalid_argument("Estructura no soportada: " + structure);
}

void dispose(const std::string& structure, std::unique_ptr<InvertedIndex>& index, const fs::path& work_dir) {
    index.reset();
    fs::remove_all(work_dir);
    if (structure == "MONGO") drop_mongo_database();
}

const Tokenizer& shared_tokenizer() {
    static const Tokenizer tokenizer;
    return tokenizer;
}

class IndexBuildState : public State {
public:
    IndexBuildState(std::string structure, int books) : structure_(std::move(structure)), books_(books) {}

    void setup_trial() override { corpus_ = load_books(books_); }

    void setup_iteration() override {
        work_dir_ = temp_directory("index_build_");
        reset_mongo(structure_);
        index_ = create_index(structure_, work_dir_, shared_tokenizer());
    }

    void teardown_iteration() override { dispose(structure_, index_, work_dir_); }

    void build_index_from_scratch() {
        for (const Book& book : corpus_) index_->index_book(book.id, book.body);
        index_->flush();
    }

private:
    std::string structure_;
    int books_;
    std::vector<Book> corpus_;
    fs::path work_dir_;
    std::unique_ptr<InvertedIndex> index_;
};

class IndexUpdateState : public State {
public:
    IndexUpdateState(std::string structure, int books) : structure_(std::move(structure)), books_(books) {}

    void setup_trial() override {
        std::vector<Book> corpus = load_books(books_ + NEW_BOOKS);
        existing_books_.assign(corpus.begin(), corpus.begin() + books_);
        new_books_.assign(corpus.begin() + books_, corpus.end());
    }

    void setup_iteration() override {
        work_dir_ = temp_directory("index_update_");
        reset_mongo(structure_);
        index_ = create_index(structure_, work_dir_, shared_tokenizer());
        for (const Book& book : existing_books_) index_->index_book(book.id, book.body);
        index_->flush();
    }

    void teardown_iteration() override { dispose(structure_, index_, work_dir_); }

    void add_new_books() {
        for (const Book& book : new_books_) index_->index_book(book.id, book.body);
        index_->flush();
    }

private:
    std::string structure_;
    int books_;
    std::vector<Book> existing_books_;
    std::vector<Book> new_books_;
    fs::path work_dir_;
    std::unique_ptr<InvertedIndex> index_;
};

std::vector<std::string> build_queries(const std::vector<Book>& corpus, const Tokenizer& tokenizer) {
    std::unordered_map<std::string, int> document_frequency;
    for (const Book& book : corpus) {
        std::vector<std::string> terms = tokenizer.tokenize(book.body);
        std::unordered_set<std::string> unique(terms.begin(), terms.end());
        for (const std::string& term : unique) ++document_frequency[term];
    }
    std::vector<std::pair<std::u32string, std::string>> keyed;
    keyed.reserve(document_frequency.size());
    for (const auto& entry : document_frequency) keyed.emplace_back(utf8::utf16_order_key(entry.first), entry.first);
    std::sort(keyed.begin(), keyed.end(), [&](const auto& a, const auto& b) {
        int fa = document_frequency.at(a.second);
        int fb = document_frequency.at(b.second);
        return fa != fb ? fa > fb : a.first < b.first;
    });
    std::vector<std::string> queries;
    for (int i = 0; i < FREQUENT_TERMS; ++i) queries.push_back(keyed[i].second);
    std::vector<std::string> others;
    for (size_t i = FREQUENT_TERMS; i < keyed.size(); ++i) others.push_back(keyed[i].second);
    JavaRandom(42).shuffle(others);
    queries.insert(queries.end(), others.begin(), others.begin() + RANDOM_TERMS);
    for (int i = 0; i < MISSING_TERMS; ++i) queries.push_back("missingterm" + std::to_string(i));
    return queries;
}

class IndexQueryState : public State {
public:
    IndexQueryState(std::string structure, int books) : structure_(std::move(structure)), books_(books) {}

    void setup_trial() override {
        std::vector<Book> corpus = load_books(books_);
        work_dir_ = temp_directory("index_query_");
        reset_mongo(structure_);
        index_ = create_index(structure_, work_dir_, shared_tokenizer());
        for (const Book& book : corpus) index_->index_book(book.id, book.body);
        index_->flush();
        queries_ = build_queries(corpus, shared_tokenizer());
    }

    void teardown_trial() override { dispose(structure_, index_, work_dir_); }

    void search_terms() {
        for (const std::string& query : queries_) consume(index_->search(query));
    }

private:
    std::string structure_;
    int books_;
    std::vector<std::string> queries_;
    fs::path work_dir_;
    std::unique_ptr<InvertedIndex> index_;
};

template <class S>
std::function<std::unique_ptr<State>(const ParamValues&)> index_state() {
    return [](const ParamValues& values) {
        return std::make_unique<S>(values.at("structure"), std::stoi(values.at("books")));
    };
}

struct DiskUsage {
    long long files = 0;
    long long directories = 0;
    long long content_bytes = 0;
    long long disk_bytes = 0;
};

DiskUsage file_disk_usage(const fs::path& root) {
    DiskUsage usage;
    files::walk(root, [&](const files::WalkEntry& entry) {
        if (entry.is_directory) {
            ++usage.directories;
            return;
        }
        long long size = static_cast<long long>(entry.size);
        ++usage.files;
        usage.content_bytes += size;
        usage.disk_bytes += std::max(1LL, (size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE;
    });
    return usage;
}

long long bson_number(const bson_t* document, const char* key) {
    bson_iter_t iterator;
    return bson_iter_init_find(&iterator, document, key) ? bson_iter_as_int64(&iterator) : 0;
}

DiskUsage mongo_disk_usage() {
    MongoClient mongo;
    bson_t reply;
    mongo.command("admin", BCON_NEW("fsync", BCON_INT32(1)), &reply);
    bson_destroy(&reply);
    mongoc_collection_t* collection = mongoc_client_get_collection(mongo.client, MONGO_DATABASE.c_str(), MONGO_COLLECTION.c_str());
    bson_t* pipeline = BCON_NEW("pipeline", "[", "{", "$collStats", "{", "storageStats", "{", "}", "}", "}", "]");
    mongoc_cursor_t* cursor = mongoc_collection_aggregate(collection, MONGOC_QUERY_NONE, pipeline, nullptr, nullptr);
    const bson_t* stats;
    DiskUsage usage;
    if (mongoc_cursor_next(cursor, &stats)) {
        bson_iter_t iterator;
        if (bson_iter_init_find(&iterator, stats, "storageStats") && BSON_ITER_HOLDS_DOCUMENT(&iterator)) {
            uint32_t length;
            const uint8_t* data;
            bson_iter_document(&iterator, &length, &data);
            bson_t storage;
            bson_init_static(&storage, data, length);
            usage.files = bson_number(&storage, "count");
            usage.content_bytes = bson_number(&storage, "size");
            usage.disk_bytes = bson_number(&storage, "storageSize") + bson_number(&storage, "totalIndexSize");
        }
    }
    mongoc_cursor_destroy(cursor);
    bson_destroy(pipeline);
    mongoc_collection_destroy(collection);
    return usage;
}

long long private_bytes() {
#ifdef _WIN32
    PROCESS_MEMORY_COUNTERS_EX counters{};
    GetProcessMemoryInfo(GetCurrentProcess(), reinterpret_cast<PROCESS_MEMORY_COUNTERS*>(&counters), sizeof counters);
    return static_cast<long long>(counters.PrivateUsage);
#else
    return 0;
#endif
}

std::string grouped(long long value) {
    std::string digits = std::to_string(value);
    std::string out;
    int count = 0;
    for (auto it = digits.rbegin(); it != digits.rend(); ++it) {
        if (count > 0 && count % 3 == 0 && *it != '-') out.push_back(',');
        out.push_back(*it);
        ++count;
    }
    return {out.rbegin(), out.rend()};
}

}

Definition index_build_benchmark() {
    return {"benchmarks.index.IndexBuildBenchmark", {Mode::SingleShot}, "ms", {1}, {3},
            {{"structure", STRUCTURES}, {"books", BOOK_COUNTS}}, index_state<IndexBuildState>(),
            {method("buildIndexFromScratch", &IndexBuildState::build_index_from_scratch)}};
}

Definition index_update_benchmark() {
    return {"benchmarks.index.IndexUpdateBenchmark", {Mode::SingleShot}, "ms", {0}, {3},
            {{"structure", STRUCTURES}, {"books", BOOK_COUNTS}}, index_state<IndexUpdateState>(),
            {method("addNewBooks", &IndexUpdateState::add_new_books)}};
}

Definition index_query_benchmark() {
    return {"benchmarks.index.IndexQueryBenchmark", {Mode::AverageTime}, "us", {2, 2}, {5, 2},
            {{"structure", STRUCTURES}, {"books", BOOK_COUNTS}}, index_state<IndexQueryState>(),
            {method("searchTerms", &IndexQueryState::search_terms, QUERIES)}};
}

void run_index_benchmark(Definition definition, Options options) {
    std::vector<std::string> structures = available_structures();
    if (structures.size() < STRUCTURES.size())
        std::cout << "[BENCHMARK] MongoDB no está arrancado: se omite la estructura MONGO.\n";
    if (!options.params.contains("structure")) options.params["structure"] = structures;
    run(std::move(definition), options);
}

void index_storage_report(const Options& options) {
    std::vector<int> counts = {25, 50, 100};
    if (auto books = options.params.find("books"); books != options.params.end()) {
        counts.clear();
        for (const std::string& value : books->second) counts.push_back(std::stoi(value));
        std::sort(counts.begin(), counts.end());
    }
    std::vector<std::string> structures = available_structures();
    if (structures.size() < STRUCTURES.size())
        std::cout << "[BENCHMARK] MongoDB no está arrancado: se omite la estructura MONGO.\n";
    std::vector<Book> all_books = load_books(counts.back());
    const Tokenizer& tokenizer = shared_tokenizer();
    std::vector<std::string> rows;
    for (int count : counts) {
        std::vector<Book> corpus(all_books.begin(), all_books.begin() + count);
        std::unordered_set<std::string> vocabulary;
        for (const Book& book : corpus)
            for (std::string& term : tokenizer.tokenize(book.body)) vocabulary.insert(std::move(term));
        for (const std::string& structure : structures) {
            fs::path work_dir = temp_directory("index_storage_");
            reset_mongo(structure);
            std::unique_ptr<InvertedIndex> index;
            long long memory_before = private_bytes();
            auto start = std::chrono::steady_clock::now();
            index = create_index(structure, work_dir, tokenizer);
            for (const Book& book : corpus) index->index_book(book.id, book.body);
            index->flush();
            long long build_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
            long long retained = std::max(0LL, private_bytes() - memory_before);
            consume(index->search("whale"));
            DiskUsage disk = structure == "MONGO" ? mongo_disk_usage() : file_disk_usage(work_dir);
            dispose(structure, index, work_dir);
            rows.push_back(structure + "," + std::to_string(count) + "," + std::to_string(vocabulary.size()) + ","
                           + std::to_string(build_ms) + "," + std::to_string(disk.files) + "," + std::to_string(disk.directories) + ","
                           + std::to_string(disk.content_bytes) + "," + std::to_string(disk.disk_bytes) + "," + std::to_string(retained));
            std::printf("[BENCHMARK] %-6s %3d libros | %7s términos | %8s ms | %7s %-10s | %8.2f MB contenido | %8.2f MB en disco | %8.2f MB heap\n",
                        structure.c_str(), count, grouped(static_cast<long long>(vocabulary.size())).c_str(), grouped(build_ms).c_str(),
                        grouped(disk.files).c_str(), structure == "MONGO" ? "documentos" : "ficheros",
                        disk.content_bytes / 1e6, disk.disk_bytes / 1e6, retained / 1e6);
            std::fflush(stdout);
        }
    }
    auto path = write_csv("IndexStorageReport",
                          "structure,books,terms,build_ms,files,directories,content_bytes,disk_bytes,retained_heap_bytes", rows);
    std::cout << "[BENCHMARK] Resultados guardados en " << files::to_string(path) << "\n";
}

}
