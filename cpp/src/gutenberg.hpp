#pragma once

#include <optional>
#include <string>
#include <string_view>

#include "model.hpp"

namespace stage1::gutenberg {

std::string decode(std::string_view content);
std::optional<std::string> download_book(int book_id);
std::optional<Book> process(const RawBook& raw_book);

}
