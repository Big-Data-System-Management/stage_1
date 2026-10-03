#include "datalake.hpp"

#include <charconv>
#include <ctime>
#include <iostream>
#include <string>

#include "files.hpp"

namespace stage1 {

namespace fs = std::filesystem;

namespace {

constexpr std::string_view BODY_SUFFIX = ".body.txt";
constexpr int BATCH_SIZE = 1000;

template <class Consumer>
void walk_bodies(const fs::path& base_path, Consumer consumer) {
    files::walk(base_path, [&](const files::WalkEntry& entry) {
        if (entry.is_directory || !entry.name.ends_with(BODY_SUFFIX)) return;
        std::string_view digits(entry.name.data(), entry.name.size() - BODY_SUFFIX.size());
        if (!digits.empty() && digits.front() == '+') digits.remove_prefix(1);
        int book_id = 0;
        auto result = std::from_chars(digits.data(), digits.data() + digits.size(), book_id);
        if (result.ec == std::errc() && result.ptr == digits.data() + digits.size()) consumer(book_id, entry.directory);
    });
}

std::optional<Book> read_book(int book_id, const fs::path& directory) {
    try {
        std::string header = files::read(directory / (std::to_string(book_id) + ".header.txt"));
        std::string body = files::read(directory / (std::to_string(book_id) + std::string(BODY_SUFFIX)));
        return Book{book_id, std::move(header), std::move(body)};
    } catch (const std::exception& error) {
        std::cerr << "Error reading book " << book_id << ": " << error.what() << "\n";
        return std::nullopt;
    }
}

fs::path write_book(const fs::path& directory, const Book& book) {
    fs::create_directories(directory);
    fs::path body_path = directory / (std::to_string(book.id) + std::string(BODY_SUFFIX));
    files::write_atomically(directory / (std::to_string(book.id) + ".header.txt"), book.head);
    files::write_atomically(body_path, book.body);
    return body_path;
}

std::string now(const char* format) {
    std::time_t time = std::time(nullptr);
    std::tm local{};
#ifdef _WIN32
    localtime_s(&local, &time);
#else
    localtime_r(&time, &local);
#endif
    char buffer[16];
    std::strftime(buffer, sizeof buffer, format, &local);
    return buffer;
}

}

DatalakeLocalStoreBookHierarchy::DatalakeLocalStoreBookHierarchy(fs::path base_path) : base_path_(std::move(base_path)) {
    if (!files::exists(base_path_)) return;
    walk_bodies(base_path_, [this](int book_id, const fs::path&) { existing_book_ids_.insert(book_id); });
    std::cout << "[Store Book-Hierarchy] Index loaded in RAM: " << existing_book_ids_.size() << " books detected.\n";
}

fs::path DatalakeLocalStoreBookHierarchy::store_data(const Book& book) {
    fs::path body_path = write_book(base_path_ / std::to_string(book.id), book);
    existing_book_ids_.insert(book.id);
    return body_path;
}

bool DatalakeLocalStoreBookHierarchy::exists(int book_id) const {
    return existing_book_ids_.contains(book_id);
}

std::optional<Book> DatalakeLocalStoreBookHierarchy::get_book(int book_id) const {
    if (!exists(book_id)) return std::nullopt;
    return read_book(book_id, base_path_ / std::to_string(book_id));
}

DatalakeLocalStoreIdRangeHierarchy::DatalakeLocalStoreIdRangeHierarchy(fs::path base_path) : base_path_(std::move(base_path)) {
    if (!files::exists(base_path_)) return;
    walk_bodies(base_path_, [this](int book_id, const fs::path&) { existing_book_ids_.insert(book_id); });
    std::cout << "[Store Batch-Hierarchy] Index loaded in RAM: " << existing_book_ids_.size() << " books detected.\n";
}

fs::path DatalakeLocalStoreIdRangeHierarchy::store_data(const Book& book) {
    fs::path body_path = write_book(batch_directory(book.id), book);
    existing_book_ids_.insert(book.id);
    return body_path;
}

bool DatalakeLocalStoreIdRangeHierarchy::exists(int book_id) const {
    return existing_book_ids_.contains(book_id);
}

std::optional<Book> DatalakeLocalStoreIdRangeHierarchy::get_book(int book_id) const {
    if (!exists(book_id)) return std::nullopt;
    return read_book(book_id, batch_directory(book_id));
}

fs::path DatalakeLocalStoreIdRangeHierarchy::batch_directory(int book_id) const {
    int batch = book_id / BATCH_SIZE;
    return base_path_ / ("batch_" + std::to_string(batch * BATCH_SIZE) + "_to_" + std::to_string((batch + 1) * BATCH_SIZE - 1));
}

DatalakeLocalStoreTimeHierarchy::DatalakeLocalStoreTimeHierarchy(fs::path base_path) : base_path_(std::move(base_path)) {
    if (!files::exists(base_path_)) return;
    walk_bodies(base_path_, [this](int book_id, const fs::path& directory) { book_directories_[book_id] = directory; });
    std::cout << "[Store] Index loaded in RAM: " << book_directories_.size() << " books detected.\n";
}

fs::path DatalakeLocalStoreTimeHierarchy::store_data(const Book& book) {
    fs::path directory = base_path_ / now("%Y%m%d") / now("%H");
    fs::path body_path = write_book(directory, book);
    book_directories_[book.id] = directory;
    return body_path;
}

bool DatalakeLocalStoreTimeHierarchy::exists(int book_id) const {
    return book_directories_.contains(book_id);
}

std::optional<Book> DatalakeLocalStoreTimeHierarchy::get_book(int book_id) const {
    auto found = book_directories_.find(book_id);
    if (found == book_directories_.end()) return std::nullopt;
    return read_book(book_id, found->second);
}

CompositeStore::CompositeStore(std::vector<std::unique_ptr<Store>> stores) : stores_(std::move(stores)) {}

fs::path CompositeStore::store_data(const Book& book) {
    for (auto& store : stores_) store->store_data(book);
    return {};
}

bool CompositeStore::exists(int book_id) const {
    for (const auto& store : stores_)
        if (!store->exists(book_id)) return false;
    return true;
}

std::optional<Book> CompositeStore::get_book(int book_id) const {
    for (const auto& store : stores_)
        if (store->exists(book_id))
            if (auto book = store->get_book(book_id)) return book;
    return std::nullopt;
}

}
