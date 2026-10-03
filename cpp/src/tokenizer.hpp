#pragma once

#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

namespace stage1 {

class Tokenizer {
public:
    Tokenizer();
    explicit Tokenizer(std::unordered_set<std::string> stopwords);

    std::vector<std::string> tokenize(std::string_view text) const;

private:
    std::unordered_set<std::string> stopwords_;
};

std::unordered_set<std::string> load_default_stopwords();

}
