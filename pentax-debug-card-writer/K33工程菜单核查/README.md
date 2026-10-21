# K-3 III v2.20 工程菜单核查与入口更正

2026-10-19。目标为彩色版 K-3 III，即用户所称 K33；“K44”已由用户确认为笔误。

## 结论与此前错误

用户看到 **Service Menu** 是正确现象。K33 固件明确区分 Service Menu、Factory Menu、Setting Menu 三种界面。**FW Setting 1 / FW Setting 2 仅对 Factory Menu 可见，Script 位于 Factory Menu → FW Setting 1 → Script。** Service Menu 不提供这两个页面，所以在当前界面继续翻页找不到 Script。

此前 v1.1 写卡工具给 K33 写入 `[OPEN_DEBUG_MENU]`，这个指令打开 Service Menu；教程却让用户在其中寻找 SCRIPT EN MODE，混淆了 K12 的菜单名称与 K33 的菜单分型。此项教程错误现已更正。不能因为 Service 看不到 Script，就推断 K33 删除了脚本功能，也不能仅凭 GR 教程认定不同型号的菜单和按键相同。

本次从固件及原始 ARM 代码离线执行确认：

| 根目录模式文件内的单条指令 | 解析后的操作号 | 菜单事件 | 结果 |
|---|---:|---|---|
| `[OPEN_DEBUG_MENU]` | 9 | `0x0100000a` | Service Menu，类型 1 |
| `[OPEN_FACTORY_DEBUG_MENU]` | 10 | `0x01000009` | Factory Menu，类型 0 |
| `[SCRIPT_EN_MODE EN]` | 4 | 非菜单入口 | 请求启用 Script |
| `[SCRIPT_EN_MODE DIS]` | 5 | 非菜单入口 | 请求关闭 Script |

以上四条指令的命令表限制字节均为 0，不要求切换到第二种 DEVELOP 校验值。继续使用此前的 10 字节二进制 DEVELOP.MOD：`07 01 2c 1f 10 03 1e 16 05 2d`。

**证据边界：** 用户已经实机进入 Service；Factory 入口、菜单项与 Script 提交函数已完成离线验证，但本轮没有操作相机，没有完整模拟启动、UI 输入循环或配置持久化。Factory 能否在用户机身按同样物理步骤进入，Script 是否成功保存并在重启后执行，仍需要实机确认。原四季调用原始函数的离线验证仍有效，不能替代四季实际拍摄测试。

## 现在怎样测试

桌面更新版：`D:\Desktop\宾得工模写卡工具_v1.2`。

1. 在工具“工程模式”中选择 **K-3 III → Factory Menu · 脚本开关入口（待实机）**。原 Service 入口仍单独保留；“四季滤镜”区的 K33 工程入口也改为 Factory。
2. 写入的仍是卡根目录 `00078420.582` 与 `DEVELOP.MOD`。前者内容只保留 `[OPEN_FACTORY_DEBUG_MENU]` 加 CRLF，ASCII，无 BOM；不要同时保留 `[OPEN_DEBUG_MENU]`，不要加 `.txt` 后缀。
3. 写卡工具会备份并移走已知 `script/startup.ttl`，防止启动脚本先运行。手动操作时，先把现有 startup.ttl 备份到电脑并暂时移出原位置。未知脚本由用户核对，不自动删除。
4. 安全弹出，装回用户此前成功进入 Service 的同一张卡、同一卡槽，**关好卡仓、按住 MENU 不放再开机**（用户实机确认的进入方式）。
5. 预期标题改为 **Factory Menu**，随后进入 **FW Setting 1 → Script**，设为启用并正常确认退出、关机。菜单里的 Camera Mode 与 Script 是两个不同设置。
6. 再用工具“只写标记探测”，它会移走已知工程入口文件。普通开机后关机取卡，检查本次是否新生成 `script/K33_SCRIPT_PROBE_OK.txt`。先证实这一点，再写四季脚本。

若仍进入 Service，先核对卡上 `00078420.582` 的实际内容、扩展名、卡槽、相机固件版本，而不是继续在 Service 中找 FW Setting。若显示 Factory 但菜单不同，保留完整屏幕照片和固件版本，以便对比。

另附四组相互独立的手动文件：Factory、Service、直接开启 Script、直接关闭 Script。每次只把一组的两个文件复制到根目录。直接 Script 指令的解析和提交函数已经核验，整机应用/保存仍待实测。

## Factory Menu 的全部一级页面与直接项目

以下为 v2.20 菜单数据表与菜单查询函数共同确认的当前可见项目。中文解释按菜单名称给出；没有把每个测试项背后的全部硬件操作都逆向为使用教程。

| 页面 | 直接项目 | 用途与边界 |
|---|---|---|
| FW Setting 1 | Camera Mode；Script；Key Emulate；Serial Input；Store Debug Log；Card Door Open；Thermal ShutDown | 相机运行模式、脚本、按键模拟、串口输入、调试日志、卡门相关状态/设置。Thermal ShutDown 对应项目的通用执行类型为 0，本轮不能把它描述为可随意开关的温控选项。 |
| FW Setting 2 | Valid WLAN；HDMI Format Priority；HDMI Force Output Resolution；Debug Switch Trigger；ScreenShot Trigger | 无线有效性、HDMI 格式及输出分辨率、调试/截图触发设置。 |
| Test Display 1 | Switch Test；Electronic Level Check；Dust Removal Test；Display Camera Log；Audio Test；SR Data Check；VLens Control Test | 按键、电子水平仪、除尘、日志显示、音频、防抖数据及 VLens 控制测试。VLens 的具体对象和硬件行为未在本轮确定。 |
| Test Display 2 | DeepLearning Object Detect Check；Jpeg Check | 目标检测与 JPEG 检查入口；不是新增识别能力或新的 JPEG 滤镜开关。 |
| AF | PAF Sensor Setting；PAF Adjust Value Display | 相位 AF 传感器设置、调整值显示。不能仅凭名称宣称可扩展 AF 微调范围，具体子页面参数与写入范围未完成验证。 |
| Camera → SD | Adjust Data；Camera Log；Debug Log；Exception Log；User Data | 校准、相机日志、调试日志、异常日志、用户数据导出入口。 |
| SD → Camera | Adjust Data；User Data | 校准数据、用户数据导入入口，会涉及机身已有数据。 |
| Initialize | Initialize User Data；Erase Debug Log；Erase Exception Log | 用户数据初始化、调试日志清除、异常日志清除。 |
| Version | Firmware | 固件版本显示。 |

Factory 有 9 个可见一级页面；FW Setting 1 有 7 个直接项目。机内显示所用字号、换页和动态状态可能影响同屏呈现，但不会让 Service 自动出现 Factory 专属页面。

**与本次目标有关的操作只有 Script 开关和探测脚本。** Adjust Data 导入、Initialize、AF 校准等不是开启滤镜的步骤，不应作为试探入口执行。

## Service Menu 的全部一级页面与直接项目

| 页面 | 直接项目 |
|---|---|
| Setting & Display | Camera Mode；Switch Test；Valid WLAN；ScreenShot Trigger |
| Camera → SD | Adjust Data；Camera Log；User Data |
| SD → Camera | Adjust Data；User Data |
| Initialize | Initialize User Data |
| Version | Firmware |

Service 有 5 个可见一级页面，没有 Script、FW Setting 1/2、AF、Test Display 1/2，也没有 Factory 中的 Debug Log / Exception Log 导出清除项。

第三种 Setting Menu 的可见页面是 Screen Shot，直接项目为 ScreenShot Trigger。本轮以两个主要入口为目标，没有把该菜单的全部启动条件作为实机教程。注意根目录 `SCREEN_SHOT_MENU` 的表索引为 51，但解析后的操作号是 48；命令表索引和操作号不可混为一谈。

菜单表中没有 K20D 那样的镜头 EEPROM 导入导出项目；这只能说明这套 K33 工程菜单未列出相同直接项目，不能证明整个固件不存在镜头读写代码。

## 原始代码证据与可复现验证

固件：工作区 `K1_K33_KF脚本模式研究_2026-10-19/K33/firmware.unpacked.bin`，v2.20 彩色版。

SHA256：`7ff301d585ef7adcd620a15575faa39b4227cc5a27eaa8d754efe50f667a30f9`。

主映射基址 `0x524e3e64`。启动代码 `0x5250038c` 把源 `0x53ce7d40` 的 `0x65a80` 字节复制到 RW 区 `0x54500000`。验证使用了这份真实初值，未把位于 RW 区的命令表误当作全零数据。

| 地址/对象 | 已核验行为 |
|---|---|
| `0x54526234` | 33 字节/项的根目录命令表，含 OPEN_DEBUG_MENU / OPEN_FACTORY_DEBUG_MENU / SCRIPT_EN_MODE EN、DIS。 |
| `0x53102ecc` / `0x53102f24` | 命令匹配、权限字节判断、输出待处理标志；Factory 写 +4，Service 写 +3。 |
| `0x531036f4` | 待处理标志转换为操作号：Service 9，Factory 10。 |
| `0x53316100` 起的启动处理段 | 读取操作号，经 `0x4a408201` 消息交给工程命令模型。外围调度未完整模拟。 |
| `0x529eb98c` / `0x529eb1d0` | 原始消息处理和命令事件分发；从操作号 9/10 分别发出 Service/Factory 菜单事件。 |
| `0x528ba618` | 菜单控制器从事件选择类型；`0x0100000a → 1`，`0x01000009 → 0`。离线执行停在类型设置完成后的 UI 构造边界。 |
| `0x529ebc8c` / `0x529ebcc4` | 菜单模型构造时默认 Service；setter 修改模型 +0x1e 的类型。 |
| `0x536ac9ac` | Factory / Service / Setting 标题表。 |
| `0x536aca7c` / `0x536ac9e4` | 一级页面名、直接项目名表。 |
| `0x5368cc94` | 一级页面可见性表，索引为 页面×3+类型；FW1/FW2 只允许类型 0。 |
| `0x528b9ea0` | 根据当前页面、菜单类型和行号查直接项目；对三种类型、11 个页面进行了 79 次原始函数/表数据交叉校验，含结束项。 |
| `0x5368cc7c` | FW Setting 1 的项目映射表；Factory 的第二项为 Script，ID 3。 |
| `0x536acaa8` / `0x529ec188` | Script 项 ID 3 映射为工程命令 ID 4；原始菜单代码可发出对应切换请求。 |
| `0x529eb690` | Script 状态读取位于设置对象 +0x10。 |
| `0x5310435c` | Script 提交函数把 +0x10 设为 `0x5aa5a55a` 或 `0xffffffff`，调用设置服务。离线验证了两个方向，持久化服务为边界替身。 |
| `0x5310430c` | DEBUG 提交对应 +4；与 Script 的 +0x10 不同。是否可以 DEBUG 关闭、Script 开启长期使用仍需整机测试。 |

复现脚本：`analysis/audit_k33_menus_20261019.py`；机器可读证据：`菜单表与原始函数验证.json`。字符串/系统工厂/消息发送/设置保存等边界服务采用明确替身；函数调用本身执行原始 ARM 指令。测试没有访问 SD 卡或相机。没有宣称完成完整固件启动模拟。

本次也查看了早期 K33 的一手讨论：2024-01-22 有用户使用同组文件后报告进入 Service Menu，却找不到 Debug 设置，现象与本次一致。网页仅作交叉印证；Factory 与 Script 的结论来自本地 v2.20 固件，不是论坛推测。

参考：[PentaxForums，K-3 MarkIII reveal - hacking，第 2 页](https://www.pentaxforums.com/forums/32-digital-processing-software-printing/464577-pentax-k-3-markiii-reveal-hacking-2.html)。

## 更新工具的范围

v1.2 为独立输出，保留 v1.1 原版。工程模式区为 K33 保留 Service 并新增 Factory 方案；四季区 K33 的 entry 改为 Factory。Factory 指令的写卡白名单只允许本次核验的 `00078420.582`，不自动扩展到其他机型。

滤镜命令、其他机型方案、备份恢复流程不变；增加 K33 从 Service 文件切换到 Factory 文件、四季区入口字节必须为 Factory 的校验。手动包独立保留两种入口。所有写卡测试使用电脑内临时目录和模拟照片，未写入用户存储卡。
