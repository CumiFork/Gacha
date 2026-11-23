package com.arkana.cppoverlay

object NativeBridge {
    init {
        System.loadLibrary("nativecore")
    }

    external fun getNativeSystemInfo(): String
    external fun getNativeCpuLoad(): String
    external fun formatCommandOutput(raw: String): String
}
