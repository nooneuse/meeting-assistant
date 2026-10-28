# Contributing

[简体中文](#中文说明)

Thank you for contributing. Read [design](docs/DESIGN.md), [architecture](docs/ARCHITECTURE.md), and [testing](docs/TESTING.md) before changing audio capture, state transitions, or storage.

## Development checks

Use JDK 21 and Android SDK 36 / Build Tools 36.0.0, then run:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_scan_secrets.py
python3 scripts/scan-secrets.py
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Java source targets Java 17. Keep Java formatting consistent with the existing Google Java Format style. Prefer focused changes; do not combine a provider addition with a large UI rewrite.

## Pull requests

- Explain the user-visible problem, the resulting behavior, and the validation performed.
- Add meaningful regression coverage for state, protocol, or persistence changes. Use synthetic transcripts and local mock servers; never require a real API key in CI.
- For audio/lifecycle, hardware-key, or layout changes, include device/emulator results or explicitly say what was not tested. Sanitize any screenshots.
- New speech providers implement `SpeechProvider`; new text protocols implement `TextProvider`. Keep vendor parameters in adapters and declare unsupported capabilities instead of silently simulating them.
- Update both English and Simplified Chinese resources. Never translate or overwrite saved user content during a UI language change.
- Keep UI rendering separate from protocol parsing and storage. Never run a blocking model request on the audio callback or main thread.
- Do not commit user records, credentials, device IDs, personal endpoints, SDK paths, generated artifacts, or release signing files. For public exports, use `scan-secrets.py --release` on a clean directory.

Maintain separate private release-signing configuration; this repository does not configure or publish release credentials. Contributions are made under the project's [MIT license](LICENSE); third-party additions must retain compatible notices and license texts.

## 中文说明

请先阅读设计、架构和测试文档，再修改录音、状态机或存储。提交前执行以上检查，说明改动效果和验证范围；模型测试使用虚构输入与本地模拟服务，不需要真实密钥。

新增供应商遵循 `SpeechProvider` / `TextProvider` 边界；中英文资源同步维护，不翻译或覆盖用户保存的数据。不要提交真实会议内容、私人地址、设备信息、构建缓存或签名文件。涉及音频、生命周期、按键、布局的变更，应说明真机或模拟器结果及未测项目。贡献遵循 MIT，第三方代码保留各自许可与声明。
