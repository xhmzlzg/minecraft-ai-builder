# v1.1.0 底层改造说明（规格契约 + 参数化构件库 + 方案体检）

> ⚠️ **2026-09-22 最新状态：全部建筑模板与旧版字符画管线已删除，程序只剩 `freeform` 一种契约 —— 见第十节。**
> 第一~九节记录的是演进过程，其中"建筑模板 / archetype / 参数化构件库 / 旧字符画管线"相关的描述
> **只反映当时状态，不代表现在的实现**。

> UI 与操作方式完全不变：K 键面板 → 描述 + 坐标 → 生成 → 3D 预览 → 确认建造 → 输入意见调整。
> 变的只是"AI 说什么、程序怎么盖"。

## 一、为什么要换契约

旧版让 AI 输出 8~24 格的**字符画蓝图**（`# W D P S R B C F L T X`）与三个全局颜色字段。
信息密度太低：层高、户型、房间语义、家具、楼梯、电梯、屋面构件、blockstate 都没有地方表达，
模型只能退化成"方盒子 + 每层复制"，玩家写得再细也落不进去。

新契约让 AI 只输出**设计规格（DSL）**，具体每块砖由本地构件库确定性地展开：

```json
{
  "spec_version": 2,
  "name": "中式高层居民楼",
  "archetype": "chinese_highrise",
  "floors": 11,
  "layer_height": 4,
  "features": ["stairs", "elevator", "balcony", "roof_equipment"],
  "materials": { "wall": "white_concrete", "accent": "brown_terracotta" },
  "rooms": [ {"type": "living", "x": 0, "z": 0, "w": 6, "d": 5} ],
  "roof": { "style": "flat", "parapet": true, "water_tank": true, "solar": true },
  "notes": "玩家提到的其它细节"
}
```

- 房间 `type`：living / master / bed2 / bed3 / kitchen / bath / dining / entry / hall /
  balcony / study / storage / stairs / lobby / courtyard / pool / garden
- ~~`archetype`：只填 `chinese_highrise`（一梯两户板楼，自动生成双跑楼梯+脚手架电梯+候梯厅）。~~
  ⚠️ **已过时**：全部建筑模板（含 `chinese_highrise`）已于 2026-09-22 删除，见**第十节** —— 现在只有 `freeform` 一种契约
- 字段几乎都可省略：只给 `floors` 也能盖出完整建筑（按原型默认户型、材质、屋面）
- 尺寸缺省时自动贴合玩家框选范围；框选高度不足时自动减层

## 二、代码结构（新增）

| 文件 | 作用 |
| --- | --- |
| `common/spec/BuildingSpec.java` | 规格 DTO + 各原型默认材质表（Pal） |
| `common/spec/SpecParser.java` | 宽容解析（别名/代码块/尾随逗号），并识别旧契约走兼容路径 |
| `common/spec/Layout.java` | 平面布局基础类型（矩形/房间/缩放/去重叠） |
| `common/spec/SpecBuilder.java` | 规格 → 具体方案的展开器（板楼户型 / 通用分格 / 门洞生成 / 屋面 / 立面） |
| `common/spec/PartLib.java` | 构件库：铺/挖/墙/窗/门/栏杆/双跑楼梯/电梯井/女儿墙/屋面设备 |
| `common/spec/Interiors.java` | 家具库：按房间类型摆床/沙发/灶台/马桶/晾衣杆…… |
| `common/PlanValidator.java` | 方案体检 + 自动修补（悬空方块、楼板空洞、堵门、房间可达） |
| `common/BuildingPlan.java` | `Entry` 增加 blockstate 属性（props），支持楼梯朝向/半砖/脚手架 distance 等 |
| `common/BuildingExecutor.java` | 按属性名还原 blockstate；旧的后缀写法继续兼容 |
| `selftest/SpecSelfTest.java` + `tools/selftest.bat` | 离线自检：不开游戏就能验证几何正确性 |

## 三、几何保证（沿用已验证存档方案的经验）

1. 灯笼一律吊在**实心天花板**下（旧版因为上方是隐形光源方块而全部掉落）；
2. 隐形光源只放房间空气格，**绝不进楼板**（`minecraft:light` 碰撞箱为空，会踩空）；
3. 每层先盖"壳"再装修 —— 否则天花板还不存在，吊灯会被跳过；
4. 门洞由房间相邻图求**生成树**得到：保证每间都连通，又不会每面墙都开洞；
5. 家具不许堵门：生成期主动清障，体检再复核；
6. 电梯：脚手架井筒 + 铁质轿厢（铁底/铁顶/顶灯）+ 双开铁门（铰链朝两侧）+ 门内压力板 + 井道按钮；
7. 楼梯：双跑、每层 8 个半格踏步升高一整层，楼梯井贯通且净空 ≥2 格。

## 四、自检怎么跑

```bat
tools\selftest.bat
```

会对 8 种建筑（33×19 / 45×19 / 25×19 / 33×25 板楼、别墅、城堡、庭院、小楼）检查：
方块数、门/灯/半砖/楼梯数量、带属性方块数量、楼板层无隐形光源、屋顶封顶 ≥95%、
多层有楼梯，以及"二次体检零修正"（说明生成的方案本身就是干净的）。

## 五、兼容与回退

- ~~旧契约（含 `floors_map` 的字符画蓝图）仍可解析，走 `PlanParser` + `PlanGenerator` 旧管线。~~
  ⚠️ **已过时**：旧管线已于 2026-09-22 **整条删除**（见**第十节**）——
  `SpecParser.parse()` 对旧格式返回 null，`BuildTaskManager` 会明确要求模型改用 `freeform` 重画；
- **回退方式**：`mods/` 目录里保留了上一版 jar 的备份 `minecraft-ai-1.1.0.jar.bak` ——
  删掉当前的 `minecraft-ai-1.1.0.jar`，把 `.bak` 改名回 `.jar` 即可（改名前建议先关游戏）。

---

## 六、自由形体通道（后续补丁，版本号仍为 1.1.0）

> ⚠️ 本节写于模板尚存之时。**2026-09-22 已把全部建筑模板与旧版字符画管线删除**（见**第十节**），
> 本节里"建筑契约 / archetype"相关的描述均为历史记录。

### 为什么要加

建筑契约的 `archetype` 只有 5 个值，且 `generic` 的唯一实现是"切房间 + 外墙 + 门窗 + 屋顶"的方盒子楼
（`defaultFloors("generic") = 2`）。玩家要"一艘船"时，模型只能塞成 `generic`，
`SpecParser.normalizeArchetype()` 还会把 `boat`/`ship` 静默折叠成 `generic` ——
**输出空间里根本没有"船"这个选项**，所以必然得到二层小楼；调整路径同理（形体字段无处表达"改成船"）。

### 契约

```json
{
  "spec_version": 3,
  "kind": "freeform",
  "name": "帆船",
  "size": [15, 9, 9],
  "palette": { "h": "dark_oak_planks", "m": "oak_log", "s": "white_wool" },
  "mirror": "x",
  "ops": [
    { "op": "layer", "y": 0, "rows": [".....hhh", "....hhhh", "...hhhhh", "..hhhhhh", ".hhhhhhh"] },
    { "op": "box", "from": [0, 3, 0], "to": [3, 3, 8], "block": "d" },
    { "op": "clear", "from": [1, 3, 1], "to": [2, 3, 7] },
    { "op": "cyl", "x": 7, "z": 4, "r": 0, "y0": 3, "y1": 8, "block": "m" },
    { "op": "sphere", "x": 7, "y": 7, "z": 4, "r": 3, "block": "s", "hollow": true }
  ]
}
```

- `size` 可省（按 ops 实际范围自动算）；`palette` 可省（用内置字符表）；
  `mirror` = `none|x|z|xz`，只写一半由程序镜像；
- `ops` 按顺序执行，后面的覆盖前面的；`layer` 的 `rows[z]` 第 x 个字符决定该格方块，`.`/空格 = 不放置。

### 三条实现铁律

1. **不走建筑逻辑**：`SpecBuilder.build()` 在 freeform 分支直接返回，不铺楼板、不开窗、不摆家具、不加屋顶。
2. **不做 `PlanValidator` 体检**：自由形体的悬空是常态（船帆/桅杆/机翼/雕像手臂），
   悬空修补会把这些部件删掉 —— 因此返回空 Report。
3. **坐标必须非负且在 `size` 内**：`BuildingPlan` 用 `& 0x3FF` 打包坐标，负坐标会破坏内部索引；
   越界/非法方块 id 一律丢弃并记警告；方块总数在 `put()` 里逐块检查，超 `MAX_BLOCKS=100000` 直接报错。

### 自动重做（"AI 不按提示词生成"的兜底）

`BuildTaskManager.mismatchHint()` 检测三类不合格输出，通过 `AiClient.askPlan(..., repairHint)` 明确告知模型并重做一次
（只重做 1 次，`attempt < 1`，避免死循环）：

1. 还在用**已删除的旧格式**：回复含 `floors_map`（旧版字符画蓝图），或 `kind:"building"` / `archetype`
   （建筑模板已全部删除，见**第十节**）—— 解析为 null 后给出"改用 freeform"的明确提示；
2. 规格解析得出来、但**一个 `ops` 都没有**（`kind:"building"` 被当成 freeform 后就会这样）⇒ 会盖出 0 个方块；
3. 调整模式下新规格与旧规格关键字段完全一致（= 什么都没改；`ops` / `size` / `palette` / `mirror` 都参与比较）。

另外，即使 `mismatchHint` 没拦下、展开结果真的是 0 个方块，`BuildTaskManager` 也会带着
"为什么会空"（palette 字符对不上 / 坐标越界 / 非法方块 id）再打回重做一次。

### 自检新增用例

`tools\selftest.bat` 第三节会检查：`rows` 不被误判成旧契约、`mirror` 左右对称、
**顶层不铺满底面且没有整层楼板**（= 不是方盒子楼）、悬空的帆/桅杆不被删、
`box/clear/cyl/sphere` 生效、越界与非法 id 被丢弃、巨物被拦下、`{"block":"air"}` 当成删除、高度超框选有提示，
以及两条容易再踩的坑：**提示词里的帆船 few-shot 示例自身必须合格**（零警告 / 镜像对称 / 帆挂在桅杆上 / 桅杆只有 1 根）、
**用了 `mirror` 但没写 `size` 时宽度要按 `2N-1` 正确推断**（否则形状会被镜像折回自身）。

第四、五节验证 blockstate 与"用 freeform 画房子"：`props` 透传、镜像翻转 `facing`/`hinge`/`shape`/连接方向、
栏杆自动连片、灯笼自动吊挂、孤立方块审计只报告不修补，
以及**提示词里的两层木屋示例**（零警告 / 室内是空的 / 门窗到位 / 楼梯每级有支撑 / 没有程序自动配的家具）。

第六节专门盯**模型常见畸形写法** —— 这些都不是契约里写的标准写法，但模型（尤其被 Minecraft 命令语料带偏时）
真的会这么写，改动前每一条都会让方块被**静默丢弃**，轻则缺料、重则整个方案 0 个方块：

| 畸形写法 | 以前的结果 | 现在的行为 |
|---|---|---|
| `"block":"oak_stairs[facing=east]"`（状态嵌在方块名里） | `sanitize()` 只认 `[a-z0-9_]+` ⇒ 整块丢弃 | 解析期拆成 id + props（`SpecParser.splitState`） |
| `"block":"minecraft:oak_planks"` | 前缀让正则不匹配 ⇒ 丢弃 | 剥掉前缀 |
| `"palette":{"S":"oak_stairs[facing=west]"}` | 同上，整块丢弃 | 拆成 `palette` + `paletteProps`，展开时合回 |
| `"mirror":"both"` | 里面没有 x/z ⇒ 当成**不镜像**，船只剩半条 | `mirrorAxes()` 识别 both/all/双向/双轴 |
| 多个 `layer` 都不写 `y` | 全部叠在 `y=0` 互相覆盖 ⇒ 只剩一层 | 按"逐层往上"递增分配 |
| `layer` 的字符不在 palette 里、但 op 有 `block` | 整层消失 | 用 `op.block` 兜底（留空字符除外） |
| 真的一格都画不出来 | 面板只显示"共 0 个方块"，看不出原因 | `BuildTaskManager` 检测到空方案后**带原因打回重做一次** |
| JSON 后面跟一段带 `}` 的说明文字 | `lastIndexOf('}')` 把说明也吞进来 ⇒ 解析失败 | `SpecParser.firstBalanced()` 按括号配对抠出第一个完整对象 |

### 请求层：输出长度交给服务端（2026-09-22 起）

freeform 的输出量比建筑契约大一个量级（提示词里那栋两层木屋示例就有 22 个 op、1500+ 字符），
而不少 OpenAI 兼容服务的**默认上限只有 1024 / 2048** ⇒ 稍复杂的作品会被截断。
截断后的 JSON 必然解析失败，玩家只看到"解析失败"，**症状与"模型不按提示词生成"几乎一致**，极易误判。

- **不发送 `max_tokens`**，由服务端按自己的默认值决定（等于把上限交给服务端 = 无限制）。
  早期版本写死 `8192`，两头都是坑：服务端默认更小时照样截断，而超过某些模型上限时会被直接 4xx 拒掉。
- 响应里 `finish_reason == "length"` 时日志明确写"回复被输出长度截断"，并建议把作品画简单些
  或关掉思考模式（思考内容也占服务端的输出预算）。
- **截断 + 正文为空**时抛 `TruncatedReplyException`，**绝不回退去取 `reasoning_content`** ——
  那是半截思考过程，`SpecParser` 会从里面抠出模型草稿的 `{"op":...}` 片段，展开成一栋与提示词无关的假楼。
- `mismatchHint()` 新增一条：**解析不出 JSON、但回复里带着 `ops`/`kind`/`freeform` 等契约字段**时，
  提示"JSON 不完整"（截断 / 夹了说明文字 / 用了中文标点）而不是含糊的"解析失败"。

---

## 七、默认路径翻转：从"程序盖"到"AI 画"（最新补丁，版本号仍为 1.1.0）

### 为什么要翻

第六节加了 freeform 通道，但它**只被路由给"非建筑"**。只要模型判定为"建筑"，就又被关回模板。
而代码事实是：`SpecBuilder.build()` 里只有 `arch == "chinese_highrise" && W >= 25 && D >= 17` 才走
`highriseModel(spec, W, D)`；其余 **4 个 archetype（courtyard / villa / castle / generic）全部共用
`genericModel(W, D, floors > 1)`** —— 把内部切成 2×2 或 3×2 房间 + 外墙 + 门窗 + 平屋顶。
`castle` / `chinese_courtyard` 与 `generic` 的**几何完全相同**，只有默认材质/层数不同（`Pal.of` / `defaultFloors`），
**名字是装饰性的**。

更严重的是 `genericModel(int W, int D, boolean needStairs)` **签名里没有 spec** ⇒ AI 写的 `rooms` 户型表
在这些 archetype 下被**直接丢弃**（`spec.rooms` 只在 `highriseModel` 第 204~206 行被用）。
全项目 `spec.has(...)` 只在 `buildFacade` 里读了 `no_ac` ⇒ `features` 里的 `stairs`/`elevator`/`balcony`/`courtyard`/`pool`/`garden`
对非 highrise 建筑**基本无效**。于是 AI 对非 highrise 建筑真正能控制的只有
`floors` / `layer_height` / `size` / `materials` / `roof.*` / notes 关键词 —— **形状永远是"方盒子 + 房间网格"**。

结论：想让"无论输入什么提示词都按提示词生成"，就必须让 **AI 成为形状的唯一作者**，模板只在玩家点名时才用。

### 改了什么

| 位置 | 改动 |
| --- | --- |
| `AiClient.SYSTEM_PROMPT_V2` | 整段重写。第一步从"判断建筑 or 自由形体"改成**"默认就是你自己画（freeform）"**；`kind:"building"` 降级为"玩家点名标准住宅楼"时的例外；契约顺序调换（A = freeform，B = building）；新增 `props` 说明表；新增**示例 2：9×9×7 两层木屋** |
| `FreeformSpec.Op` | 新增 `props`（blockstate 属性） |
| `SpecParser` | `parseOp` 认 `props` / `state` / `blockstate`，值统一转字符串 |
| `FreeformBuilder` | `put()` 透传 props；新增 `flipProps()`（镜像翻转朝向）；新增 `autoHanging()` / `connectPass()` / `audit()` 三道后处理；`Result` 增加 `blocks` / `isolated`；`isAir()` 把 `{"block":"air"}` 当成删除；补高度超框选提示 |
| `SpecBuilder.Result` | 暴露 `isolated`，供日志与自检使用 |
| `BuildTaskManager` | 新增 `STOCK_BUILDING_WORDS` 与"没点名却用模板 ⇒ 打回"的校验；`freeformSignature` 纳入 `props` |
| `PlanPreviewWidget` | 配色三层匹配：精确表补 ~60 项 + **形状后缀剥离**（`stone_bricks_stairs` → `stone_bricks`）+ 关键词长尾兜底（注意 `sandstone` 必须排在 `stone` 之前） |
| `BuildingExecutor` | 未知方块 id 不再静默跳过，改为统计并写警告日志（最多列 8 个） |
| `AiBuildScreen` | 文案跟上："建造描述"、提示改为"中式小楼 / 三桅帆船 / 石拱桥"、状态栏"建筑 / 载具 / 雕塑都行" |

### 镜像时的 blockstate 翻转规则

来自镜像的几何性质，`FreeformBuilder.flipProps()` 实现：

- `facing`：x 镜像翻 东↔西，z 镜像翻 南↔北；
- `hinge` / 楼梯 `shape`：**单轴**镜像翻转左右手性（`left↔right`、`inner_left↔inner_right`…），
  双轴镜像（`xz`）等于旋转 180°、手性不变 —— 判据是 `mx != mz`；
- `rotation`（告示牌，0=南 4=西 8=北 12=东）：x 镜像 `(16-r)%16`，z 镜像 `((8-r)%16+16)%16`；
- 栏杆/玻璃板/矮墙的 `east/west/north/south` 布尔连接键：按轴向换边（仅当值是 `true`/`false` 才动）。

注意 `connectPass()` 排在 `mirror()` **之后**，所以绝大多数栏杆的连接属性是**按镜像后的真实邻居重新算的**，
不依赖翻转；`flipProps` 里的连接键换边只处理"模型自己显式写死了方向"的情况。

### 代价（写清楚，别当没发生）

1. **模型可能仍沿用旧格式**：第一次常给 `kind:"building"`（不带 `ops`），被打回后才自己画 ⇒ 多一次 API 往返；
2. **提示词变短了**：契约 B 与全部建筑硬性规则已随模板删除（第十节）；
3. **AI 自己画的房子没有自动体检、没有自动配家具** —— 只有"孤立方块"这类只读审计；
4. 方块数更大（AI 逐块描述 vs 5 个字段），预览走 LOD 粗格降级，建造耗时也更长。

---

## 八、模板路径的四缺陷修复 + 核心筒门槛提示（最新补丁，版本号仍为 1.1.0）

玩家实测反馈的四个现象：①高层住宅楼部分房间没门、要打破方块才能进；②层数不满足要求；
③二层小楼房间太空旷、楼梯简陋；④楼梯在一楼天花板处断开、上不去二楼。

### 三个确凿根因

| # | 根因 | 证据 | 修法 |
| --- | --- | --- | --- |
| 1 | 家具用 `BuildingPlan.set`（**覆盖写**）把门的下半格顶掉 —— `Interiors.bedroom` 的床头柜正好摆在门口那一格 | 高层一层 41 个门块里 **8 处**只剩上半格 | 新增 `repairDoors()`，在清障之后统一把门的下半格补回来 |
| 2 | `doorwayBetween` 不校验垂直重叠（`overlapMid` 在不重叠时返回非法值）⇒ 邻接图含大量假边，真门没开、假位置被凿洞 | `living`(x1-6) 与 20 格外的 `u2 balcony`(x26-31) 被判相邻并"开门"在 (26,3) | `overlapMid` 不重叠时返回 `Integer.MIN_VALUE`；`doorwayBetween` 四段都加校验 |
| 3 | `genericModel` 楼梯间兜底用 `rooms.removeIf` **整间删房** ⇒ 15×13 时楼梯间四周留下 **4 格厚**墙，超过开门算法的 1~3 格上限 ⇒ 楼梯间零门 | `候选门对: 0(stairs)=[]`；二层 0/63 格可达 | 新增 `carveOut()`：与洞重叠的房间取"洞外侧最大余料"（≥2×2），不再整间丢弃 |

**"楼梯上不去二楼"不是楼梯的问题** —— 竖井剖面与 `switchbackStair` 完全吻合，井底 BFS 能爬到上一层。
是**一楼那层进不去**（根因 3），所以门一通就上得去了。

### 改了什么

| 位置 | 改动 |
| --- | --- |
| `SpecBuilder.carveDoors` | 整段重写：按净空切连通区 → 选锚区（首层=单元门内侧，楼上=楼梯间）→ 多源 BFS 找**最薄穿墙路径** → 沿路径凿 2 格高 + 放门 → 循环至无孤立区。电梯井/管道井整圈 `skip` |
| `SpecBuilder.openBand` | **门算通道**（原来把门当实心，导致连通区永远合不上、每轮重复凿、最后误报"有 N 处房间原本进不去"—— 实测 33×19 八层楼每层误报 140 处） |
| `SpecBuilder` 层数 | 框选高度不够**不再偷偷压层数**：按玩家要的层数照盖、向上溢出，只在面板提示超出多少 |
| `SpecBuilder` 核心筒门槛 | `chinese_highrise` 且框选 < 25×17 时**明确提示**"放不下电梯核心筒，已退成无电梯的通用户型"（原来静默降级）<br>⚠️ **已被第九节取代**：通用户型已删除，现在直接报错并让 AI 改用 `freeform` |
| `Interiors.dress` / `spot` | 家具密度 1% → 3~8%（贴墙小件 + 中央地毯 + 大房间书架隔断）；`spot` 只在空格上放，不覆盖门窗 |
| `PartLib.switchbackStair` | 几何不变，把"半砖+整砖"换成**真楼梯方块**（带 `facing/half/shape`）+ 中缝扶手；材质跟随 `pal.stone` / `pal.rail` |
| `SpecBuilder.buildShell` 管道井 | 井道内部（`f+1 .. f+lh-1`）**填满 `iron_bars`**。它四面封死、玩家进不去（`carveDoors` 里 `pipe` 被 `skip`），原来是个纯空洞，破墙进去会很莫名其妙；填上后像"一根通到底的管井"，顺带让可达性检查从"每层缺 3 格"变成**每层缺 0 格** |

### 核心筒门槛为什么是 25×17

不是拍脑袋：宽 = 外墙1 + 户型7 + 隔墙1 + **核心筒7** + 隔墙1 + 户型7 + 外墙1 = **25**；
深 = 外墙1 + 户型进深15 + 外墙1 = **17**。`highriseModel` 里 `uBase = (W - 11) / 2 < 7` 就返回 null。
~~比这个小就只能退成通用户型（无电梯、无候梯厅）—— 所以必须提示，不能静默。~~
**2026-09-22 更新**：通用户型已删除，比这个小现在会**直接报错**（见第九节），
并由 `BuildTaskManager` 带原因让 AI 改用 `freeform` 重做一次。

### 自检新增（第 7、8 节，共 183 项）

**第 7 节 `highriseGate()`**：25×17 不报门槛提示且真有电梯井；24×17 / 25×16 必须报提示且确实无电梯井；
`generic` 不被这条提示打扰；管道井确实填了铁栏杆。

**第 8 节 `soak()` 全组合压力测试**（270 个组合，约 1 秒）：5 原型 × 6 尺寸 × 3 层数 × 3 层高，
断言「不崩溃 / 不出 0 方块 / 每个方案都有门 / **没有白名单外的警告**」。

最后一条是**假警告的看门狗**。为什么需要它：`BuildTaskManager.summarizeWarnings` **只显示前 3 条**，
一条"每栋楼都会触发"的假警告就能把真正的提示挤掉 —— `openBand` 把门当实心时就出过这个事
（33×19 八层楼每层误报 140 处"房间原本进不去"，面板全被刷屏）。
以后新增警告，必须先想清楚它会不会在**每一栋楼**上触发；会的话就加进 `KNOWN_WARNINGS` 之前先掂量一下。


---

## 九、删掉"通用户型"模板 + 输出长度不再设限（2026-09-22，版本号仍为 1.1.0）

> 本节**取代**第八节里"放不下核心筒就退成通用户型"的做法，以及第七节里
> "其它 archetype 共用同一个 `genericModel()`"的描述 —— 那些说的是本节之前的代码。

### 9.1 动机（用户原话）

> 直接删掉"通用户型"模板，只保留通用住宅楼一个模板，不符合此模板时，由AI自由生成。

和第七节同源：`genericModel` 是一个"方盒子 + 房间网格"，任何 archetype 都能落进去。
于是玩家要别墅 / 四合院 / 城堡 / 写字楼时，全被盖成同一栋方盒子楼 ——
**等于用程序自带的模板顶替了 AI 的创作**，这正是用户反复报的问题。

### 9.2 改了什么

| 位置 | 改动 |
|---|---|
| `SpecBuilder` | 删除 `genericModel()`（连带只被它使用的 `pickType()` / `split()`，共 -86 行）。`build()` 里 `model == null` 不再静默降级，改为 `throw new IllegalArgumentException`，消息含**实际框选尺寸**、`25×17`、以及"请改用 `kind=\"freeform\"`" |
| `SpecBuilder` | `build()` 开头新增 freeform 分支（走 `FreeformBuilder`）；`Result` 扩展出 `warnings` / `isolated` 两个字段 |
| `SpecBuilder` / `BuildingSpec` / `SpecParser` | **缺省 `archetype` 从 `generic` 改成 `chinese_highrise`**（共 5 处）。否则模型写了 `kind:"building"` 却漏写 archetype 时会直接失败 |
| `BuildingSpec.Pal.of` | switch 收敛成 `chinese_highrise` + `default`，删掉 courtyard / castle / villa 三套已无去处的配色 |
| `BuildTaskManager` | ① `mismatchHint()` 的 A/B 两类提示语改成"模板只剩标准住宅楼、其它 archetype 直接报错"；② **新增闭环**：`SpecBuilder.build` 抛 `IllegalArgumentException` 时，若还没纠正过（`attempt < 1`）就带原因让模型改用 `freeform` 重做一次，第二次仍不合格才把报错显示给玩家 |
| `AiClient` 系统提示词 | "5 个 archetype 共用一个方盒子实现" → "只有 `chinese_highrise` 一个实现，其它填了会直接报错"，并写明最小 25×17 |
| `AiClient` 请求体 | **不再发送 `max_tokens`**（删掉 `useMaxTokens` / `maxTokens=8192` 与"服务端拒绝就退让"的重试分支），输出长度交给服务端 |

### 9.3 为什么"缺省 archetype"必须跟着改

`kind:"building"` 但不写 `archetype` 时，旧缺省值是 `generic` —— 那个模板删掉以后，
这个缺省会直接把"玩家点名要标准住宅楼、模型只是漏写 archetype"变成失败。
唯一的模板就是缺省值，这是删除 `genericModel` 的必然结果，不是顺手改的。

### 9.4 自检怎么跟着改（`selftest/SpecSelfTest.java`）

- 第 2 节删掉 4 条非模板用例，只留标准住宅楼。
- 第 7 节 `highriseGate()` / `stairGate()` / `bedroomGate()` 合并成 `templateGate()` + `expectThrow()`：
  25×17 必须正常展开（有电梯井、有 9 段双跑楼梯的踏步半格、体检 `clean()`、无警告）；
  24×17 / 25×16 / 其余 4 个 archetype 必须**明确抛 `IllegalArgumentException`**，且报错里指向 `freeform`。
- 第 8 节 `soak()` 重写成 **315 个组合、两条互斥路径**：
  ① 能盖的（`chinese_highrise` 且 ≥25×17）必须不崩 / 不空 / 有门；
  ② 其余必须抛 `IllegalArgumentException` —— 新增 `silent`（本该报错却悄悄出了方案）
  与 `wrongType`（抛了别的异常）两个计数器，专门守住"绝不静默降级"。
- `KNOWN_WARNINGS` 清空成空集：生成期已不再产生任何警告（模板路径抛错，freeform 走另一条路）。
- 合计 **151 项断言全绿**；`./gradlew build` 产出 `minecraft-ai-1.1.0.jar` = 165583 字节。

### 9.5 一条被推翻的旧说法

第八节末尾写着"管道井确实填了铁栏杆"。**基线代码里没有这回事** ——
实测一栋 25×17 十层楼只有 53 根 `iron_bars`，全部来自
楼梯中缝栏杆（`PartLib.switchbackStair` 每段 3 根 × 9 段 = 27）与外立面空调栏杆（`buildFacade` 26 根）。
自检里那条 `iron_bars >= 10*3*3` 的断言因此被删掉，换成口径唯一的 `polished_andesite_slab`
（只有双跑楼梯用它；10 层 = 9 段 × 3 个半格 = 27）。
⚠️ 注意 `polished_andesite` 同时是户型阳台 / 公共区地砖，**不能拿它当楼梯的判据**。

### 9.6 已知遗留（用户明确接受，不是待修 bug）

`carveDoors` 按"房间相邻图求生成树"开门，生成树在**房间图**上一定连通，但门凿在"两间房之间的墙带"上；
墙带 2~3 格厚时 `clearDoorways` 只清门口 ±1 格，够不到对面房间。
实测 33×17 标准住宅楼**核心筒与西户之间那面墙（x=12, z=1..16）全实心**，整户只能从外立面大门进、
户内与公共走廊不通。用户明确要求**保持现状**：

> 那就完全不改。就让模板留在我说有的房间没有门的哪个版本，没门就没门吧。

被放弃的补门版本留档在 `build/revert_backup/SpecBuilder.java.publicrooms-v1`，**不要自动再去做**。


---

## 十、★ 删除全部建筑模板与旧版字符画管线（2026-09-22，版本号仍为 1.1.0）

> 本节**取代**第一~九节里所有"建筑模板 / archetype / 参数化构件库 / 旧字符画管线"的描述。
> 用户原话：**"备份现在的版本，然后删除模板，以后输入任何提示词都由AI自行创作"**；
> 删除范围确认为**全部删**；AI 若仍输出 `kind:"building"`，**直接当成 freeform**（不报错）。

### 10.1 为什么是"全部删"

第九节删掉了"通用户型"，只留 `chinese_highrise`。但用户实际要的那栋楼
（"在楼的长边开入楼门，进门两侧分别有一户，前方是电梯间（脚手架当电梯）和楼梯间"）
与 `carveEntrance` 的实现不符：它按房间表顺序找"第一个贴外墙的房间"就开门、开完立刻 `return`，
而**西户 9 间房排在核心筒前面**、西户 `entry` 又贴南长边 ⇒ 入楼门永远被开进西户自家玄关
（实测 33×17 → `(10,16)`；25×17 → `(6,16)`；51×27 → `(18,26)`），
而公共门厅 `hall(13,11-19,15)` 贴南长边、正中应该是 `x=16`。

用官方 v1.1.0 jar 实跑 dump 首层，结果与当前部署版**逐格一致** —— 模板本身没问题，
是"模板的形状注定要跟提示词打架"。用户因此决定**不再保留任何模板**：
形状完全由 AI 的 `ops` 决定，程序只负责把指令翻译成方块。

### 10.2 删了什么（约 1530 行）

| 文件 | 行数 | 作用（已废弃） |
|---|---:|---|
| `common/PlanGenerator.java` | 373 | 旧契约：字符画蓝图 → 方案 |
| `common/PlanParser.java` | 256 | 旧契约：回复 → PlanSpec |
| `common/PlanSpec.java` | 33 | 旧契约 DTO |
| `common/spec/PartLib.java` | 335 | 构件库：门窗/栏杆/双跑楼梯/电梯井/女儿墙/屋面设备 |
| `common/spec/Interiors.java` | 277 | 家具库：按房间类型摆床/沙发/灶台/马桶… |
| `common/spec/NotesParser.java` | 132 | 把 `notes` 自由文字解析成生效字段（仅建筑） |
| `common/spec/Layout.java` | 124 | 平面布局基础类型（矩形/房间/缩放/去重叠） |

`PlanValidator.java` **保留**（`BuildingPlan.isSupporting()` 仍在用它判定安装面），
但自由形体路径不再调用它的 `check()`。

### 10.3 收敛了什么

| 文件 | 行数 | 说明 |
|---|---|---|
| `BuildingSpec.java` | 160 → 31 | 只留 `specVersion` / `kind` / `name` / `freeform`；删 `archetype` / `floors` / `layerHeight` / `unitsPerFloor` / `size` / `features` / `materials` / `rooms` / `roof` / `notes` 与内部类 `RoomSpec` / `RoofSpec` / `Pal` |
| `SpecBuilder.java` | 822 → 62 | 只做 freeform 分发；删 `highriseModel` / `unitRooms` / `separate` / `buildShell` / `furnishFloor` / `clearDoorways` / `carveDoors` / `carveEntrance` / `buildStairs` / `buildRoof` / `buildFacade` / `fixDoorLintels` 等全部建筑几何；`Result` 只留 `plan` / `warnings` / `isolated` |
| `SpecParser.java` | 636 → 400 | 只解析自由形体；删建筑解析、`normalizeArchetype()`、`looksLegacy()`、`normalize()`。**全部容错归一化原样保留**（别名 / 代码块 / 尾随文字 / 尾随逗号 / 嵌 blockstate / `minecraft:` 前缀 / 多个缺 `y` 的 layer / `layers` 简写） |
| `BuildTaskManager.java` | 597 → 519 | 删 `buildLegacy()` / `extractFloorHint()` / `looksLegacy` 分支 / `NotesParser.apply` / 模板报错闭环 / `OBJECT_WORDS` / `BUILDING_WORDS` / `STOCK_BUILDING_WORDS` / `containsAny()`；`diffSpec()` 改成只比 `size` / `mirror` / `ops` / `palette` / `notes` |
| `AiClient.SYSTEM_PROMPT_V2` | — | 整段重写：删"契约 B：建筑"、"唯一例外"、建筑硬性规则 1~9、`archetype` 说明；改为**唯一契约 `kind="freeform"`** + "不要写 `kind:"building"`、不要写 `archetype`"，并补上"**画房子（住宅 / 写字楼 / 别墅 / 商场）的套路**"（楼板 / 隔墙 / 楼梯 / 电梯井 / 门窗全由自己画） |
| `FreeformBuilder` / `FreeformSpec` | — | 只改过时注释（原来写着"建筑契约只剩标准住宅楼一个"） |

### 10.4 `kind:"building"` 现在怎么走

**直接当成 freeform**（用户明确选择，不报错、不特判）：

1. `SpecParser.parse()` 把 `kind` 一律归一化成 `"freeform"`，照常解析 `palette` / `size` / `mirror` / `ops`；
2. 若 AI 只写了 `kind:"building"` + `archetype` + `floors`（**没有任何 `ops`**）⇒ `ops` 为空 ⇒ 展开成 **0 个方块**；
3. `mismatchHint()` 在重做前就识别出这种写法，明确告诉模型"模板已全部删除，请改用 freeform 用 ops 亲手画"；
4. 万一还是 0 个方块，`BuildTaskManager` 再带一次"为什么会空"打回重做；
5. 两次都不行就**明确失败**给玩家看 —— **绝不静默兜底盖楼**（全项目最硬的一条铁律）。

### 10.5 自检怎么跟着改（`selftest/SpecSelfTest.java`）

- 删掉全部建筑模板用例（原第 2 节 4 条板楼、第 7 节 `templateGate()` / `expectThrow()`、
  以及只服务于它们的 `run()` / `spec()` / `mentions()` / `countBlockLike()`）。
- 新增**第 6 节 `templatesRemoved()`**：
  ① `kind:"building"` 必须被当成 freeform（`kind` 归一化成 `freeform`、`ops` 为空、展开 0 方块且**带明确警告**）；
  ② 旧格式（`floors_map` / 只有 `wall`+`floors`+`rows` / 只有 `archetype`+`floors`）必须解析为 null；
  ③ **7 个旧类必须从 classpath 上消失**（`Class.forName` 抛 `ClassNotFoundException`）——
  这条专治"删了引用没删文件"。
- **第 7 节 `soak()`** 重写成 44 个组合（11 种 ops 用例 × 4 种框选大小），盯三件事：
  ① 不崩溃；② **空方案必带"没有生成任何方块"警告**（否则玩家只看到"共 0 个方块"、自动重做也拿不到原因）；
  ③ **没有"每个组合都触发"的假警告** —— 面板只显示前 3 条，一条刷屏的假警告就能把真提示挤掉。
  这比原来的字符串白名单更稳：不必预先知道全部警告文案，直接守住"不能每条都触发"这个性质。
- 合计 **113 项断言全绿**；`./gradlew build` 产出 `minecraft-ai-1.1.0.jar` = **106798 字节**
  （原 165583 字节，**−35.5%**）；`unzip -l` 已确认 7 个旧类的 `.class` 不在包里。

### 10.6 回滚

- 仓库外备份：`D:\一些AI coding的成果\Minecraft AI小组件-备份-2026-09-22-删模板前\`
  （45 个源文件 + GitHub 官方 v1.1.0 jar + 当时的部署版 jar + `还原说明.md`）。
- 游戏实例 `mods/minecraft-ai-1.1.0.jar.bak`（130206 字节）= GitHub Release v1.1.0 官方包；
  删掉当前 jar、把 `.bak` 改名回 `.jar` 即可回到"模板尚存"的版本。
