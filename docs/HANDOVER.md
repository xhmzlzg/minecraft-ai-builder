# Minecraft AI 建造助手 —— AI 交接文档（v1.1.0，2026-09-22）

> 面向"接手这个项目的另一个 AI"。
> 读完本文 + `README.md` + `docs/SPEC_v1.1.0.md`，再跑一次 `tools\selftest.bat`，你就具备继续开发所需的全部上下文。
>
> ⚠️ **本文档已于 2026-09-22 整体重写。** 在此之前的所有描述里，凡是提到"建筑模板 / archetype / 字符画蓝图 /
> 参数化构件库 / 户型 / 门策略"的内容**全部作废** —— 用户已拍板把模板整条删除，程序现在只走一条路。

---

## 0. 一句话现状

这是一个 Fabric 模组：玩家在游戏内按 `K` 输入一句话 → AI 输出**自由形体规格 JSON**（`kind:"freeform"`）→
本地程序**逐格照做** → 3D 预览 → 一键建造 / 撤销 → 可继续提意见迭代。

**程序里现在没有任何建筑模板、没有 archetype、没有参数化构件库。** 形状完全由 AI 的 `ops` 决定。
用户原话（2026-09-22）：

> 备份现在的版本，然后删除模板，以后输入任何提示词都由AI自行创作

（删除范围选择：**全部删**。AI 若仍输出 `kind:"building"`，程序**直接当成 freeform**处理，不报错、不特判。）

**当前状态**：自检 **113 项全绿** / jar **106798 字节** / 已部署到游戏实例（md5 双边一致）。

### ★★ 两件接手后必须立刻知道的事

1. **本地工作区领先 GitHub 很多，而且全部未提交。** GitHub 上的 `v1.1.0` 还是"只有建筑模板、没有 freeform"的旧版；
   本地这一份才是删模板后的新架构。**用户明确要求过：不要擅自推 GitHub。** 详见 §1。
2. **MiMo 后端会无限期卡死**（2026-09-22 实测 700+ 秒无结果）。根因已定位、已用实验证实、
   修复方案已提出，但**用户尚未选定方案，代码未改**。详见 §7 —— 这是当前最该处理的问题。

---

## 1. 位置与环境（先记住这几个路径）

| 项 | 值 |
| --- | --- |
| 本地工程 | `D:\一些AI coding的成果\Minecraft AI小组件` |
| GitHub 仓库 | `https://github.com/xhmzlzg/minecraft-ai-builder`（账号 `xhmzlzg`，`master` 分支，public） |
| 当前版本号 | **v1.1.0**（`gradle.properties` → `version=1.1.0`，jar 名 `minecraft-ai-1.1.0.jar`） |
| **GitHub HEAD** | `3de4a60`（v1.1.0 最终版 —— **含建筑模板、无 freeform**，是本地新架构的前身） |
| **本地工作区** | **大量未提交改动**（详见下方警告） |
| 测试实例 | PCL2：`D:\minecraft\PCL_2.10.3\.minecraft\versions\26.1.2-Fabric 0.19.2\`，mods 内需有 `minecraft-ai-1.1.0.jar` + `fabric-api-0.155.2+26.1.2.jar` |
| 游戏内配置 | `…\26.1.2-Fabric 0.19.2\config\minecraft-ai.json`（**含用户 API Key，敏感：不进仓库、不进 Release、不复制进备份**） |
| 日志 | `…\26.1.2-Fabric 0.19.2\logs\latest.log`（编码可能是 UTF-8 或 GBK；旧日志 `.log.gz` 需先解压） |
| gh CLI | `C:\Program Files\GitHub CLI\gh.exe`（用户 `xhmzlzg` 已用 keyring 登录） |

### ★★ 警告：本地与 GitHub 的差距

`git log` 最新提交是 `3de4a60`，但工作区里躺着这些**从未提交**的改动：

| 状态 | 内容 |
| --- | --- |
| `D` 已删除 7 个 | `common/PlanGenerator.java`、`common/PlanParser.java`、`common/PlanSpec.java`、`spec/PartLib.java`、`spec/Interiors.java`、`spec/NotesParser.java`、`spec/Layout.java` |
| `M` 已修改 | `BuildingSpec`、`SpecBuilder`、`SpecParser`、`AiClient`、`BuildTaskManager`、`AiBuildScreen`、`PlanPreviewWidget`、`BuildingExecutor`、`README.md`、`docs/SPEC_v1.1.0.md`、`selftest/SpecSelfTest.java` |
| `??` 未跟踪 | `spec/FreeformBuilder.java`、`spec/FreeformSpec.java`（**自由形体通道的核心，GitHub 上根本不存在**）、`docs/HANDOVER.md`（本文档）、`docs/预设模板*.html/png` |

⇒ **如果只看 GitHub 上的代码，你会完全找不到 freeform 通道。** 接手时请以本地工作区为准。

**回滚锚点**（都在游戏实例的 mods 目录里）：
- `minecraft-ai-1.1.0.jar.bak` —— 130206 字节，md5 `4bda7449ebf7e07355f306cf9d9ce0cc`，= **GitHub 官方 v1.1.0**（模板版）
- `minecraft-ai-1.1.0.jar.prev` —— 170680 字节（2026-09-22 早些时候的中间版）

**仓库外完整备份**（删模板之前做的，含 45 个文件 + 两个 jar + `还原说明.md`）：
`D:\一些AI coding的成果\Minecraft AI小组件-备份-2026-09-22-删模板前\`

技术栈：Minecraft **26.1.2** + Fabric Loader **0.19.3** + Fabric API **0.155.2+26.1.2** + Java **25**
（`gradle.properties` 里写死了 `org.gradle.java.home`）+ Loom **1.17-SNAPSHOT** + Gson；
**纯 Java、没有 Mixin**；许可证 **MIT**。

---

## 2. 构建 / 部署 / 发布 / 自检

**在 Git Bash 里构建**（仓库根有 shell 版 `gradlew`，**不必碰 `cmd`** —— 跑 `cmd` 会被安全策略拦）：

```bash
cd "D:/一些AI coding的成果/Minecraft AI小组件"
./gradlew build --console=plain     # 产物 build/libs/minecraft-ai-1.1.0.jar（当前 106798 字节）
```

```bat
:: Windows 下等价写法
gradlew.bat build
tools\selftest.bat                  :: 离线自检（不开游戏），必须"全部通过 ✅"
```

**部署**（mods 里只能留一个 minecraft-ai 版本，否则 Fabric 会因重复 mod id 报错）：

```bash
cp build/libs/minecraft-ai-1.1.0.jar \
   "D:/minecraft/PCL_2.10.3/.minecraft/versions/26.1.2-Fabric 0.19.2/mods/minecraft-ai-1.1.0.jar"
md5sum build/libs/minecraft-ai-1.1.0.jar \
   "D:/minecraft/PCL_2.10.3/.minecraft/versions/26.1.2-Fabric 0.19.2/mods/minecraft-ai-1.1.0.jar"
# 两边必须一致；当前应为 e6fd723399bcb4ca978138fb62d28bb1
```

**在 Git Bash 里手动跑自检**（`cmd` 被拦时的等价命令 —— 注意仓库路径含空格，必须用 bash 数组）：

```bash
SRC="D:/一些AI coding的成果/Minecraft AI小组件"
GSONJAR="C:/Users/ASUS/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.13.2/48b8230771e573b54ce6e867a9001e75977fe78e/gson-2.13.2.jar"
files=("$SRC/src/main/java/com/mcai/common/BuildingPlan.java" "$SRC/src/main/java/com/mcai/common/PlanValidator.java")
for f in "$SRC"/src/main/java/com/mcai/common/spec/*.java; do files+=("$f"); done
files+=("$SRC/selftest/SpecSelfTest.java")
javac -encoding UTF-8 -cp "$GSONJAR" -d "$SRC/build/selftest" "${files[@]}"
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$SRC/build/selftest;$GSONJAR" SpecSelfTest
```

- `-cp` 传 gson **必须用 Windows 风格路径**（`C:/Users/...`），Git Bash 的 `/c/...` 会被 javac 当成非法路径。
- 加 `-Dstdout.encoding=UTF-8`，否则中文输出会被当成二进制。

**发布前必做**：在 `src/`、`selftest/`、`tools/`、`docs/` 里搜一遍 `sk-[A-Za-z0-9]{16,}` 与 `Bearer ` 确认没有密钥泄漏。

---

## 3. 架构与数据流（2026-09-22 删模板后）

```
K 键 → AiBuildScreen（面板：描述框/起止坐标/按钮/预览/状态）
        │  start() / adjust()
        ▼
BuildTaskManager（全局单例：后台任务、Phase 状态机、悬浮球、规格差异日志、结果校验/自动重做）
        │  askPlan(desc, pos, cfg[, previousReply, adjustment, repairHint])
        ▼
AiClient（java.net.http + Gson；OpenAI 兼容 / Ollama；错误分类 + 自动重试 + thinking 降级）
        │  返回 JSON 文本
        ▼
SpecParser（宽容解析；kind 一律归一化成 "freeform"）
        │
        ▼
SpecBuilder.build(spec, availW, availD, availH)      ← 现在只有 62 行，只做转发
        │
        ▼
FreeformBuilder.build(freeformSpec, name, W, D, H)
        ├─ ops：layer(分层字符画) / box / clear / cyl / sphere（按序执行，后者覆盖前者）
        ├─ mirror=none|x|z|xz   只写一半，程序镜像另一半（含朝向/铰链/形状翻转）
        ├─ 三道后处理：autoHanging（灯笼上方实心→吊灯）
        │              connectPass（栏杆/玻璃板/铁栏杆/矮墙按真实邻居补连接属性）
        │              audit（只统计孤立方块，**绝不修补**）
        └─ 安全阀：坐标越界丢弃 / 非法方块 id 丢弃 / MAX_BLOCKS=100000 / 单边上限 128
        ▼
BuildingPlan（Entry = x,y,z,基础方块id + blockstate 属性表）
        ├─ PlanPreviewWidget   3D 预览（暴露面剔除 + 粗格 LOD + 亚像素跳过）
        ▼
BuildingExecutor（服务端线程批量 setBlock + 撤销栈；按属性名还原 blockstate）
```

**没有任何建筑分支了。** `kind:"building"` 会被 `SpecParser` 直接归一化成 freeform；
若模型因此没给出 `ops`，展开结果是 0 个方块 + 明确警告，`BuildTaskManager` 会带着原因让模型重画一次。

### 关键文件职责

| 文件 | 职责 | 常见改动点 |
| --- | --- | --- |
| `common/spec/FreeformSpec.java` | **自由形体 DTO**（`size`/`palette`/`mirror`/`ops`/`notes`）+ 内置字符表 | 新增 op 类型时同步这里与 `FreeformBuilder` |
| `common/spec/FreeformBuilder.java` | **自由形体展开器**：逐格照做，不做建筑逻辑、不做体检 | `MAX_BLOCKS`(100000)、`MAX_SIDE`(128)、`mirror` 轴、几何原语、三道后处理 |
| `common/spec/SpecBuilder.java` | **只做转发**（62 行）：把 `BuildingSpec.freeform` 交给 `FreeformBuilder` | `Result` 只有 `plan`/`warnings`/`isolated` |
| `common/spec/BuildingSpec.java` | 规格 DTO（31 行）：`specVersion` / `kind` / `name` / `freeform` | 字段增删要同步 `SpecParser` |
| `common/spec/SpecParser.java` | 宽容解析：从模型回复里抠 JSON、归一化畸形写法、`looksLikeSpec()` 闸门 | 新畸形写法往 `SpecSelfTest` 第 5 节补回归 |
| `common/BuildingPlan.java` | 方案容器（`add` 首次生效 / `set` 覆盖 / `remove` 删除 / `get`） | 坐标用 `& 0x3FF` 打包 ⇒ **绝不允许负坐标或 >1023** |
| `common/PlanValidator.java` | 支撑判定（`isSupportingBlock`/`isSturdy`），被 `BuildingPlan.isSupporting()` 用 | freeform 路径**故意不调** `check()`，别"顺手加上" |
| `common/BuildingExecutor.java` | 放置 + 撤销；blockstate 属性还原 | 特殊方块处理（门/床等） |
| `common/AiConfig.java` | 配置（provider / baseUrl / model / apiKey / thinkingEnabled） | **目前没有任何超时配置项** |
| `client/render/PlanPreviewWidget.java` | 3D 预览渲染 + 方块配色表 | `MAX_RENDER`(6000) / `LOW_DETAIL_RENDER`(600) / `BLOCK_COLORS` |
| `client/ai/AiClient.java` | 提示词 + HTTP + 错误处理 | `SYSTEM_PROMPT_V2`（唯一契约 + 船的 few-shot）、`repairHint`、`maxAttempts=4`、**超时（见 §7）** |
| `client/ai/BuildTaskManager.java` | 任务状态 + 悬浮球 + 日志 + 结果校验/自动重做 | `mismatchHint`、`diffSpec`、`freeformSignature`、`boxSize` |

---

## 4. 设计规格契约（AI 输出什么）

**只有一种：`kind:"freeform"`。**

```json
{
  "spec_version": 3,
  "kind": "freeform",
  "name": "帆船",
  "size": [15, 11, 7],
  "palette": { "h": "dark_oak_planks", "d": "spruce_planks", "m": "oak_log", "s": "white_wool" },
  "mirror": "x",
  "ops": [
    { "op": "layer", "y": 0, "rows": ["......hh", ".....hhh", "....hhhh"] },
    { "op": "box", "from": [4,4,1], "to": [7,7,4], "block": "s" },
    { "op": "box", "from": [7,2,3], "to": [7,9,3], "block": "m" }
  ],
  "notes": "玩家提到的其它细节"
}
```

- `size`：`[宽X, 高Y, 进深Z]`，也可只给 `[宽, 进深]`；**缺省时按 ops 实际范围自动算**
- `palette`：单字符 → 方块 id；缺省用内置字符表（`#`石砖 `O`橡木板 `o`橡木原木 `T`深色橡木板 `W`白羊毛 `G`玻璃 `I`铁块 `L`灯笼 `X`海晶灯 …）
- `mirror`：`none`(默认) / `x` / `z` / `xz` —— **只写一半，程序镜像另一半**（省一半输出，且绝对对称）
- `ops`（按顺序执行，后面的覆盖前面的）：
  - `layer`：`y` + `rows`（`rows[z]` 的第 x 个字符决定该格方块，`.`/空格 = 不放置）
  - `box`：`from`/`to` + `block`（可选 `hollow` 只做外壳）
  - `clear`：`from`/`to`（掏空一个长方体）
  - `cyl`：`x`/`z`/`r`(或 `rx`/`rz`) + `y0`/`y1` + `block`
  - `sphere`：`x`/`y`/`z` + `r`(或 `rx`/`ry`/`rz`) + `block`（可选 `hollow`）
- `props`（可选，blockstate）：`facing` / `half` / `type` / `axis` / `hinge` / `open` / `shape` / `hanging` / `rotation`
- 坐标从 0 开始、必须非负且在 `size` 内；越界与非法方块 id 会被丢弃并记警告

**改契约时务必同步四处**：`BuildingSpec`/`FreeformSpec`（字段）、`SpecParser`（解析与别名）、
`FreeformBuilder`（展开）、`AiClient.SYSTEM_PROMPT_V2`（提示词说明）。否则模型会写出解析器不认的字段。

**画房子的套路**（提示词里已写明，程序不做任何补充）：地基 → 四面墙（4 个 box）→ 楼板 → 上层墙 →
屋顶 → 掏门窗 → 室内楼梯。楼板用 box、隔墙用 4 个 box、楼梯用 `*_stairs` 每格抬高 1、
电梯井四周围墙 + `scaffolding` 当轿厢、门窗用 box 盖在墙上。

---

## 5. 关键设计决策（血泪经验，改之前先读）

### 5.1 ★ 自由形体的三条铁律（改这块时别破坏）

1. **绝不走建筑逻辑**：不铺楼板、不开窗、不摆家具、不加屋顶 —— 船没有屋顶，雕像没有门窗。
   （建筑逻辑本身已随模板删除，但**别把类似的东西加回来**。）
2. **绝不做 `PlanValidator` 体检**：自由形体的"悬空"是常态（船帆、桅杆、缆绳、机翼、雕像手臂），
   体检的"悬空方块修补"会把这些部件**直接删掉**。`SpecBuilder` 现在根本不调 `check()`。
3. **坐标必须非负且在 `size` 内**：`BuildingPlan` 用 `& 0x3FF` 打包坐标，负坐标会破坏内部索引。
   越界/非法方块 id 一律丢弃并记警告；方块总数有上限（`MAX_BLOCKS=100000`，逐块检查，
   否则模型写一个 120³ 的实心 box 会直接吃光内存）。

### 5.2 ★ 铁律：AI 回复不可用时宁可明确报错，绝不静默兜底盖楼

这是本项目最硬的一条，**2026-09-22 有血的教训**。曾经的完整黑路：

`thinking=true` 把输出预算烧光 → `finish_reason=length` 且 `content` 为空 →
`extractContent` 回退去取 `reasoning_content`（半截思考过程）→
`SpecParser` 从思考文本里抠出模型草稿的 `{"op":...}` 片段 → 构造出一份全默认 spec →
盖出一栋与提示词无关的"二层小楼"。玩家只会觉得"AI 又不听提示词"。

现已修：
1. 截断 + 正文为空时抛 `TruncatedReplyException`，**绝不取 reasoning**（并自动关 thinking 重试）；
2. `SpecParser.looksLikeSpec()` 闸门 —— **单个 op 对象 / 空对象 `{}` / 无关 JSON 一律返回 null**；
3. `spec == null` 时**明确抛错**（`buildLegacy`/`PlanParser` 已整条删除，连"对任何文本都能编出一份默认楼"的路都没了）。

**任何"解析失败 → 用默认值继续"的路径，都要先问一句：这个默认值会不会盖出一栋假楼？**

### 5.3 ★ 解析层必须容忍模型的原版命令写法

模型会写 `"block":"oak_stairs[facing=east]"`、`"minecraft:oak_planks"`、`"mirror":"both"`、
多个 `layer` 都不写 `y`、palette 字符没定义……这些都必须在 `SpecParser` / `FreeformBuilder` 里归一化，
**绝不能让它落到 `sanitize()` 被当成"非法方块"整块丢弃** —— 那会让方案缺料甚至变成 0 个方块，
而玩家在面板上只看到"共 0 个方块"，完全看不出原因。

以后再发现新的畸形写法，往 `SpecSelfTest` **第 5 节 `tolerant()`** 里补一条回归。

### 5.4 光照与支撑（仍有效）

1. **吊灯必须吊在实心天花板下**。曾经因为灯笼上方放的是 `minecraft:light`（隐形光源，不是实心方块）
   导致全楼灯笼掉落。`FreeformBuilder.autoHanging` 现在会检查上方是否实心。
2. **`minecraft:light` 的碰撞箱是空的**（`VoxelShape.EMPTY`）。放进楼板/天花板平面会造成
   **看不见的洞，玩家直接掉下去**。
3. `scaffolding` 是**满块碰撞**（不是镂空！）——所以井道里全是实心，玩家站不进去。
   用 `scaffolding` 当电梯轿厢时要注意这点。

### 5.5 ★ 不要再自动去"给没门的房间补门"（用户明确叫停）

用户原话："那就完全不改。就让模板留在我说有的房间没有门的哪个版本，没门就没门吧。"

**更根本的教训**：像"房间有没有门 / 动线通不通"这类判断题，**用户不信任程序去猜**。
现在模板没了，这类问题归 AI 自己在 `ops` 里画对 —— 程序**不要**再去加"自动补门 / 自动清障"之类的兜底。

### 5.6 面板与输入

- **`EditBox` 构造时 `maxLength` 默认 32**。必须**先 `setMaxLength(...)` 再 `setValue(...)`** ——
  历史上因为顺序写反，把用户的 API Key 静默截断成 32 字符，保存后所有请求 401 `invalid_key`。
  这是最隐蔽的一个坑。
- 预览是**自绘软光栅化**，成本高：现在只画暴露面、方块多时按 2×2×2 / 3×3×3 合并、跳过屏幕亚像素方块；
  输入框聚焦（打字）时降到 600 块。**再加重绘成本就会让打字变卡**（用户反馈过两次）。

### 5.7 面板警告的硬约束

`BuildTaskManager.summarizeWarnings`：**只显示前 3 条**，且总长**截断到 160 字**。
所以新增警告文案要压到 50 字内，并且**不能有"每栋楼都会触发"的假警告**去占位置。
`SpecSelfTest` 第 7 节的 soak 用例守着这条（出现"每个组合都触发"的警告就红）。

---

## 6. MC 26.1.2 API 坑（改代码前必看）

| 主题 | 事实 |
| --- | --- |
| Screen 渲染 | 26.1.2 用 `extractRenderState(GuiGraphicsExtractor, int mouseX, int mouseY, float delta)`，不是老的 `render(...)` |
| 鼠标事件 | `mouseClicked(MouseButtonEvent, boolean doubleClick)`、`mouseScrolled(x, y, horizontal, vertical)` |
| HUD | `HudElementRegistry.addLast(Identifier, HudElement)`（`rendering.v1.hud` 包），渲染方法 `extractRenderState(GuiGraphicsExtractor, DeltaTracker)`；**无界面时鼠标被游戏捕获，HUD 不可点击** → 悬浮球只能做纯提示（按 K 查看） |
| 两格方块 | 床/门放置用 `world.setBlock(pos, state, 3, 0)`（4 参）抑制形状自检，否则先放的半格会因搭档不存在而掉落 |
| 结构文件 | 结构方块与 `/place template` 读取路径：`<存档>/generated/data/<命名空间>/structure/<名字>.nbt`（**单数 structure**）；`StructureBlockEntity.MAX_SIZE_PER_AXIS = 48` 只是**结构方块 GUI** 限制，`/place template` 无此判断 |
| 模板缓存 | `StructureTemplateManager` 会缓存已读模板：同名文件改了要重进世界或 `/reload`，或换名字 |
| 方块碰撞 | `scaffolding` = 满块；`minecraft:light` = 空（会踩空）；`iron_bars` 有小碰撞柱；`lantern`/`banner`/`wall_sign`/`pressure_plate` 需要支撑（`canSurvive`） |
| 日志编码 | 可能是 UTF-8 或 GBK；`.log.gz` 要解压 |

**看"AI 给的自由形体规格长什么样"的土办法**（改 freeform 几何时很有用，比反复进游戏快）：
写一个只有 `main` 的小类，用 `SpecParser.parse(json)` + `SpecBuilder.build(spec,64,64,64)` 拿到 `BuildingPlan`，
然后逐层打印 ASCII（有方块打印方块首字母，没有打 `.`），再打印一张 z-y 侧视投影。
放在 `build\scratch\` 下编译运行即可（`build/` 不进仓库，但 `gradle clean` 会连它一起清掉）。

---

## 7. ★★ 当前最严重的问题：AI 请求会无限期卡死（未修）

### 现象

玩家生成建筑时，**700 多秒没有结果**；MiMo 控制台显示 token **先涨后停**；
游戏日志里只有"请求发出"，**既没有"请求返回"也没有"响应超时"**。

实测记录（`logs/latest.log`）：

| 时间 | 事件 |
| --- | --- |
| 19:44:55 | 后端 openai / 模型 `mimo-v2.6-pro` / 思考 **true** |
| 19:46:49 | `请求发出: ... thinking=true (第 1 次)` |
| —— | 之后什么都没有 |
| 19:59:18 | 用户退出游戏 ⇒ 请求挂了 **12 分 29 秒**，没返回也没超时 |

### 根因（已用对照实验证实）

**JDK `HttpClient` 的 `HttpRequest.timeout()` 只保护到"收到响应头"为止，不覆盖响应体读取阶段。**

`AiClient` 里写的是 `.timeout(Duration.ofMinutes(10))`，但它**对这种情况毫无保护作用**。

实验在 `build/probe_timeout/`（JDK 25.0.4，timeout 设 8 秒，两个模拟服务器）：

| 场景 | 结果 |
| --- | --- |
| A：服务端**回响应头 + 1 个 chunk** 后卡住不发完 | **8 秒超时完全没生效**，45 秒仍挂起 |
| B：服务端**什么都不回**（连响应头都没有） | **8 秒准时抛 `HttpTimeoutException`** |

⇒ MiMo 属于**场景 A**：它先回了 `200 + chunked` 响应头，客户端随后进入
`BodyHandlers.ofString()` 无限期等 body 结束；此时 request timeout 已被取消。

### 为什么会卡住

三个因素叠加：① `thinkingEnabled = true`（思考也占输出预算）；② **不发送 `max_tokens`**
（用户要求"无限制"，交给服务端上限）；③ freeform 契约要求 AI **逐格画**，一栋楼 ops 几千~上万 token。
再加上 `stream: false`（非流式）⇒ 服务端必须生成完才结束响应，中途客户端收不到任何数据。

### 附带隐患

- `BuildTaskManager` **完全没有超时/取消逻辑**，只有 `elapsedSeconds()` 显示秒数
- `AiBuildScreen` 提示写"约 10~90 秒"，与实际严重不符
- `AiClient` 的 `EXECUTOR = Executors.newFixedThreadPool(2)` ⇒ **每卡死一次永久占用一个线程**，
  卡两次之后生成功能彻底不可用
- 就算超时正常触发：`maxAttempts = 4` × 600 秒 + 退避 ⇒ 最坏要等约 **40 分钟**
- `AiConfig` 里**没有任何超时配置项**；界面也没有"取消"按钮（ESC 只关面板，请求照旧跑）

### 两条候选修复路线（用户尚未选定）

| | ① 流式 `stream:true` + 空闲超时 | ② 非流式 + `sendAsync()` + 看门狗 |
| --- | --- | --- |
| 发现"半途停滞" | **能**（多久没数据是稳定指标） | 能，但只有总时长（粗粒度） |
| 超时值好不好定 | **好定**（与任务大小无关） | 难定（设短误杀、设长没用） |
| 显示进度 | **能** | 不能 |
| 改动量 | 大（100~150 行，解析层重写） | **小**（20~30 行） |
| 兼容性风险 | 有（SSE 格式差异） | 几乎为零 |

**注意**：无论走哪条，都要顺手处理"超时算不算可重试"，否则 `maxAttempts=4` 会把等待放大 4 倍。

---

## 8. 验证清单（每次改动后照做）

1. `./gradlew build --console=plain` 成功；
2. 自检 **全部通过 ✅**（当前 113 项，7 节）：
   - 第 1 节 JSON 解析（含别名/容错）
   - 第 2 节 自由形体：非建筑物体不再被塞成方盒子楼
   - 第 3 节 blockstate：AI 亲手画楼梯/栏杆/吊灯
   - 第 4 节 提示词示例 2（用 freeform 画房子）必须自身可用
   - 第 5 节 模型常见畸形写法的容错
   - 第 6 节 **模板已全部删除**：`kind=building` 当成 freeform、旧格式一律拒绝、7 个旧类必须 `ClassNotFoundException`
   - 第 7 节 全组合压力测试（44 组合）：不崩溃 / 空方案必有警告 / **没有刷屏的假警告**
3. 部署 jar 到 mods（确保只留一个版本），**退出重进游戏**；
4. 游戏内冒烟：`K` → 输入"造一艘帆船" → 检查：
   - 得到的是**船**（尖船头/弧形船底/甲板/桅杆/白帆），**不是二层小楼**
   - 左右对称（`mirror` 生效）、有白羊毛船帆、没有屋顶平台
   - 再点调整 → 输入"船再大一点、加个船尾楼" → 结果**真的变了**
     （日志里应出现"调整前后规格差异: ..."，不能是"无变化"）
5. 日志核对：
   ```
   [Minecraft AI] 自由形体展开: mirror=x ops=... 尺寸=WxHxD 方块数=...
   [Minecraft AI] 展开警告: ...            （越界/非法方块 id/未知 op）
   [Minecraft AI] 调整前后规格差异: ...
   [Minecraft AI] 结果不合格，自动重新生成一次: ...
   ```
   （**"规格展开: kind=building archetype=..." 这类日志已经不可能再出现**，看到就是回退了。）

---

## 9. 已知限制与待办（v1.1.0 之后）

### 待办（按优先级）

1. **★ 修 §7 的无限期卡死** —— 当前最影响体验的问题，用户还没选定方案。
2. **迭代：3D 预览彻底高清**
   - 现状：已做"只画暴露面 + 粗格 LOD + 亚像素跳过"，仍受软光栅化限制；
   - 目标：把预览**渲染一次到缓存纹理**再逐帧贴图，或做成"静态渲染 + 只在旋转/切换时重建"。
3. **调整流程 diff 化**：目前把上一版规格整份发回给模型，让它返回完整新规格。
   目标：只回传"规格摘要 + 局部 patch 指令"。现在已有 `diffSpec` / `freeformSignature` 日志作为基础。
4. **大建筑分批放置**：`BuildingExecutor` 在一个服务端 tick 内批量 `setBlock`，几十万方块会卡；
   应改为按 tick/区块分批 + 进度提示。
5. **自由形体增强**：`ops` 增加 `axis` 参数支持沿 X/Z 的柱体（目前 `cyl` 只支持竖直方向）；
   给自由形体做"落地平整/悬空连通性"的**轻量**检查（注意：不能沿用被删掉的悬空修补）。

### 已知限制

- 仅**单人游戏**（依赖内置服务器 `server.submit(...)`）。
- 悬浮球**不可点击**（MC 无界面时鼠标被捕获）——只能按 K 查看。
- 预览在极端角度可能有个别深度排序瑕疵（画家算法近似）。
- **自由形体不做任何几何体检**：外形完全由模型负责，画得难看就是难看。
  建议尺寸 ≤48 格、方块数 ≤100000。
- **AI 仍可能沿用旧格式**：虽然提示词已写明"不要写 `kind:"building"`、不要写 `archetype`"，
  模型偶尔还是会写。程序会把它当 freeform 处理；若因此没给出 `ops`，会展开成 0 方块 + 警告 +
  自动带原因重做一次。
- **超时保护缺失**（见 §7）：后端卡住时玩家只能干等或退游戏。

---

## 10. 与用户协作的偏好（非常重要）

这些是用户明确表达过的偏好，违反过会直接被指出来：

1. **全程简体中文**交流；长任务分步汇报；任务完成立即简洁收尾。
2. **改代码前先答"可行性/方案"，等确认再动手**。用户会逐条验证，要求"具体问题具体修"，
   所以**每次改动都要能说清：改了什么 / 怎么验证 / 怎么回滚**。
3. **不要擅自上传 GitHub**；用户说推才推（说"不要上传"就绝不推）。
4. **不要主动更新 `docs/HANDOVER.md`** —— 只在用户说"要更新交接文档"时才写（本文档就是被要求才写的）。
5. **不要采用"生成 nbt 文件 + 玩家用结构方块手动放置"**作为模组的生成方式；
   必须保持"输入提示词和坐标 → 生成 → 3D 预览 → 确认建造"的流程。
6. **UI 与操作方式尽量不变**，改底层逻辑即可。
7. **API Key 绝不能出现在源码/仓库/Release**；上传前自查泄漏。
8. **版本号以用户指示为准**（例如一直保持 `1.1.0`，只更新内容不升版本）。
9. 遇到"某功能太绝对、不适应各种风格"这类反馈，优先做成**跟随输入自适应**的规则，而不是硬编码兜底。
10. **用户不想要"程序提供的模板/选项"**：目标状态是 AI 按提示词自己创作。
    2026-09-22 已彻底贯彻 —— 模板全删，只留 freeform。
11. **改完要能自证**：每轮改动都要跑自检 + `gradlew build`，把"通过项数 / jar 大小"报给用户。

---

## 11. 马上能做的第一件事（建议）

```bash
cd "D:/一些AI coding的成果/Minecraft AI小组件"
git log --oneline -3        # 确认 HEAD 还是 3de4a60（本地改动未提交）
./gradlew build --console=plain
tools\selftest.bat          # 等价命令见 §2
```

然后：**先看 §7 的卡死问题**（最影响体验，用户已有两条候选方案），
或按 §9 待办挑一项开工；若只是维护，优先看 §5、§6 的坑，避免重复踩。

---

*本文档 2026-09-22 重写，对应本地工作区（v1.1.0 + 模板全删 + freeform 单契约，**未提交**）。
GitHub 上的 `3de4a60` 是它的前身，不含 freeform 通道。*
