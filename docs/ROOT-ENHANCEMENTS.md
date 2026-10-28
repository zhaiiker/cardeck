# Root 增强路线图（探讨稿）

> 2026-10-26。前提：用户手机已 root（Magisk + Zygisk-Sui）。本文探讨「有 root 能做、
> 无 root 做不到/做不好」的增强方向，按「用户价值 × 实现成本」排序，供决策参考。
> 原则：**root 是可选加速路径，不是硬依赖**——无 root（Shizuku）的功能不能退化，
> 所有 root 特性必须有降级链路（检测到 root 用之，否则走现有路径）。

## 0. 架构位：root 从哪里接入

三个候选，各有取舍：

| 途径 | 机制 | 优势 | 劣势 |
|---|---|---|---|
| **A. `su -c` 直跑 shell** | `Shell.exec` 的 root 版：`su -c "<cmd>"` | 改动最小（现有 Shell 门面加一个 RootShell）；命令面同 Shizuku 但解锁 root-only 命令 | 每次弹 Magisk 授权（可设「永久记住」）；Magisk hide 环境下部分 ROM 的 su 会拒 |
| **B. root 启动 Shizuku（已有）** | Magisk 模块方式跑 shizuku_server（root 模式） | 授权体验同现状；UserService 仍是 uid 2000——**没有解锁 root 专属能力** | 不解决本文要探讨的事 |
| **C. root 版 UserService** | 把 `CarInputServiceImpl` 挂到 su 起的进程（或直接 Magisk 模块常驻） | 虚拟屏/触摸注入进程拿到 uid 0：`@hide` API 全解锁、`settings/cmd` 全量命令、无 Shizuku 依赖 | 需自写 Magisk 模块或改造 UserService 启动方式，工程量最大 |

**建议**：先做 A（一行命令面的扩容，立刻解锁下表大部分项），把 C 留给「无感体验」
的终局形态。

## 1. 连接体验类（直接解决当前痛点）

### 1.1 specifier 预批准 —— 上车 0 弹窗的最后一公里 ⭐⭐⭐⭐⭐
`cmd wifi network-requests-set-user-approved <pkg> yes`（WIFI-JOIN.md §2 记录过：
uid 2000 被 SecurityException 拒，**root 专属**）。批准后 `WifiNetworkSpecifier`
不再弹系统确认框——「换车、重装、清数据后依然 0 弹窗」从不可能变成一行命令。
注意它是全局单值（批准本应用会挤掉别的应用的批准），要在文档里写明。
降级：无 root 时保持现状（首次弹一次、之后持久）。

### 1.2 `set-wifi-watchdog` / OEM 网络切换开关 ⭐⭐
`cmd wifi set-wifi-watchdog false`（root-only）。作用有限（管的是连不上自救，
不是无外网切走），但部分 OEM 的「智能网络切换」只能靠 root 改系统配置。
优先级低：现状（无外网热点不动默认网络）已经规避了大部分问题。

### 1.3 BT/WiFi 状态强保活 ⭐⭐⭐
- `cmd deviceidle whitelist +io.github.shaonialife.cardeck`（root 或 shell）——豁免 Doze，
  长时间会话/后台自动连接更稳；
- root 可再 `settings put global ...` 调 aggressiveness、或 `svc` 命令族直接控 BT/WiFi
  电源态（uid 2000 只有部分子命令）；
- 上车自动连接的「ACL 广播唤醒 + FGS」链路在 Doze 边缘场景更可靠。

## 2. 采集/推流质量类（画面与声音的核心提升）

### 2.1 音频内录直取 HAL ⭐⭐⭐⭐
现状的痛点：`AudioPlaybackCapture` 依赖 MediaProjection 授权（每次重启手机要重新点），
且 **app 可声明 `allowAudioPlaybackCapture=false` 拒绝被捕获**（Spotify/银行类常见，
日志里的「全零：捕获不到音频」就是它）。root 路线：
- 直开 `/dev/snd/*`（或高通 `/dev/msm_*`）PCM capture 节点，绕过框架层白名单——
  **任何 app 的声音都能录**，且不需要 MediaProjection（省掉一个每次重启都要点的授权）；
- 或 hook audio HAL（Zygisk 模块注入 loopback 设备）——工程量大，效果同上。
降级：无 root 走现有 AudioPlaybackCapture。
前置实测：`su -c "ls /dev/snd"` + `tinycap` 在该机型上验证节点可用性与声道格式。

### 2.2 编码器/合成器更高优先级 ⭐
`renice`/`sched_setscheduler`（root）把 GL 合成线程与 MediaCodec 线程提到实时优先级，
极端场景（后台被冻结、CPU 争抢）掉帧减少。收益有限（现有出帧节奏已节流），
但成本极低（几行代码）。

### 2.3 虚拟屏免 Shizuku 化 ⭐⭐
root 版 UserService（路线 C）里 `createVirtualDisplay` 带 TRUSTED/ALWAYS_UNLOCKED
等 @hide flag 不再需要「反射 + 三级降级」——直接 AOSP 值可用，ROM 魔改风险消失；
IME 策略（`setDisplayImePolicy`）、`setIgnoreOrientationRequest` 也不再依赖
`INTERNAL_SYSTEM_WINDOW` 碰运气。**对用户的表现**：换机/换 ROM 后兼容性大幅提升，
「这台能连那台黑屏」的碎片化问题减少。

## 3. 无感体验类（root 的差异化价值）

### 3.1 开机自启常驻（免打开 App）⭐⭐⭐
Magisk 模块（路线 C）= `class` 之外的常驻进程，开机即拉起：
- 上车自动连接的触发不再依赖「App 进程活着」——**重启手机后首次上车也能自动连**；
- 配合 ACL 广播接收器冷启动链路，真正「上车即连」。
降级：现状（App 被杀后靠 ACL 广播冷启动）已可用，root 版只是更快更稳。

### 3.2 免 Shizuku 依赖 ⭐⭐⭐
Sui（Zygisk）虽兼容 Shizuku API，但「装 Shizuku App 或 Sui」仍是用户门槛。
Magisk 模块形态可以把「启动服务 + 授权」合并进「装 CarDeck 模块」一步——
新用户引导从「装 App → 装 Shizuku → 启动 → 授权」缩成「装 App → 授一次 root」。

### 3.3 全局免打扰状态写入 ⭐
root 下 `settings put global` / `cmd` 全量可用：会话期间关掉「无外网 WLAN」系统
提示、钉死 WiFi 扫描间隔等零碎系统行为。单项收益小，累积体验可观。

## 4. 明确不做 / 不值得做

- **scrcpy 式「root 提帧率」**：不成立——我们的出帧瓶颈在编码器与 WiFi 链路，
  root 无增益（对照 scrcpy 官方结论）；
- **破解 DRM 音频（Widevine 等）**：即使技术上可行也不做——合规红线；
- **全局屏幕镜像替代方案**：现有 MediaProjection/虚拟屏方案已覆盖需求，
  root 的 `screenrecord --output-display` 系列没有额外增益。

## 5. 建议的落地顺序

| 阶段 | 内容 | 依赖 |
|---|---|---|
| P0（顺手） | RootShell（`su -c` 包装）+ root 检测 + specifier 预批准 + Doze 白名单 | Magisk 授权一次 |
| P1（实测后） | HAL 音频内录 POC（tinycap 验证 → 采集管线切换） | 机型节点实测 |
| P2（体验） | 上车自动连接常驻化（Magisk 模块 or root FGS 强化） | 模块工程 |
| P3（终局） | root 版 UserService：虚拟屏/IME/触摸全 @hide 直调，免 Shizuku | 模块 + UserService 双形态 |

每阶段都保留现有无 root 路径为降级分支，检测逻辑统一收口在一个
`RootGate`（对齐 ShizukuGate 的三态模型：无 root / 有 root 未授权 / 就绪）。

## 6. 风险清单

- **Magisk 授权弹窗**：`su -c` 首次触发 Magisk 弹窗——用户可能错过；建议首次主动
  `su -c true` 探测并在 UI 提示「已获得 root 加速」；
- **银行 app/DRM 检测 root**：DenyList 要把 CarDeck 排除（我们需要 su 可用），
  用户文档写清「本机 root 对其它应用可见性」的取舍；
- **ROM 碎片化**：HAL 节点、OEM 设置项各不相同——每项 root 特性都要
  「探测 → 失败静默降级 → 日志留痕」，与现有 Shell 读回校验约定一致。
