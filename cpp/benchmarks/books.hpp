#pragma once

#include <optional>
#include <vector>

#include "model.hpp"

namespace stage1::benchmarks {

constexpr int MAX_BOOK_ID_TO_TRY = 1000;

std::vector<Book> load_books(int count, int max_book_id = MAX_BOOK_ID_TO_TRY);
std::optional<RawBook> raw_book(int book_id);

}
