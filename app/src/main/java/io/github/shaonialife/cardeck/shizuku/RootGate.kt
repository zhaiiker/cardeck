package io.github.shaonialife.cardeck.shizuku

import io.github.shaonialife.cardeck.util.Bg
import java.util.concurrent.atomic.AtomicInteger

/**
 * Root 网关（对齐 [ShizukuGate] 的三态模型，2026-10-26 落地）。
 *
 * 手机已 root（Magisk + Zygisk-Sui）时解锁一批 uid 2000（Shizuku shell）做不到的
 * 加速能力（清单与取舍见 `docs/ROOT-ENHANCEMENTS.md`）：
 *  - `cmd wifi network-requests-set-user-approved` —— WifiNetworkSpecifier 预批准，
 *    「换车/重装/清数据后首次连热点 0 弹窗」的唯一途径（uid 2000 会 SecurityException，
 *    WIFI-JOIN.md §2 实测定谳）；
 *  - `cmd deviceidle whitelist +<pkg>` —— Doze 豁免，长会话与后台自动连接更稳。
 *
 * **root 只是可选加速路径**：探测失败/用户未授权时所有调用静默降级到现有
 * Shizuku 路径，无 root 用户的体验不退化。
 *
 * 授权形态：`su -c` 首次触发 Magisk 授权弹窗，用户「永久记住」后免打扰——
 * 与 Shizuku 的一次授权同量级，不追求「更早」，只在「已经要弹窗的连接流程里」
 * 顺路完成。
 */
object RootGate {
    enum class State { NO_ROOT, UNAUTHORIZED, READY }

    /** 进程内缓存：su 探测要起进程，绝不能在主线程/高频路径反复跑。 */
    @Volatile
    private var cachedState: State? = null

    /** 探测失败计数：Magisk 未授权时每次 su 都是 1s+ 超时，超过阈值就停止再试。 */
    private val deniedCount = AtomicInteger(0)
    private const val DENIED_RETRY_LIMIT = 3

    /** Doze 白名单是否已写入（持久项，进程内只做一次）。 */
    @Volatile
    private var dozeWhitelisted = false

    /** 无 root 的跳过日志是否已打过（避免每次连接重复刷屏）。 */
    @Volatile
    private var loggedSkip = false

    /**
     * 探测 root 可用性（阻塞，**只能后台线程调用**；缓存后零成本）。
     *
     * `su -c true` 的退出码即结论：0=可用；127=无 su；1=用户拒绝（Magisk 弹窗
     * 超时/拒绝都会走这里）。用户拒绝过 N 次后不再打扰——他不想给就是不想要
     * root 加速，反复弹窗反而骚扰。
     *
     * @param force 跳过缓存重探（设置面板「重新检测」用）。
     */
    fun state(force: Boolean = false): State {
        cachedState?.takeIf { !force }?.let { return it }
        val result: State =
            try {
                val process = ProcessBuilder("su", "-c", "true").start()
                // su 无授权时 Magisk 会挂到弹窗应答，不能无限等
                if (!process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroy()
                    State.UNAUTHORIZED
                } else {
                    when (process.exitValue()) {
                        0 -> State.READY
                        // 127 = su 不存在（无 root 机型），区别于「有 root 未授权」
                        127 -> State.NO_ROOT
                        else -> State.UNAUTHORIZED
                    }
                }
            } catch (_: Exception) {
                // 文件不存在（IOException）或被 SELinux 拦：一律当无 root
                State.NO_ROOT
            }
        if (result == State.UNAUTHORIZED) {
            deniedCount.incrementAndGet()
            // UNAUTHORIZED 不缓存——用户可能稍后才在 Magisk 里点「允许」
        } else {
            deniedCount.set(0)
            // READY 与 NO_ROOT 都缓存：su 有就有、没有就没有，进程生命周期内不变
            cachedState = result
        }
        return result
    }

    /** 是否值得再跑一次 root 操作（拒绝次数超限后静默放弃）。 */
    private fun worthTrying(): Boolean =
        cachedState == State.READY || deniedCount.get() < DENIED_RETRY_LIMIT

    /**
     * `network-requests-has-user-approved` 读回判定（纯函数）：
     * 本 ROM 实测输出就是裸的 `yes`；按 contains 判避免大小写/尾行差异误判。
     */
    internal fun approvalConfirmed(output: String?): Boolean =
        output?.lowercase()?.contains("yes") == true

    /**
     * 以 root 执行命令。无 root/未授权/超限返回 null（调用方降级到 Shizuku 路径）。
     *
     * 不复用 [Shell] 的 Shizuku binder——su 是独立进程体系；每次 exec 都是新进程，
     * 与 Shell.exec 同样**禁止主线程调用**（waitFor 阻塞）。
     */
    fun exec(
        cmd: String,
        timeoutMs: Long = 8_000L,
    ): Shell.Result? {
        if (!worthTrying()) return null
        return try {
            val process = ProcessBuilder("su", "-c", cmd).start()
            // stderr 单独吞掉，防止缓冲区满卡死
            Bg.run("root-shell-stderr") {
                runCatching { process.errorStream.readBytes() }
            }
            val output = runCatching { process.inputStream.readBytes().decodeToString().trim() }.getOrDefault("")
            if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroy()
                return null
            }
            if (process.exitValue() == 0) {
                cachedState = State.READY
                Shell.Result(0, output)
            } else {
                deniedCount.incrementAndGet()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 连接流程的 root 加速收口（连接起步时后台调用一次）。
     *
     * 1. **specifier 预批准**：`cmd wifi network-requests-set-user-approved`（本 ROM
     *    help 明确标 `Needs root access` + `Not persisted`——**WiFi 服务重启/开机即失效**，
     *    所以每次连接都要重新下发，不能只发一次）。效果：`WifiNetworkSpecifier`
     *    连热点不弹系统确认框（uid 2000 会 SecurityException，WIFI-JOIN.md §2）。
     *    注意它是**全局单值**：批准本应用会挤掉之前批准过的其它应用（实测 help
     *    原文 "Only 1 such app can be approved from the shell at a time"）。
     *    下发后 `network-requests-has-user-approved` 读回校验（写操作必回读——项目约定）。
     * 2. **Doze 白名单**：`cmd deviceidle whitelist +<pkg>`（**持久**，进程生命周期内
     *    只做一次即可）。
     *
     * 每项独立判失败，全部留日志。
     */
    fun applyConnectionBoosts(
        packageName: String,
        log: (String) -> Unit,
    ) {
        if (!worthTrying()) {
            if (!loggedSkip) {
                loggedSkip = true
                log("ROOT: 未授权/无 root——跳过连接加速（现有路径不受影响）")
            }
            return
        }
        // 1) specifier 预批准（不持久：每次连接都下发）
        exec("cmd wifi network-requests-set-user-approved $packageName yes", 8_000L)
        val verified =
            approvalConfirmed(
                exec("cmd wifi network-requests-has-user-approved $packageName", 5_000L)?.output,
            )
        if (verified) {
            log("ROOT: ✓ specifier 预批准已生效并读回确认（$packageName）——连热点不弹系统确认框（全局单值，挤掉其它应用的批准）")
        } else {
            log("ROOT: specifier 预批准读回未确认（命令可能随 ROM 变化）——首次连热点仍会弹一次确认框")
        }
        // 2) Doze 白名单（持久，只做一次）
        if (!dozeWhitelisted) {
            val whitelisted =
                exec("cmd deviceidle whitelist +$packageName", 5_000L) != null
            dozeWhitelisted = whitelisted
            if (whitelisted) {
                log("ROOT: ✓ 已加入 Doze 白名单（持久）——长会话/后台自动连接不被冻结")
            } else {
                log("ROOT: Doze 白名单未写入（无影响，仅少一层保活保险）")
            }
        }
    }
}
