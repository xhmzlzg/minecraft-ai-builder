package com.mcai.common.spec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mcai.common.BuildingPlan;
import com.mcai.common.PlanValidator;

/**
 * 构件库底层原语：铺/挖/墙/窗/门/栏杆/楼梯/电梯/屋面/照明。
 * 全部是纯 Java（不引用 Minecraft 类），几何规则沿用在此存档里验证过的那一套：
 *   - 灯笼一律吊在实心天花板下（不会掉）
 *   - 隐形光源只放房间空气格，绝不进楼板（不会踩空）
 *   - 门/压力板/招牌都有实心支撑
 *   - 双开门的铰链朝两侧（向两边开）
 */
public final class PartLib {

	private PartLib() {
	}

	public static Map<String, String> props(String... kv) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			m.put(kv[i], kv[i + 1]);
		}
		return m;
	}

	public static void fill(BuildingPlan p, int x0, int y0, int z0, int x1, int y1, int z1, String id) {
		fillProps(p, x0, y0, z0, x1, y1, z1, id, Map.of());
	}

	public static void fillProps(BuildingPlan p, int x0, int y0, int z0, int x1, int y1, int z1,
			String id, Map<String, String> props) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
					p.add(x, y, z, id, props);
				}
			}
		}
	}

	/** 强制覆写（开洞/换材质用） */
	public static void set(BuildingPlan p, int x, int y, int z, String id) {
		p.set(x, y, z, id);
	}

	public static void clear(BuildingPlan p, int x0, int y0, int z0, int x1, int y1, int z1) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
					p.remove(x, y, z);
				}
			}
		}
	}

	/** 两格高门（含上半格，朝向/铰链显式给全） */
	public static void door(BuildingPlan p, int x, int y, int z, String id, String facing, String hinge) {
		p.set(x, y, z, id, props("facing", facing, "half", "lower", "hinge", hinge,
				"open", "false", "powered", "false"));
		p.set(x, y + 1, z, id, props("facing", facing, "half", "upper", "hinge", hinge,
				"open", "false", "powered", "false"));
	}

	/** 单开门（占 1×2） */
	public static void door(BuildingPlan p, int x, int y, int z, String id, String facing) {
		door(p, x, y, z, id, facing, "left");
	}

	/** 窗带：装玻璃，两端用窗框色 */
	public static void windowRun(BuildingPlan p, List<int[]> cells, int y0, int y1, String glass, String frame) {
		boolean useFrame = cells.size() >= 4;
		for (int i = 0; i < cells.size(); i++) {
			int[] c = cells.get(i);
			boolean edge = useFrame && (i == 0 || i == cells.size() - 1);
			for (int y = y0; y <= y1; y++) {
				p.set(c[0], y, c[1], edge ? frame : glass);
			}
		}
	}

	/** 铁栏杆/玻璃围栏：按相邻格算好连接属性，避免渲染成一根根孤立柱子 */
	public static void railRun(BuildingPlan p, List<int[]> cells, int y, String id) {
		for (int i = 0; i < cells.size(); i++) {
			int[] c = cells.get(i);
			Map<String, String> pr = new LinkedHashMap<>();
			if (i > 0) {
				int[] prev = cells.get(i - 1);
				if (prev[0] < c[0]) {
					pr.put("west", "true");
				} else if (prev[0] > c[0]) {
					pr.put("east", "true");
				} else if (prev[1] < c[1]) {
					pr.put("north", "true");
				} else if (prev[1] > c[1]) {
					pr.put("south", "true");
				}
			}
			if (i < cells.size() - 1) {
				int[] next = cells.get(i + 1);
				if (next[0] > c[0]) {
					pr.put("east", "true");
				} else if (next[0] < c[0]) {
					pr.put("west", "true");
				} else if (next[1] > c[1]) {
					pr.put("south", "true");
				} else if (next[1] < c[1]) {
					pr.put("north", "true");
				}
			}
			p.set(c[0], y, c[1], id, pr);
		}
	}

	/**
	 * 吊灯：自动放在"天花板下方那一格"。调用方保证头顶是实心（本方法会再确认一次，
	 * 不满足就不放，避免出现掉落的灯笼）。
	 */
	public static void hangLantern(BuildingPlan p, int x, int ceilY, int z) {
		int y = ceilY - 1;
		if (!p.isSupporting(x, ceilY, z)) {
			return;
		}
		p.set(x, y, z, "lantern", props("hanging", "true"));
	}

	/** 隐形光源：只允许放在房间里（楼板平面 y%layerHeight==0 不放） */
	public static void hiddenLight(BuildingPlan p, int x, int y, int z, int layerHeight) {
		if (layerHeight > 0 && y % layerHeight == 0) {
			return;
		}
		if (p.get(x, y, z) == null) {
			p.set(x, y, z, "light", props("level", "15"));
		}
	}

	/** 双跑楼梯：8 个半格踏步升高一整层，梯井贯通（沿用已验证的几何） */
	public static void switchbackStair(BuildingPlan p, int x0, int z0, int floorY, int layerH, String mat) {
		String slab = mat + "_slab";
		// 西跑(上行)：由北(z0+6)向南(z0+3)升高 2 格
		p.add(x0, floorY + 1, z0 + 6, slab, props("type", "bottom"));
		p.add(x0, floorY + 1, z0 + 5, mat);
		p.add(x0, floorY + 2, z0 + 4, slab, props("type", "bottom"));
		p.add(x0, floorY + 2, z0 + 3, mat);
		// 半层平台 (z0+1..z0+2)
		for (int x = x0; x <= x0 + 2; x++) {
			for (int z = z0 + 1; z <= z0 + 2; z++) {
				p.add(x, floorY + 2, z, mat);
			}
		}
		// 东跑(上行)：由南(z0+3)向北(z0+6)再升高 2 格，到达上一层楼面
		p.add(x0 + 2, floorY + 3, z0 + 3, slab, props("type", "bottom"));
		p.add(x0 + 2, floorY + 3, z0 + 4, mat);
		p.add(x0 + 2, floorY + 4, z0 + 5, slab, props("type", "bottom"));
		p.add(x0 + 2, floorY + 4, z0 + 6, mat);
		// 中缝栏杆（不与人的站位冲突）
		p.add(x0 + 1, floorY + 2, z0 + 6, "iron_bars");
		p.add(x0 + 1, floorY + 3, z0 + 5, "iron_bars");
		p.add(x0 + 1, floorY + 4, z0 + 4, "iron_bars");
	}

	/** 电梯井：脚手架井筒 + 铁质轿厢 + 门套 + 门内压力板 + 井道按钮（沿用已验证的方案） */
	public static void elevator(BuildingPlan p, int x0, int z0, int floorY, int floors, int layerH,
			String doorId, int carFloorIndex, int topY) {
		// 井筒：3×3 脚手架，逐层在门前掏站位
		for (int y = 1; y <= topY; y++) {
			for (int x = x0; x <= x0 + 2; x++) {
				for (int z = z0; z <= z0 + 2; z++) {
					p.add(x, y, z, "scaffolding", props("bottom", y == 1 ? "true" : "false", "distance", "0"));
				}
			}
		}
		// 轿厢（铁底 + 铁顶 + 顶灯）
		int cy = carFloorIndex * layerH;
		for (int x = x0; x <= x0 + 2; x++) {
			for (int z = z0; z <= z0 + 2; z++) {
				p.set(x, cy, z, "iron_block");
				p.remove(x, cy + 1, z);
				p.remove(x, cy + 2, z);
				p.set(x, cy + 3, z, "iron_block");
			}
		}
		p.set(x0 + 1, cy + 3, z0 + 1, "sea_lantern");
		p.set(x0 + 1, cy + 2, z0, "light", props("level", "15"));
		// 每层门前掏站位 + 门内压力板 + 视线高度按钮
		for (int k = 0; k < floors; k++) {
			int f = k * layerH;
			for (int x = x0; x <= x0 + 2; x++) {
				p.remove(x, f + 1, z0 + 2);
				p.remove(x, f + 2, z0 + 2);
			}
			p.set(x0, f, z0 + 2, "smooth_stone");
			p.set(x0 + 1, f, z0 + 2, "smooth_stone");
			p.set(x0, f + 1, z0 + 2, "stone_pressure_plate", props("powered", "false"));
			p.set(x0 + 1, f + 1, z0 + 2, "stone_pressure_plate", props("powered", "false"));
			// 电梯门（双开铁门，铰链朝两侧 = 向两边开）+ 门套铁块(按钮的支撑)
			int doorZ = z0 + 3;
			p.remove(x0, f + 2, doorZ);
			p.remove(x0 + 1, f + 2, doorZ);
			p.remove(x0, f + 3, doorZ);
			p.remove(x0 + 1, f + 3, doorZ);
			door(p, x0, f + 1, doorZ, doorId, "south", "right");
			door(p, x0 + 1, f + 1, doorZ, doorId, "south", "left");
			p.set(x0 + 2, f + 1, doorZ, "iron_block");
			p.set(x0 + 2, f + 2, doorZ, "iron_block");
			p.set(x0 + 2, f + 3, doorZ, "iron_block");
			// 按钮背靠上面那块实心铁门套（facing=north 时支撑在 +z，即门套那格）
			p.set(x0 + 2, f + 2, z0 + 2, "stone_button",
					props("face", "wall", "facing", "north", "powered", "false"));
		}
	}

	/** 屋面女儿墙 + 压顶 */
	public static void parapet(BuildingPlan p, int w, int d, int y, String wall, String cap) {
		for (int x = 0; x < w; x++) {
			for (int z : new int[] { 0, d - 1 }) {
				p.add(x, y, z, wall);
				p.add(x, y + 1, z, cap);
			}
		}
		for (int z = 0; z < d; z++) {
			for (int x : new int[] { 0, w - 1 }) {
				p.add(x, y, z, wall);
				p.add(x, y + 1, z, cap);
			}
		}
	}

	/** 屋面设备：水箱 / 太阳能 / 排气管 / 避雷针 */
	public static void roofKit(BuildingPlan p, int w, int d, int deckY, BuildingSpec.RoofSpec rs,
			String tankMat, String metal) {
		int y = deckY + 1;
		// 水箱
		if (rs.waterTank == null || rs.waterTank) {
			int tx = Math.max(1, w - 8);
			int tz = Math.max(1, d - 8);
			fill(p, tx, y, tz, tx + 2, y + 1, tz + 2, tankMat);
			fill(p, tx, y + 2, tz, tx + 2, y + 2, tz + 2, "smooth_stone_slab");
		}
		// 太阳能热水器
		if (rs.solar == null || rs.solar) {
			int[][] spots = { { 3, 3 }, { 7, 6 }, { Math.max(2, w - 8), 3 } };
			for (int[] s : spots) {
				if (s[0] + 1 >= w - 1 || s[1] + 1 >= d - 1) {
					continue;
				}
				p.add(s[0], y, s[1], "iron_bars");
				p.add(s[0] + 1, y, s[1], "iron_bars");
				p.add(s[0], y + 1, s[1], "gray_concrete");
				p.add(s[0] + 1, y + 1, s[1], "gray_concrete");
				p.add(s[0], y + 1, s[1] + 1, "cauldron");
			}
		}
		// 通气管
		for (int[] v : new int[][] { { 2, d - 3 }, { w - 3, 2 } }) {
			if (v[0] <= 0 || v[1] <= 0 || v[0] >= w - 1 || v[1] >= d - 1) {
				continue;
			}
			p.add(v[0], y, v[1], tankMat);
			p.add(v[0], y + 1, v[1], "copper_grate");
		}
		// 避雷针
		if (rs.lightningRod == null || rs.lightningRod) {
			p.add(0, y + 2, 0, "lightning_rod", props("facing", "up"));
			p.add(w - 1, y + 2, d - 1, "lightning_rod", props("facing", "up"));
		}
	}

	/** 屋顶花园（顶层露台绿化） */
	public static void roofGarden(BuildingPlan p, int w, int d, int deckY) {
		int y = deckY + 1;
		for (int x = 2; x < w - 2; x += 3) {
			for (int z = 2; z < d - 2; z += 3) {
				p.add(x, y, z, "potted_fern");
			}
		}
	}

	/** 一个方块是不是"空气"（用于判断是否已有内容） */
	public static boolean isAir(BuildingPlan p, int x, int y, int z) {
		BuildingPlan.Entry e = p.get(x, y, z);
		return e == null || "air".equals(PlanValidator.baseId(e.blockId()));
	}
}
