# Third-party notices / 第三方声明

The [MIT license](LICENSE) covers original Meeting Assistant code. Dependencies and data retain their own copyrights and licenses. Their names identify upstream projects; they do not imply endorsement. No speech or text model weights are included.

## Runtime

Versions below were resolved from `debugRuntimeClasspath` for this source version; recheck them when changing dependencies.

| Component | Version | License | Upstream |
| --- | --- | --- | --- |
| OkHttp | 4.12.0 | Apache-2.0 | [source](https://github.com/square/okhttp/tree/parent-4.12.0) |
| Okio / Okio JVM | 3.6.0 | Apache-2.0 | [source](https://github.com/square/okio/tree/parent-3.6.0) |
| Kotlin stdlib / common / jdk7 / jdk8 | 1.9.10 | Apache-2.0 | [source](https://github.com/JetBrains/kotlin/tree/v1.9.10) |
| JetBrains annotations | 13.0 | Apache-2.0 | [source](https://github.com/JetBrains/java-annotations) |
| Gson | 2.11.0 | Apache-2.0 | [source](https://github.com/google/gson/tree/gson-parent-2.11.0) |
| Error Prone annotations | 2.27.0 | Apache-2.0 | [source](https://github.com/google/error-prone/tree/v2.27.0) |
| Public Suffix List data bundled with OkHttp | bundled with OkHttp 4.12.0 | MPL-2.0 | [pinned data](https://github.com/square/okhttp/tree/parent-4.12.0/okhttp/src/main/resources/okhttp3/internal/publicsuffix), [upstream list](https://publicsuffix.org/list/public_suffix_list.dat) |

Full [Apache-2.0](app/src/main/assets/licenses/Apache-2.0.txt), [MPL-2.0](app/src/main/assets/licenses/MPL-2.0.txt), project MIT, [runtime attribution](app/src/main/assets/licenses/NOTICE.txt), and the unmodified [OkHttp data notice](app/src/main/assets/licenses/OkHttp-Public-Suffix-NOTICE.txt) are bundled in `assets/licenses/` inside APKs. Distribute those notices with binaries. MPL-2.0 applies to the upstream data, not to all original application code; the corresponding pinned data source remains available at the linked upstream release.

## Build and test only

- The Gradle 8.11.1 wrapper is included under Apache-2.0; its original script headers are retained. The shared [Apache-2.0 text](app/src/main/assets/licenses/Apache-2.0.txt) accompanies the source tree. The downloaded Gradle distribution and Android tools retain their own notices and are not part of this repository.
- JUnit 4.13.2 (EPL-1.0), MockWebServer 4.12.0 (Apache-2.0), and Robolectric 4.16.1 (MIT) are test dependencies. They are downloaded by the build and do not ship in the application APK. Android test code uses the platform instrumentation API.

Inspect the runtime dependency graph with:

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

## 中文说明

MIT 仅覆盖项目原创代码，依赖仍遵循各自许可证。除主要 Apache-2.0 库外，OkHttp 内附的公共后缀数据使用 MPL-2.0，已保留原始声明、完整许可与对应版本的数据来源链接。APK 在 `assets/licenses/` 中携带运行时许可及声明。构建 / 测试工具不随应用 APK 分发；依赖升级后应重新核对实际版本、许可和声明。云模型服务的授权与费用由用户与服务商自行约定。

## USB video input / USB 视频输入

The application uses `com.herohan:UVCAndroid:1.0.13` without transitive recording/UI dependencies, through its low-level USB/UVC video API. Its official [source tag](https://github.com/shiyinghan/UVCAndroid/tree/1.0.13) is commit `6018c7f20238832929cf61427427bb7828056260`. The library includes native components with separate licenses; these do not replace the MIT license of our original Java code.

| Component | License / notice bundled in APK |
| --- | --- |
| UVCAndroid / UVCCamera Java and bridge | [Apache-2.0](app/src/main/assets/licenses/UVCAndroid-Apache-2.0.txt) |
| libusb | [LGPL-2.1-or-later](app/src/main/assets/licenses/libusb-LGPL-2.1.txt) |
| libuvc | [BSD-3-Clause](app/src/main/assets/licenses/libuvc-BSD-3-Clause.txt) |
| libyuv | [BSD-style license](app/src/main/assets/licenses/libyuv-LICENSE.txt), [PATENTS](app/src/main/assets/licenses/libyuv-PATENTS.txt), [upstream AUTHORS](app/src/main/assets/licenses/libyuv-AUTHORS.txt) |
| libjpeg-turbo | [IJG/BSD/zlib notices](app/src/main/assets/licenses/libjpeg-turbo-LICENSE.txt), [complete IJG README](app/src/main/assets/licenses/libjpeg-turbo-README.ijg) |
| RapidJSON | [MIT](app/src/main/assets/licenses/rapidjson-MIT.txt) |

This software is based in part on the work of the Independent JPEG Group. Read the [USB runtime notice](app/src/main/assets/licenses/UVCAndroid-NOTICE.txt) for corresponding native sources, build scripts, and replacement/rebuilding instructions. The LGPL component remains a separate shared library. Modified native distributions must provide their corresponding modified source and preserve required notices. The upstream AUTHOR and legal files contain third-party attribution, not user account or meeting information.

USB 原生制品保持上游内容，未在项目中复制二进制文件，构建时由 Maven Central 获取。已移除库附带的存储和前台服务权限，不使用录音/录像助手。原始 Java 代码继续使用 MIT，原生依赖保留上述各自许可证；其完整声明随 APK 分发。替换原生库后可自行重建并签名安装，不能将全部运行时组件笼统宣称为 MIT。


## System theme / Dynamic color

[Material Components for Android 1.13.0](https://github.com/material-components/material-components-android/tree/1.13.0) supplies Material 3 theme resources and dynamic color support (Google / Material Components contributors, Apache-2.0). Its AndroidX runtime dependencies retain their Apache-2.0 licenses. The complete shared license is bundled in `app/src/main/assets/licenses/Apache-2.0.txt`. This does not change the MIT license of original app code. [AndroidX sources and licenses](https://android.googlesource.com/platform/frameworks/support/).

Resolved runtime coordinates and attribution are included in the APK's `licenses/NOTICE.txt`. Use `:app:dependencies --configuration debugRuntimeClasspath` to inspect the dependency graph.
