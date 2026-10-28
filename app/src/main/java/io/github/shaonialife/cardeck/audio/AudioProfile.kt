package io.github.shaonialife.cardeck.audio

/** 音频负载编码方式：决定采集后如何打包 RTP。 */
enum class AudioCodec {
    /** 裸 PCM（大端 s16），type=100 通路。 */
    PCM,

    /** AAC-LC（封装方式见 [AudioProfile.tuning]），type=102 通路。 */
    AAC_LC,
}

/**
 * 一次可选的音频档位：流类型 + `audioType` + 格式位 + 采样率 + 延迟 + 编码方式
 * + 封装参数。
 *
 * 为什么需要「档位」（2026-10-20 Honda HFT / Alpine 8A250-30A 实测结论）：
 * 车机 `/info` 的 `audioFormats` 是 **按 (type, audioType) 分档声明各自位图** 的，
 * 不同车机真正的「媒体音频通路」落在不同档上：
 *  - 奥迪等：`type=100 media` + PCM 48k/44.1k 即可播放（作者 2026-10-17 打通）；
 *  - 本台 Honda：`type=100 media` 只声明 PCM 44.1k，SETUP 回 200、给 dataPort、
 *    但 `controlPort=0`、应答只有 3 个字段，车机 `/feedback` 里该流 `sampleTime`
 *    始终为 0（一个样本都没消费），约 6.5s 后车机把整条会话拆掉；
 *    而它真正的媒体通路是 **`type=102`（MainHighAudio）+ `media` + AAC-LC 44.1k**
 *    （`0x400000`，bit22）——真 iPhone 的媒体音频就走这条。
 *
 * **封装维度（2026-10-26 SmartBox-A703/YCast 定谳新增）**：同一档 AAC 在
 * 「裸帧 / ADTS」两种封装下的可用性也不同——严格实现（奥迪/DiPlay/Honda）两者
 * 都接受；YCast 类软件盒子只认 ADTS（裸帧被判非帧 → sampleTime 几乎不推进 →
 * 起流 ~9s 欠载拆链）。tuning 参与记忆键，跑满存活窗口的组合下次直接首选。
 *
 * 所以候选必须是**跨流类型、跨编码、跨封装**的多档，失败一档换下一档，并把
 * 结论按车机持久化（[AudioProfileMemory]）。
 */
data class AudioProfile(
    /** audioType 原值（media / compatibility / default …），SETUP 里原样回给车机。 */
    val audioType: String,
    /** 音频格式位（catplay `AudioFormat`：PCM48k=1<<15、PCM44.1k=1<<11、AAC-LC44.1k=1<<22）。 */
    val formatBit: Long,
    /** 与 [formatBit] 对应的采集采样率——`AudioSender` 必须用同一值采集。 */
    val sampleRate: Int,
    /** SETUP 的 audioLatencyMs。 */
    val latencyMs: Long,
    /** RTP 流类型：100=MainAudio（PCM 通路）、102=MainHighAudio（AAC-LC 通路）。 */
    val streamType: Long = 100L,
    /** 负载编码方式。 */
    val codec: AudioCodec = AudioCodec.PCM,
    /** 封装/时间戳参数（仅 AAC 有意义；PCM 档恒为 [AudioTuning.RAW_SPF] 占位）。 */
    val tuning: AudioTuning = AudioTuning.RAW_SPF,
) {
    /** 持久化记忆的键（含流类型/编码/封装：同一档位不同封装是完全不同的通路）。 */
    val key: String
        get() = "$streamType|${audioType.lowercase()}|$formatBit|${codec.name}" +
            if (codec == AudioCodec.AAC_LC) "|${tuning.name}" else ""

    /** 日志用短标签。 */
    val label: String
        get() = "t$streamType/$audioType/0x${formatBit.toString(16)}" +
            "(${sampleRate}Hz,${codec.name}" +
            (if (codec == AudioCodec.AAC_LC) ",${if (tuning.adts) "ADTS" else "RAW"}" else "") +
            ",$latencyMs ms)"
}
