#pragma once

#include <string>
#include <string_view>

namespace stage1::utf8 {

constexpr char32_t REPLACEMENT = 0xFFFD;

char32_t next(std::string_view text, size_t& position);
void append(std::string& out, char32_t code_point);
std::string encode(std::u32string_view code_points);
std::u32string decode(std::string_view text);
std::string sanitize(std::string_view bytes);
bool is_java_whitespace(char32_t code_point);
std::string_view java_strip(std::string_view text);
std::u32string utf16_order_key(std::string_view text);

}
