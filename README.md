# Minecraft AI 建造助手（minecraft-ai）

一个 Fabric 模组：在游戏里用一句话描述你的想法，AI 输出**设计规格**，程序按参数化构件库**确定性地**盖出建筑，
3D 预览确认后一键建造，随时可撤销；不满意可以继续用一句话提意见迭代调整。
生成过程在后台运行，关掉面板也不中断。

> 适用版本：Minecraft **26.1.2** + Fabric Loader **≥0.19.0** + Fabric API **0.155.2+26.1.2** + Java **≥24**

---

## v1.1.0 做了什么（相对 v1.0.2）

v1.0.2 让 AI 画 **8~24 格的字符画蓝图**（`# W D P S R B C F L T X`）+ 三个全局颜色字段。
信息密度太低：层高、户型、房间语义、家具、楼梯、电梯、屋面构件、方块朝向都没有地方表达，
模型只能退化成"方盒子 + 每层复制"，玩家写得再细也落不进去。

v1.1.0 把底层换成 **设计规格（DSL）→ 参数化构件库 → 方案体检** 三段式：

| 改动 | 说明 |
| --- | --- |
| **新契约** | AI 只输出设计规格 JSON（原型/层数/层高/房间表/特征/材质/屋顶），不再输出字符画 |
| **参数化构件库** | 房间、门窗、双跑楼梯、脚手架电梯井、阳台、女儿墙、屋面设备、家具库全部由本地代码确定性生成 |
| **方案体检 + 自动修补** | 悬空方块、楼板空洞、堵门、房间可达性 逐项检查并自动修（预览/建造前完成） |
| **blockstate 支持** | `BuildingPlan.Entry` 带方块状态属性：楼梯朝向、半砖、脚手架 distance、门/床朝向等都能精确落地 |
| **notes 落实** | AI 常把要求写进自由文字 `notes`；现在会解析常见关键词（阳台/电梯/坡屋顶/太阳能/层数/外墙颜色…）并真正生效，日志回报"落实了什么" |
| **门策略** | **建筑内只生成木门**（手就能开），**不生成任何铁门，也不生成按钮/压力板**；门楣自动补墙，门口自动清障，门洞按 1~3 格墙厚对齐轴线 |
| **预览优化** | 只画暴露面 + 块被包住就不画；方块太多时按 2×2×2/3×3×3 **合并降分辨率**（不是随机抽稀）；跳过屏幕亚像素方块；打字时自动降档，保证输入流畅 |
| **错误诊断** | HTTP 状态码分类成人话（401 鉴权 / 402 额度 / 429 限流 / 5xx 服务端错误…），5xx/429/超时 **自动退避重试**；服务端拒绝 `thinking` 参数时自动去掉重试 |
| **配置修复** | 修复"打开设置再保存就把 API Key 截断成 32 字符"的 bug（EditBox 必须先 `setMaxLength` 再 `setValue`）；启动日志打印 Key 长度便于排查 |

---

## 功能特性

- **一句话生成建筑**：输入"8 层中式高层住宅，米白外墙+棕红腰线，南向阳台"，AI 输出规格，程序生成整栋楼
- **可交互 3D 预览**：滚轮缩放、右键拖拽无级旋转、左键拖拽平移、双击复位；大建筑自动适配视口
- **后台生成 + 悬浮球**：生成期间可关面板继续玩，右上角像素风悬浮球显示秒数/成功/失败，按 K 查看结果
- **方案迭代调整**：载入上次方案 + 提意见 → AI 只改受影响字段并返回完整新规格；建造后调整会**自动替换**旧建筑
- **一键建造 / 撤销**：批量放置整栋建筑，支持一键撤销
- **空间约束**：可指定终点坐标限制范围；超出时提示（按完整方案放置，不裁剪）
- **游戏内配置**：OpenAI 兼容接口 / 本地 Ollama 一键切换，填 Base URL、模型、API Key、思考模式，保存即时生效

---

## 设计规格（AI 输出的 JSON，可全部省略只留一句话）

```json
{
  "spec_version": 2,
  "name": "中式高层居民楼",
  "archetype": "chinese_highrise",
  "floors": 8,
  "layer_height": 4,
  "features": ["stairs", "elevator", "balcony", "roof_equipment"],
  "materials": { "wall": "white_concrete", "accent": "brown_terracotta" },
  "rooms": [ { "type": "living", "x": 0, "z": 0, "w": 6, "d": 5 } ],
  "roof": { "style": "flat", "parapet": true, "water_tank": true, "solar": true },
  "notes": "玩家提到的其它细节"
}
```

- `archetype`：`chinese_highrise`（一梯两户板楼，自动生成双跑楼梯 + 脚手架电梯 + 候梯厅）/ `chinese_courtyard` / `modern_villa` / `castle` / `generic`
- 房间 `type`：`living` `master` `bed2` `bed3` `kitchen` `bath` `dining` `entry` `hall` `balcony` `study` `storage` `stairs` `lobby` `courtyard` `pool` `garden`
- 尺寸缺省 = 玩家框选范围；框选高度不足时自动减层

---

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/) 与 Minecraft 26.1.2
2. 从 [Releases](https://github.com/xhmzlzg/minecraft-ai-builder/releases) 下载 jar，与 `fabric-api` 一起放进 `.minecraft/versions/<版本>/mods/`
3. 启动游戏

## 使用

1. 游戏内按 **K** 打开"AI 建造助手"
2. 写描述（建议写清层数），设置起点坐标（默认玩家面前 10 格），终点可留空
3. 点「生成方案」，等 AI 回复（生成期间可按 ESC 继续玩，看右上角悬浮球）
4. 在预览区滚轮缩放 / 右键旋转 / 左键平移检查
5. 满意点「确认建造」；反悔点「撤销上次」

### 迭代调整

- 点「调整上次方案」→ 自动载入上次方案与坐标 → 写调整意见 → 点「确认调整」
- 日志会打印 `调整前后规格差异:` 与 `notes 落实:`，能直接看出这次到底改了什么
- 对调整后的方案点「确认建造」会**自动撤销旧建筑并重建**

### 配置

- 面板 →「设置」：切换 **OpenAI 兼容** / **本地 Ollama**，填 Base URL、模型名、API Key（Ollama 填地址与模型）
- 配置文件：`config/minecraft-ai.json`（**API Key 只存在本地，不会进仓库/Release**）
- 提示：不少厂商的 `/models` 接口不支持或有额外鉴权，面板预检失败只是**提示**，直接点生成即可；真正的错误会按 HTTP 状态码明确报出来

---

## 源码结构（`src/main/java` + `src/client/java`，包 `com.mcai`）

```
common/
  BuildingPlan.java        方案数据：Entry 带 blockstate 属性；可覆盖/删除
  PlanValidator.java       方案体检 + 自动修补（悬空/楼板空洞/堵门/可达/安装面判定）
  BuildingExecutor.java    服务端放置（按属性名还原 blockstate）、撤销栈
  PlanSpec/PlanParser/PlanGenerator.java   旧契约（字符画蓝图）兼容管线
  spec/
    BuildingSpec.java      设计规格 DTO + 各原型默认材质表
    SpecParser.java        宽容解析（别名/代码块/尾随逗号），识别旧契约
    NotesParser.java       把 notes 自由文字解析成真正生效的字段
    Layout.java            矩形/房间/缩放/去重叠
    SpecBuilder.java       规格 → 具体方案（板楼户型/通用分格/门洞/屋面/立面）
    PartLib.java           构件库：门窗/栏杆/双跑楼梯/电梯井/女儿墙/屋面设备
    Interiors.java         家具库：按房间类型摆床/沙发/灶台/马桶/晾衣杆…
client/
  ai/AiClient.java         AI 请求（OpenAI 兼容/Ollama）+ 错误分类 + 自动重试
  ai/BuildTaskManager.java 全局任务 + 悬浮球 + 规格差异日志
  gui/AiBuildScreen.java   主面板（描述/坐标/5 按钮/预览/状态）
  gui/AiConfigScreen.java  配置界面
  render/PlanPreviewWidget.java  3D 预览（暴露面剔除 + 粗格 LOD）
selftest/SpecSelfTest.java  离线自检（不开游戏验证几何）
tools/selftest.bat          一键跑自检
docs/SPEC_v1.1.0.md         契约与实现说明
```

## 构建与自检

```bat
gradlew.bat build                 :: 产物 build\libs\minecraft-ai-1.1.0.jar
tools\selftest.bat                :: 离线自检：8 种建筑 × 几何/可达/门/屋顶断言
```

自检覆盖：方块数、门/灯/半砖/楼梯数量、带属性方块数、楼板层无隐形光源、屋顶封顶 ≥95%、
多层有楼梯、对外门存在、无铁门、无按钮/压力板、门楣不漏空、二次体检零修正。

## 已知限制

- 仅单人游戏（内置服务器）
- 大体量建筑（例如 30 层以上）一次放置耗时较长，建议描述里写明层数或用框选高度限制
- `notes` 目前只解析常见关键词（层数/阳台/电梯/楼梯/坡屋顶/太阳能/水箱/外墙颜色…），更自由的说法建议直接用规格字段表达
- 预览是自绘软光栅化，极端角度可能有少量排序瑕疵

## 许可证

MIT
