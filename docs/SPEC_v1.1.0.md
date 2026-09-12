# v1.1.0 底层改造说明（规格契约 + 参数化构件库 + 方案体检）

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
- `archetype`：chinese_highrise（一梯两户板楼，自动生成双跑楼梯+脚手架电梯+候梯厅）/
  chinese_courtyard / modern_villa / castle / generic
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

- 旧契约（含 `floors_map` 的字符画蓝图）仍可解析，走 `PlanParser` + `PlanGenerator` 旧管线；
- 想回退版本：用 `jar备份\minecraft-ai-1.0.2.jar` 覆盖 mods 目录即可（见历史版本2备份）。
