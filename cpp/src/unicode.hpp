#pragma once

#include <cstddef>
#include <string>

namespace stage1::unicode {

bool is_mark(char32_t c);
bool is_letter_or_digit(char32_t c);
char32_t to_lower(char32_t c);
size_t decompose(char32_t c, char32_t* out, size_t capacity);
std::string to_upper(char32_t c);

}
