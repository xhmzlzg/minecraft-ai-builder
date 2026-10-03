# 项目记忆 · Minecraft AI 建造助手

> 面向「下次接着干活」的状态快照。细节契约见 `docs/HANDOVER.md`、`docs/SPEC_v1.1.0.md`。

## 当前版本

- **v1.1.3**（`gradle.properties`）
- **目标环境**：Minecraft **26.3** + Fabric Loader **≥0.19.0**（实测 0.19.5）+ Fabric API **0.161.0+26.3** + Java **≥24**
- **兼容**：`fabric.mod.json` 依赖 `minecraft >= 26.1.2`（26.1.2 旧实例理论上仍可加载；**主测/部署目标为 26.3**）
- GitHub：`https://github.com/xhmzlzg/minecraft-ai-builder`（`master` + tag `v1.1.3`）

## 本地路径

| 项 | 路径 |
| --- | --- |
| 工程 | `D:\一些AI coding的成果\Minecraft AI小组件` |
| 新测试实例（26.3） | `D:\minecraft\PCL_2.10.3\.minecraft\versions\26.3-Fabric 0.19.5\` |
| 旧实例（26.1.2，可能仍在用） | `…\versions\26.1.2-Fabric 0.19.2\` |
| 配置（含 API Key，**勿入库**） | `<实例>\config\minecraft-ai.json` |
| 自检脚本 | `tools/run_selftest.sh`（Windows 下用 Git Bash 跑） |

## 架构一句话

按 `K` → 对话式 `AiBuildScreen` → **两阶段**（①设计要点 JSON → ②紧凑 freeform `ops`）→ `FreeformBuilder` 逐格展开 → 3D 预览 → 确认建造 / 会话内继续改。**无建筑模板**，形状全由 AI 的 `ops` 决定。

## 已具备能力（v1.1.3）

1. **流式 + 超时**：`stream:true`；空闲超时（默认 45s）/ 思考预算（默认 240s）/ 总时长（默认 480s）
2. **两阶段生成**：设计要点（短 JSON）→ 紧凑 ops（box 优先、ops≤25、尽量 mirror）
3. **对话式像素 UI**：多会话、可折叠思考、消息内 3D 预览、取消/撤销、设置
4. **附图**：系统选图框 + **Ctrl+V /「粘贴」**（PowerShell 读系统剪贴板，兼容 PNG/DIB）
5. **上限**：`MAX_BLOCKS=500000`、`MAX_SIDE=256`（`FreeformBuilder`）
6. **26.3 API**：`setScreenAndShow`、`gui.screen()`、`InputConstants`（不再依赖 `org.lwjgl.glfw` 编译）

## 构建 / 部署

```bash
# Git Bash
cd "D:/一些AI coding的成果/Minecraft AI小组件"
./gradlew clean build --console=plain
bash tools/run_selftest.sh    # 必须「全部通过 ✅」

cp build/libs/minecraft-ai-<ver>.jar \
   "D:/minecraft/PCL_2.10.3/.minecraft/versions/26.3-Fabric 0.19.5/mods/"
```

- 发布前搜 `sk-` / Bearer / 用户 Key，**绝不能进仓库**
- 用户偏好：先答可行性再改代码；不擅自推 GitHub（**说推才推**）；不擅自改 `HANDOVER.md`

## 26.3 兼容改动（相对 26.1.2）

| 26.1.2 | 26.3 |
| --- | --- |
| `minecraft.setScreen` | `setScreenAndShow` / `gui.setScreen` |
| `client.screen` | `client.gui.screen()` |
| `org.lwjgl.glfw.GLFW_*` | `com.mojang.blaze3d.platform.InputConstants.KEY_*` |

## 待办 / 已知限制

- 识图依赖**多模态模型**；不支持 vision 的模型附图会失败（可在设置里换模型名）
- 大建筑放置仍在一个服务端 tick 内批量 `setBlock`，极大方案可能卡顿（待分批放置）
- 预览仍为软光栅化，大方案会降采样
- 仅单人游戏（`server.submit`）
- 自由形体不做几何体检（悬空为常态）

## 更新记录（摘要）

- **v1.1.3**：兼容 MC **26.3** / Fabric API 0.161；新增本记忆文档
- **v1.1.2**：附图 + 剪贴板粘贴；方块上限 50 万 / 边 256
- **v1.1.1**：流式超时、两阶段生成、对话式 UI、freeform 单契约入库
- **v1.1.0**：设计规格契约；后续补丁删除全部建筑模板

*文档更新：2026-10-03（对应 v1.1.3 / MC 26.3）*
