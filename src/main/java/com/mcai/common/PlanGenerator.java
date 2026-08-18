package com.mcai.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 蓝图执行器：把 AI 画的每层字符蓝图翻译成 3D 方块建筑。
 * 字符 -> 方块：'#'墙 'W'窗 'D'门 'P'柱 '.'空地
 * 'R'床 'B'书柜 'C'工作台 'F'熔炉 'L'灯笼 'T'花盆 'X'海晶灯。
 * 每层 3 格高，楼板自动铺，屋顶按 spec.roof 生成。
 */
public class PlanGenerator {

	public static BuildingPlan generate(PlanSpec spec) {
		return generate(spec, null);
	}

	public static BuildingPlan generate(PlanSpec spec, String description) {
		List<PlanParser.Blueprint> layers = PlanParser.normalize(spec);
		int w = layers.get(0).width();
		int d = layers.get(0).depth();
		int floors = spec.floors;
		int lh = spec.layerHeight == null ? 3 : spec.layerHeight;
		int h = floors * lh;

		String wall = spec.wall;
		String accent = spec.accent;
		String roofStyle = spec.roof;
		boolean furnished = spec.interiors == null || spec.interiors;

		BuildingPlan plan = new BuildingPlan(spec.name, w, h, d);

		// ========== 地基平台（外扩 1 格） ==========
		for (int x = -1; x <= w; x++) {
			for (int z = -1; z <= d; z++) {
				plan.add(x, 0, z, "stone_bricks");
			}
		}

		// ========== 逐层执行蓝图（层高 = spec.layerHeight，默认 3） ==========
		int midY = lh / 2 + 1; // 窗/灯所在的中层（lh=3 -> 2，与旧行为一致）
		for (int k = 0; k < floors; k++) {
			int base = k * lh;
			PlanParser.Blueprint bp = fixFacades(layers.get(k), k + 1);
			if (furnished) {
				bp = fillInteriors(bp);
			}

			// 楼板
			if (k > 0) {
				for (int x = 0; x < w; x++) {
					for (int z = 0; z < d; z++) {
						plan.add(x, base, z, "stone_bricks");
					}
				}
			}

			for (int z = 0; z < d; z++) {
				String row = bp.rows().get(z);
				for (int x = 0; x < w; x++) {
					char c = x < row.length() ? row.charAt(x) : '#';
					switch (c) {
						case '#' -> {
							for (int y = 1; y <= lh; y++) {
								plan.add(x, base + y, z, wall);
							}
						}
						case 'W' -> {
							for (int y = 1; y <= lh; y++) {
								plan.add(x, base + y, z, y == midY ? "glass_pane" : accent);
							}
						}
						case 'D' -> {
							plan.add(x, base + 1, z, "oak_door");
							plan.add(x, base + 2, z, "oak_door_upper");
							for (int y = 3; y <= lh; y++) {
								plan.add(x, base + y, z, accent);
							}
						}
						case 'P' -> {
							for (int y = 1; y <= lh; y++) {
								plan.add(x, base + y, z, accent);
							}
						}
						case 'S' -> {
							// 楼梯井/电梯井：脚手架柱贯穿全楼（到屋顶 h）
							for (int y = base + 1; y <= h; y++) {
								plan.add(x, y, z, "scaffolding");
							}
						}
						case 'X' -> plan.add(x, base + midY, z, "sea_lantern");
						default -> {
							if (furnished) {
								switch (c) {
									case 'R' -> {
									// 床是 1x2 两格方块：foot 在 R 格、head 朝 +x 方向一格
									plan.add(x, base + 1, z, "red_bed_foot");
									plan.add(x + 1, base + 1, z, "red_bed_head");
								}
									case 'B' -> plan.add(x, base + 1, z, "bookshelf");
									case 'C' -> plan.add(x, base + 1, z, "crafting_table");
									case 'F' -> plan.add(x, base + 1, z, "furnace");
									case 'L' -> plan.add(x, base + midY, z, "lantern");
									case 'T' -> plan.add(x, base + 1, z, "flower_pot");
									default -> {
									}
								}
							}
						}
					}
				}
			}
		}

		// ========== 门前台阶（蓝图外沿的 D） ==========
		PlanParser.Blueprint first = layers.get(0);
		for (int z = 0; z < d; z++) {
			String row = first.rows().get(z);
			for (int x = 0; x < w; x++) {
				char c = x < row.length() ? row.charAt(x) : '#';
				if (c == 'D' && z == 0) {
					plan.add(x, 0, -1, "stone_bricks");
					plan.add(x, 0, -2, "stone_bricks");
					plan.add(x - 1, 0, -1, "stone_bricks");
					plan.add(x + 1, 0, -1, "stone_bricks");
				} else if (c == 'D' && z == d - 1) {
					plan.add(x, 0, d, "stone_bricks");
					plan.add(x, 0, d + 1, "stone_bricks");
					plan.add(x - 1, 0, d, "stone_bricks");
					plan.add(x + 1, 0, d, "stone_bricks");
				}
			}
		}

		// ========== 屋顶 ==========
		applyRoof(plan, w, d, h, accent, wall, roofStyle, layers.get(floors - 1).ceilingRow());

		return plan;
	}

	/**
	 * 外墙校正：AI 常把外墙全画成窗（玻璃宫）或全画成墙（没窗）。
	 * 程序接管外墙比例——任何提示词都保证"约 1/3 窗 + 墙"的居民楼外观：
	 * - 窗太少（&lt; 每 4 格 1 个）：从墙身均匀补 W
	 * - 窗太多（&gt; 一半）：把多出的 W 均匀换回 #
	 * - 首层外墙没有门时：在南墙中央补 D 入口
	 * 室内（非外墙）字符一律不动，尊重 AI 的户型设计。
	 */
	private static PlanParser.Blueprint fixFacades(PlanParser.Blueprint bp, int layerNo) {
		int d = bp.depth();
		int w = bp.width();
		if (d < 4 || w < 4) {
			return bp;
		}
		char[][] grid = new char[d][w];
		for (int z = 0; z < d; z++) {
			String row = bp.rows().get(z);
			for (int x = 0; x < w; x++) {
				grid[z][x] = x < row.length() ? row.charAt(x) : '#';
			}
		}
		// 4 面墙：南 z=0、北 z=d-1、西 x=0、东 x=w-1（转角格不参与校正）
		fixSide(grid, d, w, 0, d - 1, w, true, false, layerNo);   // 南
		fixSide(grid, d, w, d - 1, d - 1, w, true, false, layerNo); // 北
		fixSide(grid, d, w, 0, 0, d, false, true, layerNo);       // 西
		fixSide(grid, d, w, w - 1, 0, d, false, true, layerNo);   // 东
		List<String> rows = new ArrayList<>();
		for (int z = 0; z < d; z++) {
			rows.add(new String(grid[z]));
		}
		return new PlanParser.Blueprint(rows);
	}

	/** 校正一面墙。fixZ 为 true 时固定 z 扫描 x；否则固定 x 扫描 z。 */
	private static void fixSide(char[][] grid, int d, int w, int fixCoord, int other, int len,
			boolean isZFixed, boolean isXFixed, int layerNo) {
		int start = 1;
		int end = len - 1; // 不含转角
		if (end - start < 2) {
			return;
		}
		int wc = 0, dc = 0;
		char[] seq = new char[end - start];
		for (int i = start; i < end; i++) {
			char c = isZFixed ? grid[fixCoord][i] : grid[i][fixCoord];
			seq[i - start] = c;
			if (c == 'W') {
				wc++;
			}
			if (c == 'D') {
				dc++;
			}
		}
		int n = seq.length;
		// 窗太多：目标约 1/3，超出部分均匀换回 #
		int target = Math.max(1, n / 3);
		if (wc > target) {
			int excess = wc - target;
			int step = Math.max(1, wc / excess);
			int j = 0;
			for (int i = 0; i < n && excess > 0; i++) {
				if (seq[i] == 'W') {
					j++;
					if (j % step == 0) {
						seq[i] = '#';
						excess--;
					}
				}
			}
		}
		// 窗太少：从非开口格均匀补 W（至少 1/4）
		int min = Math.max(1, n / 4);
		int need = min - count(seq, 'W');
		if (need > 0) {
			int step = Math.max(1, n / need);
			int pass = 0;
			for (int i = 0; i < n && need > 0; i++) {
				if (seq[i] != 'W' && seq[i] != 'D') {
					pass++;
					if (pass % step == 0) {
						seq[i] = 'W';
						need--;
					}
				}
			}
		}
		// 首层外墙无门：中央补 D
		if (layerNo == 1 && dc == 0) {
			seq[n / 2] = 'D';
		}
		for (int i = start; i < end; i++) {
			char c = seq[i - start];
			if (isZFixed) {
				grid[fixCoord][i] = c;
			} else {
				grid[i][fixCoord] = c;
			}
		}
	}

	private static int count(char[] arr, char c) {
		int n = 0;
		for (char v : arr) {
			if (v == c) {
				n++;
			}
		}
		return n;
	}

	/** 室内补家具：AI 常忘了画家具照明。若室内家具太少，在空地里均匀补灯/床/桌/灶/花盆。已有家具的层不动。 */
	private static PlanParser.Blueprint fillInteriors(PlanParser.Blueprint bp) {
		int d = bp.depth();
		int w = bp.width();
		char[][] grid = new char[d][w];
		int furniture = 0;
		for (int z = 0; z < d; z++) {
			String row = bp.rows().get(z);
			for (int x = 0; x < w; x++) {
				char c = x < row.length() ? row.charAt(x) : '#';
				grid[z][x] = c;
				if (c == 'R' || c == 'B' || c == 'C' || c == 'F' || c == 'L' || c == 'T' || c == 'X') {
					furniture++;
				}
			}
		}
		int floor = w * d;
		// 室内家具已经够多（每 20 格至少 1 个）就不动
		if (furniture >= floor / 20) {
			return bp;
		}
		char[] plan = {'L', 'R', 'C', 'F', 'T'};
		int idx = 0;
		int count = 0;
		for (int z = 1; z < d - 1; z++) {
			for (int x = 1; x < w - 1; x++) {
				char c = grid[z][x];
				boolean isInterior = c == '.' || c == 'X' || c == 'L';
				if (!isInterior) {
					continue;
				}
				count++;
				if (count % 6 == 0) {
					grid[z][x] = plan[idx % plan.length];
					idx++;
				}
			}
		}
		List<String> rows = new ArrayList<>();
		for (int z = 0; z < d; z++) {
			rows.add(new String(grid[z]));
		}
		return new PlanParser.Blueprint(rows);
	}

	private static void applyRoof(BuildingPlan plan, int w, int d, int h, String accent, String wall,
			String roofStyle, String ceilingRow) {
		// 顶板：实心平铺覆盖整个顶部（顶层室内天花板）。屋顶必须封严，
		// 不能有任何透光方块，否则从上面能看到室内、室内能看到天空。
		for (int x = 0; x < w; x++) {
			for (int z = 0; z < d; z++) {
				plan.add(x, h, z, wall);
			}
		}
		// 屋顶装饰（顶层蓝图多画的那一行）：放在顶板之上的 y=h+1 表面，
		// 绝不替换顶板的实心方块。P 柱/避雷针、X 海晶灯、L 灯笼；其余字符不生成。
		if (ceilingRow != null) {
			for (int z = 0; z < d && z < ceilingRow.length(); z++) {
				char c = ceilingRow.charAt(z);
				if (c == 'P' || c == 'X' || c == 'L') {
					for (int x = 0; x < w; x++) {
						plan.add(x, h + 1, z, c == 'P' ? accent : c == 'X' ? "sea_lantern" : "lantern");
					}
				}
			}
		}
		// 挑檐（墙顶外圈）
		for (int x = -1; x <= w; x++) {
			plan.add(x, h, -1, accent);
			plan.add(x, h, d, accent);
		}
		for (int z = -1; z <= d; z++) {
			plan.add(-1, h, z, accent);
			plan.add(w, h, z, accent);
		}

		if ("pyramid".equals(roofStyle)) {
			int layers = Math.min(4, h / 2);
			for (int i = 1; i <= layers; i++) {
				int y = h + i;
				for (int x = i; x < w - i; x++) {
					for (int z = i; z < d - i; z++) {
						plan.add(x, y, z, i == layers ? accent : wall);
					}
				}
			}
		} else if ("gabled".equals(roofStyle)) {
			int layers = Math.min(3, h / 2);
			for (int i = 1; i <= layers; i++) {
				int y = h + i;
				for (int x = i; x < w - i; x++) {
					for (int z = i; z < d - i; z++) {
						plan.add(x, y, z, i == layers ? accent : wall);
					}
				}
			}
			// 两端山墙三角
			int half = Math.max(1, w / 4);
			for (int i = 1; i <= layers; i++) {
				int y = h + i;
				int cur = Math.max(1, half - i);
				for (int x = w / 2 - cur; x <= w / 2 + cur; x++) {
					plan.add(x, y, -1, accent);
					plan.add(x, y, d, accent);
				}
			}
		} else {
			// flat：女儿墙
			for (int x = 0; x < w; x++) {
				plan.add(x, h + 1, 0, accent);
				plan.add(x, h + 1, d - 1, accent);
			}
			for (int z = 0; z < d; z++) {
				plan.add(0, h + 1, z, accent);
				plan.add(w - 1, h + 1, z, accent);
			}
			// 顶层照明
			for (int x = 2; x < w - 2; x += 3) {
				plan.add(x, h + 1, 0, "sea_lantern");
				plan.add(x, h + 1, d - 1, "sea_lantern");
			}
		}
	}
}