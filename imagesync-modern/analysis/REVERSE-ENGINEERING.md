# 逆向分析记录 · RICOH Image Sync 2.1.32

本文件记录本轮“重构 UI 但保留核心功能”所依赖的逆向证据链、方法、结论与未知项。
所有结论都来自对已安装产物的静态分析；没有设备/相机运行期证据，因此未做任何
“已在相机上验证”的声明。

## 1. 目标与身份

| 项目 | 值 |
|---|---|
| 文件 | `D:\Download\OPPO\Image Sync.apk` |
| SHA-256 | `00756f0c0474c7f6ac0271b817effadf15e92362d49eab9d86cd0d6bba639442` |
| 包名 / 版本 | `com.ricohimaging.imagesync` / 2.1.32 (versionCode 20132000) |
| SDK | minSdk 23, targetSdk 36, compileSdk 36 |
| DEX | `classes.dex` 7.79 MB + `classes2.dex` 8.34 MB |
| ABI | arm64-v8a / armeabi-v7a / x86 / x86_64 |

REA 归档清单证据（只读、未执行目标）：

- `open_binary` → digest 与上表一致。
- `inspect_artifact`（summary）→ inventory evidence
  `ev_ec4cc4a95b8142a8d1cc6bbb1a43331a72cc1afb1f7dfeaeb8edee7b8dfc7908`
  父证据 `ev_c48a47d258f88a46567ffbe1446666b332d7b8301f04287c6118014003db9207`；
  覆盖 8482 次检查、2863 个 ZIP 条目、2757 个节点、0 个完整性矛盾。
  该清单只描述归档条目，不建立运行期行为（其 limitation 已记录）。

静态代码分析使用工作机已有的 JADX 1.5.3（工具链
`...\.android-toolchain\jadx\lib\jadx-1.5.3-all.jar`）反编译到
`analysis/jadx/sources`（8496 个 Java 文件），未执行 APK。

## 2. 应用结构（观察）

- `SvApplication`：持有唯一 `CameraDevice` 字段 `a`（JADX 显示为 `f783a`，**以 DEX 名为准**），
  接口类型字段 `c`（默认 WLAN），以及传输管理器/设置访问器。
- 布局：BLE/WiFi/NFC 连接向导（`TitleActivity` → `SelectConnectionModelActivity` → …）、
  `MainActivity`（相册三 Tab：本机/相机/设置，Fragment 承载）、`ShootingActivity`（取景+拍摄）、
  `ImageViewActivity`（全屏查看）、`ShootingSettingActivity`（相机参数）、若干 Check*Activity。
- 相机通信集中在 `com.ricoh.camera.sdk.wireless.api`（未被混淆，167 个类）：
  `CameraDevice`、`CameraStorage`、`CameraImage`、`Acquisition`/`ImageDataStream`、
  `CameraEventListener`、`CameraDeviceDetector`、`setting.capture.*`、`setting.camera.*`。
- 应用侧胶水在混淆包 `m.* / s.* / n.* / o.* / a0..z1`（例如 `s.g` 工具集、`m.a0` 相机相册
  Fragment、`m.j` 下载队列、`m.e4` 实时取景监听、`s.i` 连接助手）。

## 3. 通信协议（观察，详见 NOTES-CONNECT / GALLERY / REMOTE）

| 用途 | 端点 / 机制 | 关键实现 |
|---|---|---|
| 探测机型 | `GET http://192.168.0.1/v1/props`（G900 SE：`192.168.1.1`） | `ItemCameraInfo.pullWithShortTimeout()` |
| 连接 | `CameraDeviceDetector.detect(WLAN)` → `connect(WLAN)` → `ConnectionChecker` 复核 | `ImplCameraDevice(WifiAdapter)` |
| 图片列表 | `updateImages()` + `/v1/photos`；`CameraStorage.getListImagesState()` 轮询至 `LISTED` | `ImplCameraDeviceWifiAdapter` |
| 缩略图/预览 | `CameraImage.getThumbnail/getViewData(OutputStream)` | 同步 HTTP，工作线程 |
| 原图下载 | `getDataAsync(OutputStream, AcquisitionCallback)` | 异步、按块回调进度 |
| 实时取景 | `startLiveView()`；`GET /v1/liveview` 由 SDK 内 `MjpegInputStream` 拆帧 | 回调每帧一个完整 JPEG |
| 快门 | `startCapture(boolean/Point)` → `POST /v1/camera/shoot`；完成事件异步 | `captureComplete` / `imageStored` |
| 对焦 | `focus(Point)` → `POST /v1/lens/focus`，Point 归一化 0..1 | 轮询机型以响应结果为准 |
| 参数 | `getCaptureSettings/setCaptureSettings`，`PUT /v1/params/*` | 值必须是规范化对象 |

### 3.1 关键逆向结论与影响

1. **Wi-Fi-only 直连可行**：只要手机已加入相机热点，`detect(WLAN)`+`connect(WLAN)` 即可，
   无需 BLE。第一阶段“必须走原版向导”的限制由此解除 → 新首页提供「检测相机 Wi-Fi」。
2. **JADX 字段重命名陷阱**：JADX 1.5.3 会把混淆字段 `a` 显示成 `f783a` 一类名字。
   若直接按反编译源码写反射/桩代码，运行期会 `NoSuchFieldError`。
   本项目以 `dexdump` 为字段名真值，并在 `test.py` 中加入成员级静态链接校验。
3. **原版下载在 Android 11+ 静默失败**：targetSdk 36 却仍用
   `Environment.getExternalStorageDirectory()/Pictures/...` + `FileOutputStream` 复制，
   异常被吞、成功仍上报。新界面改用 MediaStore（`Pictures/Image Sync` + `IS_PENDING`）。
4. **`finishCapture()` 是空实现**；停止拍摄需 `stopCapture()`。快门返回 `EXECUTING`，
   真正完成依赖 `captureComplete`（1–3 秒）。
5. **参数写入必须回传相机端对象**：SDK 通过反射把实例匹配到类的 `public static final`
   常量；`new ISO("640")` 这类对象不会被识别。新界面从 `getAvailableSettings()` 取对象再写。
6. **取景帧解码应在工作线程**：原版在主线程 `decodeByteArray` + `createBitmap` 旋转，
   有卡顿风险；新界面在单线程解码 RGB_565，仅主线程换帧。
7. **连接成功没有事件**：SDK 只抛 `deviceDisconnected`，“已连接”只能通过
   `isConnected(WLAN)` 观察。新首页在 onResume/2s tick/状态监听处刷新。
8. **向导成功后会压入原版 MainActivity**（无法在不改原版代码的前提下抑制）。
   新界面的策略：优先使用直连；需要 BLE 配对时打开官方向导，返回后由首页重新探测状态。

## 4. 本轮代码如何复用原版核心

- 新 DEX（`classes3.dex`）只包含 `com.ricohimaging.imagesync.*` 新界面类，
  通过编译期桩 `stubs/` 直接引用原版 SDK 类型；运行时由同一 `PathClassLoader` 解析到原版实现。
- 所有通信入口集中到 `CameraBridge`：连接、列表、缩略图、下载、取景、快门、对焦、参数。
- 原版 `MainActivity` / `ShootingActivity` / `ImageViewActivity` / `ShootingSettingActivity`
  等 Activity 一律保留，作为高级功能与故障兜底入口。
- 不触碰原版 `classes.dex` / `classes2.dex` / `resources.arsc`，仅在 ZIP 中追加 DEX 并
  修补二进制 AndroidManifest 的 launcher 归属（沿用第一阶段的二进制 XML 补丁逻辑）。

## 4.1 红外遥控（O-RC1）证据

参考实现：`D:\Desktop\K12固件\PENTAX_O-RC1_安卓遥控_v1.2_2026-10-10`
（含源码、单元测试与原始 ARM 红外解码验证 JSON，非混淆，可直接阅读）：

- 传输：`Android ConsumerIrManager.transmit(38000, frame)`，需要 `android.permission.TRANSMIT_IR`；
  参考 APK 还声明 `uses-feature android.hardware.consumerir required=false`。
- 帧格式（微秒）：`13000` 头 mark、`3000` 头 space；随后 6 位 MSB-first 数据，
  每 bit `1000` mark + （bit=0 时 `1000`、bit=1 时 `3000`）space；再 `1000` 停止 mark、`60000` 静默尾。
- 键值：快门释放 `0`、Zoom/对焦近处 `1`、Fn/对焦远处 `2`。
- 交互：短按单帧；长按 2 s 后每 200 ms 连发一帧（最长 30 s）用于对焦微调；
  相机需遥控拍摄模式，机身/镜头 AF 关闭时 Zoom/Fn 才作为对焦微调使用。
- 本项目的实现：`IrRemote.java` 复刻上述编码与优雅降级（无发射器/无 38 kHz 范围时明确报错），
  `integrate.py` 在二进制 AndroidManifest 中插入 `TRANSMIT_IR` 权限元素，`test.py` 断言该权限存在。

## 5. 覆盖范围、边界与未知

已覆盖（静态证据）：

- 连接状态模型与 Wi-Fi 直连链路；相册列表/缩略图/下载链路；取景帧契约；快门/对焦/参数命令；
  Scoped Storage 缺陷；JADX 重命名风险；原版 Activity/权限/Entry 关系。

未建立（明确未知）：

- 红外遥控在真实 PENTAX 机身上的有效距离/角度未测（参考项目同样未做真机验证）；
- 实际相机硬件上的时序、机型差异（各机型 `capture_<model>.yaml` 未随 APK 提供、
  `getAvailableSettings()` 需运行期枚举）、BLE 配对细节、Wi-Fi 热点加入失败分类。
- 部分 JADX 无法反编译的方法（`b2/d.f`、`s/g.J`、`n/e.run` 等）仅作推断，未用于实现。
- 运行期验证（真机、相机）未进行，`test.py` 只做静态一致性/链接性证明。

## 6. 复现步骤

```powershell
# 1. 反编译（工作机已有 JADX）
java -cp ...\jadx-1.5.3-all.jar jadx.cli.JadxCLI -d analysis\jadx --no-res "D:\Download\OPPO\Image Sync.apk"
# 2. 生成编译期桩
python tools\gen_stubs.py
# 3. 构建 / 集成 / 静态验证
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
python integrate.py
python test.py
```
