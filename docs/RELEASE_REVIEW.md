# Public source review

Scope: the initial 0.1.0 public source tree. This is a scoped engineering review and publication preparation, not an independent security audit or a guarantee of model accuracy.

## Review and changes

| Area | Review outcome / action |
| --- | --- |
| Publication boundary | Created a new tree from an explicit file allowlist, without Git history, local settings, device exports, generated files, or development transcripts. |
| Diagnostic surface | Removed live-model runners, private development scripts, configuration injection, loopback credential transport, network/storage diagnostic modes, and their unused source-set configuration. Only synthetic/basic instrumentation remains. |
| Provider configuration | Production URLs start empty; settings use descriptive input hints rather than endpoint examples. Keys remain device-supplied, not embedded. Private account/workspace values are absent. |
| Runtime flow | Reviewed speech and text lifecycles, callback generations, tail finalization, background cancellation, preference snapshots, and per-meeting ownership. Existing regression coverage remains. No new blocking runtime issue was identified in this scope. |
| Persistence / errors | Reviewed Keystore separation, randomized AES-GCM envelopes with meeting-ID authentication, deletion transactions and tombstones, and safe provider error presentation. Documentation accurately distinguishes encrypted keys/meetings from ordinary app-private settings. |
| Build provenance | Retained standard Gradle wrapper and pinned distribution checksum. Verified the wrapper JAR against the official Gradle 8.11.1 checksum: `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`. No local SDK/toolchain helper is required. |
| Licenses | Original project remains MIT. Added full runtime license assets and attribution, including MPL-2.0 for OkHttp's Public Suffix List data and notices for transitive libraries. |
| Meeting shortcuts | Added opt-in new/previous/next meeting and transcript-toggle bindings to both source trees; tested confirmation, tail retention, repeat guards, boundaries, modal isolation and hidden/foldable layouts. |
| Release checking | Added a strict publication-tree audit and six regression tests; retained no-matched-value logging. Public README, contribution, security and testing docs contain no private development history. |

Public build work used external cache/output locations so publication files remained source-only. The original development directory was left intact; future changes must be deliberately synchronized into the public source tree, not copied wholesale from development artifacts.

## Validation

- Core JVM: **69 passed**.
- Android UI/localization/storage (Robolectric): **53 passed**.
- Publication audit regression checks: **6 passed**.
- Debug app, androidTest APK and unsigned Release APK: built successfully.
- Debug Lint: **0 errors, 0 warnings**.
- Strict source/publication audit, local Markdown target checks, and APK license/production-entrypoint inspection: passed.
- No real microphone, paid model requests, or device configuration changes were used for this packaging pass. Subsequent external-keyboard checks are documented in [testing](TESTING.md).

## Remaining limits

- MainActivity still combines screen composition and interaction logic in a large class. Extracting view/controller components is a future maintenance improvement; this pass avoids changing established recording/navigation behavior through a broad refactor.
- Original core statuses use fixed internal messages with a localized presentation catalog. Changes to core messages require synchronized catalog/resource tests.
- Provider/model behavior, ASR accuracy, and physical accessory compatibility require real-world validation; a successful build and protocol test cannot establish them.
- The source scanner is heuristic. It uses an explicit URL allowlist, rejects publication artifacts and familiar token formats, and cannot identify every possible future secret format. Manually review each export and do not attach real logs or meeting captures.
- The release APK is intentionally unsigned. Signing and publishing are maintainer operations; no signing material or repository/account identity is supplied.

## 中文说明

此次审查重点覆盖录音 / 分析并行、任务取消、迟到回调、会议隔离、持久化与删除、错误脱敏、凭据和发布边界。未发现新的阻断性运行问题；整理中移除了开发诊断、配置注入和真实模型测试入口，补全第三方许可、发布扫描和中英文文档。

最终通过 122 项 Java 测试和 6 项发布检查测试，以及 Debug / 未签名 Release 构建和 Lint。本次不构成独立安全审计，没有重新做真实拾音或模型效果验收；后续实体键盘检查见 [测试说明](TESTING.md)。界面主类较大、内部状态目录维护、实际硬件与模型效果仍是明确的后续维护事项。原开发目录保留，新目录不继承其历史和本机产物。


## External-keyboard follow-up

The Android app's basic UI/storage/clipboard checks passed on-device. Human-operated K380 events passed 27 keyboard checks; drawer isolation passed separately with injected input and remains unconfirmed as a physical-key check. Only androidTest helpers and documentation changed during this follow-up. Synthetic fixtures preserve original settings/meetings and do not open the microphone or make model requests. Both test trees compile and pass Lint; publication scanning remains required. See [detailed test scope](TESTING.md#external-keyboard-device-validation).


## Image input / OCR follow-up

Added the image feature to both trees: independent source/recognition lifecycles, right drawer, preview/crop settings, opt-in capture/video bindings, bounded OCR work, Qwen3.5 rotated-box and structured-vision adapters, evidence-aware text prompts, and version-2 encrypted meeting payloads compatible with version 1. Current validation is **102 core + 69 Android = 171 Java tests**, plus **7 publication-audit tests**; Debug/androidTest/unsigned Release and Lint pass. See the current [test scope](TESTING.md#image-input--ocr-computer-validation).

The original project license remains MIT; the new UVC/native dependencies keep their own licenses, including LGPL-2.1-or-later for dynamically loaded libusb. Full notices, upstream tag/commit and rebuilding/replacement information are bundled in APK assets. Unused storage/foreground-service permissions and helper transitive dependencies are excluded. Arm64 ELF alignment was checked at 16 KiB.

The public tree contains no live-image test or credential-injection entry point. Separate authorized live checks used a synthetic UI image, not meeting data, and stored no credentials/private endpoints in either project. Qwen UI roles are inferred by the text model, not asserted as visual control detections. Real camera, UVC and Bluetooth-capture validation remains pending; there was no phone operation in this computer-only pass.
