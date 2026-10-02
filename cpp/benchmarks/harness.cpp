#include "harness.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <iostream>
#include <numeric>
#include <sstream>
#include <stdexcept>

#include "files.hpp"

namespace stage1::benchmarks {

namespace {

using Clock = std::chrono::steady_clock;

constexpr double MAX_BATCH_NANOS = 1'000'000;

double unit_nanos(const std::string& unit) {
    if (unit == "s") return 1e9;
    if (unit == "ms") return 1e6;
    if (unit == "us") return 1e3;
    if (unit == "ns") return 1;
    throw std::invalid_argument("Unidad desconocida: " + unit);
}

std::string mode_label(Mode mode) {
    switch (mode) {
        case Mode::SingleShot: return "ss";
        case Mode::AverageTime: return "avgt";
        case Mode::Throughput: return "thrpt";
    }
    return "";
}

std::string unit_label(Mode mode, const std::string& unit) {
    return mode == Mode::Throughput ? "ops/" + unit : unit + "/op";
}

double student_t_999(size_t degrees) {
    static const std::map<size_t, double> table = {
        {1, 636.619}, {2, 31.599}, {3, 12.924}, {4, 8.610}, {5, 6.869}, {6, 5.959}, {7, 5.408}, {8, 5.041},
        {9, 4.781}, {10, 4.587}, {11, 4.437}, {12, 4.318}, {13, 4.221}, {14, 4.140}, {15, 4.073}, {16, 4.015},
        {17, 3.965}, {18, 3.922}, {19, 3.883}, {20, 3.850}, {25, 3.725}, {30, 3.646}};
    auto found = table.upper_bound(degrees);
    return std::prev(found)->second;
}

double mean(const std::vector<double>& scores) {
    if (scores.empty()) return NAN;
    return std::accumulate(scores.begin(), scores.end(), 0.0) / static_cast<double>(scores.size());
}

double error(const std::vector<double>& scores) {
    if (scores.size() < 2) return NAN;
    double average = mean(scores);
    double squares = 0;
    for (double score : scores) squares += (score - average) * (score - average);
    double deviation = std::sqrt(squares / static_cast<double>(scores.size() - 1));
    return student_t_999(scores.size() - 1) * deviation / std::sqrt(static_cast<double>(scores.size()));
}

std::string number(double value) {
    if (std::isnan(value)) return "NaN";
    char buffer[64];
    std::snprintf(buffer, sizeof buffer, "%.6f", value);
    return buffer;
}

struct Result {
    std::string benchmark;
    Mode mode;
    size_t samples;
    double score;
    double error;
    std::string unit;
    ParamValues params;
};

double measure(State& state, const Method& method, Mode mode, const std::string& unit, const Iterations& iterations) {
    if (mode == Mode::SingleShot) {
        state.setup_invocation();
        auto start = Clock::now();
        try {
            method.run(state);
        } catch (...) {
            state.teardown_invocation();
            throw;
        }
        double elapsed = std::chrono::duration<double, std::nano>(Clock::now() - start).count();
        state.teardown_invocation();
        return elapsed / method.operations / unit_nanos(unit);
    }
    double budget = iterations.seconds * 1e9;
    long long calls = 0;
    long long batch = 1;
    double elapsed = 0;
    auto start = Clock::now();
    while (elapsed < budget) {
        auto batch_start = Clock::now();
        for (long long i = 0; i < batch; ++i) method.run(state);
        auto now = Clock::now();
        calls += batch;
        elapsed = std::chrono::duration<double, std::nano>(now - start).count();
        if (std::chrono::duration<double, std::nano>(now - batch_start).count() < MAX_BATCH_NANOS) batch *= 2;
    }
    double operations = static_cast<double>(calls) * method.operations;
    if (mode == Mode::Throughput) return operations / (elapsed / unit_nanos(unit));
    return elapsed / operations / unit_nanos(unit);
}

Result run_trial(const Definition& definition, const Method& method, Mode mode, const ParamValues& values) {
    std::string unit = method.unit.empty() ? definition.unit : method.unit;
    std::string label = unit_label(mode, unit);
    std::string benchmark = definition.name + "." + method.name;
    std::cout << "# Benchmark: " << benchmark << " (" << mode_label(mode) << ")";
    for (const auto& [name, value] : values) std::cout << " " << name << "=" << value;
    std::cout << std::endl;
    std::unique_ptr<State> state = definition.create(values);
    state->setup_trial();
    std::vector<double> scores;
    try {
        for (auto [phase, iterations] : {std::pair{"Warmup", definition.warmup}, std::pair{"Iteration", definition.measurement}}) {
            for (int i = 0; i < iterations.count; ++i) {
                state->setup_iteration();
                double score;
                try {
                    score = measure(*state, method, mode, unit, iterations);
                } catch (...) {
                    state->teardown_iteration();
                    throw;
                }
                state->teardown_iteration();
                std::printf("%s %d: %.3f %s\n", phase, i + 1, score, label.c_str());
                std::fflush(stdout);
                if (std::string(phase) == "Iteration") scores.push_back(score);
            }
        }
    } catch (...) {
        state->teardown_trial();
        throw;
    }
    state->teardown_trial();
    return {benchmark, mode, scores.size(), mean(scores), error(scores), label, values};
}

void combinations(const std::vector<Param>& params, size_t index, ParamValues& current, std::vector<ParamValues>& out) {
    if (index == params.size()) {
        out.push_back(current);
        return;
    }
    for (const std::string& value : params[index].values) {
        current[params[index].name] = value;
        combinations(params, index + 1, current, out);
    }
}

std::vector<std::string> split(const std::string& text, char separator) {
    std::vector<std::string> parts;
    std::stringstream stream(text);
    for (std::string part; std::getline(stream, part, separator);) parts.push_back(part);
    return parts;
}

}

Options parse_options(const std::vector<std::string>& args) {
    Options options;
    for (size_t i = 0; i < args.size(); ++i) {
        const std::string& flag = args[i];
        if (i + 1 >= args.size()) throw std::invalid_argument("Falta el valor de " + flag);
        const std::string& value = args[++i];
        if (flag == "-wi") options.warmup_iterations = std::stoi(value);
        else if (flag == "-i") options.measurement_iterations = std::stoi(value);
        else if (flag == "-w") options.warmup_seconds = std::stod(value);
        else if (flag == "-r") options.measurement_seconds = std::stod(value);
        else if (flag == "-p") {
            size_t equals = value.find('=');
            if (equals == std::string::npos) throw std::invalid_argument("Formato de -p: nombre=v1,v2");
            options.params[value.substr(0, equals)] = split(value.substr(equals + 1), ',');
        } else {
            throw std::invalid_argument("Opción desconocida: " + flag);
        }
    }
    return options;
}

void run(Definition definition, const Options& options) {
    if (options.warmup_iterations) definition.warmup.count = *options.warmup_iterations;
    if (options.measurement_iterations) definition.measurement.count = *options.measurement_iterations;
    if (options.warmup_seconds) definition.warmup.seconds = *options.warmup_seconds;
    if (options.measurement_seconds) definition.measurement.seconds = *options.measurement_seconds;
    for (Param& param : definition.params) {
        auto overridden = options.params.find(param.name);
        if (overridden != options.params.end()) param.values = overridden->second;
    }
    std::sort(definition.methods.begin(), definition.methods.end(),
              [](const Method& a, const Method& b) { return a.name < b.name; });
    std::vector<ParamValues> all_values;
    ParamValues current;
    combinations(definition.params, 0, current, all_values);
    std::vector<Result> results;
    for (const Method& method : definition.methods)
        for (Mode mode : definition.modes)
            for (const ParamValues& values : all_values) results.push_back(run_trial(definition, method, mode, values));

    std::vector<std::string> param_names;
    for (const Param& param : definition.params) param_names.push_back(param.name);
    std::sort(param_names.begin(), param_names.end());
    std::string header = R"csv("Benchmark","Mode","Threads","Samples","Score","Score Error (99.9%)","Unit")csv";
    for (const std::string& name : param_names) header += ",\"Param: " + name + "\"";
    std::vector<std::string> rows;
    for (const Result& result : results) {
        std::string row = "\"" + result.benchmark + "\",\"" + mode_label(result.mode) + "\",1," + std::to_string(result.samples)
                          + "," + number(result.score) + "," + number(result.error) + ",\"" + result.unit + "\"";
        for (const std::string& name : param_names) row += "," + result.params.at(name);
        rows.push_back(row);
    }
    std::string simple_name = definition.name.substr(definition.name.rfind('.') + 1);
    auto path = write_csv(simple_name, header, rows);
    std::cout << "[BENCHMARK] Resultados guardados en " << files::to_string(path) << "\n";
}

std::filesystem::path results_dir() {
    return files::repo_root() / "benchmark" / "results" / "cpp";
}

std::filesystem::path write_csv(const std::string& simple_name, const std::string& header, const std::vector<std::string>& rows) {
    std::filesystem::create_directories(results_dir());
    std::string content = header + "\n";
    for (const std::string& row : rows) content += row + "\n";
    auto path = results_dir() / (simple_name + ".csv");
    files::write(path, content);
    return path;
}

}
