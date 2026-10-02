#pragma once

#include <cstdint>
#include <filesystem>
#include <functional>
#include <string>
#include <string_view>
#include <vector>

namespace stage1::files {

namespace fs = std::filesystem;

fs::path path_of(std::string_view utf8);
std::string to_string(const fs::path& path);
const fs::path& repo_root();
bool exists(const fs::path& path);
std::string read(const fs::path& path);
void write(const fs::path& path, std::string_view content);
void replace(const fs::path& source, const fs::path& target);
void write_atomically(const fs::path& target, std::string_view content);
std::vector<std::string> lines(std::string_view text);

struct WalkEntry {
    const fs::path& directory;
    const std::string& name;
    bool is_directory;
    uint64_t size;
};

void walk(const fs::path& root, const std::function<void(const WalkEntry&)>& visit);

}
