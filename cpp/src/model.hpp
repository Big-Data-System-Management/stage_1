#pragma once

#include <optional>
#include <string>

namespace stage1 {

struct RawBook {
    int book_id;
    std::string body;
};

struct Book {
    int id;
    std::string head;
    std::string body;

    bool operator==(const Book&) const = default;
};

struct BookMetadata {
    int book_id;
    std::string title;
    std::string author;
    std::string language;
    std::optional<std::string> body_path;

    bool operator==(const BookMetadata&) const = default;
};

}
