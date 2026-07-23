# FUTO Swipe 插件后续适配计划

状态：P2 进行中（P1 代码改造已完成，等待真实轨迹语料）
目标分支：`swipe-typing-20260618`
基线提交：`5402b4b9 Require pinyin baseline top-one results`
计划日期：2026-07-24

## 1. 结论先行

后续继续采用“Fcitx5 for Android 主程序 + 独立 FUTO Swipe 插件 APK”的结构，不再把 FUTO 解码器直接编进主 APK。

现阶段不拆成独立仓库。插件继续放在当前仓库的 `plugin/swipe-futo` 模块中，但作为独立 APK、独立版本和独立发布资产维护。原因是：

- 主程序与插件共享 Binder AIDL、插件描述格式和构建约定，同仓库修改更容易保证兼容。
- Swipe 服务使用主程序定义的 `signature` 权限；同一条 CI 使用配对签名最简单。若以后改用插件独立证书，主程序必须显式验证并信任该证书。
- FUTO 模型、词典、许可证和 ABI 产物仍在快速调整，过早拆仓会增加协议、签名和依赖同步成本。

达到以下条件后再评估独立仓库：

- Binder 协议至少稳定两个发布周期。
- 主程序可以同时兼容当前版和上一版插件。
- AIDL/协议定义能以独立、带版本的依赖发布。
- 独立仓库已经具备稳定签名，并由主程序固定信任该证书，或已有等价的安全认证方案。
- FUTO 模型与词表的再分发方式已经完成许可证复核。

颜色和轨迹继续使用主程序现有主题令牌，跟随 Monet；本计划不增加独立配色系统。

### 本轮范围锁定

本轮只交付两个可用的 swipe profile：

- 英文 QWERTY swipe。
- Fcitx 中文拼音 swipe（插件输出拼音 Top1，再由 Fcitx 给出汉字候选）。

日文、韩文及其他输入法不是本轮适配对象：不为它们增加解码器、词表、手势规则、语料或性能指标；它们只维持 `Unsupported` 的禁用行为，避免误走拼音路径。浮动键盘也不在当前分支实现或合并范围内，只有获得单独授权后的集成分支才处理它与这两个 profile 的坐标共存。

## 2. 当前基线

已经具备：

- 主程序只负责采样轨迹、显示轨迹、调用插件和把结果交给 Fcitx。
- FUTO 模型、英文词表、拼音词表和 native bridge 位于独立插件进程。
- 主程序与插件通过 `ISwipeDecoderService` Binder 接口通信，当前协议版本为 2。
- 缺少插件、协议不匹配、插件未就绪和解码错误已有基础状态提示。
- 英文滑行已可用。
- 拼音已有音节合法性评分、轨迹重排和有限修复逻辑。
- 11 条核心拼音理想轨迹（包括 `nihao`、`zhongguo`、`shifou`、`shifoushi`）均以插件 Top1 作为 instrumentation 回归门槛；英文继续保留独立 smoke 回归。
- 用户参考的 `shi-fuo-si` 已有黄金回放：受约束的 `fuo -> fou`、`si -> shi` 联合修复会把 `shifoushi` 排为 Top1；插件和主程序 Binder 测试均已覆盖。
- 插件 instrumentation 还将 `zongguo -> zhongguo`、`congxin -> chongxin`、`suoshi -> shuoshi` 三类受约束卷舌修复固定为 Top1；这些仍是合成回归，不替代真实轨迹语料。
- 拼音评分器接受 Fcitx/libime 的 QWERTY `v` 代替 `ü` 别名（如 `jve`、`qvan`、`xvang`）以及 `lue/nue`；插件 Top1 与 Fcitx 拼音桥均已回归这些别名能产生中文候选。
- 已修正父键盘接管 swipe 后的事件收尾：后续 `ACTION_UP` 会交给 `onTouchEvent()` 完成解码，而不会提前重置手势状态。
- `shifoushi` 已经过 Fcitx 拼音引擎 E2E 定向测试，能产生中文候选；这证明拼音桥，不代表真实手势准确率。
- FUTO AAR、模型、英文词表与 Fcitx 拼音词典均使用固定 revision 和校验值。

当前主要缺口：

- 当前 GitHub 只启用了 `Build APK`、`Nix`、`Publish` 三条 workflow；Nightly/Sync 不在默认分支，定时发布实际没有运行。
- 当前线上 `nightly` 只有主程序的 arm64-v8a、x86_64 两个 APK，没有 FUTO 插件资产。
- `build-apk.yml` 的 push 触发仍只覆盖 floating 分支，推送 swipe 分支不会自动构建。
- 当前 nightly 会在 release job 内再次合并 upstream；同步、测试和发布没有形成不可变 SHA 的清晰边界。
- 插件的 versionName/versionCode 仍继承主程序的根仓版本逻辑，暂时不能真正独立升版。
- 现有拼音测试主要是“键中心连线”，不能代表真实手指的过冲、漏键、邻键、采样率和速度变化。
- 拼音桥当前只把插件 Top1 送进 Fcitx，Top2～Top4 命中并不能改善实际中文输入。
- 输入法路由已限定为英文和 Fcitx 拼音；其余输入法只保持禁用 swipe，不纳入适配或回归语料。
- 当前 API 36 远程模拟器将 IME 显示为不可触摸的 `IME-screenshot-surface`；`adb input` 不会进入 Fcitx 键盘，不能用于采集或验收真实 `MotionEvent`。需要可触摸模拟器或真机来补 P2 语料。
- 真实 `MotionEvent` 语料、插件升级场景和性能门禁尚未完成；floating E2E 仅属于获授权后的独立集成分支，不是本分支缺口。
- 当前 trie 每个节点固定保存 26 个子指针，英文和拼音 session 同时常驻时有明显 PSS 风险，尚未建立内存门禁。
- app 的整套 connected test 存在等待无超时的问题，不能直接作为 swipe 发布门禁。
- 当前没有对候选 Swipe 服务提供者执行可信证书校验；未来若独立签名，必须先建立主程序的证书信任策略。

## 3. 组件职责边界

| 组件 | 负责 | 不负责 |
| --- | --- | --- |
| 主程序 | 手势识别起止、坐标归一化、轨迹绘制、键盘布局、语言模式、候选 UI、Fcitx 提交 | FUTO 模型、词典加载、模型推理 |
| FUTO 插件 | 模型与词典生命周期、英文/拼音解码、拼音候选修复与排序、性能状态 | 中文汉字候选、主题颜色、键盘窗口 |
| Fcitx | 把 Top1 拼音序列转换为中文预编辑和汉字候选 | 手势几何推理 |

中文路线保持为：

`手势轨迹 -> FUTO 插件输出规范拼音 Top1 -> Fcitx 拼音引擎 -> 中文候选`

插件不重复实现一套中文输入法语言模型。它的核心任务是把不精确轨迹恢复成尽可能可靠的拼音串，例如把接近 `shi-fuo-si` 的轨迹恢复为 `shifoushi`。

## 4. 分阶段实施

### P0：冻结 swipe 独立基线

目标：先在 `swipe-typing-20260618` 完成核心适配；本阶段明确不合并、不 cherry-pick、不 rebase floating keyboard 分支。

任务：

- 先推送或备份本地 `7437fed6`，避免后续合并时丢失当前完成状态。
- 更新并验证 `plugin/swipe-futo/third_party/futo-android-libs` 固定在预期 submodule revision。
- 记录一组主 APK、插件 APK、协议版本、模型版本的兼容基线。
- 先让 swipe 分支的本地定向测试和模拟器验证可重复；CI、nightly 与发布链调整放到核心适配稳定后。
- 保持 `test-floating-keyboard-20260604-225446` 不变；后续 integration 只在用户明确授权后创建独立集成分支处理。

完成门槛：

- 工作树干净，现有 `action-artifacts` 不被加入提交。
- app 与插件的定向 JVM 测试、assemble 和插件 lint 通过。
- x86_64 模拟器能同时安装主 APK 与插件 APK，插件出现在 Fcitx 插件列表中。
- 当前工作不包含任何 floating 分支提交；两条分支的合并决策保持独立。

### P1：稳定插件协议和生命周期

目标：插件缺失、冷启动、升级或进程死亡时都不会让第一次 swipe 静默失败。

本轮已完成（截至 2026-07-24）：

- 统一 `English / Pinyin / Unsupported` profile；未适配输入法和尚未确定的 IME 不再误走拼音桥。
- 手势请求在发送前校验有限数值、时间单调性和布局长度，并重采样为至多 96 个点。
- 主线程只收集并提交不可变请求；绑定、Binder 调用和解码移至后台单线程，使用 epoch 丢弃取消、IME 切换和 View detach 后的旧结果。
- 插件会话改为显式 lazy warm-up；`isReady()` 不再触发模型初始化，状态可区分 Binding、Warming、Ready 和错误。
- Binder API v2 已收敛到 `lib/common` 的单一 `SwipeDecoderProtocol`；绑定前会验证预期插件包名、服务权限与同签名证书。
- API 36 x86_64 模拟器已验证：主 APK 与 x86_64 FUTO 插件 APK 可共同安装、系统可发现服务；冷启动识别与 `am force-stop` 插件后的恢复均成功。
- 插件 instrumentation 的 11 条核心拼音理想轨迹和用户 `shi-fuo-si` 黄金回放均为 Top1；Fcitx 拼音桥 E2E 能给出中文候选。
- `BaseKeyboard` 接管 swipe 后保持事件所有权直到 `ACTION_UP`，以完成异步识别请求。

任务：

- 把协议版本定义收敛到 `lib/common` 的单一来源。
- 先保留 API v2；需要增加结构化能力时再定义 API v3，并让主程序至少兼容 v2、v3 一个过渡周期。
- 仅以 `English / Pinyin / Unsupported` 作为两个目标 profile 的路由围栏；不扩展为多语言能力项目，也不再用 Boolean 把其它语言误判为拼音。
- 为英文和拼音会话分别实现明确状态机：
  - `Uninitialized`
  - `Warming`
  - `Ready`
  - `Failed`
- `warmUp()` 只启动加载；`isReady()` 必须是非阻塞查询，不在 Binder 线程临时加载模型。
- 把绑定和识别放到专用后台单线程；键盘主线程只提交不可变请求和接收结果，不能等待 Binder、模型或 trie。
- 为每次手势分配 epoch；新手势、IME 切换、View detach 或布局变化后，旧结果必须丢弃。
- 为识别结果增加结构化错误语义，至少区分未就绪、无候选、输入无效、模型错误和协议错误。
- 区分 `Missing`、`Binding`、`Warming`、`Ready`、`Incompatible`、`Failed`，绑定超时不能误报成未安装。
- 处理 `onNullBinding`，并校验候选插件的包名、服务 action、API 版本和可信签名证书后再绑定。
- 处理插件安装、更新、卸载、force-stop、Binder death 和主程序输入法重建。
- 给绑定和识别增加明确的超时、重试上限与日志字段，避免无限等待。
- 对 IPC 输入校验点数上限、有限数值、时间单调性和 layout 数组长度，并在发送前重采样到固定上限。

完成门槛：

- 未安装插件时普通键入完全正常，swipe 只给一次明确提示。
- 安装或更新插件后，无需重启输入法即可在下一次或第二次手势内恢复。
- 杀死插件进程后最迟 2 秒恢复，不出现 crash、ANR 或 Binder 泄漏。
- 连续切换英文/拼音模式 20 次、每种模式识别 100 次无失败。
- 主线程监测不到 Binder 等待或模型初始化，快速连续滑动不会提交过期结果。

### P2：建立真实轨迹语料和可复现测试

目标：从“理想键中心测试”转为“真实手势回放测试”。

任务：

- 增加仅 debug 可用的本地轨迹记录器，导出 JSON fixture：
  - 归一化 `x/y/t`
  - 当前键位中心与键盘尺寸
  - 当前 docked 键盘的方向与键盘尺寸（floating 数据仅在获单独授权的集成分支记录）
  - 观测到的键序列
  - 插件候选及顺序
- 不记录目标应用包名、输入框原文、账号或其他隐私内容。
- 当前已完成的最小闭环：debug 开发者页面有默认关闭的“记录滑行轨迹”开关；开启后仅写入 app 专属外部目录 `swipe-traces/`，记录归一化轨迹、键盘尺寸/键位中心、方向、观测键序列和插件候选。codec 与模拟器文件写入均有回归测试。
- 已加入首个 JSON 回放：`user_reference_shi_fuo_si.json`，在插件 instrumentation 中验证 `shi-fuo-si -> shifoushi` Top1。该 fixture 目前按用户截图中的键序列构造，尚不是从真机 MotionEvent 直接导出。
- 尚未记录 Fcitx 最终汉字候选；该项必须在输入框 E2E 钩子中补齐，不能用插件候选冒充。
- 首批录制至少：
  - 英文 30 个词，每词 3 次。
  - 拼音 30 个短语，每条 3 次。
  - 仅保存 docked 键盘样本；不为未获授权的 floating 分支采样或建回归。
- 必须包含：
  - `hello`、`world`、`swipe`、`keyboard`、`android`
  - `nihao`、`xiexie`、`zaijian`、`zhongguo`、`shijie`
  - `meiyou`、`keyi`、`chongxin`、`shuoshi`
  - `shifou`、`shifoushi`、`zhongguoren`
  - 用户参考轨迹对应的 `shi-fuo-si -> shifoushi`
- 生成扰动版本，覆盖坐标 jitter、漏采样、不同速度、空 `tracedLetters`、重复音节边界和邻键过冲。

完成门槛：

- 每个已确认 bug 都有一个不依赖人工操作的 JSON 回放用例。
- 同一 fixture 在 x86_64 模拟器和 ARM64 真机上得到相同候选顺序。
- 测试失败能输出轨迹 ID、候选列表和各评分项，不只返回“没有候选”。

### P3：提高拼音 Top1 质量

目标：插件第一候选必须足够可靠，因为主程序当前只把 Top1 交给 Fcitx。

当前进展：`shi-fuo-si -> shifoushi` 已通过插件、Binder 两层 Top1 回归。该样本仍是根据用户提供的键序列构造的回放，不替代 P2 要求的真实 MotionEvent JSON 语料，也不据此宣称整体准确率达标。

任务：

- 只对明确的 Pinyin/Fcitx 拼音输入法启用拼音桥；英文键盘继续直接输出英文。
- 其它输入法保持 swipe 禁用，不为其建立候选、轨迹或质量测试。
- 把拼音修复改为受音节图约束的候选生成：
  - 合法声母、韵母与整体认读组合。
  - 音节边界处的重复字母恢复，例如 `zhong + guo`。
  - 常见漏字母、邻键和字母顺序误差。
  - `fuo -> fou`、`si -> shi` 这类仅在几何和上下文同时支持时生效的修复。
- 重排同时使用：
  - FUTO 原始排名。
  - 完整路径几何相似度。
  - 起点、终点和转折点。
  - 直接 trace 与推断 trace。
  - 拼音音节合法性。
  - Fcitx/libime 词典频率。
- 弱 trace 不直接插入候选；只有音节、几何和词典频率同时达标时，才允许修复候选超过 FUTO 原始候选。
- 保留每个评分项的 debug 输出，先用语料调权重，再固化阈值。
- 不以“Top4 包含正确答案”代替 Top1 发布门槛。

阶段门槛：

- 理想轨迹：
  - 拼音 Top1 至少 85%，Top4 至少 95%。
  - `nihao`、`zhongguo`、`shuoshi`、`shifou`、`shifoushi` Top1 为 100%。
  - Top1 非法拼音拆分率为 0。
- 扰动轨迹：
  - 拼音 Top1 至少 75%，Top4 至少 90%。
  - 用户黄金样本 `shi-fuo-si -> shifoushi` 必须 Top1 命中。
- 真实手势：
  - 拼音 Top1 至少 80%，Top4 至少 93%。
  - `shifoushi` 进入 Fcitx 后，前三个中文候选中出现包含“是/否”的合理结果。

### P4：补齐自动化、性能和稳定性门禁

目标：模拟器可以真正覆盖“手指轨迹 -> 插件 -> Fcitx -> 输入框”的完整链路。

任务：

- 增加 debug-only EditText 测试页和 `UiAutomation.injectInputEvent` 多段轨迹注入。
- 测试钩子动态读取键盘屏幕 rect 与键中心，不硬编码分辨率。
- 给 `FcitxTest` 的所有异步事件等待加 10 秒超时，并把 swipe case 与现有可能挂起的整套测试分开。
- 增加 Binder fake service，覆盖 API mismatch、未就绪、超时、空候选和进程死亡。
- 测量英文 trie、拼音 trie、模型和 native 库各自的 PSS；当前固定 26 子节点的 trie 改为扁平稀疏边或等价紧凑结构。
- 限制英文、拼音两个 session 的常驻策略；模式切换后可回收不用的 trie，且重复切换时 PSS 不得持续增长。
- 设备矩阵：
  - API 24 基线模拟器。
  - API 29。
  - API 34 或 36 x86_64 模拟器。
  - 至少一台 ARM64 真机。
- 性能采集区分冷启动、热识别和从抬手到候选可见时间。
- 先记录当前 PSS 基线，再把 trie 部分内存至少降低 60%；总 PSS 上限以 ARM64 实测基线为依据固化。

建议门槛：

| 项目 | x86_64 模拟器 | ARM64 真机 |
| --- | ---: | ---: |
| 冷 warm-up | 不超过 5 秒 | 建立基线后收紧 |
| 热识别 p95 | 不超过 750 ms | 不超过 300 ms |
| 抬手到候选可见 p95 | 不超过 1 秒 | 不超过 500 ms |
| 连续 100 次识别 | 0 crash/ANR | 0 crash/ANR |

英文回归门槛：

- 理想轨迹 Top1 至少 90%，Top3 至少 98%。
- 真实轨迹 Top1 至少 85%，Top3 至少 95%。
- `hello`、`world`、`swipe`、`keyboard`、`android` Top3 为 100%。

### P5：形成可发布的插件交付链

目标：插件可以独立更新，但不会与主 APK 发生签名或协议错配。

任务：

- 主程序和插件分别拥有清晰的 versionName/versionCode。
- 插件版本不再继承主程序的 `baseVersionCode`，可以在协议兼容范围内独立升级。
- release notes 明确列出：
  - 主程序版本。
  - 插件版本。
  - Binder API 范围。
  - FUTO 模型 revision。
  - FUTO AAR submodule revision。
  - 支持 ABI。
- 恢复配对 nightly：从同一个已测试 SHA 构建，并在同一个 moving release 中发布 app 与插件的 arm64-v8a、x86_64 四个 APK。
- beta/stable 阶段在同仓库增加不可变的插件专用 tag，例如 `swipe-futo-v0.1.0-beta.1`，并创建只包含插件资产的独立 Release，无需立即拆仓。
- 插件 Release 附带 `compatibility.json`、`SHA256SUMS`、许可证、源码提交和依赖 revision。
- CI 对所有 APK 执行：
  - `apksigner verify`
  - 主程序与插件的签名策略检查；配对签名时证书 SHA-256 必须一致，独立签名时必须命中主程序信任列表
  - 文件 SHA-256
  - AIDL/API 兼容检查
  - 第三方许可证和 source revision 检查
- 为模型与词典下载增加可缓存、可离线复现的 manifest；网络下载失败时不得生成不完整 APK。
- 发布前单独复核 FUTO Source First License 对免费、非商业分发的要求。本计划不把插件纳入商业商店发布范围。

完成门槛：

- 用户按 release notes 安装匹配的两个 APK 后可直接使用。
- 错配版本会明确提示需要更新哪一端，不会静默无候选。
- 发布资产的 ABI、签名、哈希、协议和模型 revision 均可从 CI 结果追溯。

## 5. 自动化测试分层

| 层级 | 每次提交 | 合并门禁 | 发布门禁 |
| --- | --- | --- | --- |
| JVM 单测 | trace、拼音修复、模式路由、KeyAction、坐标换算 | 必须 | 必须 |
| 插件 instrumentation | 核心英文/拼音黄金集 | 必须 | 全量语料 |
| Binder 集成 | 基础连接、API、两个模式 | 必须 | 生命周期与故障注入 |
| 主程序 E2E | docked 英文、拼音各 1 组 | 必须 | docked 的方向矩阵 |
| 真机回放 | 可选抽查 | 至少 ARM64 smoke | 全量性能与真实轨迹 |

当前整套 `:app:connectedDebugAndroidTest` 不作为 swipe 门禁。应先修复其无超时等待，再逐步纳入。

## 6. 建议提交顺序

每个阶段保持可独立回退：

1. `Stabilize swipe plugin lifecycle and protocol state`
2. `Add real swipe trace fixtures and replay harness`
3. `Improve pinyin top-one decoding and mode routing`
4. `Harden swipe plugin release and compatibility checks`

当前 swipe 分支只适配英文 QWERTY 与 Fcitx 拼音 swipe；不得合并、cherry-pick 或 rebase floating keyboard 分支。

## 7. 最终完成定义

只有同时满足以下条件，FUTO 插件适配才算完成：

- 不安装插件时，Fcitx5 for Android 的正常输入完全不受影响。
- 安装匹配插件后，英文和拼音 swipe 均可在第一次手势使用。
- 用户参考的 `shi-fuo-si` 风格轨迹能稳定恢复为 `shifoushi`，并得到合理中文候选。
- docked 键盘在支持的方向下，英文和拼音 swipe 均通过 E2E。
- 插件丢失、升级、API 错配、进程被杀和模型加载失败都有可恢复行为。
- 关键拼音用例以 Top1 作为门禁，英文没有明显回退。
- app 与插件 APK 的协议、ABI、签名、模型和许可证信息都可验证。
- nightly 和 stable 资产能由干净 checkout 重现。

## 8. 下一批执行顺序

下一轮实现按以下顺序推进：

1. 在当前 swipe 分支固定基线，不合并 floating。
2. 采集英文与拼音的真实 docked 轨迹，加入 JSON 回放与回归集。
3. 用真实数据调拼音 Top1，优先覆盖 `shifoushi` 等易错轨迹。
4. 完成英文、拼音的插件生命周期、性能和稳定性验证。
