#include "books.hpp"

#include <chrono>
#include <stdexcept>
#include <string>
#include <thread>

#include "files.hpp"
#include "gutenberg.hpp"

namespace stage1::benchmarks {

namespace {

constexpr auto DOWNLOAD_DELAY = std::chrono::milliseconds(1000);

std::filesystem::path raw_books_dir() {
    return files::repo_root() / "benchmark" / "raw";
}

}

std::vector<Book> load_books(int count, int max_book_id) {
    std::vector<Book> books;
    for (int book_id = 1; book_id <= max_book_id && static_cast<int>(books.size()) < count; ++book_id) {
        auto raw = raw_book(book_id);
        if (!raw) continue;
        if (auto book = gutenberg::process(*raw)) books.push_back(std::move(*book));
    }
    if (static_cast<int>(books.size()) < count)
        throw std::runtime_error("Solo hay " + std::to_string(books.size()) + " libros válidos, se necesitan " + std::to_string(count));
    return books;
}

std::optional<RawBook> raw_book(int book_id) {
    std::filesystem::create_directories(raw_books_dir());
    auto cached = raw_books_dir() / ("pg" + std::to_string(book_id) + ".txt");
    auto missing = raw_books_dir() / ("pg" + std::to_string(book_id) + ".missing");
    if (files::exists(cached)) return RawBook{book_id, files::read(cached)};
    if (files::exists(missing)) return std::nullopt;
    std::this_thread::sleep_for(DOWNLOAD_DELAY);
    std::optional<std::string> text;
    try {
        text = gutenberg::download_book(book_id);
    } catch (const std::exception& error) {
        if (std::string(error.what()).find("404") != std::string::npos) files::write(missing, "");
        return std::nullopt;
    }
    if (!text || text->empty()) return std::nullopt;
    files::write(cached, *text);
    return RawBook{book_id, std::move(*text)};
}

}
