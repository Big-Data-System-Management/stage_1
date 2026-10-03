#include "tokenizer.hpp"

#include "files.hpp"
#include "unicode.hpp"
#include "utf8.hpp"

namespace stage1 {

namespace {

constexpr size_t MIN_TOKEN_LENGTH = 2;
constexpr size_t MAX_TOKEN_LENGTH = 50;
constexpr char32_t FINAL_SIGMA = 0x03C2;
constexpr char32_t SIGMA = 0x03C3;
constexpr size_t DECOMPOSITION_CAPACITY = 32;

}

Tokenizer::Tokenizer() : Tokenizer(load_default_stopwords()) {}

Tokenizer::Tokenizer(std::unordered_set<std::string> stopwords) : stopwords_(std::move(stopwords)) {}

std::vector<std::string> Tokenizer::tokenize(std::string_view text) const {
    std::vector<std::string> tokens;
    std::string token;
    size_t length = 0;
    auto finish = [&] {
        if (length >= MIN_TOKEN_LENGTH && length <= MAX_TOKEN_LENGTH && !stopwords_.contains(token))
            tokens.push_back(token);
        token.clear();
        length = 0;
    };
    auto accept = [&](char32_t c) {
        if (c < 0x80) {
            if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                token.push_back(static_cast<char>(c));
                ++length;
            } else {
                finish();
            }
            return;
        }
        if (unicode::is_mark(c)) return;
        char32_t lower = unicode::to_lower(c);
        if (lower == FINAL_SIGMA) lower = SIGMA;
        if (unicode::is_letter_or_digit(lower)) {
            utf8::append(token, lower);
            ++length;
        } else {
            finish();
        }
    };
    char32_t decomposition[DECOMPOSITION_CAPACITY];
    for (size_t position = 0; position < text.size();) {
        char32_t c = utf8::next(text, position);
        if (c < 0x80) {
            accept(c);
            continue;
        }
        size_t count = unicode::decompose(c, decomposition, DECOMPOSITION_CAPACITY);
        for (size_t i = 0; i < count; ++i) accept(decomposition[i]);
    }
    finish();
    return tokens;
}

std::unordered_set<std::string> load_default_stopwords() {
    std::unordered_set<std::string> stopwords;
    std::string content = files::read(files::repo_root() / "src" / "main" / "resources" / "stopwords.txt");
    for (const std::string& line : files::lines(content)) {
        std::string_view word = utf8::java_strip(line);
        if (!word.empty()) stopwords.emplace(word);
    }
    return stopwords;
}

}
