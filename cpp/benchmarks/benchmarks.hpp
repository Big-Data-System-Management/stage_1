#pragma once

#include <filesystem>
#include <string>

#include "harness.hpp"

namespace stage1::benchmarks {

std::filesystem::path temp_directory(const std::string& prefix);

Definition book_data_process_benchmark();
Definition incremental_processing_benchmark();
Definition look_up_cost_benchmark();
Definition recovery_behavior_benchmark();
void storage_overhead_benchmark();
void init_data();

Definition index_build_benchmark();
Definition index_update_benchmark();
Definition index_query_benchmark();
void run_index_benchmark(Definition definition, Options options);
void index_storage_report(const Options& options);

Definition metadata_insert_benchmark();
Definition metadata_query_benchmark();

}
