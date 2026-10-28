package io.github.shaonialife.cardeck.iap2

import android.content.Context

/**
 * 「第二链路」（MIB3 marker 协商 + type-130 iAP 隧道）的**按车机持久化开关**。
 *
 * 为什么需要（2026-10-26 SmartBox-A703 定谳）：这台 YCast/Samuncle 盒子
 * （`fv=WifiCP0.1, OSInfo=Android8, srcvers=320.17.6, wifiGen=LEGACY`）在
 * 「屏幕流 SETUP 200 → RECORD」之间的窗口里收到我们发的 MIB3 DETECT marker
 * （控制通道 POST /command）或 iAP 隧道 SETUP（每次回 streams=null）后，
 * **~5-100ms 内把事件通道 + 控制通道 + 数据端口全部 RST**——8 轮全部同点失败、
 * RECORD 从未送达。它不是 MIB3（无第二链路语义），这两样东西对它是纯毒药；
 * 而对奥迪 MIB3 它们是必需的（不协商 ~10s 被拆）。
 *
 * 记忆语义（对齐 [MfiLengthMemory] 的三态，但只有两种结局）：
 *  - **无记忆**：默认自动——按链路代际与 /info 信号决定（见 [defaultPolicy]）；
 *  - [recordFatal]：该接收端曾在布防/隧道协商期间被整链 RST → 下次**跳过**两者，
 *    屏幕 SETUP 后直连 dataPort + 立即 RECORD；
 *  - [recordHealthy]：布防后控制会话完成且推流存活（AUDIO_PROFILE_PROBE_MS 量级）
 *    → 证伪旧的 fatal 记忆（固件升级可能修好），回到自动。
 *
 * 用户侧「强制关闭」不存这里（那是全局偏好，见 SessionController 的调试开关），
 * 这里只记「这台车机实测会死」。
 */
object SecondLinkMemory {
    /** 单个车机的记忆：null = 无结论（自动策略）。 */
    data class Entry(val fatal: Boolean = false)

    private const val FILE = "second_link_memory"
    private const val KEY_FATAL = "fatal_"

    private val memory = HashMap<String, Entry>()

    fun load(
        context: Context?,
        identityText: String,
    ): Entry {
        val key = MfiLengthMemory.receiverKey(identityText)
        val prefs = prefs(context)
        if (prefs == null) {
            return synchronized(memory) { memory[key] ?: Entry() }
        }
        return Entry(fatal = prefs.getBoolean(KEY_FATAL + key, false))
    }

    /** 布防/隧道期间被整链拆掉（RST/看门狗形态）——下次跳过第二链路。 */
    fun recordFatal(
        context: Context?,
        identityText: String,
    ) {
        update(context, identityText) { Entry(fatal = true) }
    }

    /** 第二链路协商完成且会话健康存活——清除 fatal 记忆（固件可能已变）。 */
    fun recordHealthy(
        context: Context?,
        identityText: String,
    ) {
        update(context, identityText) { Entry(fatal = false) }
    }

    /**
     * 自动策略（无记忆时的默认，纯函数）：
     *
     *  - **CLASSIC_MIB3**（奥迪 DELPHI 真车）：第二链路是硬需求（不协商 RECORD 后
     *    ~10s 被拆，2026-10-23 二次定谳）——用；
     *  - **其余代际 + 车机自称非 MIB3**：默认也用（2026-10-23 起 marker 协商全代际
     *    统一，EVENT_LINK 车机也需要协商到 NORMAL 才有媒体键）——**但**如果
     *    `/info` 的 `manufacturer/model` 明确是已知的「软件盒子」形态（YCast 类，
     *    mDNS model=YCast + fv=WifiCP），则跳过：这类盒子没有 type-130 隧道语义
     *    （2026-10-27 实测隧道 SETUP 恒回 streams=null/空 dict，是真不支持，不是时序错）。
     *    它的「第二链路」是**事件通道 marker 协商**（VirtualIap2Link 的 MIB3 路径），
     *    与 type-130 隧道无关——所以隧道对盒子必须门禁掉，marker 布防照常开。
     *
     * @param mib3Generation 蓝牙侧 0x1D01 判定的链路代际
     * @param infoManufacturer /info 的 manufacturer（如 "Samuncle"）
     * @param infoModel /info 的 model（如 "YCast"）
     */
    fun defaultPolicy(
        mib3Generation: ReceiverProfile.LinkGeneration,
        infoManufacturer: String?,
        infoModel: String?,
    ): Boolean = when (mib3Generation) {
        // 已判 MIB3：硬需求，不看 /info
        ReceiverProfile.LinkGeneration.CLASSIC_MIB3 -> true
        // 其余：识别为「软件盒子」形态则跳过（fatal 记忆时调用方已先行跳过）
        ReceiverProfile.LinkGeneration.EVENT_LINK ->
            !looksLikeSoftwareDongle(infoManufacturer, infoModel)
    }
    /**
     * 「软件盒子」识别（纯函数）：有线转无线 CarPlay 盒子普遍自报 YCast 模型 +
     * `fv=WifiCP*` 固件标识（SmartBox-A703 实测 model=YCast, manufacturer=Samuncle,
     * firmwareRevision=WifiCP0.1）。这类接收端是精简的软件 AirPlay 实现，没有
     * 第二条 iAP2 链路的状态机——marker/隧道请求会把它直接打崩。
     * 只按能力信号识别，不碰品牌名（铁律：按能力不按品牌）。
     */
    internal fun looksLikeSoftwareDongle(
        manufacturer: String?,
        model: String?,
    ): Boolean {
        val m = model?.lowercase().orEmpty()
        val f = manufacturer?.lowercase().orEmpty()
        // model=YCast 是这类盒子的通用 AirPlay 栈自报；manufacturer 各家不同
        // （Samuncle 等），故只锚 model，manufacturer 仅作日志参考不参与判定
        return m == "ycast" || m.contains("wificp") || f.contains("wificp")
    }

    // ---- 内部 ----

    private fun prefs(context: Context?): android.content.SharedPreferences? = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun update(
        context: Context?,
        identityText: String,
        transform: (Entry) -> Entry,
    ) {
        val key = MfiLengthMemory.receiverKey(identityText)
        synchronized(memory) {
            val next = transform(load(context, identityText))
            memory[key] = next
            prefs(context)
                ?.edit()
                ?.putBoolean(KEY_FATAL + key, next.fatal)
                ?.apply()
        }
    }
}
