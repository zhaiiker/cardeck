package io.github.shaonialife.cardeck.audio

/**
 * 音频负载的封装/时间戳参数（**2026-10-26 起重新开放轮换**）。
 *
 * 历史：2026-10-20 在 Honda HFT / Alpine 8A250-30A（Telechips tcc805x Android 10）上
 * 走 `type=102` + AAC-LC 44100 时，车机 SETUP 应答完整、`/feedback` 里 `sampleTime`
 * 按 44.1k 实时推进、会话稳定不断，但**喇叭没有声音**。当时把三组可能的封装差异
 * 做成可切换方案，在车上逐一实测。定案：真正的病根不在封装，而是发送前对 AAC 比特流
 * 做了 PCM 的 s16 换序（见 [AudioSender.sealPacket]）——换序修掉后方案 1 即可出声，
 * 于是收敛为唯一一组参数。
 *
 * **2026-10-26 重新开放（SmartBox-A703 / YCast 定谳）**：廉价「有线转无线」盒子
 * （model=YCast, fv=WifiCP0.1）的音频通路是**软件接收端**（自报 Android8），它的
 * AAC 解码链按 ADTS 组织——裸帧喂进去被判为非帧，表现为「SETUP 200、收 UDP、
 * feedback 的 sampleTime 几乎不推进（欠载）、起流 ~9s 后整链 RST」。CarPlay 主音频
 * 在规范上本就是 AAC-over-ADTS 流（社区逆向与用户实测一致），只是严格实现（奥迪/
 * DiPlay/Honda）两种封装都接受。因此把 [RAW_SPF] 与 [ADTS_SPF] 做成候选轮换，
 * 顺序按 [io.github.shaonialife.cardeck.audio.AudioProfileMemory] 的车机记忆排：
 * 哪组在这台车机跑满存活窗口，下次直接首选那组。
 *
 * **不要退回「唯一组合」**——那是建立在「所有接收端都容忍裸帧」的假设上，
 * 已被 YCast 盒子证伪。
 */
enum class AudioTuning(
    /** 是否给每帧补 ADTS 头。 */
    val adts: Boolean,
    /** SETUP 里声明的 spf（每包样本帧数）；null = 不声明。 */
    val spf: Int?,
    /** SETUP 里的 supportsHighAccuracyTimestamps。 */
    val highAccuracyTimestamps: Boolean,
) {
    /** 裸 AAC 帧 + `spf=1024` + 到达时间戳——真车（奥迪/DiPlay/Honda）实测可用。 */
    RAW_SPF(
        adts = false,
        spf = 1024,
        highAccuracyTimestamps = false,
    ),

    /** ADTS 封装 + `spf=1024`——YCast 类软件盒子（解码链按 ADTS 组织）。 */
    ADTS_SPF(
        adts = true,
        spf = 1024,
        highAccuracyTimestamps = false,
    ),
    ;

    /** 日志用的一行说明。 */
    val detail: String
        get() = (if (adts) "ADTS 封装" else "裸 AAC 帧") +
            " + spf=$spf + ${if (highAccuracyTimestamps) "高精度时间戳" else "到达时间戳"}"
}
