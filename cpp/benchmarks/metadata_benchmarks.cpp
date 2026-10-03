#include "benchmarks.hpp"

#include <cstdio>
#include <filesystem>
#include <stdexcept>

#include "files.hpp"
#include "java_random.hpp"
#include "metadata.hpp"

namespace stage1::benchmarks {

namespace fs = std::filesystem;

namespace {

constexpr int64_t SEED = 42;
constexpr int BOOKS_PER_AUTHOR = 20;
constexpr int QUERIES = 100;
constexpr int64_t QUERY_SEED = 7;
const std::vector<std::string> LANGUAGES = {
    "English", "English", "English", "English", "English", "English", "English", "French", "German", "Spanish"};
const std::vector<std::string> BOOK_COUNTS = {"500", "5000", "50000"};

int author_count(int book_count) {
    return std::max(1, book_count / BOOKS_PER_AUTHOR);
}

std::string author(int index) {
    return "Author " + std::to_string(index);
}

std::string title(int book_id) {
    return "Title of book " + std::to_string(book_id);
}

std::vector<BookMetadata> generate(int count) {
    JavaRandom random(SEED);
    int authors = author_count(count);
    std::vector<BookMetadata> books;
    books.reserve(count);
    for (int book_id = 1; book_id <= count; ++book_id) {
        std::string author_name = author(random.next_int(authors));
        std::string language = LANGUAGES[random.next_int(static_cast<int32_t>(LANGUAGES.size()))];
        char hour[3];
        std::snprintf(hour, sizeof hour, "%02d", book_id % 24);
        fs::path body_path = fs::path("datalake") / "20260101" / hour / (std::to_string(book_id) + ".body.txt");
        books.push_back({book_id, title(book_id), author_name, language, files::to_string(body_path)});
    }
    return books;
}

class MetadataInsertState : public State {
public:
    explicit MetadataInsertState(int books) : books_(books) {}

    void setup_trial() override { metadata_ = generate(books_); }

    void setup_iteration() override {
        database_dir_ = temp_directory("metadata_insert_");
        repository_ = std::make_unique<SqliteMetadataRepository>(database_dir_ / "metadata.db");
    }

    void teardown_iteration() override {
        int inserted = repository_->count();
        repository_.reset();
        fs::remove_all(database_dir_);
        if (inserted != books_)
            throw std::runtime_error("Se insertaron " + std::to_string(inserted) + " libros de " + std::to_string(books_));
    }

    void insert_all_in_one_transaction() { repository_->save_all(metadata_); }

    void insert_one_transaction_per_book() {
        for (const BookMetadata& book : metadata_) repository_->save(book);
    }

private:
    int books_;
    std::vector<BookMetadata> metadata_;
    fs::path database_dir_;
    std::unique_ptr<SqliteMetadataRepository> repository_;
};

class MetadataQueryState : public State {
public:
    explicit MetadataQueryState(int books) : books_(books) {}

    void setup_trial() override {
        database_dir_ = temp_directory("metadata_query_");
        repository_ = std::make_unique<SqliteMetadataRepository>(database_dir_ / "metadata.db");
        repository_->save_all(generate(books_));
        JavaRandom random(QUERY_SEED);
        for (int i = 0; i < QUERIES; ++i) {
            int book_id = 1 + random.next_int(books_);
            book_ids_.push_back(book_id);
            titles_.push_back(title(book_id));
            authors_.push_back(author(random.next_int(author_count(books_))));
        }
    }

    void teardown_trial() override {
        repository_.reset();
        fs::remove_all(database_dir_);
    }

    void find_path_by_id() {
        for (int book_id : book_ids_) {
            auto book = repository_->find_by_id(book_id);
            consume(book ? book->body_path : std::nullopt);
        }
    }

    void find_path_by_title() {
        for (const std::string& title : titles_) consume(repository_->find_by_title(title));
    }

    void find_books_by_author() {
        for (const std::string& author : authors_) consume(repository_->find_by_author(author));
    }

private:
    int books_;
    fs::path database_dir_;
    std::unique_ptr<SqliteMetadataRepository> repository_;
    std::vector<int> book_ids_;
    std::vector<std::string> titles_;
    std::vector<std::string> authors_;
};

}

Definition metadata_insert_benchmark() {
    return {
        "benchmarks.metadata.MetadataInsertBenchmark",
        {Mode::SingleShot},
        "ms",
        {1},
        {3},
        {{"books", BOOK_COUNTS}},
        [](const ParamValues& values) { return std::make_unique<MetadataInsertState>(std::stoi(values.at("books"))); },
        {method("insertAllInOneTransaction", &MetadataInsertState::insert_all_in_one_transaction),
         method("insertOneTransactionPerBook", &MetadataInsertState::insert_one_transaction_per_book)},
    };
}

Definition metadata_query_benchmark() {
    return {
        "benchmarks.metadata.MetadataQueryBenchmark",
        {Mode::AverageTime},
        "us",
        {2, 2},
        {5, 2},
        {{"books", BOOK_COUNTS}},
        [](const ParamValues& values) { return std::make_unique<MetadataQueryState>(std::stoi(values.at("books"))); },
        {method("findPathById", &MetadataQueryState::find_path_by_id, QUERIES),
         method("findPathByTitle", &MetadataQueryState::find_path_by_title, QUERIES),
         method("findBooksByAuthor", &MetadataQueryState::find_books_by_author, QUERIES)},
    };
}

}
