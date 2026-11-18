package dev.remotecode.data

enum class DesktopDecodeColor {
    Auto, Full, Compact;

    internal fun uses32Bit(codec: String, resolution: DesktopResolution): Boolean =
        codec == "png" || this == Full || (this == Auto && resolution != DesktopResolution.Standard)
}
