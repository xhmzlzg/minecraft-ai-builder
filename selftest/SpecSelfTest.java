import java.util.List;
import java.util.Map;

import com.mcai.common.BuildingPlan;
import com.mcai.common.PlanValidator;
import com.mcai.common.spec.BuildingSpec;
import com.mcai.common.spec.SpecBuilder;
import com.mcai.common.spec.SpecParser;

/**
 * 离线自检：不进游戏就能验证"规格 → 方块方案 → 体检"这条链路。
 * 用 javac 直接编译（只依赖 common + spec 这些纯 Java 类），跑法见 tools\selftest.bat。
 */
public class SpecSelfTest {

	private static int failed = 0;

	public static void main(String[] args) {
		System.out.println("== 1. JSON 解析（含别名/容错） ==");
		String json = """
				```json
				{"spec_version":2,"name":"测试高层","archetype":"中式高层住宅","floors":9,
				 "layer_height":4,"features":["stairs","elevator","balcony"],
				 "materials":{"wall":"white_concrete","accent":"brown_terracotta"},
				 "roof":{"style":"flat","water_tank":true,"solar":true}}
				```
				""";
		BuildingSpec parsed = SpecParser.parse(json);
		check("解析出 archetype=chinese_highrise", parsed != null && "chinese_highrise".equals(parsed.archetype));
		check("解析出 floors=9", parsed != null && parsed.floors != null && parsed.floors == 9);
		check("识别旧契约", SpecParser.looksLegacy("{\"floors\":2,\"floors_map\":{\"1\":[\"####\"]}}"));
		check("新材料不误判为旧契约", !SpecParser.looksLegacy(json));

		System.out.println();
		System.out.println("== 2. 各类建筑展开 + 体检 ==");
		run("中式高层 33x19 8层", spec("chinese_highrise", 8, 33, 19, "flat"), 33, 19, 64);
		run("中式高层 45x19 12层", spec("chinese_highrise", 12, 45, 19, "flat"), 45, 19, 200);
		run("中式高层 25x19 6层", spec("chinese_highrise", 6, 25, 19, "flat"), 25, 19, 64);
		run("中式高层 33x25 10层", spec("chinese_highrise", 10, 33, 25, "flat"), 33, 25, 100);
		run("现代别墅 15x13 2层", spec("modern_villa", 2, 15, 13, "flat"), 15, 13, 32);
		run("城堡 21x21 3层坡顶", spec("castle", 3, 21, 21, "pyramid"), 21, 21, 64);
		run("中式庭院 21x19 1层", spec("chinese_courtyard", 1, 21, 19, "gabled"), 21, 19, 32);
		run("普通小楼 12x11 2层", spec("generic", 2, 12, 11, "flat"), 12, 11, 32);

		System.out.println();
		System.out.println(failed == 0 ? "全部通过 ✅" : ("失败 " + failed + " 项 ❌"));
		if (failed > 0) {
			System.exit(1);
		}
	}

	private static BuildingSpec spec(String arch, int floors, int w, int d, String roof) {
		BuildingSpec s = new BuildingSpec();
		s.archetype = arch;
		s.floors = floors;
		s.size = List.of(w, d);
		s.name = "T";
		s.roof.style = roof;
		return s;
	}

	private static void run(String label, BuildingSpec s, int w, int d, int availH) {
		SpecBuilder.Result r;
		try {
			r = SpecBuilder.build(s, w, d, availH);
		} catch (Exception e) {
			failed++;
			System.out.println("[" + label + "] 展开异常: " + e);
			e.printStackTrace(System.out);
			return;
		}
		BuildingPlan p = r.plan;
		int lightsInSlab = 0;
		int lanterns = 0;
		int doors = 0;
		int slabs = 0;
		int stairs = 0;
		int propsEntries = 0;
		for (BuildingPlan.Entry e : p.entries) {
			String id = PlanValidator.baseId(e.blockId());
			if (id.equals("light") && e.y() % Math.max(1, r.layerHeight) == 0) {
				lightsInSlab++;
			}
			if (id.equals("lantern")) {
				lanterns++;
			}
			if (id.endsWith("_door")) {
				doors++;
			}
			if (id.endsWith("_slab")) {
				slabs++;
			}
			if (id.endsWith("_stairs") || (id.endsWith("_slab") && e.y() % Math.max(1, r.layerHeight) != 0)) {
				stairs++;   // 梯段用的是半砖+整块
			}
			if (e.props() != null && !e.props().isEmpty()) {
				propsEntries++;
			}
		}
		// 二次体检：第一次已经把问题修完，第二次应该"零修正"
		PlanValidator.Report again = PlanValidator.check(p, r.layerHeight, r.floors, null);
		System.out.printf("[%s] 方块=%d 尺寸=%dx%dx%d 层=%d 层高=%d | 灯=%d 门=%d 半砖=%d 楼梯=%d 带属性方块=%d | %s%n",
				label, p.size(), p.width, p.height, p.depth, r.floors, r.layerHeight,
				lanterns, doors, slabs, stairs, propsEntries, r.report.summary());
		check(label + " → 楼板层无隐形光源", lightsInSlab == 0);
		check(label + " → 有门", doors > 0);
		check(label + " → 有灯", lanterns > 0);
		check(label + " → 使用了 blockstate 属性", propsEntries > 0);
		check(label + " → 二次体检无遗留修正 (悬空" + again.unsupportedFixed + " 空洞" + again.floorHolesFixed
				+ " 堵门" + again.doorsUnblocked + " 补洞" + again.roomsConnected + ")",
				again.unsupportedFixed == 0 && again.floorHolesFixed == 0 && again.doorsUnblocked == 0);
		if (!again.problems.isEmpty()) {
			System.out.println("      遗留: " + again.problems.subList(0, Math.min(5, again.problems.size())));
			// 诊断第一处：打印它上下左右的方块
			String first = again.problems.get(0);
			int lp = first.indexOf('(');
			int rp = first.indexOf(')');
			if (lp >= 0 && rp > lp) {
				String[] parts = first.substring(lp + 1, rp).split(",");
				int dx = Integer.parseInt(parts[0].trim());
				int dy = Integer.parseInt(parts[1].trim());
				int dz = Integer.parseInt(parts[2].trim());
				System.out.printf("      诊断 (%d,%d,%d): 自身=%s 下方=%s 上方=%s%n", dx, dy, dz,
						name(p, dx, dy, dz), name(p, dx, dy - 1, dz), name(p, dx, dy + 1, dz));
			}
		}
		// 顶层必须有屋顶封顶
		int deck = r.floors * r.layerHeight;
		int roofCells = 0;
		for (int x = 0; x < p.width; x++) {
			for (int z = 0; z < p.depth; z++) {
				if (p.get(x, deck, z) != null) {
					roofCells++;
				}
			}
		}
		int total = p.width * p.depth;
		check(label + " → 屋顶封顶 >=95% (" + roofCells + "/" + total + ")", roofCells * 100 >= total * 95);
		// 多层必须有楼梯可达（楼梯方块数 > 0）
		if (r.floors > 1) {
			check(label + " → 多层有楼梯", stairs > 0);
		}
	}

	private static void check(String what, boolean ok) {
		if (!ok) {
			failed++;
			System.out.println("   ✗ " + what);
		} else {
			System.out.println("   ✓ " + what);
		}
	}

	private static String name(BuildingPlan p, int x, int y, int z) {
		BuildingPlan.Entry e = p.get(x, y, z);
		return e == null ? "(空)" : (e.blockId() + e.props());
	}
}
