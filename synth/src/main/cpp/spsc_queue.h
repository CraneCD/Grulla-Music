// Single-producer / single-consumer lock-free ring buffer, safe to use from an
// audio callback (no locks, no allocation).
#pragma once

#include <array>
#include <atomic>
#include <cstddef>

namespace grulla {

template <typename T, std::size_t Capacity>
class SpscQueue {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");

public:
    /** Producer side. Returns false when full. */
    bool push(const T& value) {
        const std::size_t head = head_.load(std::memory_order_relaxed);
        const std::size_t tail = tail_.load(std::memory_order_acquire);
        if (head - tail == Capacity) return false;
        items_[head & (Capacity - 1)] = value;
        head_.store(head + 1, std::memory_order_release);
        return true;
    }

    /** Consumer side. Returns false when empty. */
    bool pop(T& out) {
        const std::size_t tail = tail_.load(std::memory_order_relaxed);
        const std::size_t head = head_.load(std::memory_order_acquire);
        if (head == tail) return false;
        out = items_[tail & (Capacity - 1)];
        tail_.store(tail + 1, std::memory_order_release);
        return true;
    }

private:
    std::array<T, Capacity> items_{};
    std::atomic<std::size_t> head_{0};
    std::atomic<std::size_t> tail_{0};
};

}  // namespace grulla
