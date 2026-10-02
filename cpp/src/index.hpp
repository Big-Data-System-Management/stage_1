#pragma once

#include <filesystem>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "tokenizer.hpp"

typedef struct _mongoc_client_t mongoc_client_t;
typedef struct _mongoc_collection_t mongoc_collection_t;

namespace stage1 {

using Postings = std::vector<int>;
using PostingsMap = std::unordered_map<std::string, Postings>;

class InvertedIndex {
public:
    virtual ~InvertedIndex() = default;
    virtual void index_book(int book_id, std::string_view body) = 0;
    virtual std::vector<int> search(std::string_view term) = 0;
    virtual void flush() = 0;
};

class MonolithicJsonIndex : public InvertedIndex {
public:
    MonolithicJsonIndex(std::filesystem::path index_file, const Tokenizer& tokenizer);

    void index_book(int book_id, std::string_view body) override;
    std::vector<int> search(std::string_view term) override;
    void flush() override;

private:
    std::filesystem::path index_file_;
    const Tokenizer& tokenizer_;
    PostingsMap postings_;
};

class HierarchicalFolderIndex : public InvertedIndex {
public:
    HierarchicalFolderIndex(std::filesystem::path root_directory, const Tokenizer& tokenizer);

    void index_book(int book_id, std::string_view body) override;
    std::vector<int> search(std::string_view term) override;
    void flush() override;

private:
    std::filesystem::path term_file(const std::string& term) const;

    std::filesystem::path root_directory_;
    const Tokenizer& tokenizer_;
    PostingsMap pending_postings_;
};

class MongoInvertedIndex : public InvertedIndex {
public:
    MongoInvertedIndex(const std::string& connection_uri, const std::string& database_name,
                       const std::string& collection_name, const Tokenizer& tokenizer);
    ~MongoInvertedIndex() override;
    MongoInvertedIndex(const MongoInvertedIndex&) = delete;
    MongoInvertedIndex& operator=(const MongoInvertedIndex&) = delete;

    void index_book(int book_id, std::string_view body) override;
    std::vector<int> search(std::string_view term) override;
    void flush() override;

private:
    Postings read_postings(const std::string& term);

    mongoc_client_t* client_;
    mongoc_collection_t* collection_;
    const Tokenizer& tokenizer_;
    PostingsMap pending_postings_;
};

void ensure_mongo_initialized();

}
