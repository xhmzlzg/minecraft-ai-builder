import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import com.mcai.common.BuildingPlan;
import com.mcai.common.PlanValidator;
import com.mcai.common.spec.BuildingSpec;
import com.mcai.common.spec.SpecBuilder;
import com.mcai.common.spec.SpecParser;

/**
 * 离线自检：不进游戏就能验证"AI 回复 → 规格 → 方块方案"这条链路。
 * 用 javac 直接编译（只依赖 common + spec 这些纯 Java 类），跑法见 tools\selftest.bat。
 *
 * 2026-09-22：全部建筑模板与旧版字符画管线已删除，本文件相应只剩自由形体相关的断言，
 * 并新增第 6 节守住"模板真的没了、kind=building 一律当成 freeform"这条线。
 */
public class SpecSelfTest {

	private static int failed = 0;

	public static void main(String[] args) {
		System.out.println("== 1. JSON 解析（含别名/容错） ==");
		parse();

		System.out.println();
		System.out.println("== 2. 自由形体：非建筑物体不再被塞成方盒子楼 ==");
		freeform();

		System.out.println();
		System.out.println("== 3. 自由形体的 blockstate：AI 亲手画楼梯/栏杆/吊灯 ==");
		blockstate();

		System.out.println();
		System.out.println("== 4. 提示词示例 2（用 freeform 画房子）必须自身可用 ==");
		promptHouse();

		System.out.println();
		System.out.println("== 5. 模型常见畸形写法的容错（以前这些都会静默丢方块） ==");
		tolerant();

		System.out.println();
		System.out.println("== 6. 模板已全部删除：kind=building 当成 freeform，旧格式一律拒绝 ==");
		templatesRemoved();

		System.out.println();
		System.out.println("== 7. 全组合压力测试：不崩溃 / 空方案必有警告 / 没有刷屏的假警告 ==");
		soak();

		System.out.println();
		System.out.println(failed == 0 ? "全部通过 ✅" : ("失败 " + failed + " 项 ❌"));
		if (failed > 0) {
			System.exit(1);
		}
	}

	// ==================== 1. 解析 ====================

	private static void parse() {
		String json = """
				```json
				{"spec_version":3,"kind":"freeform","name":"测试船","size":[15,9,9],"mirror":"x",
				 "palette":{"h":"dark_oak_planks","m":"oak_log"},
				 "ops":[{"op":"layer","y":0,"rows":["hh","hh"]}]}
				```
				""";
		BuildingSpec s = SpecParser.parse(json);
		check("解析出规格", s != null);
		check("解析出 name=测试船", s != null && "测试船".equals(s.name));
		check("kind 归一化成 freeform", s != null && "freeform".equals(s.kind));
		check("解析出 mirror=x", s != null && "x".equals(s.freeform.mirror));
		check("解析出 1 个 op", s != null && s.freeform.ops.size() == 1);
		check("解析出三维 size=[15,9,9]", s != null && List.of(15, 9, 9).equals(s.freeform.size));
		check("解析出 palette（h=dark_oak_planks）",
				s != null && "dark_oak_planks".equals(s.freeform.palette.get("h")));
		check("代码块围栏被剥掉", s != null);

		// 没有 ops 的规格仍然解析得出来（它不是"畸形 JSON"，只是"什么都没画"）——
		// 展开成 0 个方块后由上层带着原因要求模型重画，不在这里拒绝。
		BuildingSpec noOps = SpecParser.parse("{\"kind\":\"freeform\",\"name\":\"空\"}");
		check("没有 ops 的规格仍能解析（留给上层报\"没画出东西\"）", noOps != null);
		check("没有 ops ⇒ ops 列表为空", noOps != null && noOps.freeform.ops.isEmpty());
	}

	// ==================== 2. 自由形体 ====================

	private static void freeform() {
		// 玩家说"造一艘帆船"时模型应该给的回复（kind=freeform + 分层字符画 + 镜像）
		String boat = """
				{"spec_version":3,"kind":"freeform","name":"帆船","size":[15,9,9],"mirror":"x",
				 "palette":{"h":"dark_oak_planks","d":"spruce_planks","m":"oak_log","s":"white_wool"},
				 "ops":[
				  {"op":"layer","y":0,"rows":[".....hhh","....hhhh","...hhhhh","..hhhhhh",".hhhhhhh"]},
				  {"op":"layer","y":1,"rows":["....hhhh","...hhhhh","..hhhhhh",".hhhhhhh","hhhhhhhh"]},
				  {"op":"layer","y":2,"rows":[".....ddd","....dddd","...ddddd","..dddddd",".ddddddd"]},
				  {"op":"layer","y":3,"rows":["..........","..........","..ddddddd",".dddddddd","ddddddddd"]},
				  {"op":"cyl","x":7,"z":4,"r":0,"y0":3,"y1":8,"block":"m"},
				  {"op":"layer","y":6,"rows":["..........",".....sss..","....ssss..","...sssss..","..ssssss.."]},
				  {"op":"layer","y":7,"rows":["..........",".....sss..","....ssss..","...sssss..","..ssssss.."]}]}
				""";
		BuildingSpec bs = SpecParser.parse(boat);
		check("识别自由形体", bs != null && "freeform".equals(bs.kind));
		check("解析出 7 个 ops", bs != null && bs.freeform.ops.size() == 7);
		check("解析出 mirror=x", bs != null && "x".equals(bs.freeform.mirror));
		check("解析出三维 size=[15,9,9]",
				bs != null && bs.freeform.size != null && bs.freeform.size.equals(List.of(15, 9, 9)));

		SpecBuilder.Result r = SpecBuilder.build(bs, 33, 19, 64);
		BuildingPlan p = r.plan;
		System.out.printf("[帆船] 方块=%d 尺寸=%dx%dx%d 警告=%d%n",
				p.size(), p.width, p.height, p.depth, r.warnings.size());
		check("自由形体生成了方块", p.size() > 0);
		check("尺寸取 size 字段 15x9x9", p.width == 15 && p.height == 9 && p.depth == 9);

		// 镜像必须左右对称
		int asym = symmetric(p);
		check("mirror=x 左右对称（不对称格 " + asym + "）", asym == 0);

		// 关键回归：这必须"不是一栋方盒子楼" —— 顶层不能铺满整个底面（建筑会铺满屋顶）
		int topY = -1;
		for (BuildingPlan.Entry e : p.entries) {
			topY = Math.max(topY, e.y());
		}
		int topCells = 0;
		for (BuildingPlan.Entry e : p.entries) {
			if (e.y() == topY) {
				topCells++;
			}
		}
		int footprint = p.width * p.depth;
		check("顶层不铺满底面（船帆/桅杆，不是屋顶）" + topCells + "/" + footprint,
				topCells * 100 < footprint * 60);
		// 不能有"楼板平面"那种整层铺满的实心面
		int slabLike = 0;
		for (int y = 0; y < p.height; y++) {
			int n = 0;
			for (BuildingPlan.Entry e : p.entries) {
				if (e.y() == y) {
					n++;
				}
			}
			if (n * 100 >= footprint * 95) {
				slabLike++;
			}
		}
		check("没有整层铺满的楼板（不是建筑）", slabLike == 0);

		// 自由形体不做建筑体检：悬空的帆/桅杆必须原样保留（PlanValidator 的悬空修补会把它们删掉）
		int wool = 0;
		for (BuildingPlan.Entry e : p.entries) {
			if (PlanValidator.baseId(e.blockId()).equals("white_wool")) {
				wool++;
			}
		}
		check("跳过建筑体检：悬空的帆原样保留（白色羊毛 " + wool + " 块）", wool > 0);
		check("跳过建筑体检：方案非空（没有被\"悬空修补\"删空）", p.size() > 0);

		// ---- 原语与安全阀 ----
		String prim = """
				{"kind":"freeform","name":"原语","ops":[
				  {"op":"box","from":[0,0,0],"to":[5,2,5],"block":"stone_bricks"},
				  {"op":"clear","from":[1,1,1],"to":[4,1,4]},
				  {"op":"cyl","x":9,"z":9,"r":2,"y0":0,"y1":4,"block":"oak_log"},
				  {"op":"sphere","x":9,"y":6,"z":9,"r":2,"block":"glass","hollow":true}]}
				""";
		BuildingSpec ps = SpecParser.parse(prim);
		check("没有 size 时按 ops 自动算范围", ps != null);
		BuildingPlan pp = SpecBuilder.build(ps, 64, 64, 64).plan;
		System.out.printf("[原语] 方块=%d 尺寸=%dx%dx%d%n", pp.size(), pp.width, pp.height, pp.depth);
		check("box + clear：掏空的内部格是空的", pp.get(2, 1, 2) == null);
		check("box：外壳仍在", pp.get(0, 1, 0) != null && pp.get(5, 2, 5) != null);
		check("cyl：中心有柱", pp.get(9, 2, 9) != null);
		check("sphere hollow：球心是空的", pp.get(9, 6, 9) == null);
		check("sphere hollow：球壳存在", pp.get(9, 8, 9) != null || pp.get(7, 6, 9) != null);

		// 越界/负坐标/非法 id 必须被安全丢弃，绝不写进方案
		String bad = """
				{"kind":"freeform","name":"越界","size":[4,4,4],
				 "palette":{"x":"not a valid id!!"},
				 "ops":[{"op":"layer","y":0,"rows":["xx##########","..x"]},
				        {"op":"box","from":[-5,-5,-5],"to":[2,2,2],"block":"stone"}]}
				""";
		BuildingPlan bp = SpecBuilder.build(SpecParser.parse(bad), 8, 8, 8).plan;
		int out = 0;
		for (BuildingPlan.Entry e : bp.entries) {
			if (e.x() < 0 || e.y() < 0 || e.z() < 0 || e.x() >= bp.width || e.y() >= bp.height
					|| e.z() >= bp.depth) {
				out++;
			}
		}
		check("越界/负坐标一律丢弃（越界格 " + out + "）", out == 0);
		check("非法方块 id 被过滤", bp.entries.stream()
				.noneMatch(e -> PlanValidator.baseId(e.blockId()).contains(" ")));

		// 巨物必须被拦下来，而不是把游戏卡死
		boolean guarded = false;
		try {
			SpecBuilder.build(SpecParser.parse(
					"{\"kind\":\"freeform\",\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[120,120,120],\"block\":\"stone\"}]}"),
					64, 64, 64);
		} catch (RuntimeException e) {
			guarded = true;
			System.out.println("      已拦截巨物: " + e.getMessage());
		}
		check("超过方块数上限的形体被拦下（不卡死游戏）", guarded);

		// ---- 提示词里的 few-shot 示例必须自身可用：模型会照抄它，示例错了它只会照抄错 ----
		// ⚠ 下面这段 JSON 必须与 AiClient.SYSTEM_PROMPT_V2 里的"示例 1（一艘 15×11×7 的帆船…）"保持一致
		String promptBoat = """
				{"spec_version":3,"kind":"freeform","name":"帆船","size":[15,11,7],"mirror":"x",
				 "palette":{"h":"dark_oak_planks","d":"spruce_planks","m":"oak_log","s":"white_wool"},
				 "ops":[
				  {"op":"layer","y":0,"rows":["......hh",".....hhh","....hhhh","...hhhhh","....hhhh",".....hhh","......hh"]},
				  {"op":"layer","y":1,"rows":[".....hhh","....hhhh","...hhhhh","..hhhhhh","...hhhhh","....hhhh",".....hhh"]},
				  {"op":"layer","y":2,"rows":["......dd",".....ddd","....dddd","...ddddd","....dddd",".....ddd","......dd"]},
				  {"op":"box","from":[4,4,1],"to":[7,7,4],"block":"s"},
				  {"op":"box","from":[7,2,3],"to":[7,9,3],"block":"m"}]}
				""";
		SpecBuilder.Result pbr = SpecBuilder.build(SpecParser.parse(promptBoat), 64, 64, 64);
		BuildingPlan pb = pbr.plan;
		System.out.printf("[提示词示例] 方块=%d 尺寸=%dx%dx%d 警告=%d%n",
				pb.size(), pb.width, pb.height, pb.depth, pbr.warnings.size());
		check("提示词示例零警告", pbr.warnings.isEmpty());
		check("提示词示例镜像对称（不对称格 " + symmetric(pb) + "）", symmetric(pb) == 0);
		int sailCells = 0;
		int sailOnMast = 0;
		for (BuildingPlan.Entry e : pb.entries) {
			if (!"white_wool".equals(e.blockId())) {
				continue;
			}
			sailCells++;
			for (int[] d : new int[][] { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } }) {
				BuildingPlan.Entry nb = pb.get(e.x() + d[0], e.y() + d[1], e.z() + d[2]);
				if (nb != null && "oak_log".equals(nb.blockId())) {
					sailOnMast++;
					break;
				}
			}
		}
		check("提示词示例：帆挂在桅杆上（不是一块悬空的羊毛）", sailCells > 0 && sailOnMast > 0);
		check("提示词示例：桅杆只有 1 根（写对镜像轴，不会变成两根）", countBlock(pb, "oak_log") == 8);

		// 用了 mirror 却没写 size：必须推断出"完整宽度"，不能把形状折回自身（否则船只剩一半宽）
		String noSize = promptBoat.replace("\"size\":[15,11,7],", "");
		SpecBuilder.Result nr = SpecBuilder.build(SpecParser.parse(noSize), 64, 64, 64);
		System.out.printf("[mirror 无 size] 尺寸=%dx%dx%d 方块=%d%n",
				nr.plan.width, nr.plan.height, nr.plan.depth, nr.plan.size());
		check("mirror 无 size 时自动推断出完整宽度 15", nr.plan.width == 15);
		check("mirror 无 size 时仍左右对称", symmetric(nr.plan) == 0);
		check("mirror 无 size 时给出提示（建议显式写 size）", !nr.warnings.isEmpty());

		// 模型很爱用 {"block":"air"} 掏空间 ⇒ 必须当成"删除"，不能真塞一个 air 条目进方案
		// （否则方块数虚高、日志里的数字和实际建造数对不上）
		String airCarve = """
				{"kind":"freeform","name":"掏空","size":[5,5,5],
				 "ops":[{"op":"box","from":[0,0,0],"to":[4,4,4],"block":"stone_bricks"},
				        {"op":"box","from":[1,1,1],"to":[3,3,3],"block":"air"}]}
				""";
		BuildingPlan ap = SpecBuilder.build(SpecParser.parse(airCarve), 64, 64, 64).plan;
		check("{\"block\":\"air\"} 不往方案里塞 air 条目",
				ap.entries.stream().noneMatch(e -> PlanValidator.baseId(e.blockId()).equals("air")));
		check("{\"block\":\"air\"} 真的把内部掏空了", ap.get(2, 2, 2) == null && ap.get(1, 1, 1) == null);
		check("{\"block\":\"air\"} 只删不增（5³ 减 3³ = 98）", ap.size() == 98);

		// 高度超出框选要给提示（自由形体不做裁剪，会长到框外 —— 得让玩家知道是框小了）
		SpecBuilder.Result hr = SpecBuilder.build(SpecParser.parse(promptBoat), 64, 64, 6);
		check("高度超过框选时给出提示", hr.warnings.stream().anyMatch(w -> w.contains("高度")));
	}

	// ==================== 3. 自由形体的 blockstate ====================

	private static void blockstate() {
		String json = """
				{"kind":"freeform","name":"楼梯栏杆","size":[9,5,9],"mirror":"x",
				 "palette":{"r":"dark_oak_stairs","f":"oak_fence","l":"lantern"},
				 "ops":[
				  {"op":"box","from":[0,0,0],"to":[8,0,8],"block":"stone_bricks"},
				  {"op":"box","from":[0,4,0],"to":[8,4,8],"block":"stone_bricks"},
				  {"op":"box","from":[3,1,3],"to":[3,1,3],"block":"r",
				   "props":{"facing":"east","half":"bottom","shape":"inner_left"}},
				  {"op":"box","from":[1,3,7],"to":[1,3,7],"block":"l"},
				  {"op":"box","from":[0,2,0],"to":[8,2,0],"block":"f"}]}
				""";
		BuildingSpec bs = SpecParser.parse(json);
		check("解析出 op 的 props（facing=east）", bs != null
				&& "east".equals(bs.freeform.ops.get(2).props.get("facing")));
		SpecBuilder.Result res = SpecBuilder.build(bs, 64, 64, 64);
		BuildingPlan p = res.plan;
		System.out.printf("[blockstate] 方块=%d 警告=%d 孤立=%d%n", p.size(), res.warnings.size(), res.isolated);
		check("零警告（props 语法被正确接受）", res.warnings.isEmpty());

		BuildingPlan.Entry a = p.get(3, 1, 3);
		BuildingPlan.Entry b = p.get(5, 1, 3);
		check("props 透传进方案（x=3 是 dark_oak_stairs / facing=east）",
				a != null && "dark_oak_stairs".equals(a.blockId()) && "east".equals(a.props().get("facing")));
		check("mirror 后朝向自动翻成 west", b != null && "west".equals(b.props().get("facing")));
		check("mirror 后 half 不受影响（仍 bottom）",
				a != null && b != null && "bottom".equals(a.props().get("half"))
						&& "bottom".equals(b.props().get("half")));
		check("mirror 后楼梯形状手性翻转（inner_left → inner_right）",
				b != null && "inner_right".equals(b.props().get("shape")));

		BuildingPlan.Entry f0 = p.get(0, 2, 0);
		BuildingPlan.Entry f4 = p.get(4, 2, 0);
		check("栏杆自动连片：端点只连内侧",
				f0 != null && "true".equals(f0.props().get("east")) && !f0.props().containsKey("west"));
		check("栏杆自动连片：中间东西都连",
				f4 != null && "true".equals(f4.props().get("east"))
						&& "true".equals(f4.props().get("west")));

		BuildingPlan.Entry lan = p.get(1, 3, 7);
		check("灯笼上方是实心块 ⇒ 自动 hanging=true",
				lan != null && "true".equals(lan.props().get("hanging")));
		check("没有孤立方块（相连的悬空件不误报）", res.isolated == 0);

		// 孤立方块审计：只报告、绝不修补（不能删、不能挪）
		String lone = """
				{"kind":"freeform","name":"孤立","size":[9,9,9],
				 "ops":[{"op":"box","from":[0,0,0],"to":[2,2,2],"block":"stone_bricks"},
				        {"op":"box","from":[7,7,7],"to":[7,7,7],"block":"stone_bricks"}]}
				""";
		SpecBuilder.Result lr = SpecBuilder.build(SpecParser.parse(lone), 64, 64, 64);
		System.out.printf("[孤立审计] 方块=%d 孤立=%d 警告=%d%n", lr.plan.size(), lr.isolated, lr.warnings.size());
		check("审计出 1 个孤立方块", lr.isolated == 1);
		check("只报告不修补：方块数一个不少（27 + 1）", lr.plan.size() == 28);
		check("孤立方块写进警告", lr.warnings.stream().anyMatch(w -> w.contains("孤立方块")));

		// 模型显式写死的"连接方向"也要跟着镜像换边
		String fenceFlip = """
				{"kind":"freeform","name":"栏杆换边","size":[9,3,9],"mirror":"z",
				 "ops":[{"op":"box","from":[4,0,0],"to":[4,0,0],"block":"stone_bricks"},
				        {"op":"box","from":[4,1,0],"to":[4,1,0],"block":"oak_fence","props":{"north":"true"}}]}
				""";
		BuildingPlan fp = SpecBuilder.build(SpecParser.parse(fenceFlip), 64, 64, 64).plan;
		BuildingPlan.Entry flipped = fp.get(4, 1, 8);
		check("镜像时显式写的连接方向换边（north → south）",
				flipped != null && "true".equals(flipped.props().get("south"))
						&& !flipped.props().containsKey("north"));
	}

	// ==================== 4. 提示词示例 2：用 freeform 画房子 ====================

	/**
	 * ⚠ 下面这段 JSON 必须与 AiClient.SYSTEM_PROMPT_V2 里的"示例 2（一栋 9×9×7 的两层木屋…）"
	 * 保持一致 —— 模型会照抄示例，示例错了它只会照抄错。
	 */
	private static void promptHouse() {
		String house = """
				{"spec_version":3,"kind":"freeform","name":"两层木屋","size":[9,9,7],
				 "palette":{"w":"oak_planks","s":"cobblestone","g":"glass_pane","d":"dark_oak_door","r":"dark_oak_stairs"},
				 "ops":[
				  {"op":"box","from":[0,0,0],"to":[8,0,6],"block":"s"},
				  {"op":"box","from":[0,1,0],"to":[8,3,0],"block":"w"},
				  {"op":"box","from":[0,1,6],"to":[8,3,6],"block":"w"},
				  {"op":"box","from":[0,1,1],"to":[0,3,5],"block":"w"},
				  {"op":"box","from":[8,1,1],"to":[8,3,5],"block":"w"},
				  {"op":"box","from":[0,4,0],"to":[8,4,6],"block":"w"},
				  {"op":"box","from":[0,5,0],"to":[8,7,0],"block":"w"},
				  {"op":"box","from":[0,5,6],"to":[8,7,6],"block":"w"},
				  {"op":"box","from":[0,5,1],"to":[0,7,5],"block":"w"},
				  {"op":"box","from":[8,5,1],"to":[8,7,5],"block":"w"},
				  {"op":"box","from":[0,8,0],"to":[8,8,6],"block":"s"},
				  {"op":"box","from":[4,1,0],"to":[4,2,0],"block":"d","props":{"facing":"south"}},
				  {"op":"box","from":[2,2,0],"to":[3,2,0],"block":"g"},
				  {"op":"box","from":[6,2,0],"to":[7,2,0],"block":"g"},
				  {"op":"box","from":[1,1,3],"to":[1,1,5],"block":"w"},
				  {"op":"box","from":[1,2,3],"to":[1,2,4],"block":"w"},
				  {"op":"box","from":[1,3,3],"to":[1,3,3],"block":"w"},
				  {"op":"box","from":[1,1,6],"to":[1,1,6],"block":"r","props":{"facing":"north","half":"bottom"}},
				  {"op":"box","from":[1,2,5],"to":[1,2,5],"block":"r","props":{"facing":"north","half":"bottom"}},
				  {"op":"box","from":[1,3,4],"to":[1,3,4],"block":"r","props":{"facing":"north","half":"bottom"}},
				  {"op":"box","from":[1,4,3],"to":[1,4,3],"block":"r","props":{"facing":"north","half":"bottom"}}]}
				""";
		SpecBuilder.Result res = SpecBuilder.build(SpecParser.parse(house), 64, 64, 64);
		BuildingPlan p = res.plan;
		System.out.printf("[提示词示例2 木屋] 方块=%d 尺寸=%dx%dx%d 警告=%d 孤立=%d%n",
				p.size(), p.width, p.height, p.depth, res.warnings.size(), res.isolated);
		check("示例 2 零警告", res.warnings.isEmpty());
		check("示例 2 尺寸取 size=[9,9,7]", p.width == 9 && p.height == 9 && p.depth == 7);
		check("示例 2 没有孤立方块", res.isolated == 0);

		// 室内必须是空的（hollow 的 box 会把里面填满 —— 示例特意避开这个坑）
		check("一层室内是空的（人进得去）", p.get(4, 1, 3) == null && p.get(4, 2, 3) == null);
		check("二层室内是空的", p.get(4, 5, 3) == null && p.get(4, 6, 3) == null);
		check("门洞被门占据（不是实心墙）", p.get(4, 1, 0) != null
				&& "dark_oak_door".equals(p.get(4, 1, 0).blockId())
				&& "south".equals(p.get(4, 1, 0).props().get("facing")));
		check("窗户是玻璃板", p.get(2, 2, 0) != null && "glass_pane".equals(p.get(2, 2, 0).blockId()));
		check("玻璃板自动连片（东西都连）", p.get(2, 2, 0) != null
				&& "true".equals(p.get(2, 2, 0).props().get("east"))
				&& "true".equals(p.get(2, 2, 0).props().get("west")));
		check("楼梯朝向正确（facing=north）", p.get(1, 1, 6) != null
				&& "north".equals(p.get(1, 1, 6).props().get("facing")));
		check("楼梯每级都有支撑（下方是实心）",
				p.get(1, 1, 5) != null && p.get(1, 2, 4) != null && p.get(1, 3, 3) != null);
		check("楼板是整片实心（4 层没有空洞）", p.get(3, 4, 3) != null && p.get(5, 4, 5) != null);
		check("屋顶封顶", p.get(4, 8, 3) != null);
		// 不是"程序模板盖的方盒子"：层高/门窗/楼梯全是 ops 说了算，且没有自动配的家具
		check("没有程序自动配的家具（模板已删除，房子完全由 ops 决定）",
				p.entries.stream().noneMatch(e -> PlanValidator.baseId(e.blockId()).contains("bed")));
	}

	// ==================== 5. 模型"常见畸形写法"的容错 ====================
	//
	// 这些都不是契约里写的标准写法，但模型（尤其被 Minecraft 命令语料带偏时）真的会这么写。
	// 改动前每一条都会让方块被**静默丢弃**：轻则缺料，重则整个方案 0 个方块 ——
	// 而面板上只显示"共 0 个方块"，玩家完全看不出原因。

	private static void tolerant() {
		embeddedState();
		paletteState();
		mirrorWord();
		layerAutoY();
		layerFallback();
		emptyPlan();
		trailingText();
		notASpec();
	}

	/**
	 * ★ 2026-09-22：思考型模型（thinking 模式）的 reasoning 文本里常常夹着 {"op":...} 草稿片段。
	 * 以前 SpecParser 会把这种片段当成"一份空规格"（kind=building / archetype=generic /
	 * floors=null → 默认 2 层），于是"解析失败"被伪装成"解析成功"，绕开全部纠正逻辑，
	 * 把玩家要的雕塑盖成二层小楼。现在必须返回 null，让上层明确报错。
	 */
	private static void notASpec() {
		String reasoning = """
				The user wants a spiral ascending sculpture. Let me build a helix.
				I could use {"op":"box","from":[0,0,0],"to":[9,0,9],"block":"stone_bricks"} for the base.
				Then {"op":"cyl","x":5,"z":5,"r":3,"y0":1,"y1":6,"block":"oak_log"} for the column.
				""";
		BuildingSpec r1 = SpecParser.parse(reasoning);
		System.out.printf("[思考文本] 解析=%s%n", r1 == null ? "null（正确）" : "出了规格（错误）");
		check("含 op 草稿片段的思考文本必须解析为 null", r1 == null);

		BuildingSpec r2 = SpecParser.parse("{}");
		check("空 JSON 对象必须解析为 null（不能被当成默认楼）", r2 == null);

		BuildingSpec r3 = SpecParser.parse("{\"foo\":1,\"bar\":[1,2]}");
		check("无关 JSON 片段必须解析为 null", r3 == null);

		BuildingSpec r4 = SpecParser.parse(
				"{\"op\":\"layer\",\"y\":0,\"rows\":[\"hh\",\"hh\"]}");
		check("单独一个 op 对象必须解析为 null（它只是指令，不是规格）", r4 == null);

		// 反向：合法规格不能被这道新闸误伤
		BuildingSpec ok = SpecParser.parse(
				"{\"kind\":\"freeform\",\"name\":\"柱\",\"size\":[1,3,1],"
						+ "\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[0,2,0],\"block\":\"oak_log\"}]}");
		check("合法规格不被新闸误伤（仍解析成 freeform）", ok != null && "freeform".equals(ok.kind));
		// 模板删除后：旧建筑规格（只有 archetype+floors，没有 ops）不再算"可用规格"
		BuildingSpec ok2 = SpecParser.parse("{\"archetype\":\"chinese_highrise\",\"floors\":15}");
		check("只有 archetype+floors 的旧建筑规格不再被当成可用规格", ok2 == null);
	}

	/** JSON 后面接着写说明文字（说明里还带 '}'）—— 也要能正确抠出 JSON */
	private static void trailingText() {
		String json = """
				好的，这是我设计的方案：
				{"kind":"freeform","name":"尾随文字","size":[2,1,2],"palette":{"h":"oak_planks"},
				 "ops":[{"op":"layer","y":0,"rows":["hh","hh"]}]}
				注意：上面的 size 是 [宽, 高, 进深]，最后用 } 结束。
				""";
		BuildingSpec s = SpecParser.parse(json);
		System.out.printf("[尾随文字] 解析=%s%n", s == null ? "失败" : "成功");
		check("JSON 后面跟说明文字（含 '}'）仍能解析出来", s != null);
		if (s != null) {
			SpecBuilder.Result res = SpecBuilder.build(s, 16, 16, 16);
			check("尾随文字不影响展开（4 个方块）", res.plan.size() == 4);
		}
	}

	/** block 里直接嵌 blockstate（Minecraft 命令的写法）+ minecraft: 前缀 */
	private static void embeddedState() {
		String json = """
				{"kind":"freeform","name":"嵌状态","size":[3,1,3],
				 "ops":[{"op":"box","from":[0,0,0],"to":[2,0,2],
				         "block":"minecraft:oak_stairs[facing=east,half=top]"}]}
				""";
		SpecBuilder.Result res = SpecBuilder.build(SpecParser.parse(json), 16, 16, 16);
		BuildingPlan p = res.plan;
		System.out.printf("[嵌 blockstate] 方块=%d 警告=%d%n", p.size(), res.warnings.size());
		check("block 里嵌 blockstate 不再导致方块被丢弃", p.size() == 9);
		check("minecraft: 前缀被剥掉", p.get(0, 0, 0) != null && "oak_stairs".equals(p.get(0, 0, 0).blockId()));
		check("嵌在 block 里的 facing 生效", "east".equals(propsOf(p, 0, 0, 0, "facing")));
		check("嵌在 block 里的 half 生效", "top".equals(propsOf(p, 0, 0, 0, "half")));
	}

	/** palette 的值里嵌 blockstate：{"S":"oak_stairs[facing=west]"} */
	private static void paletteState() {
		String json = """
				{"kind":"freeform","name":"调色板状态","size":[3,1,3],
				 "palette":{"S":"oak_stairs[facing=west]"},
				 "ops":[{"op":"layer","y":0,"rows":["SSS","SSS","SSS"]}]}
				""";
		SpecBuilder.Result res = SpecBuilder.build(SpecParser.parse(json), 16, 16, 16);
		BuildingPlan p = res.plan;
		System.out.printf("[palette 嵌状态] 方块=%d 警告=%d%n", p.size(), res.warnings.size());
		check("palette 值里嵌 blockstate 不再导致方块被丢弃", p.size() == 9);
		check("palette 里的方块 id 正确", p.get(1, 0, 1) != null && "oak_stairs".equals(p.get(1, 0, 1).blockId()));
		check("palette 里嵌的 facing 生效", "west".equals(propsOf(p, 1, 0, 1, "facing")));
	}

	/** mirror 写了 "both"（不是契约里的 xz，但模型很爱这么写） */
	private static void mirrorWord() {
		String json = """
				{"kind":"freeform","name":"both","mirror":"both","palette":{"h":"oak_planks"},
				 "ops":[{"op":"layer","y":0,"rows":["hh","hh","hh"]}]}
				""";
		SpecBuilder.Result res = SpecBuilder.build(SpecParser.parse(json), 32, 32, 32);
		BuildingPlan p = res.plan;
		System.out.printf("[mirror=both] 尺寸=%dx%dx%d 方块=%d 不对称=%d%n",
				p.width, p.height, p.depth, p.size(), symmetric(p));
		// 2 字符宽 ⇒ 宽 2*2-1=3；3 行 ⇒ 进深 2*3-1=5；填满 = 15
		check("mirror=\"both\" 被当成双轴镜像（宽 3 进深 5）", p.width == 3 && p.depth == 5);
		check("mirror=\"both\" 真的镜像了（6 块 → 15 块）", p.size() == 15);
		check("mirror=\"both\" 结果左右对称", symmetric(p) == 0);
	}

	/** 多个 layer 都不写 y：必须逐层往上，而不是全叠在 y=0 互相覆盖 */
	private static void layerAutoY() {
		String json = """
				{"kind":"freeform","name":"自动层","size":[2,3,2],"palette":{"h":"oak_planks"},
				 "ops":[{"op":"layer","rows":["hh","hh"]},
				        {"op":"layer","rows":["hh","hh"]},
				        {"op":"layer","rows":["hh","hh"]}]}
				""";
		SpecBuilder.Result res = SpecBuilder.build(SpecParser.parse(json), 16, 16, 16);
		BuildingPlan p = res.plan;
		System.out.printf("[layer 自动 y] 方块=%d 尺寸=%dx%dx%d%n", p.size(), p.width, p.height, p.depth);
		check("三个不写 y 的 layer 叠成三层（不是一层）", p.size() == 12);
		check("三层都在（y=0/1/2 各有一格）",
				p.get(0, 0, 0) != null && p.get(0, 1, 0) != null && p.get(0, 2, 0) != null);
	}

	/** layer 的 rows 用了 palette 里没定义的字符，但 op 有 block ⇒ 用 block 兜底 */
	private static void layerFallback() {
		String json = """
				{"kind":"freeform","name":"兜底","size":[3,1,3],
				 "ops":[{"op":"layer","y":0,"rows":["xxx","x.x","xxx"],"block":"stone_bricks"}]}
				""";
		SpecBuilder.Result res = SpecBuilder.build(SpecParser.parse(json), 16, 16, 16);
		BuildingPlan p = res.plan;
		System.out.printf("[layer 兜底] 方块=%d 警告=%d%n", p.size(), res.warnings.size());
		check("palette 缺字符时用 op.block 兜底（不是整层消失）", p.size() == 8);
		check("兜底出来的方块是 op 指定的 stone_bricks", countBlock(p, "stone_bricks") == 8);
		check("留空字符 '.' 仍然留空（中心那一格没被填）", p.get(1, 0, 1) == null);
		check("兜底时给出提示（建议补 palette）",
				res.warnings.stream().anyMatch(w -> w.contains("兜底")));
	}

	/** 真的一格都画不出来时，必须能被识别成"空方案"（client 侧据此打回重做） */
	private static void emptyPlan() {
		String json = """
				{"kind":"freeform","name":"空","size":[3,1,3],
				 "ops":[{"op":"layer","y":0,"rows":["zzz","zzz","zzz"]}]}
				""";
		SpecBuilder.Result res = SpecBuilder.build(SpecParser.parse(json), 16, 16, 16);
		System.out.printf("[空方案] 方块=%d 警告=%d%n", res.plan.size(), res.warnings.size());
		check("空方案：0 个方块", res.plan.size() == 0);
		check("空方案：有明确警告（面板会显示，并触发自动重做）",
				res.warnings.stream().anyMatch(w -> w.contains("没有生成任何方块")));
	}

	// ==================== 6. 模板已全部删除 ====================

	/**
	 * ★ 2026-09-22：用户要求"删除模板，以后输入任何提示词都由 AI 自行创作"。
	 *
	 * 建筑模板（kind="building" + archetype 参数化展开）与旧版字符画管线
	 * （PlanGenerator / PlanParser / PlanSpec / NotesParser / PartLib / Interiors / Layout）
	 * 已**整条删除**。本节守住三件事：
	 *   ① AI 仍写 kind="building" 时**直接当成 freeform**（不报错、不特判），
	 *      它没给 ops ⇒ 0 个方块 + 明确警告，由上层要求模型重画；
	 *   ② 旧格式（floors_map / 纯建筑字段）不再被当成可用规格 ⇒ 解析返回 null；
	 *   ③ 那些旧类真的从 classpath 上消失了（防止"删了引用没删文件"）。
	 */
	private static void templatesRemoved() {
		// ① kind=building 一律当成 freeform
		BuildingSpec b = SpecParser.parse(
				"{\"kind\":\"building\",\"archetype\":\"chinese_highrise\",\"floors\":8,\"size\":[33,19]}");
		check("kind=building 被当成 freeform（不报错）", b != null);
		check("kind 被归一化成 freeform", b != null && "freeform".equals(b.kind));
		check("它没有 ops ⇒ ops 列表为空", b != null && b.freeform.ops.isEmpty());
		SpecBuilder.Result r = SpecBuilder.build(b, 33, 19, 64);
		check("展开不抛异常，只是空方案（0 个方块）", r.plan.size() == 0);
		check("空方案带明确警告（面板会显示并触发自动重做）",
				r.warnings.stream().anyMatch(w -> w.contains("没有生成任何方块")));

		// ② 旧版字符画蓝图：整条管线已删除 ⇒ 解析为 null，上层明确报错
		check("旧版字符画蓝图（floors_map）不再被解析",
				SpecParser.parse("{\"floors\":2,\"floors_map\":{\"1\":[\"####\"]}}") == null);
		check("旧版蓝图特征（wall+floors+rows）也不再被解析",
				SpecParser.parse("{\"floors\":2,\"layer_height\":4,\"wall\":\"stone\",\"rows\":[\"####\"]}") == null);

		// ③ 旧类必须真的不在 classpath 上
		for (String fq : new String[] { "com.mcai.common.PlanGenerator", "com.mcai.common.PlanParser",
				"com.mcai.common.PlanSpec", "com.mcai.common.spec.NotesParser",
				"com.mcai.common.spec.PartLib", "com.mcai.common.spec.Interiors",
				"com.mcai.common.spec.Layout" }) {
			boolean gone = false;
			try {
				Class.forName(fq);
			} catch (ClassNotFoundException e) {
				gone = true;
			}
			check("旧类已删除: " + fq, gone);
		}
	}

	// ==================== 7. 全组合压力测试 ====================

	/**
	 * 把"模型可能给出的各种 ops × 各种框选大小"扫一遍。要盯的是三件事：
	 *   ① 不崩溃；
	 *   ② **空方案必须带"没有生成任何方块"警告** —— 否则玩家只看到"共 0 个方块"，
	 *      而面板的自动重做也拿不到原因（这是"AI 又不听话"的典型表象）；
	 *   ③ **没有"每个组合都触发"的假警告** —— 面板只显示前 3 条，
	 *      一条刷屏的假警告就能把真正的提示挤掉。
	 */
	private static void soak() {
		String[] cases = {
				// 分层字符画 + 镜像
				"{\"kind\":\"freeform\",\"size\":[15,9,9],\"mirror\":\"x\",\"palette\":{\"h\":\"oak_planks\"},"
						+ "\"ops\":[{\"op\":\"layer\",\"y\":0,\"rows\":[\"hhh\",\"hhh\",\"hhh\"]}]}",
				// 纯 box
				"{\"kind\":\"freeform\",\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[9,4,9],"
						+ "\"block\":\"stone_bricks\"}]}",
				// 空心 box
				"{\"kind\":\"freeform\",\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[9,9,9],"
						+ "\"block\":\"glass\",\"hollow\":true}]}",
				// cyl + sphere
				"{\"kind\":\"freeform\",\"ops\":[{\"op\":\"cyl\",\"x\":5,\"z\":5,\"r\":3,\"y0\":0,\"y1\":6,"
						+ "\"block\":\"oak_log\"},{\"op\":\"sphere\",\"x\":5,\"y\":10,\"z\":5,\"r\":3,"
						+ "\"block\":\"white_wool\"}]}",
				// box + clear 掏空
				"{\"kind\":\"freeform\",\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[8,8,8],"
						+ "\"block\":\"stone\"},{\"op\":\"clear\",\"from\":[1,1,1],\"to\":[7,7,7]}]}",
				// palette 缺字符（触发兜底警告）
				"{\"kind\":\"freeform\",\"size\":[3,3,3],\"ops\":[{\"op\":\"layer\",\"y\":0,"
						+ "\"rows\":[\"xxx\",\"x.x\",\"xxx\"],\"block\":\"stone_bricks\"}]}",
				// 孤立方块（触发孤立警告）
				"{\"kind\":\"freeform\",\"size\":[9,9,9],\"ops\":[{\"op\":\"box\",\"from\":[0,0,0],\"to\":[2,2,2],"
						+ "\"block\":\"stone\"},{\"op\":\"box\",\"from\":[7,7,7],\"to\":[7,7,7],\"block\":\"stone\"}]}",
				// 空方案（ops 全是未定义字符）
				"{\"kind\":\"freeform\",\"size\":[3,1,3],\"ops\":[{\"op\":\"layer\",\"y\":0,"
						+ "\"rows\":[\"zzz\",\"zzz\",\"zzz\"]}]}",
				// kind=building（模板已删除 → 当成 freeform → 空方案）
				"{\"kind\":\"building\",\"archetype\":\"chinese_highrise\",\"floors\":8,\"size\":[33,19]}",
				// 完全没有 ops
				"{\"kind\":\"freeform\",\"name\":\"空\"}",
				// 未知 op 类型（应被跳过并给出警告，而不是崩溃）
				"{\"kind\":\"freeform\",\"size\":[3,3,3],\"ops\":[{\"op\":\"torus\",\"r\":3},"
						+ "{\"op\":\"box\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"stone\"}]}",
		};
		int[][] boxes = { { 8, 8, 8 }, { 16, 16, 32 }, { 33, 19, 64 }, { 64, 64, 64 } };

		int total = 0;
		int crashed = 0;
		int silentEmpty = 0;   // 空方案却没有"没有生成任何方块"警告
		int falseEmptyWarn = 0; // 非空方案却带了"没有生成任何方块"警告
		TreeSet<String> unparsed = new TreeSet<>();
		TreeMap<String, Integer> warnCount = new TreeMap<>();
		for (String c : cases) {
			BuildingSpec s = SpecParser.parse(c);
			if (s == null) {
				unparsed.add(c);
				continue;
			}
			for (int[] b : boxes) {
				total++;
				String tag = c.substring(0, Math.min(40, c.length())) + " @ " + b[0] + "x" + b[1] + "x" + b[2];
				SpecBuilder.Result r;
				try {
					r = SpecBuilder.build(s, b[0], b[1], b[2]);
				} catch (Exception e) {
					crashed++;
					System.out.println("   [崩溃] " + tag + " -> " + e);
					continue;
				}
				boolean hasEmptyWarn = false;
				for (String w : r.warnings) {
					String key = w.replaceAll("\\d+", "N");
					warnCount.merge(key, 1, Integer::sum);
					if (w.contains("没有生成任何方块")) {
						hasEmptyWarn = true;
					}
				}
				if (r.plan.size() == 0 && !hasEmptyWarn) {
					silentEmpty++;
					System.out.println("   [空方案却无警告] " + tag);
				}
				if (r.plan.size() > 0 && hasEmptyWarn) {
					falseEmptyWarn++;
					System.out.println("   [非空却报\"没有方块\"] " + tag);
				}
			}
		}
		check("所有用例都能解析出规格", unparsed.isEmpty());
		check(total + " 个组合：不崩溃（崩=" + crashed + "）", crashed == 0);
		check("空方案必带\"没有生成任何方块\"警告（静默空=" + silentEmpty + "）", silentEmpty == 0);
		check("非空方案不带\"没有生成任何方块\"警告（误报=" + falseEmptyWarn + "）", falseEmptyWarn == 0);

		// ③ 假警告看门狗：任何一条警告都不该在**每一个**组合里都出现
		System.out.println("   [警告覆盖] 共 " + warnCount.size() + " 种，总组合 " + total + "：");
		for (Map.Entry<String, Integer> en : warnCount.entrySet()) {
			System.out.println("      " + en.getValue() + "x  " + en.getKey());
		}
		TreeSet<String> universal = new TreeSet<>();
		for (Map.Entry<String, Integer> en : warnCount.entrySet()) {
			if (en.getValue() >= total) {
				universal.add(en.getKey());
			}
		}
		check("没有「每个组合都触发」的假警告（面板只有 3 个位置）", universal.isEmpty());
	}

	// ==================== 工具 ====================

	/** 取某格方块的某个 blockstate 值（取不到返回 null） */
	private static String propsOf(BuildingPlan p, int x, int y, int z, String key) {
		BuildingPlan.Entry e = p.get(x, y, z);
		return e == null ? null : e.props().get(key);
	}

	/** 左右镜像的不对称格数（0 = 完全对称） */
	private static int symmetric(BuildingPlan p) {
		int asym = 0;
		for (BuildingPlan.Entry e : p.entries) {
			if (p.get(p.width - 1 - e.x(), e.y(), e.z()) == null) {
				asym++;
			}
		}
		return asym;
	}

	private static int countBlock(BuildingPlan p, String id) {
		int n = 0;
		for (BuildingPlan.Entry e : p.entries) {
			if (id.equals(e.blockId())) {
				n++;
			}
		}
		return n;
	}

	private static void check(String what, boolean ok) {
		if (!ok) {
			failed++;
			System.out.println("   ✗ " + what);
		} else {
			System.out.println("   ✓ " + what);
		}
	}
}
