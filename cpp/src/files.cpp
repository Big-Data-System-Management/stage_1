#include "files.hpp"

#include <cstdio>
#include <cwchar>
#include <memory>
#include <stdexcept>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

namespace stage1::files {

namespace {

struct FileCloser {
    void operator()(std::FILE* file) const { std::fclose(file); }
};

using File = std::unique_ptr<std::FILE, FileCloser>;

File open(const fs::path& path, const char* mode) {
#ifdef _WIN32
    std::wstring wide_mode(mode, mode + std::char_traits<char>::length(mode));
    File file(_wfopen(path.c_str(), wide_mode.c_str()));
#else
    File file(std::fopen(path.c_str(), mode));
#endif
    if (!file) throw std::runtime_error("No se puede abrir " + to_string(path));
    return file;
}

}

fs::path path_of(std::string_view utf8) {
    return fs::path(std::u8string(reinterpret_cast<const char8_t*>(utf8.data()), utf8.size()));
}

std::string to_string(const fs::path& path) {
    std::u8string text = path.u8string();
    return {reinterpret_cast<const char*>(text.data()), text.size()};
}

const fs::path& repo_root() {
    static const fs::path root = path_of(STAGE1_REPO_ROOT).make_preferred();
    return root;
}

bool exists(const fs::path& path) {
    std::error_code error;
    return fs::exists(path, error);
}

std::string read(const fs::path& path) {
    File file = open(path, "rb");
    std::string content;
    char buffer[1 << 16];
    size_t count;
    while ((count = std::fread(buffer, 1, sizeof buffer, file.get())) > 0) content.append(buffer, count);
    return content;
}

void write(const fs::path& path, std::string_view content) {
    File file = open(path, "wb");
    if (!content.empty() && std::fwrite(content.data(), 1, content.size(), file.get()) != content.size())
        throw std::runtime_error("Error escribiendo " + to_string(path));
}

void replace(const fs::path& source, const fs::path& target) {
#ifdef _WIN32
    if (!MoveFileExW(source.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING))
        throw std::runtime_error("Error moviendo " + to_string(source) + " a " + to_string(target));
#else
    fs::rename(source, target);
#endif
}

void write_atomically(const fs::path& target, std::string_view content) {
    fs::path tmp = target;
    tmp += ".tmp";
    write(tmp, content);
    replace(tmp, target);
}

#ifdef _WIN32
namespace {

std::string narrow(const wchar_t* wide) {
    int length = WideCharToMultiByte(CP_UTF8, 0, wide, -1, nullptr, 0, nullptr, nullptr);
    std::string out(length > 0 ? length - 1 : 0, '\0');
    if (length > 1) WideCharToMultiByte(CP_UTF8, 0, wide, -1, out.data(), length, nullptr, nullptr);
    return out;
}

void walk_directory(const fs::path& directory, const std::function<void(const WalkEntry&)>& visit) {
    WIN32_FIND_DATAW data;
    HANDLE handle = FindFirstFileExW((directory / L"*").c_str(), FindExInfoBasic, &data, FindExSearchNameMatch,
                                     nullptr, FIND_FIRST_EX_LARGE_FETCH);
    if (handle == INVALID_HANDLE_VALUE) return;
    std::vector<fs::path> subdirectories;
    do {
        if (std::wcscmp(data.cFileName, L".") == 0 || std::wcscmp(data.cFileName, L"..") == 0) continue;
        bool is_directory = (data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
        uint64_t size = (static_cast<uint64_t>(data.nFileSizeHigh) << 32) | data.nFileSizeLow;
        std::string name = narrow(data.cFileName);
        visit({directory, name, is_directory, is_directory ? 0 : size});
        if (is_directory && (data.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) == 0)
            subdirectories.push_back(directory / data.cFileName);
    } while (FindNextFileW(handle, &data));
    FindClose(handle);
    for (const fs::path& subdirectory : subdirectories) walk_directory(subdirectory, visit);
}

}

void walk(const fs::path& root, const std::function<void(const WalkEntry&)>& visit) {
    walk_directory(root, visit);
}
#else
void walk(const fs::path& root, const std::function<void(const WalkEntry&)>& visit) {
    std::error_code error;
    for (fs::recursive_directory_iterator it(root, error), end; !error && it != end; it.increment(error)) {
        bool is_directory = it->is_directory(error);
        std::string name = to_string(it->path().filename());
        fs::path directory = it->path().parent_path();
        visit({directory, name, is_directory, is_directory ? 0 : it->file_size(error)});
    }
}
#endif

std::vector<std::string> lines(std::string_view text) {
    std::vector<std::string> result;
    size_t start = 0;
    for (size_t i = 0; i < text.size(); ++i) {
        if (text[i] != '\n' && text[i] != '\r') continue;
        result.emplace_back(text.substr(start, i - start));
        if (text[i] == '\r' && i + 1 < text.size() && text[i + 1] == '\n') ++i;
        start = i + 1;
    }
    if (start < text.size()) result.emplace_back(text.substr(start));
    return result;
}

}
