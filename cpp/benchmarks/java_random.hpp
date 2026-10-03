#pragma once

#include <cstdint>
#include <utility>
#include <vector>

namespace stage1::benchmarks {

class JavaRandom {
public:
    explicit JavaRandom(int64_t seed) : seed_((static_cast<uint64_t>(seed) ^ MULTIPLIER) & MASK) {}

    int32_t next(int bits) {
        seed_ = (seed_ * MULTIPLIER + ADDEND) & MASK;
        return static_cast<int32_t>(static_cast<uint32_t>(seed_ >> (48 - bits)));
    }

    int32_t next_int(int32_t bound) {
        int32_t r = next(31);
        int32_t m = bound - 1;
        if ((bound & m) == 0) return static_cast<int32_t>((static_cast<int64_t>(bound) * r) >> 31);
        for (int32_t u = r; static_cast<int64_t>(u) - (r = u % bound) + m > INT32_MAX; u = next(31)) {}
        return r;
    }

    template <class T>
    void shuffle(std::vector<T>& items) {
        for (size_t i = items.size(); i > 1; --i) std::swap(items[i - 1], items[next_int(static_cast<int32_t>(i))]);
    }

private:
    static constexpr uint64_t MULTIPLIER = 0x5DEECE66DULL;
    static constexpr uint64_t ADDEND = 0xBULL;
    static constexpr uint64_t MASK = (1ULL << 48) - 1;

    uint64_t seed_;
};

}
