package com.realgen.offline;

/** JNI bridge to stable-diffusion.cpp (see src/main/cpp/sdjni.cpp). */
public final class Native {
    static {
        System.loadLibrary("sdjni");
    }

    public interface Progress {
        void on(int step, int steps);
    }

    static volatile Progress progress;

    /** Called from native code on every sampling step. */
    public static void onProgress(int step, int steps) {
        Progress p = progress;
        if (p != null) p.on(step, steps);
    }

    public static native boolean load(String modelPath, int threads);

    /** Returns [width, height, ARGB pixels...] or null on failure / cancel. */
    public static native int[] generate(String prompt, String negative, String loraPath,
                                        float loraStrength, int width, int height,
                                        int steps, float cfg, long seed);

    public static native void cancel();

    public static native void unload();

    public static native String lastLog();

    public static native String sysInfo();

    private Native() {}
}
