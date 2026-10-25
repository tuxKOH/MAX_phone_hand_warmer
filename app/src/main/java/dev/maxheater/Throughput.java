package dev.maxheater;
final class Throughput {
    // Counts are completed work; use floating-point multiplication to avoid overflow.
    static double operationsPerSecond(long completed, long operationsPerUnit, double elapsedSeconds) {
        if(completed<0 || operationsPerUnit<=0 || !Double.isFinite(elapsedSeconds) || elapsedSeconds<=0) return 0;
        return ((double)completed)*operationsPerUnit/elapsedSeconds;
    }
}
