#pragma once

#include <filesystem>
#include <memory>
#include <optional>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "model.hpp"

namespace stage1 {

class Store {
public:
    virtual ~Store() = default;
    virtual std::filesystem::path store_data(const Book& book) = 0;
    virtual bool exists(int book_id) const = 0;
    virtual std::optional<Book> get_book(int book_id) const = 0;
};

class DatalakeLocalStoreBookHierarchy : public Store {
public:
    explicit DatalakeLocalStoreBookHierarchy(std::filesystem::path base_path);

    std::filesystem::path store_data(const Book& book) override;
    bool exists(int book_id) const override;
    std::optional<Book> get_book(int book_id) const override;

private:
    std::filesystem::path base_path_;
    std::unordered_set<int> existing_book_ids_;
};

class DatalakeLocalStoreIdRangeHierarchy : public Store {
public:
    explicit DatalakeLocalStoreIdRangeHierarchy(std::filesystem::path base_path);

    std::filesystem::path store_data(const Book& book) override;
    bool exists(int book_id) const override;
    std::optional<Book> get_book(int book_id) const override;

private:
    std::filesystem::path batch_directory(int book_id) const;

    std::filesystem::path base_path_;
    std::unordered_set<int> existing_book_ids_;
};

class DatalakeLocalStoreTimeHierarchy : public Store {
public:
    explicit DatalakeLocalStoreTimeHierarchy(std::filesystem::path base_path);

    std::filesystem::path store_data(const Book& book) override;
    bool exists(int book_id) const override;
    std::optional<Book> get_book(int book_id) const override;

private:
    std::filesystem::path base_path_;
    std::unordered_map<int, std::filesystem::path> book_directories_;
};

class CompositeStore : public Store {
public:
    explicit CompositeStore(std::vector<std::unique_ptr<Store>> stores);

    std::filesystem::path store_data(const Book& book) override;
    bool exists(int book_id) const override;
    std::optional<Book> get_book(int book_id) const override;

private:
    std::vector<std::unique_ptr<Store>> stores_;
};

}
