# Image Sync · Modern UI

> 宾得（RICOH / PENTAX）官方传图 App **Image Sync 2.1.32** 的 **UI 重绘版**：
> 把界面整套重绘为自绘的原生 Android UI，**相机通信核心完全保留原厂实现**——
> BLE / Wi-Fi 连接、设备发现、PTP 图片传输、实时取景、快门与拍摄参数全部继续由原版
> Ricoh Wireless SDK 执行。

个人自用 / 学习项目，非官方，与 RICOH 无关。

| 首页 | 相册 |
|---|---|
| ![首页](https://cdn.jsdelivr.net/gh/linda6177/pentax-tools@main/imagesync-modern/docs/screenshots/01-home.jpg) | ![相册](https://cdn.jsdelivr.net/gh/linda6177/pentax-tools@main/imagesync-modern/docs/screenshots/02-gallery.jpg) |

| 遥控 · Wi-Fi | 遥控 · 红外 |
|---|---|
| ![WiFi 遥控](https://cdn.jsdelivr.net/gh/linda6177/pentax-tools@main/imagesync-modern/docs/screenshots/03-remote-wifi.jpg) | ![红外遥控](https://cdn.jsdelivr.net/gh/linda6177/pentax-tools@main/imagesync-modern/docs/screenshots/04-remote-ir.jpg) |

> 图片走 jsDelivr CDN（因为 raw.githubusercontent.com 在部分网络下不可达）；原图在 [`docs/screenshots/`](docs/screenshots/)。

## 做了什么

- **首页**：实时连接状态、电量、最近拍摄、快捷操作
- **相册**：三列网格、相机/本机切换、多选下载队列与进度
- **查看器**：全屏、双击/双指缩放、滑动切换、下拉关闭、下载/分享
- **遥控**：实时取景 + 点按对焦 + 快门 + 自拍计时 + 拍摄参数面板（模式 / 曝光 / 画质色彩）
- **红外遥控**：兼容 **PENTAX O-RC1**（38 kHz，快门释放 / 对焦近 / 对焦远，长按连发）
- **主题色**：9 种配色 + 跟随系统（Android 12+ 壁纸取色）
- **修复原版缺陷**：Android 11+ 原版下载静默失败，改为 MediaStore 发布（`Pictures/Image Sync`）
- 界面层是 **single-activity + 自绘组件**，不依赖 AndroidX / Material，不新增资源

原理上不反编译、不替换原版代码：新界面编译成独立的 `classes3.dex` 追加进 APK，
`classes.dex` / `classes2.dex` / `resources.arsc` 与原始 2858 个条目保持逐字节一致（`test.py` 断言）。

## 构建

需要自备：

1. **官方 Image Sync 2.1.32 APK**（自行从应用商店提取；仓库不包含官方 APK）
   - 期望 sha256 `00756f0c0474c7f6ac0271b817effadf15e92362d49eab9d86cd0d6bba639442`
   - 路径写在 `integrate.py` 的 `APP` 常量里，按需修改
2. **Android 构建工具链**：JDK 8+（编译用 `-source 8`）、`android.jar`（API 35）、`d8`、`dexdump`、`zipalign`、`apksigner`、Python 3
   - `build.ps1` / `integrate.py` 默认到 `D:\Desktop\*\tools\.android-toolchain` 找工具链，可按自己环境改 `toolchain()`
3. **签名**：`build/development.keystore` 是我自己的测试签名，公开仓库里不含私钥；首次构建请生成自己的 keystore 并在 `integrate.py` 里改签名参数

```powershell
# 1) 编译新界面 -> dist\modern-shell.dex
powershell -ExecutionPolicy Bypass -File .\build.ps1

# 2) 把 dex 装进你自备的官方 APK -> dist\ImageSync-Modern-<版本>.apk
python .\integrate.py

# 3) 自检（断言原版 dex / 资源未被改动、签名与包名正确）
python .\test.py
```

产物在 `dist/`。本仓库 `dist/` 里附了一个已构建的 APK 供自己使用，**请勿二次分发**。

## 文档

- [`docs/技术说明.md`](docs/技术说明.md)——完整技术记录：逆向结论、连接生命周期、实时取景帧契约、快门状态机、参数字典、三轮真机反馈修复
- [`analysis/NOTES-CONNECT.md`](analysis/NOTES-CONNECT.md)、[`analysis/NOTES-GALLERY.md`](analysis/NOTES-GALLERY.md)、[`analysis/NOTES-REMOTE.md`](analysis/NOTES-REMOTE.md)——各模块逆向笔记
- [`analysis/REVERSE-ENGINEERING.md`](analysis/REVERSE-ENGINEERING.md)、[`analysis/UIKIT-API.md`](analysis/UIKIT-API.md)

## 目录

```
src/        新界面的 Java 源码（25 个文件，编译进 classes3.dex）
stubs/      编译期占位类（原版 SDK 签名，不打包）
tools/      gen_stubs.py 等构建辅助
analysis/   逆向笔记与证据（不含官方反编译源码）
docs/       技术说明 + 截图
dist/       构建产物
build.ps1   编译新界面
integrate.py  集成进官方 APK（改 manifest + 签名）
test.py     一致性自检
```

## 免责

- 非官方项目，与 RICOH IMAGING 无关；仅限个人学习与自用。
- 修改版 APK 基于你自有的官方应用构建，**不要分发**。
- 刷机/卸载/重装导致的任何问题自行承担。
