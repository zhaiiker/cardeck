package io.github.shaonialife.cardeck.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RootGate 纯函数部分（2026-10-26 root 增强首版）。
 *
 * su 探测/命令执行依赖真机 Magisk，单测只钉死**读回判定**语义——
 * 写操作必须读回确认是项目约定（AGENTS.md shell 读回校验），
 * `network-requests-has-user-approved` 的输出形态一旦误判，预批准会
 * 「假成功」，首次连热点照样弹窗、用户还以为已生效。
 */
class RootGateTest {
    @Test
    fun approvalConfirmedAcceptsBareYes() {
        // 本机 ROM 实测输出就是裸的 "yes"
        assertTrue(RootGate.approvalConfirmed("yes"))
        assertTrue(RootGate.approvalConfirmed("YES"))
    }

    @Test
    fun approvalConfirmedRejectsNoAndNoise() {
        // "no" 里不含 "yes" 子串；空/异常输出一律当未确认（宁降级不假成功）
        assertFalse(RootGate.approvalConfirmed("no"))
        assertFalse(RootGate.approvalConfirmed(""))
        assertFalse(RootGate.approvalConfirmed(null))
    }

    @Test
    fun approvalConfirmedIsCaseInsensitive() {
        // 各家 ROM 可能输出 "Yes" / "yes\r\n" 等
        assertTrue(RootGate.approvalConfirmed("Yes"))
        assertTrue(RootGate.approvalConfirmed("yes\n"))
    }
}
