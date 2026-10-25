#pragma once
#include <atomic>
#include <cstdint>
#include <string>
#include <chrono>
extern std::atomic<bool> running;
extern std::atomic<long long> counters[3];
extern std::atomic<int> states[3]; // 0 off, 1 preparing, 2 executing, 3 failed, 4 stopped
bool active();
void detail(int engine, const std::string& message);
void runQnn(const std::string& libraryDir);

constexpr uint32_t GPU_LANES=262144;
struct WorkloadConfig {
    uint32_t matrix=2048, layers=16, gpuIterations=512, gpuChains=16, queueDepth=4;
};
extern WorkloadConfig workload;
inline uint64_t gpuOps() { return uint64_t(GPU_LANES)*workload.gpuIterations*workload.gpuChains*4*2; }
inline uint64_t npuOps() { return uint64_t(2)*workload.matrix*workload.matrix*workload.matrix*workload.layers; }
