#include "gutenberg.hpp"

#include <chrono>
#include <algorithm>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <thread>

#include <curl/curl.h>
#include <zlib.h>

#include "utf8.hpp"

namespace stage1::gutenberg {

namespace {

constexpr const char* HEADERS[] = {
    "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
    "Accept-Language: es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7",
    "Cache-Control: max-age=0",
    "Upgrade-Insecure-Requests: 1",
    "Sec-Ch-Ua: \"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"",
    "Sec-Ch-Ua-Mobile: ?0",
    "Sec-Ch-Ua-Platform: \"Windows\"",
    "Sec-Fetch-Dest: document",
    "Sec-Fetch-Mode: navigate",
    "Sec-Fetch-Site: none",
    "Sec-Fetch-User: ?1",
};

bool is_gzip(std::string_view content) {
    return content.size() >= 2 && static_cast<unsigned char>(content[0]) == 0x1F
           && static_cast<unsigned char>(content[1]) == 0x8B;
}

std::string gunzip(std::string_view content) {
    z_stream stream{};
    if (inflateInit2(&stream, 16 + MAX_WBITS) != Z_OK) throw std::runtime_error("Error initializing zlib");
    stream.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(content.data()));
    stream.avail_in = static_cast<uInt>(content.size());
    std::string out;
    char buffer[1 << 16];
    while (true) {
        stream.next_out = reinterpret_cast<Bytef*>(buffer);
        stream.avail_out = sizeof buffer;
        int status = inflate(&stream, Z_NO_FLUSH);
        out.append(buffer, sizeof buffer - stream.avail_out);
        if (status == Z_STREAM_END) {
            if (stream.avail_in == 0 || !is_gzip({reinterpret_cast<char*>(stream.next_in), stream.avail_in})) break;
            inflateReset(&stream);
        } else if (status != Z_OK) {
            inflateEnd(&stream);
            throw std::runtime_error("Corrupt gzip content");
        }
    }
    inflateEnd(&stream);
    return out;
}

size_t collect(char* data, size_t size, size_t count, void* target) {
    static_cast<std::string*>(target)->append(data, size * count);
    return size * count;
}

void pause(int seconds) {
    std::this_thread::sleep_for(std::chrono::seconds(seconds));
}

void handle_status(long status_code, int book_id) {
    if (status_code == 403) {
        std::cerr << "CRITICAL ALERT HTTP 403 for ID " << book_id << "! Access denied/possible ban. Pausing 2 minutes...\n";
        pause(120);
        throw std::runtime_error("Access forbidden (HTTP 403)");
    }
    if (status_code == 429) {
        std::cerr << "ALERT HTTP 429 for ID " << book_id << "! Server overloaded. Pausing 1 minute...\n";
        pause(60);
        throw std::runtime_error("Too many requests (HTTP 429)");
    }
    if (status_code >= 500 && status_code < 600) {
        std::cerr << "Server error HTTP " << status_code << " for ID " << book_id << ". Pausing 10 seconds...\n";
        pause(10);
        throw std::runtime_error("Internal server error (HTTP " + std::to_string(status_code) + ")");
    }
    throw std::runtime_error("Unclassified HTTP error: " + std::to_string(status_code));
}

bool matches_ignore_case(std::string_view text, size_t& position, std::string_view expected) {
    if (text.size() - position < expected.size()) return false;
    for (size_t i = 0; i < expected.size(); ++i) {
        char c = text[position + i];
        if (c >= 'a' && c <= 'z') c = static_cast<char>(c - 'a' + 'A');
        if (c != expected[i]) return false;
    }
    position += expected.size();
    return true;
}

bool matches_book_suffix(std::string_view text, size_t& position) {
    size_t p = position;
    if (!matches_ignore_case(text, p, " PROJECT GUTENBERG E")) return false;
    if (p < text.size() && text[p] == '-') ++p;
    if (!matches_ignore_case(text, p, "BOOK")) return false;
    position = p;
    return true;
}

size_t match_marker(std::string_view text, size_t position, std::string_view keyword) {
    size_t p = position + 3;
    if (p < text.size() && text[p] == ' ') ++p;
    if (!matches_ignore_case(text, p, keyword) || !matches_ignore_case(text, p, " OF ")) return std::string_view::npos;
    for (std::string_view article : {"THE", "THIS"}) {
        size_t q = p;
        if (matches_ignore_case(text, q, article) && matches_book_suffix(text, q)) return q;
    }
    return std::string_view::npos;
}

struct Match {
    size_t start;
    size_t end;
};

std::optional<Match> find_marker(std::string_view text, size_t from, std::string_view keyword) {
    for (size_t start = text.find("***", from); start != std::string_view::npos; start = text.find("***", start + 1)) {
        size_t end = match_marker(text, start, keyword);
        if (end != std::string_view::npos) return Match{start, end};
    }
    return std::nullopt;
}

}

std::string decode(std::string_view content) {
    return utf8::sanitize(is_gzip(content) ? gunzip(content) : std::string(content));
}

std::optional<std::string> download_book(int book_id) {
    static const bool initialized = curl_global_init(CURL_GLOBAL_DEFAULT) == CURLE_OK;
    if (!initialized) throw std::runtime_error("Error initializing libcurl");
    std::string url = "https://www.gutenberg.org/cache/epub/" + std::to_string(book_id) + "/pg" + std::to_string(book_id) + ".txt";
    std::unique_ptr<CURL, decltype(&curl_easy_cleanup)> curl(curl_easy_init(), curl_easy_cleanup);
    curl_slist* headers = nullptr;
    for (const char* header : HEADERS) headers = curl_slist_append(headers, header);
    std::string body;
    curl_easy_setopt(curl.get(), CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl.get(), CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl.get(), CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl.get(), CURLOPT_CONNECTTIMEOUT, 10L);
    curl_easy_setopt(curl.get(), CURLOPT_WRITEFUNCTION, collect);
    curl_easy_setopt(curl.get(), CURLOPT_WRITEDATA, &body);
    CURLcode result = curl_easy_perform(curl.get());
    curl_slist_free_all(headers);
    if (result != CURLE_OK) throw std::runtime_error(curl_easy_strerror(result));
    long status_code = 0;
    curl_easy_getinfo(curl.get(), CURLINFO_RESPONSE_CODE, &status_code);
    if (status_code == 200) return decode(body);
    handle_status(status_code, book_id);
    return std::nullopt;
}

std::optional<Book> process(const RawBook& raw_book) {
    std::string_view text = raw_book.body;
    auto start = find_marker(text, 0, "START");
    size_t body_start = start ? std::min(text.find_first_of("\r\n", start->end), text.size()) : 0;
    auto end = start ? find_marker(text, body_start, "END") : std::nullopt;
    if (!end) {
        std::cerr << "Project Gutenberg markers not found for book ID: " << raw_book.book_id << "\n";
        return std::nullopt;
    }
    std::string header(utf8::java_strip(text.substr(0, start->start)));
    std::string body(utf8::java_strip(text.substr(body_start, end->start - body_start)));
    return Book{raw_book.book_id, std::move(header), std::move(body)};
}

}
