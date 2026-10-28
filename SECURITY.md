# Security and privacy

## Data flow

The app sends microphone audio to the speech endpoint configured by the user. It sends finalized transcripts, a bounded transcript history, and saved answer preferences to the configured text endpoint. Captured image regions are sent as JPEG data URIs to the separately configured image service. OCR text, positions, and related transcripts go to the text service; recognized evidence is encrypted with its meeting. Raw images are temporary memory data, not gallery files or long-term records. There is no project-operated backend, telemetry, or cloud synchronization. Provider retention and billing policies remain outside the app's control.

Audio is never intentionally written to disk. API keys use Android Keystore-backed AES-GCM encryption. Meeting payloads use a separate Keystore key, randomized IVs, and authenticated meeting IDs. Provider URLs, model names, preferences, and keyboard bindings are ordinary app-private settings; do not treat them as an encrypted secret vault. Cloud backup and device transfer are disabled. On a rooted or compromised device, local app-private storage is not a sufficient security boundary.

Recording is foreground-only. Leaving the app cancels active speech capture; process recreation never resumes the microphone or model requests. Explicit copying places meeting content on the system clipboard. Screenshots are not blocked; be careful when sharing them.

## Network boundary

Production adapters accept HTTPS/WSS, use the platform TLS verifier, and reject URLs containing user information, queries, or fragments. Redirects and automatic transport retries are disabled. Error messages expose owned categories and HTTP status codes rather than provider response bodies. Model content is displayed as text, not executed. Prompt instructions reduce ambiguity but cannot guarantee factual accuracy or prevent every prompt-injection attempt.

## Keep private material out of source control

Do not commit API keys, tokens, personal URLs, workspace IDs, recordings, meeting exports, device identifiers, local SDK paths, signing files, or screenshots containing user data. Enter credentials only on the device. The public test suite requires no keys and includes no configuration listener or live-model test entry point.

```sh
python3 scripts/scan-secrets.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_scan_secrets.py
```

For a clean publication/export directory, also run:

```sh
python3 scripts/scan-secrets.py --release
```

The strict check rejects local/build artifacts, symlinks, unexpected binaries, common token formats, personal home paths, workspace identifiers, and URLs outside a small public/reserved-domain allowlist. The sole binary exception is the Gradle wrapper JAR. New legitimate public documentation hosts require an explicit allowlist update and review. A few complete upstream legal texts contain historical public references and contributor contacts; their exact reviewed hashes are pinned, and changed bytes are rejected. This exemption does not apply to application source or configuration. Production application/core sources also reject bundled WebSocket URLs and reserved-domain service examples; synthetic endpoints belong in tests. Scanning is heuristic and never guarantees that every possible credential format is absent. Review the full publication tree as well.

The source audit excludes `.git` and does not inspect commit author/committer names or email addresses. Review Git identity and published history separately; a project handle and GitHub noreply email can avoid exposing personal or employer contact details. Changing identity for future commits does not change existing history.

## Reporting vulnerabilities

If GitHub private vulnerability reporting is enabled for the eventual repository, use its Security tab. Otherwise request a private reporting channel without publishing exploit details or user data. Never include keys or real meeting content in a public issue. Revoke exposed provider credentials at the provider, then replace them on the device; removing a file from the latest source does not remove it from Git history.

## 中文说明

音频发送给用户配置的语音服务；抓拍区域发送给独立配置的图像服务；定稿原文、OCR 布局、有限历史及回答偏好发送给文字服务。原始图片仅短暂保留在内存，识别资料与会议一起加密保存。API Key 和会议载荷分别使用 Keystore 加密，普通设置仅保存在应用私有目录；没有后端、遥测、录音文件或云同步。剪贴板和截图可能包含会议内容。

源码与测试不需要真实凭证。上传前运行上述检查，并人工查看完整发布目录。发现泄露时先撤销服务商凭证，不能仅依赖删除本地文件。安全问题尽量通过仓库私密报告渠道反馈，不公开密钥、会议原文或利用细节。


Audio input selection can use built-in, USB or supported Bluetooth sources. Selecting a source alone does not record. The explicit local input test displays level only and sends nothing to a model. During normal listening, the selected source is verified before audio is sent to the configured speech service. Lost or mismatched routes stop capture; the app does not silently choose another microphone or resume after reconnection. Source preference stores a hashed device locator, not a raw hardware address. Bluetooth audio may request Nearby devices permission; keyboard shortcuts do not require it.
