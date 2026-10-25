package dev.maxheater;
final class NativeEngine {
    static { System.loadLibrary("maxheater"); }
    private static long generation, activeSession;
    private static boolean preparing;
    static synchronized long beginSession() {
        if (activeSession != 0 || preparing) return 0;
        activeSession = ++generation;
        arm();
        return activeSession;
    }
    static synchronized void cancelSession(long token) {
        if (token != 0 && token == activeSession) stop();
    }
    static synchronized void finishSession(long token) {
        if (token == activeSession) activeSession = 0;
    }
    static synchronized boolean configure(int matrix,int layers,int iterations,int chains,int depth) {
        return activeSession==0 && !preparing && configureNative(matrix,layers,iterations,chains,depth);
    }
    static synchronized boolean isPreparing() { return preparing; }
    static synchronized void prepareQnnAsync(String directory) {
        if(preparing || activeSession!=0) return;
        preparing=true;
        new Thread(()->{
            try { prepareQnnNative(directory); }
            finally { synchronized(NativeEngine.class) { preparing=false; NativeEngine.class.notifyAll(); } }
        },"qnn-prepare-once").start();
    }
    static boolean prepareQnnBlocking(String directory) throws InterruptedException {
        synchronized(NativeEngine.class) {
            while(preparing) NativeEngine.class.wait();
            if(activeSession!=0) return false;
            preparing=true;
        }
        try { return prepareQnnNative(directory); }
        finally { synchronized(NativeEngine.class) { preparing=false; NativeEngine.class.notifyAll(); } }
    }
    private static native boolean prepareQnnNative(String directory);
    static native long[] qnnPreparation(); // state, successful compilations, last compile ms
    private static native boolean configureNative(int matrix,int layers,int iterations,int chains,int depth);
    private static native void arm();
    static native void run(boolean cpu, boolean gpu, boolean npu, String libraryDir, int seconds);
    static native void stop();
    static native long[] snapshot();
    static native long[] operationCounts();
    static native String detail(int engine);
}
