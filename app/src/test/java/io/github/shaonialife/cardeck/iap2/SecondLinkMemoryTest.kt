package io.github.shaonialife.cardeck.iap2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二链路（MIB3 marker + iAP 隧道）门禁策略。
 *
 * 背景（2026-10-26 SmartBox-A703 真机定谳，2026-10-27 修正）：YCast 类软件盒子
 * **没有 type-130 隧道语义**——隧道 SETUP 恒回 streams=null/空 dict（真不支持，不是
 * 时序错）。它的「第二链路」是事件通道 marker 协商（VirtualIap2Link 的 MIB3 路径），
 * 与 type-130 隧道无关。所以 defaultPolicy 对软件盒子跳过隧道，marker 布防照常开。
 */
class SecondLinkMemoryTest {
    @Test
    fun yCastDongleSkipsTunnel() {
        // SmartBox-A703 实测自报：model=YCast, manufacturer=Samuncle。
        // looksLikeSoftwareDongle 识别保留，defaultPolicy 对软件盒子返回 false
        // （跳过 type-130 隧道——盒子真不支持，streams=null）。
        assertTrue(SecondLinkMemory.looksLikeSoftwareDongle("Samuncle", "YCast"))
        assertFalse(
            SecondLinkMemory.defaultPolicy(
                ReceiverProfile.LinkGeneration.EVENT_LINK,
                "Samuncle",
                "YCast",
            ),
        )
    }

    @Test
    fun wificpFirmwareIdIsSkipped() {
        // model 或 manufacturer 带 WifiCP 固件标识（fv=WifiCP0.1）
        assertTrue(SecondLinkMemory.looksLikeSoftwareDongle(null, "WifiCP0.1"))
        assertTrue(SecondLinkMemory.looksLikeSoftwareDongle("wificp vendor", "box"))
    }

    @Test
    fun realCarsAreNotSkipped() {
        // 奥迪/DiPlay 等真车与测试台：model 各不相同，不匹配 YCast/WifiCP
        assertFalse(SecondLinkMemory.looksLikeSoftwareDongle("Audi", "AUDI 3260"))
        assertFalse(SecondLinkMemory.looksLikeSoftwareDongle("Delphi", "MMI 3315"))
        assertFalse(SecondLinkMemory.looksLikeSoftwareDongle(null, null))
    }

    @Test
    fun classicMib3AlwaysUsesSecondLink() {
        // 奥迪 DELPHI（CLASSIC_MIB3）：第二链路是硬需求——即使 /info 信号缺失/误导
        // 也不能跳过（不协商 RECORD 后 ~10s 被拆，2026-10-23 二次定谳）
        assertTrue(
            SecondLinkMemory.defaultPolicy(
                ReceiverProfile.LinkGeneration.CLASSIC_MIB3,
                null,
                null,
            ),
        )
        assertTrue(
            SecondLinkMemory.defaultPolicy(
                ReceiverProfile.LinkGeneration.CLASSIC_MIB3,
                "Samuncle",
                "YCast",
            ),
        )
    }

    @Test
    fun eventLinkUnknownReceiverUsesSecondLink() {
        // EVENT_LINK 且非软件盒子（奥迪 iD9/DiPlay/未知车机）：默认布防
        // （2026-10-23 起 marker 协商全代际统一——EVENT_LINK 也要协商到 NORMAL）
        assertTrue(
            SecondLinkMemory.defaultPolicy(
                ReceiverProfile.LinkGeneration.EVENT_LINK,
                null,
                null,
            ),
        )
        assertTrue(
            SecondLinkMemory.defaultPolicy(
                ReceiverProfile.LinkGeneration.EVENT_LINK,
                "Audi",
                "AUDI 3260",
            ),
        )
    }

    @Test
    fun memoryEntryDefaultsToNonFatal() {
        // 无 context（单测形态）：默认 Entry()，非 fatal——自动策略照常生效
        val entry = SecondLinkMemory.load(null, "any receiver")
        assertFalse(entry.fatal)
    }
}
