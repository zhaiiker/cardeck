package io.github.shaonialife.cardeck.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuGuidanceTest {
    @Test
    fun notRunningTellsUserToStartService() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.toEnv(ShizukuGate.State.NOT_RUNNING))
        assertTrue(guidance.title.contains("未运行"))
        // 必须给出可执行动作（打开 Shizuku App 启动），否则用户无从下手
        assertEquals("打开 Shizuku", guidance.primaryLabel)
        assertTrue(guidance.message.contains("启动"))
    }

    @Test
    fun unauthorizedTellsUserToGrant() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.toEnv(ShizukuGate.State.UNAUTHORIZED))
        assertTrue(guidance.title.contains("未授权"))
        assertEquals("请求授权", guidance.primaryLabel)
        assertTrue(guidance.message.contains("允许"))
    }

    @Test
    fun notInstalledHasNoFakeAction() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.toEnv(ShizukuGate.State.NOT_INSTALLED))
        assertTrue(guidance.title.contains("安装"))
        // 未安装时不该给一个编造的跳转/下载按钮
        assertNull(guidance.primaryLabel)
        assertTrue(guidance.message.contains("Shizuku"))
    }

    @Test
    fun eachStateExplainsConsequenceOthersDoNotAllLookSame() {
        // 三态文案必须互不相同：混成一句「Shizuku 未就绪」用户无法判断该装/启动/授权
        val titles =
            listOf(
                ShizukuGate.State.NOT_INSTALLED,
                ShizukuGate.State.NOT_RUNNING,
                ShizukuGate.State.UNAUTHORIZED,
            ).map { ShizukuGate.guidanceOf(ShizukuGate.toEnv(it)).title }
        assertEquals(titles.size, titles.toSet().size)
        // 三种未就绪状态都要说明「连接会失败」的后果（否则用户以为能连）
        listOf(
            ShizukuGate.State.NOT_RUNNING,
            ShizukuGate.State.UNAUTHORIZED,
        ).forEach {
            assertTrue(
                ShizukuGate.guidanceOf(ShizukuGate.toEnv(it)).message.contains("失败"),
            )
        }
    }

    @Test
    fun readyHasGuidanceObject() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.toEnv(ShizukuGate.State.READY))
        assertNotNull(guidance)
        assertNull(guidance.primaryLabel)
    }

    @Test
    fun notRunningMentionsSuiAlternative() {
        // Sui（Zygisk）用户没有 Shizuku App 可打开：服务未运行的文案必须提示
        // Sui 模块的排查路径（模块启用 + 重启），否则 root 用户无从下手
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.toEnv(ShizukuGate.State.NOT_RUNNING))
        assertTrue(guidance.message.contains("Sui"))
    }

    @Test
    fun notInstalledMentionsSuiAlternative() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.toEnv(ShizukuGate.State.NOT_INSTALLED))
        assertTrue(guidance.message.contains("Sui"))
    }
}


