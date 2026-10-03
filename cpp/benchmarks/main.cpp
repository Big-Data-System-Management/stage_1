#include <functional>
#include <iostream>
#include <map>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

#include "benchmarks.hpp"

using namespace stage1::benchmarks;

int main(int argc, char** argv) {
#ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);
#endif
    const std::map<std::string, std::function<void(const Options&)>> commands = {
        {"BookDataProcessBenchmark", [](const Options& o) { run(book_data_process_benchmark(), o); }},
        {"IncrementalProcessingBenchmark", [](const Options& o) { run(incremental_processing_benchmark(), o); }},
        {"LookUpCostBenchmark", [](const Options& o) { run(look_up_cost_benchmark(), o); }},
        {"RecoveryBehaviorBenchmark", [](const Options& o) { run(recovery_behavior_benchmark(), o); }},
        {"StorageOverheadBenchmark", [](const Options&) { storage_overhead_benchmark(); }},
        {"InitData", [](const Options&) { init_data(); }},
        {"IndexBuildBenchmark", [](const Options& o) { run_index_benchmark(index_build_benchmark(), o); }},
        {"IndexUpdateBenchmark", [](const Options& o) { run_index_benchmark(index_update_benchmark(), o); }},
        {"IndexQueryBenchmark", [](const Options& o) { run_index_benchmark(index_query_benchmark(), o); }},
        {"IndexStorageReport", [](const Options&) { index_storage_report(); }},
        {"MetadataInsertBenchmark", [](const Options& o) { run(metadata_insert_benchmark(), o); }},
        {"MetadataQueryBenchmark", [](const Options& o) { run(metadata_query_benchmark(), o); }},
    };
    auto command = argc > 1 ? commands.find(argv[1]) : commands.end();
    if (command == commands.end()) {
        std::cerr << "Uso: stage1_benchmarks <benchmark> [-wi N] [-i N] [-w segundos] [-r segundos] [-p nombre=v1,v2]\n";
        for (const auto& [name, _] : commands) std::cerr << "  " << name << "\n";
        return 1;
    }
    try {
        command->second(parse_options(std::vector<std::string>(argv + 2, argv + argc)));
    } catch (const std::exception& error) {
        std::cerr << "Error: " << error.what() << "\n";
        return 1;
    }
    return 0;
}
