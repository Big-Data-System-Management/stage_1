#pragma once

#include <filesystem>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace stage1::benchmarks {

enum class Mode { SingleShot, AverageTime, Throughput };

struct Iterations {
    int count;
    double seconds = 10.0;
};

using ParamValues = std::map<std::string, std::string>;

struct Param {
    std::string name;
    std::vector<std::string> values;
};

class State {
public:
    virtual ~State() = default;
    virtual void setup_trial() {}
    virtual void teardown_trial() {}
    virtual void setup_iteration() {}
    virtual void teardown_iteration() {}
    virtual void setup_invocation() {}
    virtual void teardown_invocation() {}
};

struct Method {
    std::string name;
    std::function<void(State&)> run;
    int operations = 1;
    std::string unit;
};

template <class S>
Method method(std::string name, void (S::*function)(), int operations = 1, std::string unit = "") {
    return {std::move(name), [function](State& state) { (static_cast<S&>(state).*function)(); }, operations, std::move(unit)};
}

struct Definition {
    std::string name;
    std::vector<Mode> modes;
    std::string unit;
    Iterations warmup{5};
    Iterations measurement{5};
    std::vector<Param> params;
    std::function<std::unique_ptr<State>(const ParamValues&)> create;
    std::vector<Method> methods;
};

struct Options {
    std::optional<int> warmup_iterations;
    std::optional<int> measurement_iterations;
    std::optional<double> warmup_seconds;
    std::optional<double> measurement_seconds;
    std::map<std::string, std::vector<std::string>> params;
};

Options parse_options(const std::vector<std::string>& args);
void run(Definition definition, const Options& options);
std::filesystem::path results_dir();
std::filesystem::path write_csv(const std::string& simple_name, const std::string& header, const std::vector<std::string>& rows);

template <class T>
void consume(const T& value) {
    asm volatile("" : : "r"(&value) : "memory");
}

}
