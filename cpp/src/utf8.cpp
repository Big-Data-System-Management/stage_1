#include "utf8.hpp"

namespace stage1::utf8 {

namespace {

bool is_continuation(unsigned char byte) {
    return (byte & 0xC0) == 0x80;
}

}

char32_t next(std::string_view text, size_t& position) {
    auto byte = static_cast<unsigned char>(text[position]);
    if (byte < 0x80) {
        ++position;
        return byte;
    }
    int length;
    char32_t code_point;
    unsigned char lower = 0x80;
    unsigned char upper = 0xBF;
    if (byte >= 0xC2 && byte <= 0xDF) {
        length = 2;
        code_point = byte & 0x1F;
    } else if (byte >= 0xE0 && byte <= 0xEF) {
        length = 3;
        code_point = byte & 0x0F;
        if (byte == 0xE0) lower = 0xA0;
        if (byte == 0xED) upper = 0x9F;
    } else if (byte >= 0xF0 && byte <= 0xF4) {
        length = 4;
        code_point = byte & 0x07;
        if (byte == 0xF0) lower = 0x90;
        if (byte == 0xF4) upper = 0x8F;
    } else {
        ++position;
        return REPLACEMENT;
    }
    size_t index = position + 1;
    for (int i = 1; i < length; ++i, ++index) {
        if (index >= text.size()) {
            position = index;
            return REPLACEMENT;
        }
        auto continuation = static_cast<unsigned char>(text[index]);
        unsigned char low = i == 1 ? lower : 0x80;
        unsigned char high = i == 1 ? upper : 0xBF;
        if (continuation < low || continuation > high) {
            position = index;
            return REPLACEMENT;
        }
        code_point = (code_point << 6) | (continuation & 0x3F);
    }
    position = index;
    return code_point;
}

void append(std::string& out, char32_t code_point) {
    if (code_point < 0x80) {
        out.push_back(static_cast<char>(code_point));
    } else if (code_point < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (code_point >> 6)));
        out.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    } else if (code_point < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (code_point >> 12)));
        out.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (code_point >> 18)));
        out.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    }
}

std::string encode(std::u32string_view code_points) {
    std::string out;
    out.reserve(code_points.size());
    for (char32_t code_point : code_points) append(out, code_point);
    return out;
}

std::u32string decode(std::string_view text) {
    std::u32string out;
    out.reserve(text.size());
    for (size_t position = 0; position < text.size();) out.push_back(next(text, position));
    return out;
}

std::string sanitize(std::string_view bytes) {
    std::string out;
    out.reserve(bytes.size());
    for (size_t position = 0; position < bytes.size();) append(out, next(bytes, position));
    return out;
}

bool is_java_whitespace(char32_t c) {
    return (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x20) || c == 0x1680
           || (c >= 0x2000 && c <= 0x2006) || (c >= 0x2008 && c <= 0x200A)
           || c == 0x2028 || c == 0x2029 || c == 0x205F || c == 0x3000;
}

std::string_view java_strip(std::string_view text) {
    size_t begin = 0;
    while (begin < text.size()) {
        size_t position = begin;
        if (!is_java_whitespace(next(text, position))) break;
        begin = position;
    }
    size_t end = text.size();
    while (end > begin) {
        size_t start = end - 1;
        while (start > begin && is_continuation(static_cast<unsigned char>(text[start]))) --start;
        size_t position = start;
        if (!is_java_whitespace(next(text, position)) || position != end) break;
        end = start;
    }
    return text.substr(begin, end - begin);
}

std::u32string utf16_order_key(std::string_view text) {
    std::u32string key;
    key.reserve(text.size());
    for (size_t position = 0; position < text.size();) {
        char32_t c = next(text, position);
        if (c >= 0x10000) key.push_back(c - 0x10000 + 0xD800);
        else if (c >= 0xE000) key.push_back(c + 0x100000);
        else key.push_back(c);
    }
    return key;
}

}
