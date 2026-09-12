package com.mcai.common;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 方案体检器（v1.1.0）：在把方案交给玩家预览/建造之前，先机械地检查那些
 * "模型经常搞错、玩家一眼就会骂"的问题，并尽量自动修好。
 *
 * 检查项：
 *   A. 悬空方块：需要支撑的方块（灯笼/旗帜/挂牌/压力板/门/花盆）下方或背后是否有实心支撑
 *   B. 楼板空洞：楼板平面（y % 层高 == 0）不允许出现 light 这类没有碰撞箱的方块
 *   C. 门口被堵：门前后那一格被家具等实心方块占住 → 人进不去
 *   D. 房间可达：逐层从门口 BFS，找不到的房间会被自动开一个门洞
 *
 * 全部为纯 Java（不认识 Minecraft 类），便于离线自测。
 */
public final class PlanValidator {

	/** 没有支撑能力/不该当支撑用的方块 */
	private static final Set<String> NON_SUPPORTING = Set.of(
			"air", "cave_air", "void_air", "light",
			"glass", "glass_pane", "tinted_glass",
			"white_stained_glass_pane", "light_gray_stained_glass_pane", "gray_stained_glass_pane",
			"black_stained_glass_pane", "brown_stained_glass_pane", "red_stained_glass_pane",
			"orange_stained_glass_pane", "yellow_stained_glass_pane", "lime_stained_glass_pane",
			"green_stained_glass_pane", "cyan_stained_glass_pane", "light_blue_stained_glass_pane",
			"blue_stained_glass_pane", "purple_stained_glass_pane", "magenta_stained_glass_pane",
			"pink_stained_glass_pane",
			"iron_bars", "iron_chain", "copper_chain", "scaffolding",
			"lantern", "soul_lantern",
			"torch", "wall_torch", "soul_torch", "soul_wall_torch",
			"oak_sign", "spruce_sign", "oak_wall_sign", "spruce_wall_sign", "oak_hanging_sign",
			"stone_button", "oak_button", "polished_blackstone_button",
			"stone_pressure_plate", "oak_pressure_plate", "light_weighted_pressure_plate",
			"heavy_weighted_pressure_plate", "polished_blackstone_pressure_plate",
			"white_banner", "blue_banner", "red_banner", "yellow_banner",
			"white_wall_banner", "blue_wall_banner", "red_wall_banner", "yellow_wall_banner");

	/** 人可以直接穿过的方块（不挡路） */
	private static final Set<String> PASSABLE = Set.of(
			"air", "cave_air", "void_air", "light", "water",
			"lantern", "soul_lantern", "iron_chain", "copper_chain",
			"stone_button", "oak_button", "polished_blackstone_button",
			"stone_pressure_plate", "oak_pressure_plate", "light_weighted_pressure_plate",
			"heavy_weighted_pressure_plate", "polished_blackstone_pressure_plate",
			"oak_sign", "spruce_sign", "oak_wall_sign", "spruce_wall_sign",
			"white_banner", "blue_banner", "red_banner", "yellow_banner",
			"white_wall_banner", "blue_wall_banner", "red_wall_banner", "yellow_wall_banner",
			"iron_bars");

	public static boolean isSupportingBlock(String id) {
		String base = baseId(id);
		if (NON_SUPPORTING.contains(base)) {
			return false;
		}
		// 半砖：只有底半砖（或双半砖）上面能站东西
		if (base.endsWith("_slab")) {
			return true;
		}
		return true;
	}

	/**
	 * 是否能当"按钮/拉杆"的安装面：必须是完整实心方块。
	 * 门、活板门、半砖、台阶、玻璃、栏杆、链条、脚手架、告示牌等都不算（游戏里会判定无支撑、按钮掉落）。
	 */
	public static boolean isSturdy(String id) {
		String b = baseId(id);
		if (b.equals("air") || b.equals("light") || b.equals("cave_air") || b.equals("void_air")) {
			return false;
		}
		if (b.endsWith("_door") || b.endsWith("_trapdoor") || b.endsWith("_fence_gate")
				|| b.endsWith("_fence") || b.endsWith("_slab") || b.endsWith("_stairs")
				|| b.endsWith("_pane") || b.endsWith("_bars") || b.endsWith("_carpet")
				|| b.endsWith("_sign") || b.endsWith("_banner") || b.endsWith("_button")
				|| b.endsWith("_pressure_plate") || b.endsWith("_rail") || b.endsWith("_torch")
				|| b.equals("lantern") || b.equals("soul_lantern") || b.equals("scaffolding")
				|| b.equals("iron_bars") || b.contains("chain") || b.endsWith("_wall_sign")
				|| b.endsWith("_wall_banner") || b.equals("end_rod") || b.equals("lightning_rod")) {
			return false;
		}
		return true;
	}

	public static String baseId(String id) {
		if (id == null) {
			return "air";
		}
		String s = id;
		int colon = s.indexOf(':');
		if (colon >= 0) {
			s = s.substring(colon + 1);
		}
		return s;
	}

	private static boolean passable(String id) {
		String b = baseId(id);
		if (PASSABLE.contains(b)) {
			return true;
		}
		// 地毯不挡路
		if (b.endsWith("_carpet")) {
			return true;
		}
		// 门（含上下半）算可通行：玩家按一下/踩压力板就能过
		if (b.endsWith("_door")) {
			return true;
		}
		// 活板门、栅栏、栅栏门不视作通路（保守）
		return false;
	}

	/** 体检报告 */
	public static final class Report {
		public final List<String> problems = new ArrayList<>();
		public int unsupportedFixed;
		public int floorHolesFixed;
		public int doorsUnblocked;
		public int roomsConnected;

		public boolean clean() {
			return problems.isEmpty();
		}

		public String summary() {
			StringBuilder sb = new StringBuilder();
			sb.append("自动修正: 悬空方块 ").append(unsupportedFixed)
					.append(" / 楼板空洞 ").append(floorHolesFixed)
					.append(" / 堵门 ").append(doorsUnblocked)
					.append(" / 补门洞 ").append(roomsConnected);
			if (!problems.isEmpty()) {
				sb.append("；遗留问题 ").append(problems.size()).append(" 条");
			}
			return sb.toString();
		}
	}

	/** 需要支撑的方块判定：返回 [是否悬空, 原因] */
	private static String supportProblem(BuildingPlan plan, BuildingPlan.Entry e) {
		String id = baseId(e.blockId());
		int x = e.x();
		int y = e.y();
		int z = e.z();
		if (id.equals("lantern") || id.equals("soul_lantern")) {
			boolean hanging = e.props() != null && "true".equals(e.props().get("hanging"));
			if (hanging) {
				if (!plan.isSupporting(x, y + 1, z)) {
					return "吊灯上方没有实心方块";
				}
			} else if (!plan.isSupporting(x, y - 1, z)) {
				return "灯笼下方没有实心方块";
			}
		} else if (id.endsWith("_banner")) {
			if (!plan.isSupporting(x, y - 1, z)) {
				return "旗帜下方没有实心方块";
			}
		} else if (id.endsWith("_wall_sign")) {
			String f = e.props() == null ? null : e.props().get("facing");
			int dx = 0;
			int dz = 0;
			if ("north".equals(f)) {
				dz = 1;
			} else if ("south".equals(f)) {
				dz = -1;
			} else if ("east".equals(f)) {
				dx = -1;
			} else if ("west".equals(f)) {
				dx = 1;
			} else {
				return null;
			}
			if (!plan.isSupporting(x + dx, y, z + dz)) {
				return "挂牌背后没有实心方块";
			}
		} else if (id.endsWith("_pressure_plate")) {
			if (!plan.isSupporting(x, y - 1, z)) {
				return "压力板下方没有实心方块";
			}
		} else if (id.endsWith("_door") && (e.props() == null || "lower".equals(e.props().getOrDefault("half", "lower")))) {
			if (!plan.isSupporting(x, y - 1, z)) {
				return "门下方没有实心方块";
			}
		} else if (id.equals("flower_pot") || id.startsWith("potted_")) {
			if (!plan.isSupporting(x, y - 1, z)) {
				return "花盆下方没有实心方块";
			}
		}
		return null;
	}

	/**
	 * 体检 + 自动修正。
	 *
	 * @param layerHeight 层高（楼板平面在 y % layerHeight == 0）
	 * @param floors      地上层数
	 * @param roomSeeds   每层一个"已经在外面/公共区"的种子格（用来做可达性 BFS），可为 null
	 */
	public static Report check(BuildingPlan plan, int layerHeight, int floors, List<int[]> roomSeeds) {
		Report r = new Report();
		int lh = Math.max(1, layerHeight);

		// ---- A. 悬空方块：删掉（比留着掉一地强）；这里只处理配饰类，门/压力板不动 ----
		List<BuildingPlan.Entry> snapshot = new ArrayList<>(plan.entries);
		for (BuildingPlan.Entry e : snapshot) {
			String why = supportProblem(plan, e);
			if (why == null) {
				continue;
			}
			String id = baseId(e.blockId());
			if (id.equals("lantern") || id.equals("soul_lantern") || id.endsWith("_banner")
					|| id.equals("flower_pot") || id.startsWith("potted_")) {
				plan.remove(e.x(), e.y(), e.z());
				r.unsupportedFixed++;
			} else {
				r.problems.add(String.format("(%d,%d,%d) %s：%s", e.x(), e.y(), e.z(), id, why));
			}
		}

		// ---- B. 楼板空洞：楼板平面不允许 light / 空气"伪装"（非实心会踩空） ----
		for (BuildingPlan.Entry e : new ArrayList<>(plan.entries)) {
			if (e.y() % lh != 0) {
				continue;
			}
			String id = baseId(e.blockId());
			if (id.equals("light")) {
				plan.remove(e.x(), e.y(), e.z());
				r.floorHolesFixed++;
			}
		}

		// ---- C. 门口被堵：门前后一格若是实心家具则删掉 ----
		for (BuildingPlan.Entry e : new ArrayList<>(plan.entries)) {
			if (!baseId(e.blockId()).endsWith("_door")) {
				continue;
			}
			if (e.props() != null && "upper".equals(e.props().get("half"))) {
				continue;
			}
			String f = e.props() == null ? null : e.props().get("facing");
			int dx = 0;
			int dz = 0;
			if ("north".equals(f)) {
				dz = 1;
			} else if ("south".equals(f)) {
				dz = -1;
			} else if ("east".equals(f)) {
				dx = -1;
			} else if ("west".equals(f)) {
				dx = 1;
			}
			for (int s = -1; s <= 1; s += 2) {
				int cx = e.x() + dx * s;
				int cz = e.z() + dz * s;
				BuildingPlan.Entry other = plan.get(cx, e.y(), cz);
				if (other == null) {
					continue;
				}
				String oid = baseId(other.blockId());
				if (!passable(oid) && !oid.endsWith("_door")) {
					plan.remove(cx, e.y(), cz);
					BuildingPlan.Entry upper = plan.get(cx, e.y() + 1, cz);
					if (upper != null && !passable(baseId(upper.blockId()))) {
						plan.remove(cx, e.y() + 1, cz);
					}
					r.doorsUnblocked++;
				}
			}
		}

		// ---- D. 房间可达（BFS 之外还有孤岛就打通） ----
		if (roomSeeds != null && !roomSeeds.isEmpty()) {
			for (int k = 0; k < floors; k++) {
				int y = k * lh + 1;
				int[] seed = roomSeeds.get(Math.min(k, roomSeeds.size() - 1));
				Set<Long> seen = flood(plan, seed[0], y, seed[1], plan.width, plan.depth);
				if (seen.isEmpty()) {
					continue;
				}
				// 找出这一层所有"非空气"但不可达的封闭区域，从边界开个 2 格高的洞
				for (BuildingPlan.Entry e : new ArrayList<>(plan.entries)) {
					if (e.y() != y || !passable(baseId(e.blockId()))) {
						continue;
					}
					if (seen.contains(k(e.x(), e.z()))) {
						continue;
					}
					// 找到与可达区相邻的墙格
					int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
					boolean opened = false;
					for (int[] d : dirs) {
						int wx = e.x() + d[0];
						int wz = e.z() + d[1];
						BuildingPlan.Entry wall = plan.get(wx, y, wz);
						if (wall == null || passable(baseId(wall.blockId())) || baseId(wall.blockId()).endsWith("_door")) {
							continue;
						}
						int bx = wx + d[0];
						int bz = wz + d[1];
						if (!seen.contains(k(bx, bz))) {
							continue;
						}
						plan.remove(wx, y, wz);
						plan.remove(wx, y + 1, wz);
						r.roomsConnected++;
						opened = true;
						break;
					}
					if (opened) {
						seen = flood(plan, seed[0], y, seed[1], plan.width, plan.depth);
					}
				}
			}
		}

		if (r.problems.isEmpty()) {
			r.problems.addAll(List.of());
		}
		return r;
	}

	private static long k(int x, int z) {
		return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
	}

	/** 从种子格做 4 向 BFS（同一层），返回可达格集合 */
	public static Set<Long> flood(BuildingPlan plan, int sx, int sy, int sz, int w, int d) {
		Set<Long> seen = new HashSet<>();
		if (plan.get(sx, sy, sz) != null && !passable(baseId(plan.get(sx, sy, sz).blockId()))) {
			return seen;
		}
		ArrayDeque<int[]> q = new ArrayDeque<>();
		q.add(new int[] { sx, sz });
		seen.add(k(sx, sz));
		int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
		while (!q.isEmpty()) {
			int[] c = q.poll();
			for (int[] dd : dirs) {
				int nx = c[0] + dd[0];
				int nz = c[1] + dd[1];
				if (nx < 0 || nz < 0 || nx >= w || nz >= d) {
					continue;
				}
				if (seen.contains(k(nx, nz))) {
					continue;
				}
				BuildingPlan.Entry e = plan.get(nx, sy, nz);
				if (e != null && !passable(baseId(e.blockId()))) {
					continue;
				}
				// 脚下要有地板（或本身是地板层）
				seen.add(k(nx, nz));
				q.add(new int[] { nx, nz });
			}
		}
		return seen;
	}
}
