# PENTAX 工具箱（个人项目合集）

存放我自己在用的、围绕 **PENTAX / RICOH** 相机的几个工具和实验项目。所有内容都是个人自用与学习性质，非官方、与理光无关。

| 项目 | 内容 | 状态 |
|---|---|---|
| [`pentax-debug-card-writer/`](pentax-debug-card-writer/) | 写卡进入 PENTAX 工程模式（Debug / Development Menu）；部分机型可写四季滤镜脚本。含 exe、手动包、固件核验工具 | v1.3 · 文件名已按官方固件元数据核验，待实机 |
| [`imagesync-modern/`](imagesync-modern/) | 宾得官方传图 App「Image Sync 2.1.32」的 **UI 重绘版**：界面整套重写为自绘原生 Android UI，相机通信仍用原厂 SDK | 2.1.32 · 可用，APK 在 `dist/` |

## 关于仓库结构

这是一个**单仓库多项目**（monorepo）：每个项目一个子目录，根目录只放这个说明。

以后加新的宾得相关项目，直接在根目录新建子目录提交即可，**不需要每次新建仓库**：

```powershell
# 加一个新项目后
git add .
git commit -m "add: 新项目名"
git push
```

## 免责声明

- 均为个人非官方项目，与 RICOH IMAGING / PENTAX 无任何关系。
- 写卡工具只写存储卡，不刷固件、不改机身数据；工程模式内的高风险操作（校准、初始化、导入导出）请勿乱点。
- ImageSync-Modern 是在**你自己拥有并提供的官方 APK** 基础上附加界面层的修改产物，仓库里不包含官方 APK 原始文件；请勿二次分发修改后的 APK。
- 所有结论以各自项目文档里的"证据与边界"为准，**实机结果未验证的部分都明确标注了**。使用风险自负。
