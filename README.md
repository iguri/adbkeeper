# 无线调试助手（adbkeeper）

会话式开启 Android 无线调试的小工具：点一下临时打开「开发者选项 + 无线调试 + adb 授权不过期」，退出应用或划掉后台卡片即自动还原。不常驻、不申请开机自启。

## 功能

- **一键开启 / 结束还原**：开始会话时记录三项设置的原始值，结束或进程被回收后写回
- **三项状态与端口**：读取 `development_settings_enabled`、`adb_wifi_enabled`、`adb_allowed_connection_time`，以及无线调试实际端口
- **连接命令**：显示 IPv4 / IPv6 的 `adb connect` 命令，一键复制
- **自定义端口**：会话期间在手机本地做 TCP 中继，把固定端口转发到 adbd 随机端口
- **通知推送**：企业微信 / 钉钉 / 飞书 / Bark，开启成功后推送连接信息，失败自动重试
- **会话超时**：默认 120 分钟自动还原，可在设置里调整
- **自更新**：读取 GitHub Releases，可下载并调起安装

## 权限

唯一需要的特殊权限是 `WRITE_SECURE_SETTINGS`，必须通过 adb 一次性授予：

```bash
adb shell pm grant com.adbkeeper android.permission.WRITE_SECURE_SETTINGS
```

ColorOS 16 起中文界面会隐藏「禁止权限监控」，需把系统语言临时切成 English，在开发者选项里打开 `Disable system optimization`（旧版叫 `Disable permission monitoring`）后再执行上面的命令。

应用内「手动开启步骤」页有完整图文流程，并可直接跳转开发者选项 / 语言设置。

## 构建

```bash
./gradlew assembleStableRelease   # 稳定版 com.adbkeeper
./gradlew assembleDevRelease      # 开发版 com.adbkeeper.dev（保留完整文件日志）
```

要求 JDK 17、Android SDK 35。

## 说明

- 无线调试端口由 adbd 随机分配，普通应用无法指定，也不应写 `service.adb.tls.port`；自定义端口通过本机中继实现。
- 卸载由 adb 授予的权限：`adb uninstall com.adbkeeper`。
