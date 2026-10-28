package io.github.shaonialife.cardeck.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * 蓝牙音频旁路（2026-10-26）：主动连接**车机本体**（非 CarPlay 盒子的另一台设备）
 * 的 A2DP，让手机声音经系统蓝牙直接进车机喇叭。
 *
 * 为什么需要（别克威朗 2020 + SmartBox-A703 实测场景）：CarPlay 盒子只负责画面
 * （iAP2→AirPlay 视频），其 AirPlay 音频通道对 YCast 固件不兼容（AAC 消费欠载/
 * 采集全零），而车机本体是标准蓝牙音箱。手机蓝牙 A2DP 直连车机本体放声音，
 * 绕开 AirPlay 音频与 `AudioPlaybackCapture` 的全部限制（Spotify 等禁止播放捕获
 * 的 app 也照常出声）。
 *
 * 与 [BtProfiles]「只配对、不连接」的关系：后者只断开**要连 iAP2 的那台设备**
 * （CarPlay 盒子）的 HFP/A2DP——盒子本就没有这些配置文件；车机本体的 A2DP 不在
 * 断开范围。因此两条蓝牙链路（iAP2 连盒子 / A2DP 连车机）互不干扰。
 *
 * A2DP 连接是**系统级**的：`getProfileProxy(A2DP)` + 反射 `connect(device)`（A2dp
 * 的 connect 是 @SystemApi，运行时只要求 BLUETOOTH_CONNECT）。连接成功后系统会把
 * 媒体音频路由到车机，无需本应用做任何音频搬运。
 */
object BtAudioRoute {
    private const val TAG = "BT-AUDIO"

    @Volatile
    private var proxy: BluetoothProfile? = null

    /** 该地址当前是否已通过 A2DP 在线。 */
    fun isA2dpConnected(
        context: Context,
        address: String,
    ): Boolean {
        val p = obtainProxy(context) ?: return false
        return try {
            @Suppress("DEPRECATION")
            p.connectedDevices.any { it.address.equals(address, ignoreCase = true) }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 尽力连接指定设备的 A2DP。返回是否已连接（或本就连接）。
     * 连接是异步的（系统建链 + 车机应答），此处只发起，不阻塞等完成。
     */
    @SuppressLint("MissingPermission")
    fun connectA2dp(
        context: Context,
        device: BluetoothDevice,
        log: (String) -> Unit,
    ): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log("$TAG: 缺少 BLUETOOTH_CONNECT 权限，无法连接 A2DP")
            return false
        }
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return false
            }
        if (isA2dpConnected(context, address)) {
            log("$TAG: 车机本体（$address）A2DP 已在线——声音走蓝牙")
            return true
        }
        val p = obtainProxy(context)
        if (p == null) {
            log("$TAG: 无法获取 A2DP 代理（蓝牙服务未就绪）")
            return false
        }
        return try {
            // A2dp.connect(device) 是 @SystemApi：反射调用，运行时仅要求 BLUETOOTH_CONNECT
            val connected =
                p.javaClass
                    .getMethod("connect", BluetoothDevice::class.java)
                    .invoke(p, device)
                    ?.let { it as? Boolean }
                    ?: false
            log(
                "$TAG: 已发起 A2DP 连接车机本体（$address，connect=$connected）——" +
                    "建链完成后手机声音自动进车机",
            )
            connected
        } catch (error: Throwable) {
            log("$TAG: A2DP connect 失败 - ${error.message}")
            false
        }
    }

    /** 断开 A2DP（会话结束/手动断开时收口，让手机音频回到本机）。 */
    @SuppressLint("MissingPermission")
    fun disconnectA2dp(
        context: Context,
        device: BluetoothDevice,
        log: (String) -> Unit,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        val p = obtainProxy(context) ?: return
        try {
            @Suppress("DEPRECATION")
            val online = p.connectedDevices.any { it.address.equals(address, ignoreCase = true) }
            if (!online) return
            p.javaClass
                .getMethod("disconnect", BluetoothDevice::class.java)
                .invoke(p, device)
            log("$TAG: 已断开车机本体 A2DP（$address）——手机音频回到本机")
        } catch (_: Throwable) {
        }
    }

    /** 按地址查找已配对设备（供主控把偏好地址转成 BluetoothDevice）。 */
    @SuppressLint("MissingPermission")
    fun bondedDevice(
        context: Context,
        address: String,
    ): BluetoothDevice? {
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return null
        return try {
            adapter.bondedDevices?.firstOrNull {
                it.address.equals(address, ignoreCase = true)
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun obtainProxy(context: Context): BluetoothProfile? {
        proxy?.let { return it }
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return null
        // getProfileProxy 回调经主线程 Handler 投递：主线程调用必然超时（同 BtProfiles 的教训）
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return null
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val ref = java.util.concurrent.atomic.AtomicReference<BluetoothProfile?>(null)
        val requested =
            runCatching {
                adapter.getProfileProxy(
                    context.applicationContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(
                            profile: Int,
                            profileProxy: BluetoothProfile,
                        ) {
                            ref.set(profileProxy)
                            latch.countDown()
                        }

                        override fun onServiceDisconnected(profile: Int) {
                            proxy = null
                            latch.countDown()
                        }
                    },
                    BluetoothProfile.A2DP,
                )
            }.getOrDefault(false)
        if (!requested) return null
        if (!latch.await(1_200, java.util.concurrent.TimeUnit.MILLISECONDS)) return null
        return ref.get()?.also { proxy = it }
    }
}
