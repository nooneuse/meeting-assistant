# 系统明暗模式与动态配色

## 目标与范围

默认全自动跟随系统明暗模式；支持动态配色的 Android 12+ 设备使用系统壁纸/配色调色板，其他设备使用本地浅色/深色备用配色。保留 Java/View、胶囊按钮、透明工具图标、独立滚动和自适应布局，不增加主界面主题按钮。开发版和开源版同步。

## 颜色角色

| 用途 | Material 3 角色 |
| --- | --- |
| 页面、左右抽屉、设置及手势区后方 | surface / onSurface |
| 普通按钮、输入框、原文与代码块容器 | surfaceContainer / onSurface |
| 次要说明 | onSurfaceVariant |
| 主要操作、电平有效部分、开启的开关 | primary / onPrimary |
| 已选议题/目录/来源 | secondaryContainer / onSecondaryContainer |
| Auto 开启 | tertiaryContainer / onTertiaryContainer，附加勾标记 |
| Auto 关闭 | 普通容器，不显示勾标记 |
| 错误、危险操作 | error / onError 或 errorContainer / onErrorContainer |
| 边界、分隔线 | outlineVariant |

背景与前景始终成对使用；禁用状态和按下反馈由当前主题推导。Auto 不再固定红色，错误和删除保留错误语义色。灯泡开启使用主色实心，关闭使用正文色轮廓；无障碍状态和切换提示保持现有行为。

## 覆盖面

主题资源、主界面、左右抽屉、折叠设置、音视频输入、上下文预算、偏好/绑定/管理弹窗、输入框及提示文字、选中状态、图标、电平条、Markdown代码块、原生对话框和状态/导航栏全部适配。电平未填充部分与页面同色。状态/手势图标按背景明暗切换，窗口底色统一并保留系统边距。视频/相机原始图像不改色，预览空区允许黑色；裁剪框使用带明暗双层边缘的覆盖线。

## 生命周期与状态

统一颜色入口解析 Material 主题属性；视图只绑定语义角色，不保存具体系统色值。uiMode 变化原地刷新现有视图、已打开窗口与 drawable，不重建业务控制器或表单，不启动/停止录音、不重复模型请求、不取消 OCR。回到前台时重新检查系统调色板。系统因其他配置重建 Activity 时继续使用既有 Application 会话；模型表单草稿仅通过内存中的非配置实例保留，密钥不写 Bundle 或磁盘。

主题切换保留议题/偏好、答案与原文、阅读位置、显隐状态、折叠设置、当前输入及选择。刷新仅更改样式，不发送数据、不保存模型配置。真正切入后台仍遵守现有停止前台采集规则；用户到系统设置修改主题造成的后台停止不冒充无缝采集。

## 实现路径

使用 Material Components 的 Material 3 DayNight 主题与 DynamicColors；保留原生 Activity/View。统一 AppTheme 管理角色解析、弱引用窗口跟踪与颜色刷新，UiControls 复用主题样式；去除业务视图内的固定可见色值。备用配色通过 values / values-night 定义；语言包装只覆盖 locale，不冻结 uiMode。

## 验证

电脑覆盖浅色/深色、至少两套替代调色板、Android 8备用主题、文字/按钮/选中项的成对对比度、系统栏图标、已打开抽屉/弹窗、Markdown颜色与横竖/折叠比例。Mock会话在切换主题时继续输出PCM/定稿/答案，确认采集身份和请求次数不变；模拟待完成OCR回调仍归属同一议题。检查未保存模型输入、焦点、阅读位置及折叠状态不变。真机后续确认系统动态配色和实际明暗切换；测试不调用模型。

## 官方依据

- [Dynamic Colors for Views](https://developer.android.com/develop/ui/views/theming/dynamic-colors)
- [Dark theme](https://developer.android.com/develop/ui/views/theming/darktheme)
- [Material color roles](https://github.com/material-components/material-components-android/blob/master/docs/theming/Color.md)
- [Edge-to-edge](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
