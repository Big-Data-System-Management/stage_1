#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "model.hpp"

typedef struct sqlite3 sqlite3;

namespace stage1 {

class SqliteMetadataRepository {
public:
    explicit SqliteMetadataRepository(const std::filesystem::path& database_file);
    ~SqliteMetadataRepository();
    SqliteMetadataRepository(const SqliteMetadataRepository&) = delete;
    SqliteMetadataRepository& operator=(const SqliteMetadataRepository&) = delete;

    void save(const BookMetadata& metadata);
    void save_all(const std::vector<BookMetadata>& metadata);
    std::optional<BookMetadata> find_by_id(int book_id);
    std::vector<BookMetadata> find_by_author(const std::string& author);
    std::vector<BookMetadata> find_by_title(const std::string& title);
    std::vector<BookMetadata> find_by_language(const std::string& language);
    std::vector<BookMetadata> find_all();
    int count();

private:
    void execute(const char* sql);
    std::vector<BookMetadata> query(const std::string& sql, const std::optional<std::string>& text, std::optional<int> number);

    sqlite3* connection_ = nullptr;
};

class MetadataExtractor {
public:
    explicit MetadataExtractor(SqliteMetadataRepository& repository);

    void extract_and_process(const Book& book, const std::optional<std::string>& body_path);

private:
    SqliteMetadataRepository& repository_;
};

std::optional<std::string> find_field(std::string_view header, std::string_view name, std::string_view line_separator);

}
