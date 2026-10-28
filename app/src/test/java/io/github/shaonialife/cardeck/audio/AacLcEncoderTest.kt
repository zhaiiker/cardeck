package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADTS 头拼装与音频方案切换的单测。
 *
 * 背景：车机（Honda HFT / Alpine 8A250-30A）走 type=102 + AAC-LC 时「能连、能消费、
 * 没声音」，剩下没法用日志区分的差异就是负载封装（裸帧 vs ADTS）。这里把 ADTS 头的
 * 每一位固定下来，避免真车试错时把「封装写错」误当成「车机不认」。
 */
class AacLcEncoderTest {
    /** 从帧头解析 13 位帧长。 */
    private fun frameLength(header: ByteArray): Int =
        ((header[3].toInt() and 0x03) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() and 0xFF) shr 5)

    /** 从帧头解析声道配置。 */
    private fun channelConfig(header: ByteArray): Int = ((header[2].toInt() and 0x01) shl 2) or ((header[3].toInt() and 0xFF) shr 6)

    @Test
    fun adtsHeaderFor44100Stereo() {
        val payload = 592
        val header = AacLcEncoder.adtsHeader(payload, 44_100, 2)

        assertEquals(7, header.size)
        // 同步字 FFF + MPEG-4 + layer 0 + protection_absent=1
        assertEquals(0xFF, header[0].toInt() and 0xFF)
        assertEquals(0xF1, header[1].toInt() and 0xFF)
        // profile = AAC-LC（存 AOT-1 = 1）
        assertEquals(1, (header[2].toInt() and 0xFF) shr 6)
        // 采样率索引：44100 → 4
        assertEquals(4, ((header[2].toInt() and 0xFF) shr 2) and 0x0F)
        // 声道配置：立体声 → 2
        assertEquals(2, channelConfig(header))
        // 帧长（含 7 字节头）
        assertEquals(payload + 7, frameLength(header))
        // buffer_fullness = 0x7FF（11 位：h5 低 5 位 + h6 高 6 位），num_raw_data_blocks = 0
        assertEquals(
            0x7FF,
            ((header[5].toInt() and 0x1F) shl 6) or ((header[6].toInt() and 0xFC) shr 2),
        )
        assertEquals(0, header[6].toInt() and 0x03)
    }

    @Test
    fun adtsHeaderTracksSampleRateAndChannels() {
        // 48k → 索引 3；单声道 → 1
        val header = AacLcEncoder.adtsHeader(100, 48_000, 1)
        assertEquals(3, ((header[2].toInt() and 0xFF) shr 2) and 0x0F)
        assertEquals(1, channelConfig(header))
        assertEquals(107, frameLength(header))
    }

    @Test
    fun frequencyIndexTable() {
        assertEquals(4, AacLcEncoder.frequencyIndex(44_100))
        assertEquals(3, AacLcEncoder.frequencyIndex(48_000))
        assertEquals(8, AacLcEncoder.frequencyIndex(16_000))
        // 未收录的采样率退回 44100，避免写出非法索引
        assertEquals(4, AacLcEncoder.frequencyIndex(37_000))
    }

    @Test
    fun tuningVariantsForDongles() {
        // 2026-10-26 重新开放轮换（YCast 盒子只认 ADTS）：RAW 是真车验证的默认，
        // ADTS 是软件盒子通路——两组并存、按车机记忆排序轮换（详见 AudioTuning 文档）
        assertEquals(2, AudioTuning.entries.size)
        assertTrue(!AudioTuning.RAW_SPF.adts)
        assertEquals(1024, AudioTuning.RAW_SPF.spf)
        assertTrue(!AudioTuning.RAW_SPF.highAccuracyTimestamps)
        assertTrue(AudioTuning.ADTS_SPF.adts)
        assertEquals(1024, AudioTuning.ADTS_SPF.spf)
        assertTrue(!AudioTuning.ADTS_SPF.highAccuracyTimestamps)
    }

    @Test
    fun profileKeySeparatesTunings() {
        // 同一档 AAC 在 RAW/ADTS 两封装下是不同通路：记忆键必须分开，
        // 否则 RAW 被拒的结论会错误地连坐 ADTS（下次直接不试）
        val raw =
            AudioProfile(
                "media", (1L shl 23), 48_000, 250L,
                streamType = 102L, codec = AudioCodec.AAC_LC,
                tuning = AudioTuning.RAW_SPF,
            )
        val adts = raw.copy(tuning = AudioTuning.ADTS_SPF)
        assertTrue(raw.key != adts.key)
        assertTrue(raw.key.endsWith("|RAW_SPF"))
        assertTrue(adts.key.endsWith("|ADTS_SPF"))
        // PCM 档不受封装维度影响（无 AAC 帧可封装）
        val pcm = AudioProfile("media", (1L shl 15), 48_000, 250L)
        assertTrue(pcm.key.endsWith("PCM"))
    }

    @Test
    fun profileLabelIncludesTuning() {
        val raw =
            AudioProfile(
                "media", (1L shl 23), 48_000, 250L,
                streamType = 102L, codec = AudioCodec.AAC_LC,
            )
        val adts = raw.copy(tuning = AudioTuning.ADTS_SPF)
        // 日志标签必须能一眼区分两种封装（排查「收包无声」时按标签对号入座）
        assertTrue(raw.label.contains("RAW"))
        assertTrue(adts.label.contains("ADTS"))
    }
}
