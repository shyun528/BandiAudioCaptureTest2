package com.whispercpp.java.whisper;

import android.content.res.AssetManager;

public class WhisperLib {

    static {
        System.loadLibrary("whisper");
    }

    public native long initContextFromAsset(
            AssetManager assetManager,
            String assetPath);

    public native void freeContext(long context);

    public native void fullTranscribe(
            long context,
            int numThreads,
            float[] audioData);

    public native int getTextSegmentCount(long context);

    public native String getTextSegment(
            long context,
            int index);

    public native long getTextSegmentT0(
            long context,
            int index);

    public native long getTextSegmentT1(
            long context,
            int index);

    public native String getSystemInfo();
}
