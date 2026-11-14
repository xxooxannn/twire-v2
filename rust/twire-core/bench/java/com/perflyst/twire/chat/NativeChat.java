package com.perflyst.twire.chat;

/**
 * Host-side stand-in for the Kotlin `NativeChat` object. It declares exactly the
 * same native methods (and therefore the same JNI symbols) so the bridge can be
 * exercised against a desktop JVM without an Android device.
 */
public final class NativeChat {
    private NativeChat() {
    }

    public static void loadForHost(String explicitPath) {
        if (explicitPath != null) {
            System.load(explicitPath);
        } else {
            System.loadLibrary("twire_core");
        }
    }

    public static native long nativeNewEmoteIndex(String[] keywords);

    public static native void nativeFreeEmoteIndex(long handle);

    public static native int[] nativeScanEmotes(long handle, String message);

    public static native int[] nativeSearchEmotes(long handle, String query, int limit);

    public static native int[] nativeParseEmoteTag(String tag);
}
