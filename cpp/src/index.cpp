#include "index.hpp"

#include <algorithm>
#include <charconv>
#include <optional>
#include <stdexcept>
#include <unordered_set>

#include <mongoc/mongoc.h>
#include <nlohmann/json.hpp>

#include "files.hpp"
#include "unicode.hpp"
#include "utf8.hpp"

namespace stage1 {

namespace fs = std::filesystem;

namespace {

const std::unordered_set<std::string> WINDOWS_RESERVED_NAMES = {
    "con", "prn", "aux", "nul",
    "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
    "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"};

void add(Postings& books, int book_id) {
    if (books.empty() || books.back() < book_id) {
        books.push_back(book_id);
        return;
    }
    auto position = std::lower_bound(books.begin(), books.end(), book_id);
    if (position == books.end() || *position != book_id) books.insert(position, book_id);
}

Postings merge(const Postings& left, const Postings& right) {
    Postings merged;
    merged.reserve(left.size() + right.size());
    std::set_union(left.begin(), left.end(), right.begin(), right.end(), std::back_inserter(merged));
    return merged;
}

void add_postings(PostingsMap& postings, const Tokenizer& tokenizer, int book_id, std::string_view body) {
    std::vector<std::string> terms = tokenizer.tokenize(body);
    std::sort(terms.begin(), terms.end());
    terms.erase(std::unique(terms.begin(), terms.end()), terms.end());
    for (std::string& term : terms) add(postings[std::move(term)], book_id);
}

std::optional<std::string> single_term(const Tokenizer& tokenizer, std::string_view term) {
    std::vector<std::string> tokens = tokenizer.tokenize(term);
    if (tokens.size() != 1) return std::nullopt;
    return tokens.front();
}

void append_json_string(std::string& out, const std::string& text) {
    static constexpr char HEX[] = "0123456789abcdef";
    out.push_back('"');
    for (size_t position = 0; position < text.size();) {
        size_t start = position;
        char32_t c = utf8::next(text, position);
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\t': out += "\\t"; break;
            case '\b': out += "\\b"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\f': out += "\\f"; break;
            case 0x2028: out += "\\u2028"; break;
            case 0x2029: out += "\\u2029"; break;
            default:
                if (c < 0x20) {
                    out += "\\u00";
                    out.push_back(HEX[c >> 4]);
                    out.push_back(HEX[c & 0xF]);
                } else {
                    out.append(text, start, position - start);
                }
        }
    }
    out.push_back('"');
}

Postings parse_postings(std::string_view content) {
    Postings books;
    for (const std::string& line : files::lines(content)) {
        std::string_view value = utf8::java_strip(line);
        if (value.empty()) continue;
        int book_id = 0;
        auto result = std::from_chars(value.data(), value.data() + value.size(), book_id);
        if (result.ec != std::errc() || result.ptr != value.data() + value.size())
            throw std::runtime_error("Posting inválido: " + std::string(value));
        books.push_back(book_id);
    }
    std::sort(books.begin(), books.end());
    books.erase(std::unique(books.begin(), books.end()), books.end());
    return books;
}

Postings read_postings_file(const fs::path& file) {
    if (!files::exists(file)) return {};
    return parse_postings(files::read(file));
}

std::string folder_name(const std::string& term) {
    size_t position = 0;
    return unicode::to_upper(utf8::next(term, position));
}

std::string file_name(const std::string& term) {
    return (WINDOWS_RESERVED_NAMES.contains(term) ? "_" + term : term) + ".txt";
}

void merge_into_file(const fs::path& file, const Postings& new_book_ids) {
    Postings books = read_postings_file(file);
    Postings merged = merge(books, new_book_ids);
    if (merged.size() == books.size()) return;
    fs::create_directories(file.parent_path());
    std::string content;
    for (int book_id : merged) {
        content += std::to_string(book_id);
        content.push_back('\n');
    }
    files::write_atomically(file, content);
}

}

MonolithicJsonIndex::MonolithicJsonIndex(fs::path index_file, const Tokenizer& tokenizer)
    : index_file_(std::move(index_file)), tokenizer_(tokenizer) {
    if (!files::exists(index_file_)) return;
    nlohmann::json loaded = nlohmann::json::parse(files::read(index_file_));
    if (loaded.is_null()) return;
    for (auto& [term, books] : loaded.items()) {
        Postings& postings = postings_[term];
        for (int book_id : books.get<std::vector<int>>()) add(postings, book_id);
    }
}

void MonolithicJsonIndex::index_book(int book_id, std::string_view body) {
    add_postings(postings_, tokenizer_, book_id, body);
}

std::vector<int> MonolithicJsonIndex::search(std::string_view term) {
    auto normalized = single_term(tokenizer_, term);
    if (!normalized) return {};
    auto found = postings_.find(*normalized);
    return found == postings_.end() ? std::vector<int>{} : found->second;
}

void MonolithicJsonIndex::flush() {
    fs::path parent = fs::absolute(index_file_).parent_path();
    fs::create_directories(parent);
    std::vector<std::pair<std::u32string, const PostingsMap::value_type*>> entries;
    entries.reserve(postings_.size());
    for (const auto& entry : postings_) entries.emplace_back(utf8::utf16_order_key(entry.first), &entry);
    std::sort(entries.begin(), entries.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    std::string json = "{";
    for (size_t i = 0; i < entries.size(); ++i) {
        if (i > 0) json.push_back(',');
        append_json_string(json, entries[i].second->first);
        json += ":[";
        const Postings& books = entries[i].second->second;
        for (size_t j = 0; j < books.size(); ++j) {
            if (j > 0) json.push_back(',');
            json += std::to_string(books[j]);
        }
        json.push_back(']');
    }
    json.push_back('}');
    files::write_atomically(index_file_, json);
}

HierarchicalFolderIndex::HierarchicalFolderIndex(fs::path root_directory, const Tokenizer& tokenizer)
    : root_directory_(std::move(root_directory)), tokenizer_(tokenizer) {}

void HierarchicalFolderIndex::index_book(int book_id, std::string_view body) {
    add_postings(pending_postings_, tokenizer_, book_id, body);
}

std::vector<int> HierarchicalFolderIndex::search(std::string_view term) {
    auto normalized = single_term(tokenizer_, term);
    if (!normalized) return {};
    Postings books = read_postings_file(term_file(*normalized));
    auto pending = pending_postings_.find(*normalized);
    return pending == pending_postings_.end() ? books : merge(books, pending->second);
}

void HierarchicalFolderIndex::flush() {
    for (const auto& [term, book_ids] : pending_postings_) merge_into_file(term_file(term), book_ids);
    pending_postings_.clear();
}

fs::path HierarchicalFolderIndex::term_file(const std::string& term) const {
    return root_directory_ / files::path_of(folder_name(term)) / files::path_of(file_name(term));
}

void ensure_mongo_initialized() {
    static const bool initialized = [] {
        mongoc_init();
        return true;
    }();
    (void) initialized;
}

namespace {

constexpr const char* TERM_FIELD = "term";
constexpr const char* POSTINGS_FIELD = "postings";

struct Bson {
    bson_t* value;
    explicit Bson(bson_t* document) : value(document) {}
    ~Bson() { bson_destroy(value); }
    Bson(const Bson&) = delete;
    Bson& operator=(const Bson&) = delete;
};

bson_t* term_filter(const std::string& term) {
    bson_t* filter = bson_new();
    bson_append_utf8(filter, TERM_FIELD, -1, term.data(), static_cast<int>(term.size()));
    return filter;
}

bson_t* add_each_to_set(const Postings& book_ids) {
    bson_t* update = bson_new();
    bson_t add_to_set, postings;
    bson_append_document_begin(update, "$addToSet", -1, &add_to_set);
    bson_append_document_begin(&add_to_set, POSTINGS_FIELD, -1, &postings);
    bson_array_builder_t* each;
    bson_append_array_builder_begin(&postings, "$each", -1, &each);
    for (int book_id : book_ids) bson_array_builder_append_int32(each, book_id);
    bson_append_array_builder_end(&postings, each);
    bson_append_document_end(&add_to_set, &postings);
    bson_append_document_end(update, &add_to_set);
    return update;
}

[[noreturn]] void fail(const std::string& action, const bson_error_t& error) {
    throw std::runtime_error(action + ": " + error.message);
}

}

MongoInvertedIndex::MongoInvertedIndex(const std::string& connection_uri, const std::string& database_name,
                                       const std::string& collection_name, const Tokenizer& tokenizer)
    : tokenizer_(tokenizer) {
    ensure_mongo_initialized();
    client_ = mongoc_client_new(connection_uri.c_str());
    if (!client_) throw std::runtime_error("URI de MongoDB inválida: " + connection_uri);
    collection_ = mongoc_client_get_collection(client_, database_name.c_str(), collection_name.c_str());
    Bson keys(BCON_NEW(TERM_FIELD, BCON_INT32(1)));
    Bson unique(BCON_NEW("unique", BCON_BOOL(true)));
    mongoc_index_model_t* model = mongoc_index_model_new(keys.value, unique.value);
    bson_error_t error;
    bool created = mongoc_collection_create_indexes_with_opts(collection_, &model, 1, nullptr, nullptr, &error);
    mongoc_index_model_destroy(model);
    if (!created) {
        mongoc_collection_destroy(collection_);
        mongoc_client_destroy(client_);
        fail("Error creando el índice de MongoDB", error);
    }
}

MongoInvertedIndex::~MongoInvertedIndex() {
    mongoc_collection_destroy(collection_);
    mongoc_client_destroy(client_);
}

void MongoInvertedIndex::index_book(int book_id, std::string_view body) {
    add_postings(pending_postings_, tokenizer_, book_id, body);
}

std::vector<int> MongoInvertedIndex::search(std::string_view term) {
    auto normalized = single_term(tokenizer_, term);
    if (!normalized) return {};
    Postings books = read_postings(*normalized);
    auto pending = pending_postings_.find(*normalized);
    return pending == pending_postings_.end() ? books : merge(books, pending->second);
}

void MongoInvertedIndex::flush() {
    if (pending_postings_.empty()) return;
    Bson options(BCON_NEW("ordered", BCON_BOOL(false)));
    Bson upsert(BCON_NEW("upsert", BCON_BOOL(true)));
    mongoc_bulk_operation_t* bulk = mongoc_collection_create_bulk_operation_with_opts(collection_, options.value);
    bson_error_t error;
    for (const auto& [term, book_ids] : pending_postings_) {
        Bson filter(term_filter(term));
        Bson update(add_each_to_set(book_ids));
        if (!mongoc_bulk_operation_update_one_with_opts(bulk, filter.value, update.value, upsert.value, &error)) {
            mongoc_bulk_operation_destroy(bulk);
            fail("Error preparando el upsert", error);
        }
    }
    bson_t reply;
    bool executed = mongoc_bulk_operation_execute(bulk, &reply, &error) != 0;
    bson_destroy(&reply);
    mongoc_bulk_operation_destroy(bulk);
    if (!executed) fail("Error en bulkWrite", error);
    pending_postings_.clear();
}

Postings MongoInvertedIndex::read_postings(const std::string& term) {
    Bson filter(term_filter(term));
    Bson options(BCON_NEW("limit", BCON_INT64(1)));
    mongoc_cursor_t* cursor = mongoc_collection_find_with_opts(collection_, filter.value, options.value, nullptr);
    const bson_t* document;
    Postings books;
    if (mongoc_cursor_next(cursor, &document)) {
        bson_iter_t iterator, array;
        if (bson_iter_init_find(&iterator, document, POSTINGS_FIELD) && BSON_ITER_HOLDS_ARRAY(&iterator)
            && bson_iter_recurse(&iterator, &array)) {
            while (bson_iter_next(&array)) books.push_back(static_cast<int>(bson_iter_as_int64(&array)));
        }
    }
    bson_error_t error;
    bool failed = mongoc_cursor_error(cursor, &error);
    mongoc_cursor_destroy(cursor);
    if (failed) fail("Error leyendo de MongoDB", error);
    std::sort(books.begin(), books.end());
    books.erase(std::unique(books.begin(), books.end()), books.end());
    return books;
}

}
