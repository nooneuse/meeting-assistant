# 测试与验收

## 电脑测试

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

核心测试覆盖定稿顺序、空语音、重复点击、取消与迟到事件、偏好快照、历史边界、段落替换、快捷键重复 / 冲突 / 删除恢复、模型消息组织与 URL 校验。通过 MockWebServer 测试 WebSocket 启动 / 音频 / 收尾协议，以及 SSE 分片、思考内容隔离、错误和截断。

Robolectric 使用 Android API 35 原生绘图，覆盖竖屏、横屏与大屏双栏、快捷键、设置隔离和旋转保留会话；预览 PNG 位于 `app/build/reports/ui`。这些是电脑运行 Android 代码绘制的预览，不是手机截图。

可选真实接口测试使用 `scripts/live-smoke.sh`，从控制终端隐藏读取密钥。环境变量 `MEETING_TEST_HOST` 指定域名，`MEETING_TEST_PCM` 可选指定不超过 15 秒的合成 PCM16LE / 单声道 / 16kHz 文件。只做一次极小文字请求和一小段合成语音识别；不会自动在 CI 运行。输出只包含测试结果与耗时，不打印凭据、请求头或完整响应。该测试不等于真实会议准确率验证。

真机基础 UI 冒烟（应用空闲、默认快捷键时运行，不采集声音、不调用 API）：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w io.github.meetingassistant.test/io.github.meetingassistant.android.SmokeInstrumentation
```

## 真机验收清单

复合条目只有全部子项都覆盖才勾选；已通过的子项与未覆盖部分分别记录在下方，避免把部分验证当作全部通过。

- [ ] 首次安装、麦克风拒绝 / 授权、无配置时明确提示。
- [ ] 真实录音：中文、英文与 C++ 术语；快速点击开始 / 停止不丢尾句、不重复提交。
- [ ] 横竖屏切换、折叠 / 展开、窗口缩放时会话保持；内容和控件不被系统栏遮挡。
- [ ] 两区独立滚动，原文向上阅读暂停自动跟随，翻页键自动展开。
- [ ] 屏幕常亮、停止后阅读、返回后台 / 手动锁屏恢复系统行为。
- [ ] 外接键盘默认键、重绑、冲突、长按；设置页输入不触发录音。
- [ ] 实际蓝牙快门捕获键值与开始 / 停止；未绑定音量键不受影响。
- [ ] 断网、无效密钥、限流、后台切换与任务取消；没有不受控后台录音。
- [x] 先讨论具体问题，再问「刚才的问题」；返回原文中的问题，不凭空补全。
- [ ] 偏好保存 / 切换 / 删除 / 撤销 / 重启；删除全部后不恢复默认配置。
- [ ] 清除密钥和应用数据后不可再调用；备份不包含凭据。

## 验证记录

### 2026-10-25 · 电脑验证

环境：macOS ARM64、JDK 21、Gradle 8.11.1、AGP 8.10.1、Android SDK 36 / Build Tools 36.0.0。界面测试使用 Robolectric 4.16.1 / Android API 35 / 原生绘图。

执行 `scripts/check-local.sh`：

| 检查 | 实际结果 |
| --- | --- |
| `:core:test` | 25 项通过，0 失败 / 跳过（包含真机反馈后增加的网络错误隐私检查） |
| `:app:testDebugUnitTest` | 6 项通过，0 失败 / 跳过 |
| `:app:assembleDebug` | 通过，生成约 1.5MB APK |
| `:app:assembleDebugAndroidTest` | 通过；已在手机执行，结果见下节 |
| `:app:lintDebug` | 0 错误，2 警告（固定 Gradle 版本、中文界面字符串拼接） |
| `apksigner verify --verbose` | 通过，APK v2 调试签名 |
| `zipalign -c 4` | 通过 |
| `python3 scripts/scan-secrets.py` | 通过 |

本次初版验证 APK SHA-256：`63985e0e0a0c18ec8bbf387d5c8e866bad40c57c1ad9d654f03a548512b0a3d4`。后续版本的哈希见对应记录；重新构建也可能因 ZIP 时间等因素改变哈希。

额外执行全项目扫描（包含被 Git 忽略的构建目录），未发现测试密钥前缀或测试者专属域名。确认 `local.properties`、APK 和构建报告被 Git 排除。没有将真实测试凭据写入项目或提交到 Git。

### 少量真实接口验证

使用终端隐藏输入，仅通过测试进程环境传递密钥：

- `deepseek-v4.1-flash-1017`：使用实际生产 SSE 适配器，单次短请求正确返回 `OK`；本次约 1012ms。此数值仅说明这一次请求，不代表会议问答延迟。
- `fun-asr-realtime-2026-02-28`：使用实际生产 WebSocket 适配器，发送 3919ms 合成中文语音，收到 17 字符的定稿结果并完成 `task-finished`。未打印原始返回全文。
- 未用真实会议数据；本轮只调用一次文字模型与一次语音模型，未自动重试。

### 界面检查与限制

已检查电脑绘制的 393×852 竖屏、852×393 横屏、840×900 双栏，以及设置界面 PNG；修复了自适应布局在布局回调中直接重建子视图导致的空面板问题。旋转测试确认会话与常亮选择保留，且没有重新启动录音源。

电脑阶段没有 Android 模拟器运行时，使用 JVM / Robolectric 测试；不把它们表述成真实设备结果。以下是随后补充的手机验收结果。

ASR 连通测试只验证非空定稿和收尾协议，未衡量识别字错率；短文字请求也未验证算法正确率、金融分析质量或复杂指代能力。这些需要后续使用约定的会议样例评估。

### 2026-10-25 · Pixel 折叠屏真机

设备：Pixel 11 Pro Fold，Android 17；应用 targetSdk 36。外屏自然尺寸 1080×2342，大内屏 2076×2152。未使用真实会议录音。

已通过：

- 安装 / 冷启动、系统麦克风权限提示与拒绝路径、之后授权录音。
- `basic` 模式：主界面快捷键、切换开关的长按保护、设置页输入隔离；使用注入事件，不冒充真实蓝牙测试。
- `storage` 模式：真实 Android Keystore AES-GCM 加密 / 解密、防篡改拒绝、清除凭据、偏好删空后重新读取不恢复，以及禁用备份标志。使用单独测试配置文件，结束后删除，不覆盖用户配置。
- 实际 `AudioRecord` 持续产生 PCM 帧；回到系统桌面后会话终止，后续不再发送帧；该项使用假语音服务，只在内存统计帧数，不调用模型。
- 真实声学链路两轮：电脑扬声器播放 Tingting 合成中文，手机拾音，经过 Fun-ASR 定稿后调用 DeepSeek。第一轮为合并两个升序单向链表并给出 C++ 代码；第二轮问「刚才讨论的问题」，模型正确引用前一轮链表题目并复述思路。
- 第一轮 PCM 约 12.1 秒、定稿 30 字符、答案 1584 字符；第二轮 PCM 约 10.9 秒、定稿 33 字符、答案 485 字符。停止到完整答案分别约 10.7 秒、5.8 秒，只代表这两次测试，不是延迟承诺。未单独计量首 token 延迟。
- 大内屏两栏与外屏竖屏截图、答案滚动键；Markdown 代码与长文本可显示。真实合成语音存在「C 加加」等转写差异，模型能按题意理解；这不等于完整字错率评测。
- 外屏横屏 2342×1080：聆听按钮位于可用屏幕内，触摸模式下第一次注入 Page Up 就能展开原文。测试临时切换了方向，结束后已恢复原来的竖屏锁定与自动旋转设置。截图仅在会议助手为前台应用时保存。
- `awake` 模式：通过真正的常亮按钮开启窗口标志，等待 35 秒后屏幕仍为交互状态；系统息屏原值为 30 秒，测试没有修改该超时。设置页使用系统返回键可回到主界面。

排查与修复：

1. 手机初次无法解析业务空间域名，未进入模型识别；之后公共域名与业务空间域名均能解析，不带密钥的 HTTPS 探测返回 401。应用新增 DNS / TLS / 超时等固定错误分类，保持系统证书校验，不使用绕过。
2. 首次使用 Eddy 机械合成声的语音任务失败。用户确认声音不清晰，换为 Tingting 后两轮成功；没有仅凭这一结果断言服务端失败的唯一原因。新增 ASR 错误码 / 本地分类，不回显服务端原始错误内容。
3. Android 17 的系统返回未走旧 `onBackPressed` 路径。修复为设置页注册 `OnBackInvokedCallback`，主界面仍由系统处理退出；保留低版本路径。修复后通过真机检查。测试断言也改为在线程间传递失败，避免断言本身造成进程崩溃。
4. 首次导航键可能先被系统用于建立焦点。主界面现在保留触摸模式下的根焦点，设置页保持普通输入行为；实际注入翻页事件明确指定显示器 0。修复后在触摸后第一次 Page Up 验证通过。

本次手机测试实际发起一轮失败语音任务、两轮成功语音任务和两次文字分析；另有一次 DNS 阶段失败，没有进入识别。没有自动重试。加上前述电脑连通测试，均为开发期少量验证。

测试密钥经隐藏输入和临时 ADB loopback 内存通道交给手机，随后由设备 Keystore 加密保存。通道和转发已关闭；监听器只存在于 androidTest APK，已检查应用 APK 不含该代码。项目、构建目录和测试日志不保存真实密钥。真机截图保存在被 Git 排除的 `.local/device/`，不进入 GitHub。

结束时确认录音操作没有运行、方向设置恢复为原值；移除了手机上的临时测试包和界面 XML。保留主应用及其加密模型配置，并重新打开初始主界面供手动试用。

尚待专项验证：实际蓝牙键盘 / 快门（本次没有设备）、录音中的物理折叠 / 展开连续性、半折铰链遮挡、复杂多人会议与金融分析质量。未运行生成的 C++ 代码，不声称算法代码已经编译或覆盖所有边界。

复用声学测试（macOS、手机已配置模型且授权麦克风，需要扬声器与手机靠近）：

```sh
python3 scripts/device-live.py --adb /path/to/adb --serial YOUR_DEVICE
```

它会主动调用两轮模型，非 CI 默认检查。无模型的检查可将 instrumentation 参数设为 `-e mode storage` 或 `-e mode awake`；网络诊断为 `-e mode network`，不发送密钥。

`scripts/device-layout.py --adb /path/to/adb --serial YOUR_DEVICE` 检查外屏横屏与首个翻页键，会在 `finally` 中恢复原来的方向设置。`scripts/device_capture.py` 检查当前前台包名再保存截图，避免将其他应用界面纳入测试产物。

### 2026-10-25 · 顶部工具栏更新

电脑执行 `scripts/check-local.sh` 通过：核心 25 项、界面 12 项，共 37 项；构建应用 / androidTest APK 成功，Lint 仍为 0 错误、2 项既有警告，APK 签名和对齐校验通过。新增界面验证包括：

- 圆形按钮触摸尺寸、无障碍名称、灯泡黑白内部填充、偏好名称居中且无冗余前缀。
- 连接中保持显示、聆听后收起、内容增加 56dp、轻点唤出与再次轻点重置计时，底部停止按钮可用。
- 滚动 / 长按不唤出、错误保持显示、菜单打开暂停收起；旋转保留隐藏状态且不重启音频源。
- 收尾 / 分析 / 结果阅读保留自动收起模式，显隐后仍保留答案滚动位置；横屏动画不重建双栏面板。

检查电脑绘制的竖屏、横屏、折叠屏、工具栏隐藏及阅读中展开预览；文件位于被 Git 排除的 `app/build/reports/ui/`。界面测试使用模拟语音 / 文字服务，不访问真实接口。

本轮应用 APK SHA-256：`6286080d0463ba3be9c86252b47e144cbb9cfa2e76021e804edb256077c36c96`。

新增真机测试模式 `-e mode toolbar`：不访问保存的密钥，使用内存模拟会话，不采集音频、不发网络请求；验证灯泡按钮、自动收起与内容空间、真实触摸唤出、4 秒后收起以及隐藏时停止。要求手机已解锁；截图先保存在应用私有缓存，提取到被 Git 排除的 `.local/device/` 后清除。

同一台 Pixel 11 Pro Fold 外屏竖屏实际运行 `toolbar` 模式通过，已查看工具栏显示 / 隐藏两张真机截图；`basic` 回归通过（常亮快捷键与长按保护、齿轮进入设置、设置页按键隔离）。首次检查因手机锁屏主动拦截，用户解锁后完成。该轮仅为界面验证，没有再次调用付费模型或启动真实麦克风。

外屏横屏 2342×1080 布局与首个 Page Up 展开原文检查通过；方向设置恢复原值。折叠屏大内屏的新布局本轮通过电脑绘图检查，未重复物理展开验收。结束时清除截图缓存及临时界面 XML、卸载测试包，保留新版主应用与加密模型配置。

### 2026-10-25 · 合并设置入口

移除与更多菜单重复的齿轮按钮，顶部仅保留灯泡和三点。电脑 37 项测试、应用 / 测试 APK 构建、Lint 均通过（仍为 0 错误、2 项既有警告）；界面测试通过三点菜单的设置项进入设置页。真机测试代码也改走菜单，后续执行 `basic` / `awake` 不再依赖齿轮入口。

新版已覆盖安装到同一手机，保留用户配置。本轮手机处于锁屏，未重复真机交互测试；未采集音频或调用模型。APK SHA-256：`932d794382d194a8c1a4beddfbbae9af86a92bbf1300dae0093b0115648538aa`。

### 2026-10-25 · 偏好名称左对齐

偏好名称改为与状态行左对齐，已同步产品文档和已有界面断言；12 项界面测试与 APK 构建通过。本轮不调用模型。APK SHA-256：`0c61df6218ed84bd74112c25da71c9554dc2aa5313e04d35debb08bc3f41ffad`。

### 2026-10-25 · 大内屏允许隐藏原文

取消大屏强制显示原文和禁用按钮的规则。大内屏默认双栏，按钮仍可「收起原文 / 听写原文」切换，隐藏后答案占满内容宽度。显式选择在本次运行内跨尺寸变化 / Activity 重建保留；翻页键可展开隐藏的原文。修复隐藏原文后保存布局状态可能覆盖原文阅读位置的问题。

`scripts/check-local.sh` 通过：核心 25 项、界面 14 项，共 39 项；应用 / androidTest 构建和 Lint 通过（0 错误、2 项既有警告）。界面回归覆盖大屏按钮往返切换、Page Up / Down 唤出、模拟折叠 / 展开与重建保留选择、隐藏期间听写继续且阅读位置恢复。检查新增 `foldable-transcript-hidden.png` 电脑预览。

新版已安装到 Pixel 折叠屏。在已展开的大内屏实际点击原文按钮，显示 / 隐藏往返通过，按钮始终可用，隐藏后答案与内容区边界一致（内容区 `[29,318][2047,1907]`）；结束恢复测试前的显示状态并删除临时 XML。首次检查使用不含前台摘要的 dumpsys 子命令，未执行点击；改用窗口总览确认前台后通过。本轮没有真实录音或模型请求，未重复物理折叠过程测试。

APK SHA-256：`41db62f1166e946c7ecc77bc0e2ae4510bb5aa9610f24bfd13fd07c3992e99d3`。

### 2026-10-25 · 缩小内容字号

答案正文 19sp → 17sp，听写原文 16sp → 14sp；两区行距统一为 1.05 倍基础行高加 2dp，保留系统字体缩放。14 项既有界面测试及 APK 构建通过，检查电脑生成的横竖屏、内屏和长答案阅读预览。本轮不录音、不调用模型。APK SHA-256：`613a0aa36c99a9d315c98ebc4456226a0206a41b7e14e505121f475079bc42e5`。

### 2026-10-25 · 上下操作区同步自动收起

顶部状态 / 偏好工具栏与底部聆听 / 原文 / 任务操作区统一显隐：进入主界面后约 4 秒无触摸操作收起，轻点恢复约 4 秒，聆听启动后约 1 秒收起，连接中 / 错误保持显示。上下高度均让给内容区，共 116dp；滚动按键不唤出控件，录音快捷键不依赖按钮可见性。统一动画与固定展开尺寸分类避免面板反复重建。

电脑验证：核心 25 项、界面 16 项，共 41 项通过；应用 / androidTest APK 构建及 Lint 通过（0 错误、2 项既有警告），源代码密钥扫描通过。覆盖初始闲置、轻点恢复、隐藏时滚动和空格停止、显隐后阅读位置、横屏模式稳定和旋转保留显隐状态；检查竖横屏隐藏预览。横屏辅助脚本也改为先轻点唤出控件再检查，并通过 Python 语法检查。

真机外屏（1080×2342）：模拟会话 `toolbar` 模式通过，验证闲置和聆听时上下控件同步隐藏、内容变高、轻点同步恢复、隐藏时通过注入录音按键停止；`basic` 菜单入口 / 键盘长按保护 / 设置输入隔离回归通过。另用 ADB 实际触摸确认三点菜单能进入设置并返回。本轮不采集真实声音、不调用模型，也不把注入事件当成物理蓝牙设备验证。

菜单回归最初未等待自动化服务取得弹窗 / 页面切换完成，分别出现空窗口和页面尚未打开的测试断言；测试包调整连接顺序与状态等待后通过，没有因此修改应用菜单行为。截图提取到被 Git 排除的 `.local/device/` 并清除设备私有缓存，临时 XML 与测试包已移除，保留新版主应用。

APK SHA-256：`9150b9053e9108461323a1610a8703103b4c8d3a6422c484e3b309b68cff0613`。

### 2026-10-25 · 透明底麦克风图标

删除应用矢量图标的深色方形底板，仅保留原有薄荷绿麦克风。APK 构建和 Lint 检查通过；检查图标源文件与 APK 中的编译资源，确认只包含麦克风的两个路径。新版已覆盖安装到手机，未调用模型。尝试打开系统应用信息页查看时，该页未保持前台，因此没有采集系统界面，也不将此记为桌面图标视觉验收。APK SHA-256：`25c8d17473dc9f7cdd508d85c6e9c4f53812f103148a3eae0eb2bd99ef23efdf`。

### 2026-10-25 · 自适应与单色主题图标

保留原有麦克风路径轮廓，普通模式采用用户确认的白底黑色外观。前景使用 108×108dp 画布，麦克风置于中央安全区；`mipmap-anydpi/ic_launcher.xml` 提供背景、前景及显式 monochrome 层，普通 / 圆形入口指向同一自适应资源。单色层复用麦克风的透明 alpha 轮廓，配色由桌面主题决定；不再依赖自动提取单色。

按官方自适应图标文档配置统一图层，minSdk 为 26。APK 构建及 Lint 通过，0 错误、2 项既有警告；检查 APK 编译 XML 确认三个图层存在、清单入口一致；源代码密钥扫描通过。资源目录合并期间出现一次增量链接旧缓存问题，强制重新生成后通过。新版覆盖安装到手机并保留配置，本轮未修改桌面主题开关、未采集音频或调用模型，未将资源检查表述成不同桌面主题的视觉验收。

APK SHA-256：`a7296ff6a885fb590bbd9cde9fdde8e883141b695eaf2f7822f64b858aaf53b6`。

### 2026-10-25 · 缩小麦克风图标

前景和单色主题共用的麦克风组缩放从 1.5 调为 1.25，线性尺寸约缩小 16.7%，平移从 18 调为 24，保持在 108×108dp 画布中央。造型与配色不变。APK 构建通过，已检查安装包编译资源中的缩放 / 平移参数。本轮不调用模型。APK SHA-256：`4a89f2232a2510b81c124a8be4c6967140d69a3ab8de8a0a8f4d276169300353`。

### 2026-10-25 · 自动模式与主界面偏好选择（电脑验收）

设计已更新至 DESIGN 第 10 节与 ARCHITECTURE 的自动调度章节，README 同步使用方式。实现 Auto 红色开关、设置同步和未绑定的自动模式快捷键；点击顶部偏好名称可单选已保存配置 / 不使用偏好，对话框打开时暂停工具栏收起。

`scripts/check-local.sh` 全部通过：核心 43 项、Android 界面 20 项，共 63 项，0 失败 / 跳过。应用与 androidTest APK 构建通过；Lint 0 错误、2 项既有警告（Gradle 新版本提示、状态文字拼接）。签名和 zipalign 校验、源代码密钥扫描通过。

重点检查：

- 虚拟时钟模拟文字模型 60 秒不完成，期间逐秒追加 60 段定稿、持续发送音频；音频帧完整到达模拟识别器，麦克风仅启动一次，文字请求只合并为最新快照。
- 真实工作线程中的模拟文字适配器阻塞时，100 帧音频发送和停止操作仍及时完成；不在音频回调中执行文字请求。
- 中间结果不触发分析；停顿、连续讲话和明确问题调度；纯语气词不产生独立请求；停止后尾段最多追加一次，无新信息不重复调用。
- Auto 聆听中切换不重启麦克风；文字错误、超时或空回复不会终止识别，同一失败输入不自动重试。语音断流会终止采集并拒绝迟到结果。
- 偏好快照、纠正 / 修订、轮次与请求身份保护；旧偏好答案不覆盖新选择，历史中不加入模型输出，失败重试不把本轮原文重复加入历史。
- 新答案完整后替换；阅读 / 选字时暂存，退出选字或回到底部可应用。超长模型输入明确省略首尾之间的上下文，完整显示原文不被截断。
- 主界面偏好弹窗、空配置列表、单次模式本轮冻结、Auto 持久化与设置同步、按键长按保护、分析时停止按钮可用；原有横竖屏 / 大屏布局与工具栏自动收起回归。

已检查电脑生成的竖屏、横屏和大内屏预览，以及 `automatic-completed.png` 的 Auto 红色外观。首次新界面断言使用原始 Markdown 字符串，未考虑既有渲染器追加的换行，修正测试为比较渲染后文本后通过。

本轮没有连接 / 操作手机、采集真实音频或调用付费模型。自动模式真机连续拾音、物理蓝牙设备、真实会议语义切段与答案质量尚待验证；电脑模拟结果不代替这些验收。模型上下文首尾截取可能遗漏长讨论中间的约束，已在输入和设计中说明。

应用 APK：`app/build/outputs/apk/debug/app-debug.apk`。SHA-256：`621638bf5ea033beafcb3ad5dd197f1bf5e755e261a661676df20e2e5be93470`。

### 2026-10-25 · 自动模式真机测试准备（等待解锁）

新版应用和测试包已安装到同一台 Pixel 折叠屏。读取到开发者选项「充电时不休眠」全局值为 15，当时提前关闭为 0，未按用户期望保留到测试结束；系统 30,000ms 息屏超时保持原值，当前自动旋转为 1、旋转值 0，未修改。

首轮 basic 检查时手机已按超时息屏，窗口焦点为锁屏，快捷键断言未通过；未将其判断为应用键盘功能失败。补充测试前台焦点检查，以明确提示解锁。等待用户解锁后继续，当前未启动真实录音、未调用付费模型。

新增可选 `live-auto` 真机模式及 `scripts/device-auto.py`（已构建测试 APK、Lint 通过，脚本语法检查通过）。此模式通过真实偏好弹窗和 Auto 按键，再用真实麦克风、Fun-ASR 和文字模型验证两段连续语音；刻意将第一次文字完成回调延迟至请求启动 25 秒后，以核对分析期间音频持续到达。限制最多 3 次实际文字请求，不打印密钥、地址或原文；finally 恢复偏好、按键、Auto 与应用常亮，取消会话。脚本中断会 force-stop 应用。该模式目前尚未运行，需手机解锁并靠近电脑扬声器。


### 2026-10-25 · 自动模式真机验收完成

用户解锁后，先恢复「充电时不休眠」为测试前的 15，测试期间保持开启，所有检查结束后再关闭为 0。此前提前关闭造成锁屏的操作顺序已纠正。

同一台 Pixel 11 Pro Fold 外屏完成：

- `basic`：主界面、常亮快捷键长按保护、更多菜单进入设置、设置页快捷键隔离通过。
- `live-auto`：实际点击偏好名称，选择「不使用回答偏好」通过；Auto 按钮和注入 F9 的切换通过，切换本身未启动麦克风；结束恢复原偏好、按键与模式。
- 真实声学链路：电脑 Tingting 播放两段中文，手机麦克风 → Fun-ASR → 文字模型。第一段提出链表合并问题，第二段补充升序、原地合并条件。第一次文字完成回调刻意延迟到请求启动 25 秒后；约第 20 秒检查时分析仍在进行，麦克风继续收到 200 帧音频，原文包含两段的关键词，麦克风总计只启动一次。
- 第二次请求自动使用补充后的内容，返回包含链表代码的更新答案；停止后正常收尾，没有再次重复请求。总计 1 个真实语音任务、2 次真实文字调用；定稿原文 69 字符、最终答案 1647 字符，该次脚本约 63.6 秒完成。这不是延迟或识别准确率承诺，未对模型生成的 C++ 代码做编译 / 运行验证。
- `toolbar`：模拟会话，无音频 / 网络，闲置与聆听时上下操作区同步隐藏、内容变高、真实触摸唤出、隐藏时注入停止键通过。
- 外屏横屏 2342×1080：控件位于显示范围内，第一次注入 Page Up 展开原文通过，并查看实际截图。原来的自动旋转 1、旋转值 0 均已恢复。布局脚本的触摸定位更新为内容区，避免状态 / 偏好区域的新点击功能打开选择框。

此次原文包含预期关键词及答案更新，不代表任意会议、多人重叠语音或所有约束都能准确处理。物理蓝牙设备和大内屏的本轮实际折叠过程没有重测。

收尾已停止测试进程、卸载 androidTest 包、删除手机私有截图缓存和临时布局 XML；主应用与加密模型配置保留。开发者选项「充电时不休眠」最终为 0，原有 30 秒屏幕超时保留；应用重新打开且常亮关闭，录音 app-op 不在运行状态。真机截图仅保存在 Git 排除的 `.local/device/`。

复用自动模式声学验收（需先安装测试 APK、解锁手机并靠近电脑扬声器，会实际调用模型，最多三次文字请求）：

```sh
python3 scripts/device-auto.py --adb /path/to/adb --serial YOUR_DEVICE
```

测试结束需移除测试包，并将 `settings global stay_on_while_plugged_in` 置为 0。日常使用的主应用不包含测试监听器或回调延迟逻辑。


### 2026-10-25 · 状态常驻答案区与统一背景

移除答案区固定「答案与提示」标题，将当前会话状态放在该位置（14sp），位于答案滚动视图外，不随上下工具栏收起或答案滚动而消失。保留聆听时长、自动分析进度、覆盖时间、文字错误与可点击的「有更新」，自动分析期间仍显示聆听状态。顶部仅保留左对齐、垂直居中的 18sp 偏好名称，点击选择功能保留。答案面板使用与页面一致的 #10141e 背景，听写原文面板保持原样。

电脑执行 `scripts/check-local.sh` 通过：核心 43 项、界面 20 项，共 63 项；应用 / androidTest APK 构建通过。Lint 0 错误、1 项既有 Gradle 新版本提示；原状态文字拼接警告随视图整理消除。密钥扫描通过。已有界面回归增加检查：工具栏隐藏时准备 / 聆听状态仍可见，状态不属于工具栏；自动分析时同时显示聆听与更新状态；字号、统一背景、旧固定标题消失；横屏不重建状态视图、错误与新版答案入口保持可见。检查电脑生成的竖屏、横屏、大内屏及隐藏工具栏预览。

新版覆盖安装到 Pixel 折叠屏，配置保留。手机当前为锁屏，未重复真机交互；本轮没有录音或真实模型调用，未修改开发者常亮，读回 `stay_on_while_plugged_in=0`。

应用 APK SHA-256：`2f4c006ca72ccb1c03dd093f33116068ad2b1ed882249364fd9810a0c16d1230`。

### 2026-10-25 · 会议管理（电脑验收完成，真机待测）

设计整理至 DESIGN 第 11 节、ARCHITECTURE 会议管理章节；README / SECURITY 同步本机加密持久保存、继续会议及新菜单路径。实现左侧会议抽屉、新建 / 切换 / 继续 / 重命名 / 删除、各轮原文与历史答案、复制完整原文，以及设置页左尖括号返回。

电脑完整检查通过：核心 64 项、Android / SQLite 界面层 32 项，共 96 项，0 失败、错误或跳过。应用 / androidTest APK 构建通过；Lint 0 错误，保留 1 项既有 Gradle 版本提示；APK 签名、zipalign 和源代码密钥扫描通过。

新增验证包括：

- 不同会议的模型上下文、原文、答案、模式与阅读状态隔离；同一会议继续时保留先前答案、追加原文并归档轮次。
- 文字分析中切换后，迟到结果写回原会议；UI 切换通知尚未执行的短窗口内，原会议回调也不能重绘新会议。
- 聆听中切换先确认，排空尾帧并收齐定稿后切换；收尾失败保留不完整标记；取消待切换不撤销已经发出的停止操作。
- 重启不恢复麦克风或自动重试，所有未完成任务标记中断；失败轮次手动重试不把自身重复加入历史。
- 各轮答案保留实际生效的偏好快照；阅读时暂存的新答案不会越过用户选择自动覆盖，完整待更新版本仍可持久保存。
- 删除取消任务、拒绝迟到回调、排队写入不复活已删记录。当前会议替代记录与删除 / 选中项使用 SQLite 事务，故障注入验证回滚后旧数据仍在；删除失败可重试。
- AES-GCM 随机 IV、会议 ID 附加认证数据、错误密钥与篡改拒绝。Robolectric 使用显式测试密钥检查 SQLite 载荷不含明文标题 / 正文，重新打开可恢复；加密失败不替换旧行，读取失败不以空库覆盖。
- 连续文字 token 到达时仍周期性保存草稿，不能无限推迟到生成结束；存储失败不阻塞音频路径，并显示保存错误。
- 侧栏新建 / 切换 / 删除、历史答案、完整原文复制、设置入口与返回、快捷键隔离；横屏和大内屏侧栏顶部 / 底部选项可达。列表仅在摘要发生变化时重建，避免逐字生成打断触摸。

已查看主界面、设置页，以及竖屏 / 横屏 / 大内屏会议栏电脑预览。文件位于 Git 排除的 `app/build/reports/ui/`，包括 `meetings-drawer-portrait.png`、`meetings-drawer-landscape.png`、`meetings-drawer-foldable.png`。

新增手机专用 `-e mode meetings`：在独立测试数据库中使用真实 Android Keystore / SQLite，语音和文本均使用内存模拟；验证侧栏新会议、加密保存、重新打开、阅读位置、中断恢复与删除，并在 finally 清理独立测试库和恢复用户会议管理器。该测试 APK 已构建，本轮尚未连接手机执行。既有真机 basic / toolbar / live-auto 检查也已迁移到侧栏设置入口，模拟会话使用独立内存记录，不写入用户会议历史。

本轮没有操作手机、读取真实 API Key、录音或调用模型；尚需真机验证系统加密与真实窗口交互。升级前未持久保存的旧会话无法补回。

APK：`app/build/outputs/apk/debug/app-debug.apk`。SHA-256：`8e6764a506faca2a7b85a5b06694384a5a3545efc86dd3487bdc5681bc989616`。

### 2026-10-25 · 会议管理真机验收完成

用户连接并解锁 Pixel 11 Pro Fold 后，覆盖安装上述 APK，保留既有配置与会议。测试期间保留「充电时不休眠」为 15，全部交互检查结束后才关闭。全程未采集真实音频，也未调用语音或文字模型。

- `meetings`：独立测试库使用真实 Android Keystore / SQLite，模拟一轮原文和长答案；验证载荷不含原文明文、侧栏新建、会议切换、关闭管理器和数据库后重新打开、答案及 60px 阅读位置恢复、未完成聆听恢复为中断状态，以及删除后的内存 / 磁盘移除。恢复和切换均未额外启动模拟麦克风。
- `basic`：侧栏底部进入设置、左尖括号返回、快捷键重复保护，以及设置页不触发会议录音快捷键，通过。
- `toolbar`：独立内存会议下，闲置 / 聆听时上下工具栏收起，状态继续可见，触摸唤出，隐藏时注入停止键，通过。
- 外屏竖屏 1080×2342、横屏 2342×1080：实际打开会议侧栏，新会议和底部设置均在可点击范围内；更多菜单包含「复制答案」「复制听写原文」，不再包含新会议或设置。查看两种方向的实际截图；横屏原文翻页键展开检查通过。
- 另用明确标记的临时会议通过真实界面新建、重命名，强制结束应用后冷启动，确认选中项及名称恢复。重新选择原会议后，经确认对话框删除该临时会议；再次冷启动确认其没有重新出现，原会议仍选中。该检查验证进程重启，不是手机整机重启。

首次专项脚本暴露了两处测试同步问题：轮询成功后再次调用有点击副作用的条件，以及 `finish()` 后仅等待消息队列空闲、未等待完整退出生命周期。已修正测试代码并重新构建测试 APK，最终 `meetings` / `basic` / `toolbar` 均通过。正式应用代码与 APK 未因这些脚本修正发生变化；电脑 96 项测试的结果及 APK 哈希仍如上一节。

清理已完成：临时界面会议、独立测试数据库、手机截图缓存、临时布局 XML 与 androidTest 包均已移除；正式应用、原会议和加密模型配置保留。应用冷启动后常亮默认关闭，录音 app-op 未处于运行中。开发者「充电时不休眠」最终读回 0，电源服务 `mStayOn=false`；原 30,000ms 屏幕超时、自动旋转 1、旋转值 0 均保留 / 恢复。

真机截图仅保存在 Git 排除的 `.local/device/meeting-drawer-portrait.png`、`meeting-drawer-landscape.png` 等文件。本轮未实测展开大内屏和物理蓝牙设备；大内屏侧栏已做电脑布局检查。

### 2026-10-25 · 精简侧栏与复制图标（电脑验收完成）

侧栏仅选中项显示三点操作；整行共用圆角背景，文本选择区与操作区分别接收点击，取消独立胶囊和圆形底板。移除侧栏「会议」标题及返回图标，顶部直接为新会议，保留点击遮罩、系统返回 / Escape 关闭。主界面移除更多菜单，将答案复制图标移至状态行最右侧，将完整原文复制图标移至听写原文面板右上角；图标均有 48dp 触摸区域及无障碍名称，工具栏隐藏后仍可使用，原文面板隐藏时其复制图标也不可交互。

电脑检查通过：核心 64 项、界面 / SQLite 32 项，共 96 项，无失败、错误或跳过。更新既有界面用例，验证选中 / 未选中项的三点显示和一体轮廓、复制全部轮次原文并保留未定稿标记、复制当前答案原始 Markdown，以及工具栏隐藏后的复制入口与布局。查看竖屏侧栏、工具栏隐藏时的复制区域、横屏和大内屏预览。

应用及 androidTest APK 构建、APK 签名、zipalign、源代码密钥扫描通过；Lint 0 错误、1 项既有 Gradle 版本提示。手机 `toolbar` 模式补充真实系统剪贴板校验，已构建；尚待手机解锁执行。当前未安装本轮新版，也未录音或调用模型，开发者「充电时不休眠」保持 0。

APK SHA-256：`6e88f5c4a6b447243c8e2d192dfcbf1100c4ff410d5aa7451781e73ed2db21bf`。

### 2026-10-25 · 精简侧栏与无轮廓复制图标真机验收

用户连接并解锁手机后覆盖安装上一节 APK。确认两个复制控件仅显示双页图标，没有按钮边框、轮廓或底板；透明背景仅在按下时提供反馈，保留 48dp 触摸范围及无障碍名称。此外观已在上一节构建中实现，本轮补充明确设计说明，并在实际手机确认，APK 哈希不变。

- `basic` 通过：侧栏设置入口、返回、快捷键重复保护及设置页快捷键隔离。
- `toolbar` 通过：闲置 / 聆听时工具栏收起、状态常驻、触摸唤出、隐藏时停止快捷键；新增真实 Android 剪贴板检查，复制当前模拟会议完整答案与原文均一致。使用独立内存会议，不向用户会议写入模拟内容。
- 外屏竖屏 1080×2342、横屏 2342×1080：查看实际截图，两个复制图标外观无轮廓，位于各自内容区域右上角，工具栏收起后仍可见。主界面三点、侧栏标题及关闭图标均已移除；当前会议三点与会议项背景连成一体，点侧栏外可关闭。原文若由检查临时展开，结束时恢复其收起状态；自动旋转和方向均恢复原值。

本轮没有真实录音或模型调用。测试期间保留「充电时不休眠」15，所有手机检查结束后才关闭为 0，电源服务读回 `mStayOn=false`，窗口 `mHoldScreenWindow=null`，录音 app-op 未在运行。原有 30,000ms 屏幕超时、自动旋转 1、旋转值 0 均保留。测试包、手机临时 XML 和截图缓存已清理；正式应用、会议及加密配置保留。截图仅存于 Git 排除的 `.local/device/refined-*.png`。本轮未实测展开大内屏或物理蓝牙设备。

### 2026-10-25 · 当前会议删除与状态 tooltip（电脑验收完成，等待解锁）

问题原因：原流程删除当前记录后立刻创建并选中一条可见空会议，标题精度到分钟；快速连续删除会出现名称相同的新行，看起来无法删除。修复后优先切换到其他已有会议，不增加记录；最后一条删除后侧栏显示「暂无会议记录」，主界面为待开始草稿。新增 `draft` 字段，旧记录默认仍可见，草稿内部加密保存但不列入会议历史；仅调整偏好、模式或阅读位置不会加入历史，开始聆听 / 明确新建 / 命名才成为会议，切换离开未使用草稿时清理。事务保证删除与选中其他记录一起提交，删除失败保留原记录。成功提示「会议已删除」。

Auto 和灯泡点击后使用按钮附近的浮动 tooltip，内容分别为「自动分段 / 手动分段」「保持屏幕常亮 / 屏幕正常熄灭」。显示 1.8 秒，不抢焦点、不截获触摸；快速切换替换旧提示，页面切换 / 退出前台立即移除；键盘对应操作也提示。

电脑完整检查通过：核心 67 项、界面 / SQLite 35 项，共 102 项，无失败、错误或跳过。新增覆盖：当前会议删除后恢复其他会议内容、不产生额外行；最后一条空会议删除后重启仍为空历史；草稿的偏好 / 阅读变化不进入历史，显式新建清理旧草稿；开始聆听后仅产生一条可恢复记录；经选中项三点及确认框删除最后记录后显示空列表；删除和选择事务故障回滚；四种 tooltip 文本、超时、替换、非焦点 / 非触摸属性及设置页清理。既有跨会议、音频 / 分析并行和复制检查仍通过。

应用和 androidTest APK 构建、Lint、APK 签名、zipalign、源代码密钥扫描通过。Lint 0 错误、1 项既有 Gradle 版本提示。手机专项 `meetings` 追加实际点击选中项三点删除最后一条、重开数据库仍无历史行的检查，已构建但尚未执行。

当前手机仍连接但处于锁屏，新版尚未安装，已请求用户解锁；本轮未更改开发者常亮、未录音或调用模型。

APK SHA-256：`fd97306185a588a531e8e76afc46da7e6bbff33510454622bf3aaa47cadf7dc3`。

### 2026-10-25 · 当前会议删除与状态 tooltip 真机验收完成

用户连接并解锁手机后完成覆盖安装与实际窗口验证。`meetings` 使用独立测试数据库及模拟语音 / 文字，不删除用户会议：当前会议删除后保留并选中另一个会议，不增加历史行；通过选中项三点 → 删除会议 → 确认，删除最后一条后历史列表为空、返回待开始草稿；关闭再打开数据库，历史仍为空，未自动启动模拟麦克风。加密载荷、原文 / 答案恢复、阅读位置和中断恢复检查同样通过。

`basic` 与 `toolbar` 在最终安装版本均通过，涵盖设置路径 / 返回、快捷键保护、工具栏隐藏 / 触摸唤出 / 隐藏时停止，以及真实系统剪贴板的答案 / 原文复制。

提示的首轮真机截图发现底部 Auto 的 `WRAP_CONTENT` 弹窗被裁为细条，已修复：先测量完整宽高，剩余空间不足时明确放在按钮上方。重新构建并安装后，在外屏竖屏 1080×2342、横屏 2342×1080 实际点击两组开关、查看截图，确认「保持屏幕常亮」「屏幕正常熄灭」「自动分段」「手动分段」四种提示均完整可见，Auto 提示位于按钮上方，等待后提示消失。测试结束恢复原 Auto / 常亮状态和旋转设置。

布局修复后重新执行界面 35 项测试、构建与 Lint，全部通过；核心 67 项未改动且此前通过，总计 102 项，无失败 / 错误 / 跳过。APK 签名、zipalign、源代码密钥扫描通过。最终版本再次执行手机 `meetings` / `basic` / `toolbar`，全部通过。

全程未真实录音或调用模型。所有手机测试结束后，清理独立测试数据库、截图缓存、临时 XML 和 androidTest 包；用户会议及加密配置保留。开发者「充电时不休眠」最终读回 0，`mStayOn=false`、`mHoldScreenWindow=null`，录音 app-op 不在运行；原 30,000ms 屏幕超时、自动旋转 1、旋转值 0 均保留 / 恢复。截图仅保存在 Git 排除的 `.local/device/tooltip-*.png` 和 `.local/device/tooltip-landscape/`。本轮未实测展开大内屏或物理蓝牙设备。

最终 APK SHA-256：`1653a824f172c9d34b772da673e0991ebb5d0d0bd55e6370aac091c3d65d69ec`。

### 2026-10-26 · 系统 Toast 与中英文界面（电脑验收完成）

移除 Auto / 灯泡的 PopupWindow 和按钮定位，改用系统短 Toast，与会议删除反馈同一外观；快速切换取消旧提示，离开页面清理状态提示。删除成功等操作反馈不会因当前会议切换重建主页面而被提前取消。

默认英文资源与 `values-b+zh+Hans` 简体资源覆盖界面、设置、弹窗、无障碍名称、Toast、状态 / 错误、默认会议名和原文展示标记。读取系统首选语言，简体显示中文，其余含繁体统一英文；按界面方向归一为 LTR。首次安装初始化对应语言的预设，已有用户内容和配置不重写。core 及旧存档固定中文状态通过显式目录在呈现时本地化；新归档保留结构化段落，翻译标记不替换正文中的相同文字。默认模型规则改为跟随当前问题所用语言，明确指定回答语言时遵从用户。

多语言顺序测试曾发现应用 Resources 会提前选择次选中文。修复为 Android 13+ 直接读取 LocaleManager 系统语言列表，旧版使用系统 Resources，随后明确选择 en / zh-Hans。App Bundle 关闭语言拆分，两套资源均随包分发。

电脑完整检查通过：核心 68 项、界面 / SQLite / 本地化 46 项，共 114 项，无失败、错误或跳过。新增 / 更新覆盖：

- Auto / 常亮短 Toast 四种内容、重复切换取消旧实例、页面切换清理、不再创建 PopupWindow；当前会议删除提示仍能持续显示。
- zh / zh-CN / zh-SG / Hans、Hant / 台湾 / 香港 / 澳门及非中文语言策略；首选法语、次选简体中文时仍用英文。
- 英文资源无遗漏中文、历史会话状态、HTTP / ASR / DNS 参数化错误及安全回退；首次安装英文预设与已有用户偏好保持原样。
- 英语、法语系统、繁体中文系统下的主界面 / 侧栏 / 设置及四种 Toast；英文竖屏、横屏和大内屏预览。
- 语言重建后会议 ID、人工中文名称和中文模型答案保留；原文正文中包含「[未定稿]」时不会被本地化替换。
- 既有中文界面和所有会议、删除、存储、自动分析 / 听写检查继续通过。

已查看 `english-main-portrait.png`、`english-drawer-portrait.png`、`english-settings-portrait.png` 和 `english-landscape.png` 电脑预览；其他语言策略与大内屏也生成预览。图片位于 Git 排除的 `app/build/reports/ui/`。

应用 / androidTest 构建、APK 签名、zipalign、源代码密钥扫描及布局脚本语法检查通过。Lint 0 错误，仅保留 1 项既有 Gradle 版本提示。真机测试入口的可见文字查找也已改为当前语言资源，横屏脚本支持两套界面标签。DESIGN / ARCHITECTURE / README 已同步，并新增 LOCALIZATION.md 维护说明。

手机仍连接但处于锁屏，已请求解锁以继续实际 Toast 和系统语言切换检查；本轮新版尚未安装，未修改手机语言 / 开发者常亮，未录音或调用模型。

APK SHA-256：`a7c6e851ca83f0e861d72ff76254c9a7fea1138f9675b5c134389c2222f9007c`。

### 2026-10-26 · 系统 Toast 与中英文界面真机验收

用户连接并解锁 Pixel 11 Pro Fold 后覆盖安装上一节正式 APK。按最终确认的规则，两个预置偏好只在首次安装时选择名称和提示词的语言；之后与用户建立的配置一样，切换系统语言不改变已保存内容。补充电脑回归，覆盖英文首次初始化 → 中文仍保留英文名称 / 提示词，以及中文首次初始化 → 英文仍保留中文名称。LOCALIZATION.md 已明确此规则。

- 实际将系统首选语言从 `zh-Hans-HK` 改为 `en-US`，确认主界面、状态、侧栏、设置和偏好选择对话框使用英文；已有「算法讨论」「金融会议」名称保持中文，没有重新初始化用户配置。
- 再切换为 `zh-Hant-HK`，确认应用回退英文，即使语言列表中仍有简体中文也不选用次选中文。最后恢复唯一语言 `zh-Hans-HK`、香港区域，并移除临时添加的英语 / 繁体语言。
- 两种界面语言下实际点击 Auto 和灯泡，四种状态提示均使用系统短 Toast，与会议删除反馈同一风格；查看截图确认文字完整、居中显示，随后消失，重复操作后原开关状态保留。横屏另做中文提示检查，结束恢复自动旋转与方向。
- 英文系统下 `basic`、`toolbar`、`meetings` 均通过，包含设置返回、快捷键保护、工具栏收起 / 触摸恢复 / 隐藏时停止、真实系统剪贴板，以及独立加密测试库的会议切换、恢复、删除当前 / 最后一条和重启保持空历史。没有修改用户会议内容。

英文会议删除测试最初停在确认按钮：系统会将英文按钮的「Delete」呈现为「DELETE」，旧脚本精确匹配失败。修正测试按钮文字匹配以允许系统大小写转换，重新构建 androidTest 后完整专项通过；正式应用代码和 APK 未因此改动。英文 Toast 截图脚本的一次末尾 XML 读取与下一项 instrumentation 交叠而被终止，随后串行重跑，四种状态和原模式恢复断言均通过。

最终电脑检查仍为 core 68 项 + app 46 项，共 114 项，无失败、错误或跳过；构建与 Lint 通过，密钥扫描通过。正式 APK SHA-256 与上一节相同：`a7c6e851ca83f0e861d72ff76254c9a7fea1138f9675b5c134389c2222f9007c`。本轮没有真实录音或模型请求，也未实测物理蓝牙设备或展开大内屏。

横屏 Toast 检查通过。全部手机检查结束后卸载 androidTest，移除测试数据库 / 截图缓存及临时 XML；正式应用、用户会议与加密 API 配置保留。开发者「充电时不休眠」读回 0，电源服务 `mStayOn=false`，窗口 `mHoldScreenWindow=null`，录音 app-op 不在运行。恢复本轮开始时的自动旋转 1、方向 0，保留屏幕超时 30,000ms。截图位于 Git 排除的 `.local/device/`，未写入发布源码。


### 2026-10-26 · 会议导航与原文显隐快捷键（电脑验收）

开发目录与独立开源目录同步新增四个可绑定动作：「新建会议」「上一个会议（向上）」「下一个会议（向下）」「显示 / 隐藏听写原文」。默认均未绑定，旧设置无需迁移，不覆盖已有键位。设置列表、按键捕获、清除、冲突提示和 JSON 持久化复用现有流程，中英文标签同步。

新建 / 切换复用侧栏导航：按最近更新时间排序，首尾不循环，草稿不占历史项；空历史和边界仅提示。聆听中先确认停止，取消继续聆听，确认后保留尾句再切换；正在收尾导航期间拒绝重复导航，不重复创建记录。原文显隐复用 UI 按钮逻辑，在控件收起与大内屏默认双栏下均可使用，保存每场会议的选择；不因显隐重启音频。

两套目录分别通过 core 69 项、app 53 项，共 122 项，无失败、错误或跳过。新增 1 项核心与 7 项界面测试覆盖：默认未绑定 / 保存恢复 / 冲突与长按、会议顺序 / 边界 / 空列表、新建与切换的停止确认及尾句、收尾导航防重入、隐藏控件下原文显隐、每场会议选择、大内屏与 Activity 重建、设置捕获 / 清除 / 全局关闭 / 弹窗隔离。捕获测试曾对模拟器键名显示作了硬编码、对确认框异步清除断言过早，已按平台键名和消息队列完成时机修正；正式功能无需因此改动。

两套目录 Debug / androidTest 构建与 Lint 均通过；原开发目录 Lint 保留 1 项既有 Gradle 版本提醒，开源目录 0 警告。开源目录额外通过未签名 Release 构建、6 项发布审计测试和严格发布扫描；产物仍位于发布目录外。两套功能代码 / 资源 / 测试逐文件比对一致（仅保留开源版本的示例地址差异），各自文档更新。没有操作手机、采集真实音频或调用模型；物理蓝牙验证尚未进行。


### 2026-10-26 · 新快捷键与 K380 蓝牙键盘真机验收

覆盖安装独立开源目录构建的正式 Debug APK，`basic` / `toolbar` / `meetings` 均通过，检查设置与返回、工具栏收起 / 唤回、复制、独立加密测试库及删除恢复。正式应用代码未在本轮改变。

新增仅编译进 androidTest 的 `KeyboardDeviceCheck`：使用独立内存会议与 `keyboard-device-check` 配置、非功能性测试凭据、模拟 PCM / 语音与文字提供商；不读取真实密钥、不采集麦克风、不调用模型。真实外接键盘事件由临时窗口回调观察，仅统计键码及重复次数，不记录输入文本、设备描述符或地址。退出时恢复原 repository、settings、meeting manager 和常亮状态，移除临时配置；生命周期结束异常也会进入嵌套清理。两套目录同步该辅助测试。

用户使用实际 K380 键盘完成 27 项检查：按键捕获 N/U/J/T 与保存、空格冲突保护、设置页隔离、偏好 2/0/1、常亮开关及长按、Auto A、上 / 下切换会议、长按 N 仅新建一次、原文 T 在控件隐藏时显示 / 隐藏及长按保护、答案上下箭头长按滚动、Page Down / Page Up 原文翻页、空格开始 / 停止模拟聆听、聆听中切换原文仍继续收模拟音频，以及停止时保留模拟尾句。

最后一项「侧栏打开时按 N 不新建」未取得实体测试通过结果：最初测试脚本的窗口监听 / 事件基准检查超时，随后将提示放进侧栏并明确计数，最后一次人工补测未收到新的外接事件（last external key=-1）。该项单独以 `keyboard-modal-auto` 自动注入 N 检查通过，不能将自动注入算作实体蓝牙验证。`keyboard-modal` 支持后续仅补测这一项，无需重复整套流程。没有为这些脚本问题改动正式功能代码。

本轮新增测试辅助与文档同步开源目录；两套 androidTest 构建 / Lint 通过，开源扫描与 6 项审计回归通过。原有 122 项 Java 测试对应的生产功能代码未变。测试过程保留开发者充电常亮，所有手机工作结束后再关闭。真实模型效果、本次未操作的折叠姿态及其他品牌遥控器不在这轮验收结论内。


所有手机检查结束后，正式应用回到原会议前台，删除 androidTest 包、模拟配置和测试缓存。手机只保留 `settings-v1.xml` 和正式会议库，未保留键盘测试配置。开发者「充电时不休眠」读回 0，`mStayOn=false`、`mHoldScreenWindow=null`，录音 app-op 不在运行。系统语言仍为 zh-Hans-HK，自动旋转 1、方向 0、屏幕超时 30,000ms 均保留。普通测试界面随手机当前传感器方向横屏显示，未通过系统设置强制旋转。宿主截图仅保存在 Git 排除的 `.local/device/keyboard-modal-check.png`，不进入开源目录。


## Image input / OCR computer validation

The image feature is implemented in both trees. The current computer suite has **102 core tests and 69 Android/Robolectric tests (171 total)**. It covers OCR-only analysis without a microphone, manual/Auto audio continuity, a blocked text response while 60 audio frames and finalized segments continue, image queue limits/FIFO/cancellation, invalid/truncated OCR, normalized and Qwen rotated-box parsing, preference snapshots, pending answers and image references on restore, tail drain, meeting ownership/deletion, version-1 migration, encrypted OCR payloads, source changes/disconnects, cropping/rotation, keyboard repeat guards, drawer isolation, English controls and Activity recreation.

Debug app and androidTest builds, unsigned public Release, and Debug Lint pass. The public release scanner and **7 audit regression checks** pass; reviewed upstream legal texts with historic public URLs/contributor contacts are accepted only at their exact pinned hashes. Public build/cache output remains outside the source tree. The merged app manifest requests only Internet, microphone and camera permissions; the UVC library's storage/foreground-service permissions are removed. Packaged arm64 native libraries have 16 KiB ELF load alignment.

A separate, explicitly authorized live check used only a synthetic algorithm-site image: three small OCR requests (two output-format probes and one successful Java-adapter check), followed by one text-model request. The final OCR produced 19 validated regions in about 4.3 seconds; the complete OCR-to-answer check took about 33 seconds. The returned solution used C++, relinked existing nodes, and retained the O(1) extra-space constraint. API credentials were used only in temporary processes; no image, response, credential or private endpoint from that check was added to either project. These manual requests are not part of the public test runner.

Qwen3.5-OCR returned rotated text boxes rather than arbitrary UI-role JSON. Its adapter explicitly requests the 0..1000 coordinate grid and labels UI roles unknown, so the final text model infers roles from text/layout. This does not establish detection of unlabeled icons, selected states, every table structure, or every photo's small characters.

No phone was operated during this feature's computer-development pass. Remaining device checks: runtime camera permission, actual back/front cameras, preview/crop/fold/rotation, physical capture keys with hidden controls, camera/OCR during uninterrupted real listening, USB permission/MJPEG/frame delivery/unplug behavior, audio route retention, and background resource release. Use nonsensitive visual test material; real camera images go to the configured image provider only when a capture is accepted. Keep charging stay-awake enabled if needed throughout testing, and turn it off only after all device checks are finished, as requested.


### 2026-10-26 · USB 采集卡电脑实测（不等同于 Android 验收）

在 macOS 上使用临时 AVFoundation 检测程序，对连接的 UGREEN 95348 进行本地取帧。系统枚举到标准 UVC 视频接口及 USB 音频接口；本轮只测试视频，没有录制或验证采集卡音频。

| 实际输出帧尺寸 | 单次测试时长 | 收到帧数 | 去除前 3 秒后的交付帧率 |
| --- | --- | --- | --- |
| 1920 × 1080 | 20 秒 | 1125 | 约 59.66 fps |
| 3840 × 2160 | 15 秒 | 442 | 约 29.75 fps |

两种模式均抓到有效电脑桌面，界面文字可辨认。所报帧率为回调交付速率，不代表每帧内容独立；AVFoundation 的丢帧回调均为 0，不据此推断 HDMI/USB 链路完全无丢帧。1080p 这次完整样本的最大时间戳间隔约 783ms，4K 约 201ms，短测不足以保证长时间稳定或测定端到端延迟。初始化曾出现黑帧，模式切换需要等待画面恢复。

macOS 会话启动会重选格式。最终测试在启动后固定实际格式，并选择设备单独提供的 4K30 格式；先前使用另一 4K 格式降到 30fps 没有收到帧，不算通过。系统还声明 4K60 / 1080p 高帧率等模式，本轮未验证这些模式，也未核实 HDMI 原始输入分辨率，不能由输出尺寸推断原始画面具有原生 4K 细节。

未调用 OCR/文字模型，未操作手机，未修改应用代码。检查图片只暂存系统临时目录，查看后清理；两个项目均不包含真实桌面截图、设备序列号或个人数据。当前 Android USB 选型策略仍优先不超过 1080p；以上结果确认该硬件的电脑视频输入可用，Android USB 权限、实际格式协商、拔插、音频路由与边听边抓拍仍待真机验证。


## Opt-in image device checks

The following modes are built into **androidTest only**, never the regular APK. Connect/unlock a test device and install both Debug APKs first. They use isolated temporary meetings/preferences and restore the original app objects on cleanup. Run only while the app is idle. Do not interrupt an instrumentation run during real work.

- `image-hardware`: **opens real cameras and the real microphone**, with synthetic OCR/text providers and no network requests. Checks full frames, preview, crop, settings drag gestures, reopening/background release, and continued PCM during delayed image/text work. Camera pixels stay in memory. Shortcut actions are injected and do not establish physical Bluetooth compatibility.
- `image-usb-preflight`: saves one real USB frame in app-private temporary cache (`usb-preflight.jpg`) for local visual inspection before any model upload; it makes no network requests. Remove the cache file and any local review copy after inspection. Use this mode to ensure the input contains only a synthetic test page.
- `image-usb-hardware`: the same local-only real microphone/preview/crop checks, restricted to attached USB video inputs. Accept the Android USB device permission prompt if it appears. The image source is explicitly selected and never silently falls back to a built-in camera.
- `image-live`: **sends one real camera capture to the configured image service and one text request**, using already saved encrypted keys. If the image endpoint/key is unset, the test temporarily borrows the configured text endpoint/key for the same provider and selects `qwen3.5-ocr`. It uses only a disposable algorithm profile/meeting and does not start the microphone. Do not use this mode with a provider whose text credentials are not intended for OCR.
- `image-live-audio`: the same paid image/text check **plus real microphone/Fun-ASR streaming**. Check the camera points only at a synthetic linked-list exercise, and the surrounding audio is appropriate for sending to the configured ASR service.
- `image-live-usb-audio`: combines the USB image check with real microphone/Fun-ASR streaming, to count PCM delivery while actual OCR and text requests run. It does not establish word accuracy without an appropriate spoken test.
- `image-live-usb`: the paid image/text check with a connected USB video source, without starting the microphone. A USB permission prompt may need human acceptance.

Live modes expect a nonsensitive test page about merging sorted linked lists, not an arbitrary camera scene. They reject topic/preference mismatches and use one image-triggered text request. The resulting synthetic answer is temporarily left in app-private cache (`synthetic-image-answer.txt`) for local review; remove it after inspection. They do not export images, keys or settings. A shared account key/endpoint is a test-only convenience and does not couple the production image and text configurations.

```sh
adb shell am instrument -w -e mode image-hardware io.github.meetingassistant.test/io.github.meetingassistant.android.SmokeInstrumentation
# Opt in separately after preparing the test scene and reviewing the network behavior above:
adb shell am instrument -w -e mode image-live io.github.meetingassistant.test/io.github.meetingassistant.android.SmokeInstrumentation
```

Keep the phone awake throughout all planned checks. Restore original rotation settings and disable developer charging stay-awake only after the entire device session, including USB testing, is complete. Never commit device dumps or real camera images.


### 2026-10-26 · 无线 ADB 相机端验收（USB 阶段待连接）

覆盖安装当前正式 Debug 包，原会议与模型配置保留。基础、工具栏、会议存储三个既有真机模式均通过。新增的 `image-hardware` 在隔离设置 / 数据库中使用真实相机和麦克风、本地模拟 OCR / 文本提供商，不联网、不落地相机照片。

- 竖屏实际抓拍：后置 2000×4000，前置 2448×3264；中心裁剪分别为 1200×2400、1470×1960。横屏为后置 4000×2000、前置 3264×2448，裁剪与旋转元数据同步变化。
- 两个来源均完成连续预览、抓拍、裁剪和重新开启；真实设置页的拖动裁剪、返回图标、返回后继续抓拍通过。来源关闭时抓拍控件隐藏，测试注入的视频开关键可重新打开来源。
- 延迟的本地 OCR 与文本分析期间，真实麦克风持续采集；最终完整 UI 检查收到 144 个 PCM 帧，最大交付间隔 107ms。横屏基础硬件检查为 115 帧、最大 110ms。此结果验证本地并行采集，不等同于真实 ASR 服务逐字准确率或所有环境下的绝对无漏听。
- 页面退出后视频来源关闭，麦克风停止。检查后仅有原生产会议数据库与 `settings-v1` 设置文件，临时数据库 / 设置已删除，系统相机和录音 app-op 均无运行中记录。旋转恢复为原自动旋转开启、方向值 0。

补充测试最初连续发送系统返回键，过早退出 Activity，导致在途拍照被正确取消；随后同一轮关闭对话框时又碰到异步返回侧栏的时序。脚本已改为点击实际返回图标，等待侧栏创建并关闭后继续操作，最终完整检查通过；正式应用没有因此改动。

两套源码同步新增显式硬件 / 真实模型验收模式，仅包含于 androidTest。两套测试 APK 构建及开源 Lint 通过；尚未通过手机发送真实 OCR 请求。已请求将采集卡接到手机以继续 USB 硬件与真实 OCR 链路验收。全部手机测试尚未结束，因此按用户要求保留开发者「充电时不休眠」，不在阶段间提前关闭。


### 2026-10-27 · USB 原生回调退出竞态修复（真机回归等待解锁）

手机已枚举 UGREEN 95348 视频 / 音频接口，`image-usb-hardware` 收到首帧并进入 READY；随后来源重开触发 `libUVCCamera` 的 CheckJNI 终止，错误为 `jmethodID was NULL`。上游 `do_capture_callback` 在颜色转换后仍使用回调方法 ID；原清理逻辑在视频线程停止前清空该 ID，存在竞态。

两套源码同步修复：先 `stopPreview` 等待原生视频线程退出，再清理回调和销毁相机；USB 打开、关闭及监听回调统一在一个惰性创建的后台 Handler 上串行执行，避免旧设备释放与新设备占用接口重叠；关闭幂等，忽略重复打开通知。库版本 / 原生二进制未更换。

两套 Android/Robolectric 69 项测试、Debug / androidTest 构建与 Lint 通过。修复版已覆盖安装；下一次真机检查停在锁屏断言，不能计为 USB 修复验收通过。原生崩溃留下的独立测试数据库 / 设置已清理，生产会议和凭证保留。未发送真实 OCR 请求。

已请求解锁。因 USB 卡占用端口、手机没有充电，充电常亮本身无法维持亮屏；测试期间临时将息屏超时由 30 秒延长为 30 分钟。所有真机测试结束后必须恢复原 30 秒，并关闭开发者充电不休眠。目前仍在同一测试会话中等待解锁，尚未执行最终恢复。


### 2026-10-27 · USB 图像硬件回归通过（单帧观察与实时预览结论修正）

两套代码同步补充 USB 生命周期修复：显式关闭 UVC 克隆连接以外的原始控制连接；在原生打开前读取有界 VS 帧描述符，优先协商不超过 1080p 的格式，避免库默认先尝试最大格式；仅对视频流接口的 Bulk IN 端点恢复初始状态，不改动 USB 音频接口或整机配置。描述符解析补充离散 / 连续帧间隔、截断数据和音频接口隔离测试。原生依赖仍为 UVCAndroid 1.0.13。

USB 硬件模式已在 Android 折叠屏手机和 UGREEN 95348 上通过：1920×1080 抓帧、1152×648 中央裁剪、设置页真实预览拖动裁剪、关闭 / 重新打开输入及退出清理。延迟的模拟 OCR / 文字分析过程中，开发版与开源版分别安装后各通过一轮；两轮真实内置麦克风均收到 103 个 PCM 包，最大包间隔分别为 102ms / 104ms；结束后自有 USB 设备句柄均回到基线。该结果不等于语音识别准确率、长时稳定性或其他采集卡兼容性结论。

早期脚本在 Activity 刚启动时立即检查窗口焦点，将未取得焦点误报为需要解锁。已改为等待窗口焦点；系统 Keyguard 检查确认当时并未锁屏。用于定位端点状态的临时恢复探针已从两套 androidTest 源码移除，正式输入适配器保留必要的端点初始化。

两套电脑测试均为 **core 102 项 + app 72 项，共 174 项，无失败 / 错误 / 跳过**。开源目录 Debug、androidTest、Release 构建和 Lint 通过，发布目录凭证扫描通过。

预检脚本另建图像控制器、选择枚举到的第一个 USB 视频来源，在就绪后立即抓一帧；最后一份本地复核文件生成于 01:09:26，内容为绿色图案壁纸。用户随后反馈 App 预览正常，01:19 左右直接检查 App 的「图像输入设置」也确认：选中 USB 视频来源，预览能正常显示电脑窗口。因此此前「HDMI 当前只有壁纸」的概括不成立，更不能据此判断镜像设置。独立脚本单帧与当前持续预览的差异原因尚未确定，后续应核对同一来源、同一时刻的预览与抓拍，不要求用户因此修改显示模式。这些图像只作本地检查，未发送给模型；真实 OCR 验证仍须使用不含个人信息的测试页。临时照片和测试配置不得提交源码。测试会话仍在进行，最终屏幕超时、开发者充电常亮及临时文件恢复将在结束时单独记录。


### 2026-10-27 · 按用户要求恢复手机熄屏设置

已通过系统设置写入并回读确认：`screen_off_timeout = 30000`（30 秒），`stay_on_while_plugged_in = 0`（开发者「充电时不休眠」关闭）。系统报告 `mStayOn = false`、`mHoldScreenWindow = null`，手机已进入 Dozing，未被 App 常亮窗口保持唤醒。本次仅恢复屏幕设置，未继续发起模型调用；手机端真实 OCR / 文字链路仍未验收。


### 2026-10-27 · 手机真实 OCR 独立链路通过；电脑锁屏造成并发测试输入变化

重新获准测试后临时保持手机亮屏。测试前在 App 中框选电脑右侧的虚构链表题页，本地预检确认 875×790 抓拍只包含题目与导航文字；没有发送聊天窗口、私有地址或其他桌面内容。androidTest 预检现沿用 App 当前的来源 / ROI，并等待流启动稳定；真实模型模式保留已检查的 ROI，不再重置为整帧。两套测试源码同步，androidTest 构建与 Lint 通过；正式 App 代码未因此改变。

真实 `image-live-usb` 验证通过：Qwen3.5-OCR 返回 12 个文字区域，OCR 阶段约 2990ms，完整答案约 29423ms、1495 字符。模型根据中央题目 B5–B11 给出复用原节点的 C++ 合并链表解法，并明确忽略左侧导航 B1–B4；全程未打开麦克风（PCM 包 0）。本地提取这一份生成的 C++，编译通过，7 组固定边界 + 200 组确定性随机输入共 207 组检查通过，检查排序结果、所有输入节点恰好复用一次以及无环输出；这仅验证本次输出，不保证其他答案正确。

随后的真实语音并发轮次已连接 Fun-ASR，但在题目识别断言失败，未计为通过。紧接着本地预检发现相同 ROI 已变成绿色图案。进一步读取电脑系统信息确认 UGREEN 输出确实为镜像（不是扩展显示），且 `CGSSessionScreenIsLocked = true`：HDMI 上游电脑锁屏导致题页不再显示。之前仅凭绿色单帧怀疑镜像设置的推断撤回。此轮不是 OCR 服务报错，仍须在电脑解锁后重新验证并发链路。当前已请求解锁电脑，测试期间保留手机临时亮屏，结束后恢复 30 秒超时和关闭充电不休眠。


### 2026-10-27 · 手机真实语音 / OCR / 答案并发通过与最终恢复

电脑解锁后，先确认其锁屏状态为 false，再做本地 USB 预检，875×790 ROI 内容与虚构链表题一致，随后运行开源 androidTest 的 `image-live-usb-audio`。真实 Fun-ASR 连接进入聆听，Qwen3.5-OCR 识别 12 个文字区域（约 2736ms），文字模型完成 1713 字符的 C++ 解答；从抓拍到答案完成约 25480ms。分析完成时语音引擎仍为 LISTENING：共 268 个 PCM 包，其中 OCR 期间 27 个、文字生成期间 227 个，最大包间隔 104ms。这是本次短测中拾音持续和并发链路的实测，不是语音内容准确率或长时间零丢包承诺；本轮无需人工说话。

本轮答案正确区分左侧导航与中央题目。第二份生成的 C++ 也独立编译，并通过相同的 207 组固定 / 随机输入检查（值序、输入节点完整复用及无环输出）。此前独立 OCR 轮次与本轮答案均通过；不能由此保证其他题目、画质或模型输出始终正确。

手机测试结束后，已通过界面将临时题页裁剪恢复为「使用完整图像」，保持原 USB 来源选择，关闭视频输入；原会议与凭证保留。临时预检图片 / 生成答案缓存已删除，测试 APK 已卸载，正式 App 保留。系统设置写入并回读确认：`screen_off_timeout = 30000`、`stay_on_while_plugged_in = 0`、`mStayOn = false`、`mHoldScreenWindow = null`；相机和麦克风 AppOps 均无 running 标记。测试页及本地复核临时文件已清理。两套文档与 androidTest 修正保持同步；开源目录凭证扫描通过。

至此已完成本次独立图像和语音并发真实模型验收。实体蓝牙图像快捷键、物理热拔插 / 异常断电、其他 USB 卡及长时间稳定性仍不在此次已验证范围内。


### 2026-10-27 · 有界上下文、后台整理与响应提醒（全 mock）

两套目录均完成并各通过 **core 127 + app 83，共 210 项测试**，无失败、错误或跳过；较上一版新增 25 项核心测试及 11 项 UI 测试，另扩展原有加密持久化检查。两套 Debug / androidTest 构建及 Lint 通过，开源 Release 构建和凭证扫描通过。Lint 只保留原有第三方清单与工具链升级警告，未新增网络 / 存储权限或依赖。

设计见 [CONTEXT_MANAGEMENT.md](CONTEXT_MANAGEMENT.md)。已覆盖总预算下的多轮听写 / 多图 OCR、完整档案保留、早期中英文问题检索、摘要水位与纠正失效、完整引文校验（不能删掉否定词）、非法 / 超时 / 迟到摘要、独立整理与 PCM / 收尾并行、首段慢与长输出区分、提醒冷却及消费恢复、模型切换、单次上下文超限重试、过时规划发出前丢弃、设置迁移及加密存储。UI 覆盖短暂提示条、详情 / 按键隔离、空白新会议与显式短摘录延续、设置校验保存、中英文、竖屏 / 横屏 / 折叠比例及 Activity 重建。

所有新增测试均为 mock：虚拟时钟模拟长会议和慢模型，Factory fake 模拟回答 / 整理 / 语音 / PCM，提供商错误适配仅使用本机 MockWebServer。未使用用户密钥，未向真实模型发送任何长上下文或摘要请求，未连接 / 操作手机，手机此前已恢复的熄屏设置未改动。真实整理质量与服务延迟不由本次 mock 测试保证。


### 2026-10-27 · Selectable audio input（desktop mock）

两套目录均完成来源选择、右侧输入栏、音频设置页和本地音量测试。各通过 **core 131 + app 113，共 244 项测试**，无失败、错误或跳过；较上一版本新增 4 项 core 和 30 项 app 测试。两套 Debug / androidTest 构建及 Lint 通过，开源目录 Release 构建和发布凭证扫描通过。生产代码与单元测试保持一致，仅保留开发版 / 公开版原有的通用服务地址提示差异；开发诊断工具没有整体复制到开源目录。

覆盖来源选择不录音、局部设置迁移、设备缺失 / 歧义 / 路由拒绝、真实路由不符、热拔插通知、重连不自动收音、蓝牙连接等待中取消、快速切回、先释放后打开、互斥采集、本地测试离页 / 后台释放、收尾队列排空、有界背压失败、未验证音频丢弃、采样率 / 声道 / 分片转换、切换保留 ASR 且不触发手动分析、应用事件入原文 / 存档 / 模型上下文、事件单独不生成答案、按键隔离、中英文与竖屏 / 横屏 / 折叠比例及 Activity 重建。已人工检查本地生成的音频设置页与横屏输入栏截图。Lint 无新增告警，保留原有第三方清单移除项和工具链提示。

设计及实现边界见 [AUDIO_INPUT.md](AUDIO_INPUT.md)。测试为 fake 设备 / 音频 / 路由、Robolectric 权限与 UI、mock 识别和文本模型；本轮没有使用用户凭证或调用真实模型，没有连接手机或改变手机熄屏设置。真实 AudioRecord 路由、USB/UAC、蓝牙 SCO / LE Audio、采集卡音画并发、实体插拔和长时稳定性仍待手机验收，不能由本轮 mock 结果保证。新版 APK 已生成，尚未安装到手机。


### 2026-10-27 · Enco X3 蓝牙音频真机验证与首次授权修复

在一台 Android 17 折叠屏手机上，OPPO Enco X3 以系统 SCO 通话麦克风路由完成本地检查。首次授权后 AudioPolicy 的缓存地址由隐私掩码更新为完整形式，原 locator 随之改变，导致旧选择被安全拒绝；首次授权复现确认后，两套实现统一改为蓝牙类型 + 产品名 + 保留地址后缀的哈希标识。输入端与通信端均检查碰撞；同名且同后缀的不可区分设备标为歧义，不猜测设备。新增 2 项回归，覆盖掩码/完整地址一致性及通信端碰撞。其他物理来源定位规则保持一致。

修复前已验证耳机能提供实际输入：用户配合讲话的约 20 秒测试持续收到 201 个音频帧，原始峰值由低背景水平升至 28,252，非零采样 70,456、RMS 约 913；没有记录可回放的音频。修复后从蓝牙权限未授权状态重新开始，机身 → 耳机 → 机身切换、应用事件、队列收尾、通信模式/路由释放和设置页本地测试均通过。该轮共 176,000 字节 PCM，蓝牙阶段至少 25 帧，含两次切换的最大帧间隔约 1493ms；切换确实存在间隔，不能描述为无缝。停止后来源/常亮设置恢复原值。

两套目录各 **core 131 + app 115，共 246 项测试**通过，无失败/错误/跳过；Debug、androidTest、Lint 和开源 Release 构建通过，发布凭证扫描通过。生产修复和测试保持同步。`audio-input` 与 `audio-input-signal` 仅存在于显式安装的 androidTest 包，不在正式 APK 中。测试仅使用真实本地 AudioRecord 和统计量，没有读取模型凭证、调用模型、保存音频、检验转写准确率或删除用户会议。USB/UAC、采集卡音频、BLE Audio、其他耳机、实体断连及长时稳定性仍未验收。

测试结束卸载临时测试 APK，保留修复版 App 和原有会议/凭证；恢复 `screen_off_timeout=30000` 并关闭开发者 `stay_on_while_plugged_in=0`。最终系统回读与麦克风释放检查另见下述完成记录。

完成回读：息屏超时 30000、充电常亮 0、mStayOn=false、mWakefulness=Dozing；麦克风 AppOps 无 running 标记，通信模式恢复正常，SCO 未被保留。手机已正常息屏。


### 2026-10-27 · 工具栏自动收起与右侧图标调整（电脑回归）

排查确认旧 `canHideBars` 对 CONNECTING / ERROR 永久返回 false，因此恢复到中断错误等状态时上下工具栏不会自动收起；同时缺少窗口焦点恢复处理，系统窗口吞掉 UP 时可能残留 touching。修复为状态行始终显示状态/错误，连接与错误也遵守闲置收起；离焦取消计时并清理未结束触摸，回焦重新安排 4 秒计时。对话框、选字和触摸探索仍暂停收起。

新增「中断会议冷恢复后收起」与「焦点返回清理未完成触摸并重计时」两个回归用例，修复前各自失败、修复后通过；更新既有错误场景检查，验证短暂展开后收起而错误状态保持可见。两套目录各 **core 131 + app 117，共 248 项测试**通过，无失败/错误/跳过。Debug / androidTest 构建、Lint、公开版 Release 及发布凭证扫描通过。未调用真实模型。

灯泡和输入来源按钮使用透明底图标，保留按下反馈及 48dp 触摸范围；输入来源位于灯泡右边、标题栏最右侧。已检查英文竖屏截图，现有横屏与折叠比例回归通过；两套生产代码及单元测试一致，仅保留原有公开版通用地址示例。

手机 ADB 可连接但检查时为 Dozing，未唤醒或改变系统设置。新版 APK 已生成，等待用户解锁后安装及真机工具栏回归；此记录不将尚未执行的手机检查计为通过。


### 2026-10-27 · 工具栏原生窗口真机回归通过

已覆盖安装修复版，并用 `toolbar-idle` 临时 androidTest 模式检查：空闲、中断恢复 ERROR、模拟 CONNECTING / LISTENING 的上下栏自动收起；状态行持续显示；轻点唤回及再次闲置收起；未完成 DOWN 手势后，真实 Dialog 取得/归还窗口焦点，关闭后不再点击仍能恢复收起。原生检查全部通过，同时确认灯泡/输入按钮仅有透明按下反馈、输入来源位于最右侧。人工核对外屏竖屏的展开和收起截图，内容区扩展正常。

测试仅创建内存会议与本地模拟 SpeechProvider / AudioSource，没有声音采集、模型请求、凭证读取或剪贴板改写；原会议管理器恢复，用户会议与配置保留。测试入口及实现同步到两套目录，androidTest 构建及 Lint 均通过；生产代码未再改变，电脑回归仍为两套各 248 项通过。

测试结束删除手机缓存与电脑上的两张临时截图，卸载测试 APK，保留新版应用。恢复 30 秒息屏、关闭开发者充电常亮；最终系统回读见完成记录。

完成回读：screen_off_timeout=30000、stay_on_while_plugged_in=0、mStayOn=false、mWakefulness=Dozing，麦克风 AppOps 无 running 标记。手机已正常息屏。


### 2026-10-28 · 输入页面视觉统一与图像模型设置归位

两套目录完成六项调整：左右侧栏和输入设置共用胶囊按钮；视频启停为原生 Switch；音视频来源统一为带选中背景/品牌色圆环的原生 RadioGroup / RadioButton 行；图像服务地址/模型/密钥/Qwen 格式移到主设置的模型连接；设置返回无常驻背景；两类刷新改为状态行右侧透明图标，各有独立中英文无障碍名称。右侧栏和设置页保持整体滚动，最小 48dp 交互范围，保留默认键盘/无障碍的 checkable 语义。

图像模型字段复用既有配置，不需要迁移或重新输入密钥。`saveModels` 在一个偏好提交中保存语音/文字/图像设置及各自加密的新密钥；空白保留原密文，非法配置不部分保存，失败恢复内存配置，未保存更改仍能丢弃。保留旧的三参数接口及图像单独保存入口，供既有测试/工具使用。音源切换确认规则未变。

两套各 **core 131 + app 121，共 252 项测试**通过，无失败/错误/跳过；两套 Debug、androidTest 和 Lint、开源 Release 及发布凭证扫描通过。新增 4 项功能回归覆盖图像服务的新入口/旧入口移除、空白密钥保持、来源与裁剪保留、非法地址不部分保存、未保存丢弃、关闭视频时选择/刷新不打开设备；扩展音频用例验证刷新保留单选状态及取消切换保持原选项。人工检查右栏、音视频设置、模型配置及现有横屏/折叠/英文截图。无新增依赖和 Lint 告警，仅保留原有清单移除项/工具链提示。

本轮测试均为 mock 或本地 UI/存储检查，没有使用真实模型或修改用户手机设置。检查时手机 ADB 仍连接、系统为 Dozing 且 mStayOn=false；新版 APK 已生成，等待用户解锁后安装并做真机界面验证，不能将该阶段记为已经通过。


### 2026-10-28 · 输入控件原生真机验收

已在 Android 17 折叠屏手机外屏覆盖安装新版，人工检查右侧栏、图像输入、音频输入和模型连接页面。真机发现系统会将 Switch 的滑块拉伸为椭圆，两套共享控件补充垂直内边距，保持圆形滑块；电脑 UI 回归重新执行仍为两套各 core 131 + app 121，共 252 项通过。Debug、androidTest、Lint 与开源 Release 构建通过，发布凭证扫描通过。

新增两套共用的显式 `input-style` androidTest 模式：用独立临时偏好、内存会议及禁止打开的模拟音视频来源检查原生 Switch / RadioGroup、单选互斥、刷新保留选择、透明刷新/返回按钮、设置页返回导航及图像服务归位。使用合成测试密钥完成实际 Android Keystore 加密和解密，验证模型保存时留空的三类密钥保持原密文，来源与裁剪不丢失；结束后逐项比较生产偏好完全未改变，清空测试偏好并恢复原控制器与会议管理器。没有读取用户模型密钥、采集音视频或请求模型。原生 Dialog 关闭回调异步到达，测试在下一步前等待主队列，截图额外等待 surface 提交。

真机截图确认胶囊按钮、品牌色单选项、圆形开关滑块、状态行右侧无常驻背景的刷新图标正常。USB 项来自模拟设备，本轮不计为 USB 硬件或真实模型功能验收。


`toolbar-idle` 原生回归再次通过，覆盖空闲/中断恢复/模拟连接和聆听状态、轻触唤回、Dialog 焦点归还后的上下工具栏收起；没有真实采音或模型调用。测试结束删除临时截图和独立测试偏好，卸载 androidTest 包，保留新版 App 及原有用户数据。最终回读：screen_off_timeout=30000、stay_on_while_plugged_in=0、mStayOn=false、mWakefulness=Dozing，麦克风 AppOps 无 running 标记。手机已正常息屏。


### 2026-10-28 · 议题集与议题

设计落于 [TOPIC_COLLECTIONS.md](TOPIC_COLLECTIONS.md)。两套代码将界面会议改称议题，新增单层目录、默认集兜底、日期分组/展开持久化、仅选中目录/议题的三点、顶部唯一新建议题、目录/议题重命名、移动、删除以及移动全部后删除。默认集不可删除，可重命名/清空，永远排在日期分组后。切换目录可恢复上次议题或准备页；议题快捷切换不跨集，新增目录上下切换默认未绑定。原有绑定动作ID保持兼容。

TopicCatalog通过独立AAD的AES-GCM加密保存；SQLite schema 2兼容schema 1，议题payload 3兼容1/2。初次迁移在事务中创建默认集与补充归属，保留原ID、原文/OCR/答案/偏好/阅读位置及选择；无模型请求。目录缺失但存在带非默认归属的v3记录时拒绝覆盖，保留原数据。数据库故障注入确认迁移/移动/目录与选择一起回滚，解除故障后可原样重试。

结构操作在串行worker写盘时释放管理器锁，提交后只修改内存归属，保留写入期间到达的新快照。慢Store测试在事务阻塞期间继续推送定稿和PCM，验证仍可收发、移动前后engine身份不变、最终磁盘原文与归属正确。删除先取消并拒绝迟到输出，删除失败保留记录。恢复已完成的待显示答案不增加议题/目录修改时间。

两套各 **core 146 + app 133，共279项**电脑测试通过，无失败/错误/跳过；Debug、androidTest、Lint和开源Release构建通过，开源凭证扫描通过。新增27项测试覆盖默认归属、旧数据迁移/重启、选择与展开、移动/合并、清空/删除及迟到回调、写入失败与音频并行、目录时间与DST/月份、集内/跨集键盘范围、目录菜单和单一新建入口。人工检查电脑生成的中文竖屏/日期分组/横屏及英文折叠比例截图。Lint无新增告警，仅保留原有清单/工具链提示。

本轮全部使用mock、合成数据及本地存储，没有真实收音/模型请求、读取用户密钥、安装手机或改变手机息屏设置。手机升级后的真实Keystore目录恢复、真机触控及蓝牙新导航尚待验证，不记为已通过。


### 2026-10-28 · 议题集真机升级与原生流程验收

在 Android 17 折叠屏手机外屏完成覆盖安装。`topic-migration-baseline` 在旧 APK 中把记录 ID / 选中项与规范化内容的 SHA-256 摘要留在手机私有缓存；`topic-migration-verify` 在新 APK 中排除版本号与新增 collectionId 后逐条比较。原有 **4 条记录**、其他记录字段及选中项全部匹配，载荷已为 v3，均归默认集；比较不导出原文、凭证、数据库或摘要文件，成功后删除私有缓存。该两阶段入口仅供从无目录旧版升级时使用，不是已有多目录版本的通用审计。

新增显式 `topic-collections` androidTest 模式，使用独立临时 SQLite / 设置、合成文本以及禁止调用真实音视频和模型的 factory：真实 Keystore 与 SQLite v1→v2 / payload v2→v3迁移、内容/阅读位置保持、目录新建与唯一新建入口、仅选中项三点、箭头不导航、F5新建议题、F6/F7集内边界、F8/F9跨集恢复、折叠/选中/记忆重启恢复、目录密文、菜单移动保留engine、空/非空目录删除、回默认与默认不可删除全部通过。临时数据库/设置清理，生产设置逐项比较保持一致；人工检查两张原生截图。

既有 `meetings` 原生回归通过，覆盖模拟多轮、答案阅读位置、SQLite重启、任务中断和最后一条记录删除；`toolbar-idle` 通过，覆盖空闲/模拟连接与聆听、状态常显、轻点唤回以及Dialog归还焦点后的自动收起。没有真实收音、摄像头抓拍或模型请求。快捷键由 Android 原生 KeyEvent 注入验证，本轮尚未有人配合实体蓝牙键盘，不能计为无线硬件链路验收。

两套原生测试入口/实现同步，androidTest构建与Lint通过，公开源码凭证扫描通过。生产行为本轮未修改，电脑回归仍是两套各279项。测试包、隔离数据库/偏好及手机/电脑临时截图已清理，保留新版App与用户数据；恢复30秒息屏并关闭开发者充电常亮，最终回读见下方。

完成回读：screen_off_timeout=30000、stay_on_while_plugged_in=0、mStayOn=false、mWakefulness=Dozing；麦克风AppOps无running标记，手机已正常息屏。


### 2026-10-28 · 设置折叠、电平条与系统栏底色（电脑验证）

页面、左右抽屉、音视频输入设置及主题窗口统一黑色背景，与黑色手势区衔接。电平条使用共享分层 drawable，未填充轨道与页面同色，已填充部分保留品牌色。主设置八个正文分区及模型内的图像识别服务增加整行标题折叠；首次收起，本机记录展开状态。折叠保留同一表单及未保存输入，离开时的丢弃确认不变，提供双语无障碍展开/折叠动作。

新增六项 Robolectric 用例：初始折叠与无障碍动作、折叠保留未保存模型输入及丢弃保护、设置往返和 Activity 重建保留展开状态、电平条绘制像素、左右抽屉与音频设置背景一致、英文横屏标题。更新原有答案背景断言和设置表单入口，检查横竖屏设置与输入界面预览。两套各 **core 146 + app 139，共 285 项测试**通过，无失败/错误/跳过；Debug、androidTest、Lint 与开源 Release 构建通过，开源发布凭证扫描通过。没有真实音视频采集、模型调用或新增依赖。

`input-style` 原生模式补充黑色抽屉/输入窗口、两处电平条底色、首次折叠与点击展开、折叠保留未保存字段的检查；截图仅使用合成来源和隔离配置，并在 finally 恢复 UI 展开偏好。键盘原生测试显式展开其设置分区并恢复展开状态。本阶段未连接手机，新增真机检查尚未执行，手势区实际观感待验收。


### 2026-10-28 · 设置折叠与黑色底面真机验收

在 Android 17 折叠屏手机外屏覆盖安装，`input-style` 在竖屏及临时横屏均通过：原生分区首次折叠、点击展开/收起保留未保存字段、模型保存/留空密钥保留、左右抽屉及输入设置的黑色背景、电平条轨道颜色。人工检查模拟来源的右侧栏、音视频设置、折叠设置、展开表单及左侧栏截图，黑色页面与手势区衔接正常；横屏正文仍可滚动。方向设置恢复测试前的自动旋转开启及 user_rotation=0。大内屏本轮未物理展开重测。

真机截图发现音频设置页返回图标未正常显示；音视频设置的返回按钮统一改用 `UiControls.icon`，采用明确的图标内边距、48dp触摸区域和透明按下反馈。两套代码同步后，各139项app测试重跑通过，core沿用同版146项结果；两套Debug/androidTest/Lint及开源Release通过。重新覆盖安装后 `input-style` 再次通过，截图确认返回图标正常且无常驻底板。

`toolbar-idle` 原生窗口回归通过：空闲/中断恢复/模拟连接和聆听、轻触唤回、Dialog焦点归还后的自动收起及透明图标保持正常。检查使用临时模拟音视频来源、内存议题和隔离偏好，不采集音视频、不调用模型、不导出用户记录；测试结束恢复生产配置与UI展开状态。已删除设备上的本轮截图及测试偏好，卸载androidTest包，保留新版App和原用户数据。熄屏设置及最终回读见下方完成记录。

完成回读：screen_off_timeout=30000、stay_on_while_plugged_in=0、mStayOn=false、mWakefulness=Dozing、mHoldScreenWindow=null；麦克风与摄像头 AppOps 均无 running 标记。手机已正常自动息屏。


### 2026-10-28 · 系统明暗与动态配色

设计见 [THEMING.md](THEMING.md)。两套目录引入 Material Components 1.13.0 的 Material 3 DayNight / DynamicColors，保留原生 Activity/View 和既有布局；颜色通过语义角色及视图样式绑定统一管理。背景与文字分别绑定，避免状态文字更新覆盖按钮背景刷新。Auto 使用第三强调容器及勾标记，灯泡为开启主色实心/关闭空心；Markdown代码块、侧栏、来源选择、输入框、原生弹窗和系统栏同步适配。相机原图不改色，裁剪框采用明暗双层边缘。

新增9项Robolectric回归：明暗切换不重建视图及Auto状态、编辑中的模型字段/焦点/选区保留、其他配置重建时模型草稿仅在内存恢复、已打开抽屉/原生危险操作弹窗保持、Markdown span原地变色及答案阅读位置、Mock听写和待完成OCR跨主题继续且分析不重复、Android 8备用配色、两套不同Material调色板、Android 12读取实际系统accent色阶。测试检查配套文字/背景对比度及实际原文按钮底色；修复测试发现的主题缓存与旧按钮底色问题。检查浅深主界面、既有横竖/折叠比例及输入页面电脑预览。

两套各 **core 146 + app 148，共294项Android测试**通过，无失败/错误/跳过；Debug、androidTest、Lint与开源Release构建通过。开源发布扫描及7项扫描器回归通过。新增公开AndroidX上游许可链接加入精确主机允许清单，原有凭证/私人URL检查保留；实际依赖与Apache许可归属更新到THIRD_PARTY和APK内NOTICE，应用原创代码仍为MIT。Lint为0错误；保留既有外部存储清单移除提示及开发工具链/依赖升级提示，继续使用兼容现有工具链的Material 1.13.0。

Android 17折叠屏手机外屏实际运行新增 `theme` 模式通过：使用隔离设置、内存议题和禁止打开的模拟音视频来源，临时切换系统深/浅模式，验证Activity和字段身份、未保存合成密钥、焦点、选区、设置展开状态、窗口底色、动态主色角色与系统栏图标。截图确认深/浅文字和输入框对比清楚，手势区随主题衔接；系统当前动态配色为中性色系。本轮没有改变壁纸或手动选择另一套真实系统调色板，大内屏未物理展开重测；替代调色板及大屏比例由电脑回归覆盖。

`theme`复用`input-style`的单选/刷新/返回/Keystore合成凭证/留空保持及无采集检查，并在finally恢复生产配置与UI展开状态。`toolbar-idle`原生自动收起、触摸唤回和弹窗焦点归还回归再次通过。实际音频连续性和OCR分析连续性由前述Mock用例验证，本轮不采集真实音视频、不调用模型、不读取用户模型密钥或导出用户记录。

设备原系统主题MODE_NIGHT_YES已恢复并通过shell回读确认；删除本轮设备截图和隔离测试偏好，卸载androidTest包，保留新版应用和用户数据。充电不休眠与最终息屏回读见下方完成记录。

复用（Android 12+、应用采集空闲、手机已解锁；会临时修改全局明暗模式并在finally恢复）：

```sh
adb shell am instrument -w -r -e mode theme io.github.meetingassistant.test/io.github.meetingassistant.android.SmokeInstrumentation
```

完成回读：系统 Night mode=yes（原模式2）、screen_off_timeout=30000、stay_on_while_plugged_in=0、mStayOn=false、mWakefulness=Dozing、mHoldScreenWindow=null；麦克风和摄像头 AppOps 均无 running 标记。手机已正常自动息屏。


### 2026-10-28 · 独立 GitHub 仓库迁移验证

开源代码迁入已有独立 Git 仓库，保留目标仓库的 Git 历史与原 MIT 许可，合并 Android/敏感数据忽略规则，替换初始 README 为完整英文与中文说明。设计文档改用开发目录/开源仓库的通用表述，不依赖旧目录名或兄弟目录。

从新检出目录使用其 Gradle wrapper 和独立项目缓存重新执行全部123个构建任务：core 146 + app 148，共294项测试通过，无失败/错误/跳过；Debug、androidTest、Release和Lint通过。构建产物、项目缓存和本机SDK配置均留在仓库外；开源扫描器7项回归和发布扫描通过，git diff --check通过，wrapper执行权限及Git可见性保留。逐文件校验导入内容后移除旧开源副本。此次迁移未提交或推送Git更改，也未调用模型或操作手机。
