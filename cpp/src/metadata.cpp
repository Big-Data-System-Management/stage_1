#include "metadata.hpp"

#include <stdexcept>

#include <sqlite3.h>

#include "files.hpp"
#include "utf8.hpp"

namespace stage1 {

namespace {

constexpr const char* UNKNOWN = "Unknown";
constexpr const char* AUTHOR_FIELDS[] = {"Author", "Editor", "Translator", "Compiler"};

constexpr const char* CREATE_TABLE = R"(CREATE TABLE IF NOT EXISTS books (
    book_id   INTEGER PRIMARY KEY,
    title     TEXT COLLATE NOCASE,
    author    TEXT COLLATE NOCASE,
    language  TEXT COLLATE NOCASE,
    body_path TEXT
))";
constexpr const char* CREATE_AUTHOR_INDEX = "CREATE INDEX IF NOT EXISTS idx_books_author ON books(author)";
constexpr const char* CREATE_TITLE_INDEX = "CREATE INDEX IF NOT EXISTS idx_books_title ON books(title)";
constexpr const char* CREATE_LANGUAGE_INDEX = "CREATE INDEX IF NOT EXISTS idx_books_language ON books(language)";
constexpr const char* UPSERT = R"(INSERT INTO books (book_id, title, author, language, body_path) VALUES (?, ?, ?, ?, ?)
ON CONFLICT(book_id) DO UPDATE SET
    title = excluded.title,
    author = excluded.author,
    language = excluded.language,
    body_path = excluded.body_path)";
constexpr const char* SELECT = "SELECT book_id, title, author, language, body_path FROM books";

bool is_blank(char c) {
    return c == ' ' || c == '\t';
}

bool is_line_terminator(char32_t c) {
    return c == '\n' || c == '\r' || c == 0x85 || c == 0x2028 || c == 0x2029;
}

size_t line_break_length(std::string_view text, size_t position) {
    if (position >= text.size()) return 0;
    if (text[position] == '\r' && position + 1 < text.size() && text[position + 1] == '\n') return 2;
    size_t next = position;
    char32_t c = utf8::next(text, next);
    return is_line_terminator(c) || c == 0x0B || c == 0x0C ? next - position : 0;
}

size_t line_end(std::string_view text, size_t position) {
    while (position < text.size()) {
        size_t next = position;
        if (is_line_terminator(utf8::next(text, next))) return position;
        position = next;
    }
    return position;
}

bool is_line_start(std::string_view text, size_t position) {
    if (position == 0) return true;
    size_t start = position - 1;
    while (start > 0 && (static_cast<unsigned char>(text[start]) & 0xC0) == 0x80) --start;
    size_t next = start;
    char32_t previous = utf8::next(text, next);
    if (previous == '\r') return text[position] != '\n';
    return is_line_terminator(previous);
}

std::optional<std::pair<size_t, size_t>> match_field(std::string_view header, std::string_view name) {
    std::string prefix = std::string(name) + ":";
    for (size_t candidate = header.find(prefix); candidate != std::string_view::npos; candidate = header.find(prefix, candidate + 1)) {
        if (!is_line_start(header, candidate)) continue;
        size_t after_colon = candidate + prefix.size();
        size_t value_start = after_colon;
        while (value_start < header.size() && is_blank(header[value_start])) ++value_start;
        size_t value_end = line_end(header, value_start);
        if (value_end == value_start) {
            if (value_start == after_colon) continue;
            --value_start;
        }
        while (true) {
            size_t line_break = line_break_length(header, value_end);
            if (line_break == 0) break;
            size_t next_line = value_end + line_break;
            size_t content = next_line;
            while (content < header.size() && is_blank(header[content])) ++content;
            size_t next_end = line_end(header, content);
            if (content == next_line || (next_end == content && content - next_line < 2)) break;
            value_end = next_end;
        }
        return std::make_pair(value_start, value_end);
    }
    return std::nullopt;
}

std::string join_continuations(std::string_view value, std::string_view line_separator) {
    std::string joined;
    for (size_t i = 0; i < value.size();) {
        size_t line_break = line_break_length(value, i);
        if (line_break > 0 && i + line_break < value.size() && is_blank(value[i + line_break])) {
            i += line_break;
            while (i < value.size() && is_blank(value[i])) ++i;
            joined += line_separator;
        } else {
            joined.push_back(value[i++]);
        }
    }
    return joined;
}

std::string collapse_blanks(std::string_view value) {
    std::string collapsed;
    for (size_t i = 0; i < value.size();) {
        if (is_blank(value[i])) {
            while (i < value.size() && is_blank(value[i])) ++i;
            collapsed.push_back(' ');
        } else {
            collapsed.push_back(value[i++]);
        }
    }
    return collapsed;
}

std::optional<std::string> find_author(std::string_view header) {
    for (const char* field : AUTHOR_FIELDS)
        if (auto value = find_field(header, field, "; ")) return value;
    return std::nullopt;
}

std::string column_text(sqlite3_stmt* statement, int column) {
    const unsigned char* text = sqlite3_column_text(statement, column);
    return text ? std::string(reinterpret_cast<const char*>(text), sqlite3_column_bytes(statement, column)) : std::string();
}

void bind_text(sqlite3_stmt* statement, int index, const std::string& value) {
    sqlite3_bind_text(statement, index, value.data(), static_cast<int>(value.size()), SQLITE_TRANSIENT);
}

}

std::optional<std::string> find_field(std::string_view header, std::string_view name, std::string_view line_separator) {
    auto match = match_field(header, name);
    if (!match) return std::nullopt;
    std::string value = collapse_blanks(join_continuations(header.substr(match->first, match->second - match->first), line_separator));
    std::string_view stripped = utf8::java_strip(value);
    if (stripped.empty()) return std::nullopt;
    return std::string(stripped);
}

MetadataExtractor::MetadataExtractor(SqliteMetadataRepository& repository) : repository_(repository) {}

void MetadataExtractor::extract_and_process(const Book& book, const std::optional<std::string>& body_path) {
    std::string_view header = book.head;
    repository_.save({book.id,
                      find_field(header, "Title", " ").value_or(UNKNOWN),
                      find_author(header).value_or(UNKNOWN),
                      find_field(header, "Language", " ").value_or(UNKNOWN),
                      body_path});
}

SqliteMetadataRepository::SqliteMetadataRepository(const std::filesystem::path& database_file) {
    std::filesystem::create_directories(std::filesystem::absolute(database_file).parent_path());
    if (sqlite3_open(files::to_string(database_file).c_str(), &connection_) != SQLITE_OK) {
        std::string message = sqlite3_errmsg(connection_);
        sqlite3_close(connection_);
        throw std::runtime_error("Error opening SQLite: " + message);
    }
    for (const char* statement : {CREATE_TABLE, CREATE_AUTHOR_INDEX, CREATE_TITLE_INDEX, CREATE_LANGUAGE_INDEX})
        execute(statement);
}

SqliteMetadataRepository::~SqliteMetadataRepository() {
    sqlite3_close(connection_);
}

void SqliteMetadataRepository::execute(const char* sql) {
    char* error = nullptr;
    if (sqlite3_exec(connection_, sql, nullptr, nullptr, &error) != SQLITE_OK) {
        std::string message = error ? error : "unknown";
        sqlite3_free(error);
        throw std::runtime_error("SQLite error: " + message);
    }
}

void SqliteMetadataRepository::save(const BookMetadata& metadata) {
    save_all({metadata});
}

void SqliteMetadataRepository::save_all(const std::vector<BookMetadata>& metadata) {
    execute("BEGIN");
    sqlite3_stmt* statement = nullptr;
    try {
        if (sqlite3_prepare_v2(connection_, UPSERT, -1, &statement, nullptr) != SQLITE_OK)
            throw std::runtime_error(sqlite3_errmsg(connection_));
        for (const BookMetadata& book : metadata) {
            sqlite3_bind_int(statement, 1, book.book_id);
            bind_text(statement, 2, book.title);
            bind_text(statement, 3, book.author);
            bind_text(statement, 4, book.language);
            if (book.body_path) bind_text(statement, 5, *book.body_path);
            else sqlite3_bind_null(statement, 5);
            if (sqlite3_step(statement) != SQLITE_DONE) throw std::runtime_error(sqlite3_errmsg(connection_));
            sqlite3_reset(statement);
        }
        sqlite3_finalize(statement);
        execute("COMMIT");
    } catch (...) {
        sqlite3_finalize(statement);
        execute("ROLLBACK");
        throw;
    }
}

std::optional<BookMetadata> SqliteMetadataRepository::find_by_id(int book_id) {
    auto books = query(std::string(SELECT) + " WHERE book_id = ?", std::nullopt, book_id);
    if (books.empty()) return std::nullopt;
    return books.front();
}

std::vector<BookMetadata> SqliteMetadataRepository::find_by_author(const std::string& author) {
    return query(std::string(SELECT) + " WHERE author = ? ORDER BY book_id", author, std::nullopt);
}

std::vector<BookMetadata> SqliteMetadataRepository::find_by_title(const std::string& title) {
    return query(std::string(SELECT) + " WHERE title = ? ORDER BY book_id", title, std::nullopt);
}

std::vector<BookMetadata> SqliteMetadataRepository::find_by_language(const std::string& language) {
    return query(std::string(SELECT) + " WHERE language = ? ORDER BY book_id", language, std::nullopt);
}

std::vector<BookMetadata> SqliteMetadataRepository::find_all() {
    return query(std::string(SELECT) + " ORDER BY book_id", std::nullopt, std::nullopt);
}

int SqliteMetadataRepository::count() {
    sqlite3_stmt* statement = nullptr;
    sqlite3_prepare_v2(connection_, "SELECT COUNT(*) FROM books", -1, &statement, nullptr);
    int result = sqlite3_step(statement) == SQLITE_ROW ? sqlite3_column_int(statement, 0) : 0;
    sqlite3_finalize(statement);
    return result;
}

std::vector<BookMetadata> SqliteMetadataRepository::query(const std::string& sql, const std::optional<std::string>& text,
                                                          std::optional<int> number) {
    sqlite3_stmt* statement = nullptr;
    if (sqlite3_prepare_v2(connection_, sql.c_str(), -1, &statement, nullptr) != SQLITE_OK)
        throw std::runtime_error(sqlite3_errmsg(connection_));
    if (text) bind_text(statement, 1, *text);
    if (number) sqlite3_bind_int(statement, 1, *number);
    std::vector<BookMetadata> books;
    int status;
    while ((status = sqlite3_step(statement)) == SQLITE_ROW) {
        std::optional<std::string> body_path;
        if (sqlite3_column_type(statement, 4) != SQLITE_NULL) body_path = column_text(statement, 4);
        books.push_back({sqlite3_column_int(statement, 0), column_text(statement, 1), column_text(statement, 2),
                         column_text(statement, 3), body_path});
    }
    sqlite3_finalize(statement);
    if (status != SQLITE_DONE) throw std::runtime_error(sqlite3_errmsg(connection_));
    return books;
}

}
