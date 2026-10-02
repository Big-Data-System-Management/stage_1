#include <filesystem>
#include <functional>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

#include <zlib.h>

#include "datalake.hpp"
#include "files.hpp"
#include "gutenberg.hpp"
#include "index.hpp"
#include "metadata.hpp"
#include "tokenizer.hpp"

#ifdef _WIN32
#include <windows.h>
#endif

using namespace stage1;
namespace fs = std::filesystem;

namespace {

int failures = 0;
std::vector<std::pair<std::string, std::function<void()>>> tests;

#define CHECK(condition) \
    do { \
        if (!(condition)) { \
            ++failures; \
            std::cerr << "  FALLO " << __FILE__ << ":" << __LINE__ << ": " #condition "\n"; \
        } \
    } while (false)

struct Register {
    Register(std::string name, std::function<void()> test) { tests.emplace_back(std::move(name), std::move(test)); }
};

#define TEST(name) \
    void name(); \
    Register register_##name(#name, name); \
    void name()

class TempDir {
public:
    TempDir() : path_(fs::temp_directory_path() / ("stage1_test_" + std::to_string(counter_++))) {
        fs::remove_all(path_);
        fs::create_directories(path_);
    }
    ~TempDir() {
        std::error_code error;
        fs::remove_all(path_, error);
    }
    const fs::path& path() const { return path_; }

private:
    static inline int counter_ = 0;
    fs::path path_;
};

const Tokenizer& tokenizer() {
    static const Tokenizer instance;
    return instance;
}

using Tokens = std::vector<std::string>;

std::string gzip(const std::string& text) {
    z_stream stream{};
    deflateInit2(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 16 + MAX_WBITS, 8, Z_DEFAULT_STRATEGY);
    std::string out(deflateBound(&stream, static_cast<uLong>(text.size())) + 32, '\0');
    stream.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(text.data()));
    stream.avail_in = static_cast<uInt>(text.size());
    stream.next_out = reinterpret_cast<Bytef*>(out.data());
    stream.avail_out = static_cast<uInt>(out.size());
    deflate(&stream, Z_FINISH);
    out.resize(stream.total_out);
    deflateEnd(&stream);
    return out;
}

}

TEST(tokenizer_lowercases_and_removes_accents) {
    CHECK((tokenizer().tokenize("Café ÉCOLE") == Tokens{"cafe", "ecole"}));
}

TEST(tokenizer_splits_on_everything_that_is_not_a_letter_or_digit) {
    CHECK((tokenizer().tokenize("sea-shore, whale's!") == Tokens{"sea", "shore", "whale"}));
}

TEST(tokenizer_removes_stopwords_and_single_character_tokens) {
    CHECK((tokenizer().tokenize("The cat is on a mat") == Tokens{"cat", "mat"}));
}

TEST(tokenizer_keeps_digits_and_other_alphabets) {
    CHECK((tokenizer().tokenize("1813 Ωμέγα Straße") == Tokens{"1813", "ωμεγα", "straße"}));
}

TEST(tokenizer_folds_final_sigma) {
    CHECK((tokenizer().tokenize("ΟΔΟΣ οδος ΟΔΟΣ-ΒΑΣ") == Tokens{"οδοσ", "οδοσ", "οδοσ", "βασ"}));
}

TEST(tokenizer_discards_tokens_longer_than_fifty_characters) {
    std::string fifty(50, 'a');
    CHECK((tokenizer().tokenize(fifty + " " + fifty + "a") == Tokens{fifty}));
}

TEST(tokenizer_keeps_order_and_repetitions) {
    CHECK((tokenizer().tokenize("whale sea whale") == Tokens{"whale", "sea", "whale"}));
}

TEST(tokenizer_returns_nothing_for_blank_text) {
    CHECK(tokenizer().tokenize("  ,;  ").empty());
}

TEST(tokenizer_uses_the_given_stopwords) {
    CHECK((Tokenizer({"whale"}).tokenize("the whale sea") == Tokens{"the", "sea"}));
}

TEST(gutenberg_accepts_the_start_marker_variants) {
    for (std::string marker : {"*** START OF THE PROJECT GUTENBERG EBOOK PI ***",
                               "*** START OF THIS PROJECT GUTENBERG EBOOK PI ***",
                               "***START OF THE PROJECT GUTENBERG EBOOK PI***",
                               "*** Start of the Project Gutenberg eBook Pi ***",
                               "*** START OF THIS PROJECT GUTENBERG E-BOOK PI ***"}) {
        std::string text = "Title: Pi\r\n\r\n" + marker + "\r\n\r\n3.14159\r\n\r\n*** END OF THIS PROJECT GUTENBERG EBOOK PI ***\r\nfooter";
        auto book = gutenberg::process({1, text});
        CHECK(book && book->head == "Title: Pi" && book->body == "3.14159");
    }
}

TEST(gutenberg_body_does_not_contain_the_rest_of_the_start_marker_line) {
    auto book = gutenberg::process({1, "Title: Alice\n*** START OF THE PROJECT GUTENBERG EBOOK ALICE'S ADVENTURES ***\n"
                                       "Alice was beginning to get very tired\n"
                                       "*** END OF THE PROJECT GUTENBERG EBOOK ALICE'S ADVENTURES ***\n"});
    CHECK(book && book->body == "Alice was beginning to get very tired");
}

TEST(gutenberg_ignores_an_end_marker_before_the_start_marker) {
    auto book = gutenberg::process({1, "*** END OF THE PROJECT GUTENBERG EBOOK X ***\nheader\n"
                                       "*** START OF THE PROJECT GUTENBERG EBOOK X ***\nbody\n"
                                       "*** END OF THE PROJECT GUTENBERG EBOOK X ***\n"});
    CHECK(book && book->body == "body");
}

TEST(gutenberg_skips_books_without_markers) {
    CHECK(!gutenberg::process({1, "plain text without markers"}));
}

TEST(gutenberg_decodes_plain_and_gzipped_content) {
    std::string text = "Ωμέγα café";
    CHECK(gutenberg::decode(text) == text);
    CHECK(gutenberg::decode(gzip(text)) == text);
}

TEST(index_monolithic_writes_sorted_compact_json) {
    TempDir temp;
    fs::path file = temp.path() / "datamarts" / "inverted_index.json";
    MonolithicJsonIndex index(file, tokenizer());
    index.index_book(12, "island adventure");
    index.index_book(5, "adventure");
    index.flush();
    CHECK(files::read(file) == R"({"adventure":[5,12],"island":[12]})");
    CHECK(!files::exists(temp.path() / "datamarts" / "inverted_index.json.tmp"));
}

TEST(index_monolithic_reloads_and_extends_the_existing_file) {
    TempDir temp;
    fs::path file = temp.path() / "inverted_index.json";
    {
        MonolithicJsonIndex first(file, tokenizer());
        first.index_book(5, "whale");
        first.flush();
    }
    MonolithicJsonIndex second(file, tokenizer());
    second.index_book(2701, "Whale");
    CHECK((second.search("WHALE") == std::vector<int>{5, 2701}));
}

TEST(index_folder_writes_one_file_per_term_grouped_by_first_letter) {
    TempDir temp;
    fs::path root = temp.path() / "inverted_index";
    HierarchicalFolderIndex index(root, tokenizer());
    index.index_book(12, "adventure 1813 ωμεγα straße");
    index.index_book(5, "adventure");
    index.flush();
    CHECK(files::read(root / "A" / "adventure.txt") == "5\n12\n");
    CHECK(files::read(root / "1" / "1813.txt") == "12\n");
    CHECK(files::exists(root / files::path_of("Ω") / files::path_of("ωμεγα.txt")));
    CHECK((index.search("Straße") == std::vector<int>{12}));
}

TEST(index_folder_prefixes_windows_reserved_names) {
    TempDir temp;
    fs::path root = temp.path() / "inverted_index";
    HierarchicalFolderIndex index(root, tokenizer());
    index.index_book(1, "café con leche");
    index.flush();
    CHECK(files::exists(root / "C" / "_con.txt"));
    CHECK(files::exists(root / "C" / "cafe.txt"));
    CHECK((index.search("con") == std::vector<int>{1}));
}

TEST(index_search_returns_nothing_for_several_terms_or_stopwords) {
    TempDir temp;
    MonolithicJsonIndex index(temp.path() / "inverted_index.json", tokenizer());
    index.index_book(1, "whale island");
    CHECK(index.search("whale island").empty());
    CHECK(index.search("the").empty());
}

TEST(metadata_extracts_fields_joining_continuation_lines) {
    TempDir temp;
    SqliteMetadataRepository repository(temp.path() / "metadata.db");
    std::string header = "Title: Peter Pan\n        [Peter and Wendy]\nEditor: Eric S. Raymond\n        Guy L. Steele\nLanguage: English\n";
    MetadataExtractor(repository).extract_and_process({16, header, "body"}, std::string("datalake\\16.body.txt"));
    auto saved = repository.find_by_id(16);
    CHECK(saved && saved->title == "Peter Pan [Peter and Wendy]");
    CHECK(saved && saved->author == "Eric S. Raymond; Guy L. Steele");
    CHECK(saved && saved->language == "English");
    CHECK(saved && saved->body_path == "datalake\\16.body.txt");
}

TEST(metadata_uses_unknown_when_a_field_is_missing) {
    TempDir temp;
    SqliteMetadataRepository repository(temp.path() / "metadata.db");
    MetadataExtractor(repository).extract_and_process({7, "Title: The Mayflower Compact\r\n", "body"}, std::nullopt);
    auto saved = repository.find_by_id(7);
    CHECK(saved && saved->title == "The Mayflower Compact");
    CHECK(saved && saved->author == "Unknown" && saved->language == "Unknown" && !saved->body_path);
}

TEST(metadata_handles_blank_values_like_java) {
    CHECK(find_field("Title:\nTitle: Second\n", "Title", " ") == "Second");
    CHECK(find_field("Title:  \n   Foo\n", "Title", " ") == "Foo");
    CHECK(find_field("Title:\n\nTitle: Real", "Title", " ") == "Real");
    CHECK(!find_field("Title: \t\n\t\nX", "Title", " "));
    CHECK(find_field("Title: a\x0b  b", "Title", " ") == "a b");
}

TEST(metadata_upserts_and_queries_case_insensitively) {
    TempDir temp;
    SqliteMetadataRepository repository(temp.path() / "metadata.db");
    MetadataExtractor extractor(repository);
    extractor.extract_and_process({1, "Title: Old\nAuthor: Jane Austen\n", ""}, std::nullopt);
    extractor.extract_and_process({1, "Title: Emma\nAuthor: Jane Austen\n", ""}, std::nullopt);
    auto books = repository.find_by_author("jane austen");
    CHECK(repository.count() == 1);
    CHECK(books.size() == 1 && books.front().title == "Emma");
}

TEST(datalake_stores_reads_and_recovers_books) {
    TempDir temp;
    Book book{1342, "Title: Pride\r\n", "It is a truth\r\nuniversally acknowledged"};
    auto check = [&](auto create, const char* name) {
        fs::path base = temp.path() / name;
        fs::path body_path = create(base).store_data(book);
        CHECK(files::read(body_path) == book.body);
        auto reopened = create(base);
        CHECK(reopened.exists(1342) && !reopened.exists(1));
        CHECK(reopened.get_book(1342) == book);
    };
    check([](const fs::path& p) { return DatalakeLocalStoreBookHierarchy(p); }, "book");
    check([](const fs::path& p) { return DatalakeLocalStoreIdRangeHierarchy(p); }, "range");
    check([](const fs::path& p) { return DatalakeLocalStoreTimeHierarchy(p); }, "time");
}

TEST(datalake_layouts) {
    TempDir temp;
    Book book{1342, "h", "b"};
    CHECK(DatalakeLocalStoreBookHierarchy(temp.path()).store_data(book) == temp.path() / "1342" / "1342.body.txt");
    CHECK(DatalakeLocalStoreIdRangeHierarchy(temp.path()).store_data(book) == temp.path() / "batch_1000_to_1999" / "1342.body.txt");
}

TEST(datalake_ignores_books_without_body) {
    TempDir temp;
    fs::create_directories(temp.path() / "5");
    files::write(temp.path() / "5" / "5.header.txt", "h");
    files::write(temp.path() / "5" / "5.body.txt.tmp", "b");
    CHECK(!DatalakeLocalStoreBookHierarchy(temp.path()).exists(5));
}

int main() {
#ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);
#endif
    for (const auto& [name, test] : tests) {
        int before = failures;
        try {
            test();
        } catch (const std::exception& error) {
            ++failures;
            std::cerr << "  EXCEPCIÓN: " << error.what() << "\n";
        }
        std::cout << (failures == before ? "[OK]    " : "[FALLO] ") << name << "\n";
    }
    std::cout << tests.size() << " tests, " << failures << " fallos\n";
    return failures == 0 ? 0 : 1;
}
