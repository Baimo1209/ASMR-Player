# ASMR Player

ASMR Player 是一款安卓本地 ASMR 音声播放器，应用安装后显示名称为“白沫播放器”。它用于管理手机本地文件夹中的音频、封面图片和 WebVTT 同步台词，适合类似 `RJ01534688/1-普通版/MP3` 的多层音声作品目录。

项目只提供播放器本体，不包含任何音频、图片或台词资源。源码公开，发布包体积较小，适合个人本地侧载使用。

## 截图

<img width="1080" height="2400" alt="ASMR Player 作品列表" src="https://github.com/user-attachments/assets/aae1d6ff-f6f3-4185-afcc-a665689a18f0" />

<img width="1080" height="2400" alt="ASMR Player 播放页面" src="https://github.com/user-attachments/assets/5e1d4fc6-1cec-4054-8f6a-55b9ac088d31" />

## 功能

- 使用 Android 系统文件夹选择器选择本地目录。
- 递归扫描 `.mp3`、`.wav`、图片和 `.vtt` 台词文件。
- 自动整理为作品列表和音轨列表。
- 作品列表显示封面、作品名和音轨数量。
- 支持播放页封面轮播、同步台词显示、歌词式台词页和点击台词跳转。
- 返回列表或切换页面时保持当前音轨播放。
- 底部悬浮迷你播放器支持暂停、上一首、下一首和快速回到播放页。
- 支持“听过”近期播放记录。
- 支持作品和音轨长按拖动排序，并持久保存。
- 支持可选台词悬浮窗。
- 支持本地缓存扫描结果，下次启动可快速加载上次列表。

## 文件识别

支持的文件类型：

```text
音频：.mp3, .wav
图片：.jpg, .jpeg, .png, .webp, .bmp
台词：.vtt
```

台词匹配支持常见命名：

```text
track.vtt
track.mp3.vtt
```

图片匹配优先级：

1. 与音轨同名的图片
2. 音轨同目录图片
3. 作品目录内其他图片

## 隐私与权限

ASMR Player 以本地播放为主，当前版本不声明 `INTERNET` 权限，应用本身不会联网、不会上传文件列表、播放记录、音频、图片或台词内容。

应用使用的权限和系统能力：

- 文件夹访问：通过 Android 系统文件夹选择器授权，只读取用户主动选择的目录。应用不申请传统的全盘存储权限。
- 持久化目录授权：选择文件夹后会保存系统授予的目录 URI，用于下次启动快速加载缓存和重新扫描。
- 悬浮窗权限：`SYSTEM_ALERT_WINDOW` 仅用于“台词悬浮”功能。不开启该功能时不需要使用悬浮窗。
- 本地缓存：应用会在本机保存作品列表、排序、近期播放记录和播放相关状态，用于提升启动和进入列表速度。
- 备份策略：应用已关闭 Android 自动备份，减少本地缓存被系统备份到云端的可能。

应用内“打开 GitHub 项目”会调用系统浏览器访问 GitHub；该网页访问由用户设备上的浏览器处理，不由本应用内置联网加载。

## 安装

在 GitHub Release 中下载最新正式签名 APK：

```text
ASMR-Player-v1.4.0-release.apk
```

复制到安卓手机后，允许安装未知来源应用即可安装。v1.4.0 起发布包使用本项目本地 release key 签名，不再使用 debug 签名。

## 构建

本仓库使用 Android Gradle Plugin 构建。调试包：

```powershell
.\build-apk.ps1
```

正式签名 release 包：

```powershell
.\build-apk.ps1 -Release
```

release 构建需要根目录存在未提交的 `keystore.properties` 和对应 keystore 文件。签名密钥不应上传到 GitHub。

## 反馈

欢迎在 GitHub 提交 Issue、建议或支持项目：

```text
https://github.com/Baimo1209/ASMR-Player
```
