# About / 关于

Settings ends with a collapsible **About** section, using the same remembered
expanded state, system theme and English/Simplified Chinese behavior as other
settings sections.

It shows the localized app name, installed build version, MIT license identifier
and public project address: [meeting-assistant](https://github.com/nooneuse/meeting-assistant).
The project address opens the system browser only when tapped. Viewing About
does not request a model or make a network call.

The version format is `0.1.0+YYYYMMDD`, for example `0.1.0+20261028`. Gradle stamps
the build machine's local calendar date into `versionName` during the build;
About reads the generated `BuildConfig.VERSION_NAME`. The Android package and
About therefore share a version, and the date does not change when the app is
opened or the phone's date changes. This date suffix is metadata, not a separate
`versionCode`; published upgrades must still increment `versionCode` as needed.

中文：从「设置 → 关于」查看软件名称、版本号、MIT 许可和开源项目地址。
默认折叠并记住展开状态；版本使用「版本 + 编译日期」，日期以编译机器的
本地日期为准。点击项目地址才打开浏览器，不调用模型。
