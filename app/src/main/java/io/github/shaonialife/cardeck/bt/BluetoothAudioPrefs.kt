package io.github.shaonialife.cardeck.bt

import android.content.Context

/**
 * 「蓝牙音频目标」偏好（2026-10-26）：声音走蓝牙 A2DP 直连车机本体的方案开关。
 *
 * 场景：有线转无线 CarPlay 盒子（如 SmartBox-A703，YCast 固件）的 AirPlay 音频
 * 通道不兼容（AAC 消费欠载 / app 禁止播放捕获导致采集全零），而车机本体（如
 * 别克威朗 2020 的 myBuick）是标准蓝牙音箱（A2DP AudioSink）。让手机系统蓝牙
 * 直连车机本体放声音，画面上 iAP2 连盒子——两条链路是两个不同蓝牙设备，互不
 * 干扰，且**绕过 AirPlay 音频与播放捕获的所有限制**（Spotify 声音照常走系统
 * 蓝牙 A2DP，不经过 AudioPlaybackCapture）。
 *
 * 存地址 + 名称快照（对齐 [TargetDevicePrefs]）：地址是判定依据，名称仅 UI 展示。
 * 未设置 = 不启用蓝牙音频旁路（保持原有 AirPlay 音频路径）。
 */
object BluetoothAudioPrefs {
    private const val PREFS = "bt_audio_target"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"

    data class Target(
        val address: String,
        val name: String,
    )

    /** null = 未启用（声音走 AirPlay 音频通道，原路径）。 */
    fun get(context: Context): Target? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val address = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return Target(address = address, name = prefs.getString(KEY_NAME, null) ?: address)
    }

    fun set(
        context: Context,
        target: Target?,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (target == null) {
            prefs.edit().clear().apply()
        } else {
            prefs
                .edit()
                .putString(KEY_ADDRESS, target.address)
                .putString(KEY_NAME, target.name)
                .apply()
        }
    }

    fun matches(
        target: Target,
        address: String?,
    ): Boolean = address != null && address.equals(target.address, ignoreCase = true)
}
