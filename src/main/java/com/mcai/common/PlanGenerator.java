package com.mcai.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 蓝图执行器：把 AI 画的每层字符蓝图翻译成 3D 方块建筑。
 * 字符 -> 方块：'#'墙 'W'窗 'D'门 'P'柱 '.'空地
 * 'R'床 'B'书柜 'C'工作台 'F'熔炉 'L'灯笼 'T'花盆 'X'海晶灯。
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

		String wall = spec.wall == null ? "oak_planks" : spec.wall;
		String accent = spec.accent == null ? "dark_oak_log" : spec.accent;
		String roofStyle = spec.roof == null ? "gabled" : spec.roof;
		boolean furnished = spec.interiors == null || spec.interiors;

		BuildingPlan plan = new BuildingPlan(spec.name, w, h, d);
		String floorBlock = getFloorBlock(wall, accent);

		// ========== 1. 地基平台（实心平铺外扩 1 格，杜绝地面破洞） ==========
		for (int x = -1; x <= w; x++) {
			for (int z = -1; z <= d; z++) {
				plan.add(x, 0, z, "stone_bricks");
			}
		}

		// ========== 2. 逐层执行蓝图 ==========
		int midY = lh / 2 + 1; // 窗户中心高度
		PlanParser.Blueprint groundBp = null;
		for (int k = 0; k < floors; k++) {
			int base = k * lh;
			PlanParser.Blueprint bp = ensureRoomEntrances(layers.get(k), k == 0);
			if (k == 0) {
				groundBp = bp;
			}
			if (furnished && k < floors - 1) {
				bp = fillInteriors(bp);
			}

			// 2.1 楼板生成：实心平铺整层木地板，杜绝空中悬空杂物
			if (k > 0) {
				for (int z = 0; z < d; z++) {
					for (int x = 0; x < w; x++) {
						plan.add(x, base, z, floorBlock);
					}
				}
			}

			// 2.2 生成本层各方块
			for (int z = 0; z < d; z++) {
				String row = bp.rows().get(z);
				for (int x = 0; x < w; x++) {
					char c = x < row.length() ? row.charAt(x) : '.';
					switch (c) {
						case '#' -> {
							for (int y = 1; y <= lh; y++) {
								plan.add(x, base + y, z, wall);
							}
						}
						case 'W' -> {
							for (int y = 1; y <= lh; y++) {
								if (y == midY) {
									plan.add(x, base + y, z, "glass_pane");
								} else if (y == 1 || y == lh) {
									plan.add(x, base + y, z, accent);
								} else {
									plan.add(x, base + y, z, "glass_pane");
								}
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
							for (int y = base + 1; y <= h; y++) {
								plan.add(x, y, z, "scaffolding");
							}
						}
						case 'X' -> plan.add(x, base + lh, z, "sea_lantern");
						default -> {
							if (furnished) {
								switch (c) {
									case 'R' -> {
										String bed = getBedType(accent, x, z);
										plan.add(x, base + 1, z, bed + "_foot");
										if (x + 1 < w - 1 && getChar(bp, x + 1, z) != '#' && getChar(bp, x + 1, z) != 'W') {
											plan.add(x + 1, base + 1, z, bed + "_head");
										}
										if (z - 1 > 0 && getChar(bp, x, z - 1) == '.') {
											plan.add(x, base + 1, z - 1, getCarpetColor(accent));
										}
									}
									case 'B' -> plan.add(x, base + 1, z, (x + z) % 2 == 0 ? "bookshelf" : "chiseled_bookshelf");
									case 'C' -> plan.add(x, base + 1, z, getWorkstation(x, z));
									case 'F' -> plan.add(x, base + 1, z, (x + z) % 2 == 0 ? "smoker" : "furnace");
									case 'K' -> plan.add(x, base + 1, z, (x + z) % 2 == 0 ? "chest" : "barrel");
									case 'H' -> plan.add(x, base + 1, z, getSofa(accent, wall));
									case 'M' -> plan.add(x, base + 1, z, "cauldron");
									case 'Y' -> plan.add(x, base + 1, z, getCarpetColor(accent));
									case 'L' -> plan.add(x, base + 1, z, (x + z) % 3 == 0 ? "soul_lantern" : "lantern");
									case 'T' -> plan.add(x, base + 1, z, getPottedPlant(x, base + 1, z));
									default -> {
									}
								}
							}
						}
					}
				}
			}
		}

		// ========== 3. 门前台阶（自动朝向空地生成迎宾台阶） ==========
		if (groundBp != null) {
			for (int z = 0; z < d; z++) {
				String row = groundBp.rows().get(z);
				for (int x = 0; x < w; x++) {
					char c = x < row.length() ? row.charAt(x) : '.';
					if (c == 'D') {
						int[] dxs = {0, 0, -1, 1};
						int[] dzs = {-1, 1, 0, 0};
						for (int i = 0; i < 4; i++) {
							int nx = x + dxs[i];
							int nz = z + dzs[i];
							if (getChar(groundBp, nx, nz) == '.') {
								plan.add(nx, 0, nz, "stone_bricks");
								plan.add(nx + dxs[i], 0, nz + dzs[i], "stone_bricks");
							}
						}
					}
				}
			}
		}

		// ========== 4. 多样化屋顶系统 ==========
		applyRoof(plan, layers.get(floors - 1), w, d, h, accent, wall, roofStyle, layers.get(floors - 1).ceilingRow());

		return plan;
	}

	private static char getChar(PlanParser.Blueprint bp, int x, int z) {
		if (bp == null || bp.rows() == null || z < 0 || z >= bp.rows().size()) {
			return '.';
		}
		String r = bp.rows().get(z);
		if (x < 0 || x >= r.length()) {
			return '.';
		}
		return r.charAt(x);
	}

	private static boolean isBuildingBlock(char c) {
		return c != '.' && c != ' ';
	}

	private static boolean isBoundary(PlanParser.Blueprint bp, int x, int z) {
		if (!isBuildingBlock(getChar(bp, x, z))) {
			return false;
		}
		return !isBuildingBlock(getChar(bp, x + 1, z)) || !isBuildingBlock(getChar(bp, x - 1, z))
				|| !isBuildingBlock(getChar(bp, x, z + 1)) || !isBuildingBlock(getChar(bp, x - 1, z));
	}

	/**
	 * 房间连通性与出入口畅通保证：
	 * 1. 门前阻挡清除：若门 'D' 紧挨外墙 '#'，自动将门提至外立面或打通门廊通道，杜绝门被方块挡住！
	 * 2. 首层大门直通室外保证：确保首层必定有至少一扇直达室外的大门（优先南面外立面正中）。
	 * 3. 门两侧通行空间保证：确保任何一扇门（室内或室外）两侧都具备可通行空间，绝不出现死胡同门。
	 * 4. 房间独立连通块打通：自动检测所有密室并在隔墙开门。
	 */
	private static PlanParser.Blueprint ensureRoomEntrances(PlanParser.Blueprint bp, boolean isGroundFloor) {
		int d = bp.depth();
		int w = bp.width();
		if (d < 3 || w < 3) {
			return bp;
		}
		char[][] grid = new char[d][w];
		for (int z = 0; z < d; z++) {
			String row = bp.rows().get(z);
			for (int x = 0; x < w; x++) {
				grid[z][x] = x < row.length() ? row.charAt(x) : '.';
			}
		}

		// ========== 1. 门前阻挡清除（若门被外立面实心墙挡住，直接将门前外墙开门或打通） ==========
		for (int z = 0; z < d; z++) {
			for (int x = 0; x < w; x++) {
				if (grid[z][x] == 'D') {
					// 检查北外立面 (z=1 被 z=0 的 '#' 挡住)
					if (z == 1 && grid[0][x] == '#') {
						grid[0][x] = 'D';
						grid[1][x] = '.';
					}
					// 检查南外立面 (z=d-2 被 z=d-1 的 '#' 挡住)
					else if (z == d - 2 && grid[d - 1][x] == '#') {
						grid[d - 1][x] = 'D';
						grid[d - 2][x] = '.';
					}
					// 检查西外立面 (x=1 被 x=0 的 '#' 挡住)
					else if (x == 1 && grid[z][0] == '#') {
						grid[z][0] = 'D';
						grid[z][1] = '.';
					}
					// 检查东外立面 (x=w-2 被 x=w-1 的 '#' 挡住)
					else if (x == w - 2 && grid[z][w - 1] == '#') {
						grid[z][w - 1] = 'D';
						grid[z][w - 2] = '.';
					}
				}
			}
		}

		// ========== 2. 首层大门直通室外保证 ==========
		if (isGroundFloor) {
			boolean hasExteriorDoor = false;
			for (int z = 0; z < d; z++) {
				for (int x = 0; x < w; x++) {
					if (grid[z][x] == 'D') {
						// 检查是否位于外立面或紧挨室外
						if (x == 0 || x == w - 1 || z == 0 || z == d - 1) {
							hasExteriorDoor = true;
							break;
						}
					}
				}
				if (hasExteriorDoor) break;
			}

			// 如果首层完全没有直通室外的大门，在南外墙（或北外墙）正中央开大门
			if (!hasExteriorDoor) {
				boolean doorPlaced = false;
				// 优先在南外立面 (z = d - 1)
				int southZ = d - 1;
				for (int offset = 0; offset < w / 2; offset++) {
					int x1 = w / 2 + offset;
					int x2 = w / 2 - offset;
					if (x1 < w - 1 && grid[southZ][x1] == '#') {
						grid[southZ][x1] = 'D';
						if (southZ - 1 >= 0 && grid[southZ - 1][x1] == '#') grid[southZ - 1][x1] = '.';
						doorPlaced = true;
						break;
					}
					if (x2 > 0 && grid[southZ][x2] == '#') {
						grid[southZ][x2] = 'D';
						if (southZ - 1 >= 0 && grid[southZ - 1][x2] == '#') grid[southZ - 1][x2] = '.';
						doorPlaced = true;
						break;
					}
				}
				// 备选北外立面 (z = 0)
				if (!doorPlaced) {
					int northZ = 0;
					for (int offset = 0; offset < w / 2; offset++) {
						int x1 = w / 2 + offset;
						int x2 = w / 2 - offset;
						if (x1 < w - 1 && grid[northZ][x1] == '#') {
							grid[northZ][x1] = 'D';
							if (northZ + 1 < d && grid[northZ + 1][x1] == '#') grid[northZ + 1][x1] = '.';
							doorPlaced = true;
							break;
						}
						if (x2 > 0 && grid[northZ][x2] == '#') {
							grid[northZ][x2] = 'D';
							if (northZ + 1 < d && grid[northZ + 1][x2] == '#') grid[northZ + 1][x2] = '.';
							doorPlaced = true;
							break;
						}
					}
				}
			}
		}

		// ========== 3. 门两侧畅通保护（杜绝门前后都被实心方块封死） ==========
		for (int z = 0; z < d; z++) {
			for (int x = 0; x < w; x++) {
				if (grid[z][x] == 'D') {
					boolean northWall = z > 0 && (grid[z - 1][x] == '#' || grid[z - 1][x] == 'W');
					boolean southWall = z < d - 1 && (grid[z + 1][x] == '#' || grid[z + 1][x] == 'W');
					boolean westWall = x > 0 && (grid[z][x - 1] == '#' || grid[z][x - 1] == 'W');
					boolean eastWall = x < w - 1 && (grid[z][x + 1] == '#' || grid[z][x + 1] == 'W');

					// 如果四周被 3 面或 4 面墙封堵，优先打通室内方向
					if (northWall && southWall && westWall && eastWall) {
						if (z + 1 < d) grid[z + 1][x] = '.';
						if (z - 1 >= 0) grid[z - 1][x] = '.';
					} else if (northWall && southWall && (westWall || eastWall)) {
						// 门处于南北墙夹缝中，打通东西方向
						if (westWall && x > 0) grid[z][x - 1] = '.';
						if (eastWall && x < w - 1) grid[z][x + 1] = '.';
					} else if (westWall && eastWall && (northWall || southWall)) {
						// 门处于东西墙夹缝中，打通南北方向
						if (northWall && z > 0) grid[z - 1][x] = '.';
						if (southWall && z < d - 1) grid[z + 1][x] = '.';
					}
				}
			}
		}

		// ========== 4. 连通块划分 (Flood Fill) ==========
		int[][] comp = new int[d][w];
		int compId = 0;
		List<Integer> compSizes = new ArrayList<>();
		List<Boolean> hasMainAccess = new ArrayList<>(); // 包含 D、S 或直接通向室外

		for (int z = 0; z < d; z++) {
			for (int x = 0; x < w; x++) {
				char c = grid[z][x];
				if (isWalkable(c) && comp[z][x] == 0) {
					compId++;
					int size = 0;
					boolean main = false;
					List<int[]> queue = new ArrayList<>();
					queue.add(new int[]{x, z});
					comp[z][x] = compId;
					int head = 0;
					while (head < queue.size()) {
						int[] cur = queue.get(head++);
						int cx = cur[0];
						int cz = cur[1];
						size++;
						char cc = grid[cz][cx];
						if (cc == 'D' || cc == 'S' || isOutdoors(grid, d, w, cx, cz)) {
							main = true;
						}
						int[] dxs = {0, 0, -1, 1};
						int[] dzs = {-1, 1, 0, 0};
						for (int i = 0; i < 4; i++) {
							int nx = cx + dxs[i];
							int nz = cz + dzs[i];
							if (nx >= 0 && nx < w && nz >= 0 && nz < d) {
								if (isWalkable(grid[nz][nx]) && comp[nz][nx] == 0) {
									comp[nz][nx] = compId;
									queue.add(new int[]{nx, nz});
								}
							}
						}
					}
					compSizes.add(size);
					hasMainAccess.add(main);
				}
			}
		}

		if (compId <= 1) {
			List<String> rows = new ArrayList<>();
			for (int z = 0; z < d; z++) {
				rows.add(new String(grid[z]));
			}
			return new PlanParser.Blueprint(rows);
		}

		// ========== 5. 确定主连通区域并打通孤立房间 ==========
		boolean[] connected = new boolean[compId + 1];
		int rootId = 1;
		int maxSize = 0;
		for (int i = 1; i <= compId; i++) {
			boolean isMain = hasMainAccess.get(i - 1);
			int sz = compSizes.get(i - 1);
			if (isMain) {
				connected[i] = true;
			}
			if (sz > maxSize) {
				maxSize = sz;
				rootId = i;
			}
		}
		boolean anyMain = false;
		for (int i = 1; i <= compId; i++) {
			if (connected[i]) {
				anyMain = true;
				break;
			}
		}
		if (!anyMain) {
			connected[rootId] = true;
		}

		boolean changed = true;
		while (changed) {
			changed = false;
			for (int unconn = 1; unconn <= compId; unconn++) {
				if (connected[unconn]) {
					continue;
				}
				int bestX = -1, bestZ = -1;

				for (int z = 1; z < d - 1; z++) {
					for (int x = 1; x < w - 1; x++) {
						if (grid[z][x] == '#') {
							if (x > 0 && x < w - 1) {
								int left = comp[z][x - 1];
								int right = comp[z][x + 1];
								if ((left == unconn && right > 0 && connected[right])
										|| (right == unconn && left > 0 && connected[left])) {
									bestX = x;
									bestZ = z;
									break;
								}
							}
							if (z > 0 && z < d - 1) {
								int up = comp[z - 1][x];
								int down = comp[z + 1][x];
								if ((up == unconn && down > 0 && connected[down])
										|| (down == unconn && up > 0 && connected[up])) {
									bestX = x;
									bestZ = z;
									break;
								}
							}
						}
					}
					if (bestX != -1) {
						break;
					}
				}

				if (bestX != -1) {
					grid[bestZ][bestX] = 'D'; // 打通房门！
					connected[unconn] = true;
					changed = true;
				}
			}
		}

		List<String> rows = new ArrayList<>();
		for (int z = 0; z < d; z++) {
			rows.add(new String(grid[z]));
		}
		return new PlanParser.Blueprint(rows);
	}

	private static boolean isWalkable(char c) {
		return c != '#' && c != 'W' && c != 'P' && c != ' ';
	}

	private static boolean isOutdoors(char[][] grid, int d, int w, int x, int z) {
		return (x == 0 || x == w - 1 || z == 0 || z == d - 1) && grid[z][x] == '.';
	}

	/** 室内补家具：仅在室内完全空旷时做适度点缀，绝不泛滥铺满 */
	private static PlanParser.Blueprint fillInteriors(PlanParser.Blueprint bp) {
		int d = bp.depth();
		int w = bp.width();
		char[][] grid = new char[d][w];
		int furniture = 0;
		for (int z = 0; z < d; z++) {
			String row = bp.rows().get(z);
			for (int x = 0; x < w; x++) {
				char c = x < row.length() ? row.charAt(x) : '.';
				grid[z][x] = c;
				if (c == 'R' || c == 'B' || c == 'C' || c == 'F' || c == 'L' || c == 'T' || c == 'X'
						|| c == 'K' || c == 'H' || c == 'M' || c == 'Y') {
					furniture++;
				}
			}
		}
		// 如果室内已有家具，尊重 AI 的原始设计，不随意篡改
		int floor = w * d;
		if (furniture >= floor / 24) {
			return bp;
		}
		char[] plan = {'H', 'Y', 'K', 'T', 'B', 'C', 'M', 'F'};
		int idx = 0;
		int count = 0;
		for (int z = 2; z < d - 2; z++) {
			for (int x = 2; x < w - 2; x++) {
				char c = grid[z][x];
				if (c == '.') {
					// 必须四周都是室内，且不在外墙或阳台边缘
					boolean isInner = isBuildingBlock(grid[z - 1][x]) && isBuildingBlock(grid[z + 1][x])
							&& isBuildingBlock(grid[z][x - 1]) && isBuildingBlock(grid[z][x + 1]);
					if (isInner) {
						count++;
						if (count % 16 == 0) {
							grid[z][x] = plan[idx % plan.length];
							idx++;
						}
					}
				}
			}
		}
		List<String> rows = new ArrayList<>();
		for (int z = 0; z < d; z++) {
			rows.add(new String(grid[z]));
		}
		return new PlanParser.Blueprint(rows);
	}

	private static void applyRoof(BuildingPlan plan, PlanParser.Blueprint topBp, int w, int d, int h,
			String accent, String wall, String roofStyle, String ceilingRow) {
		// 1. 实心天花板：必须 100% 完整平铺覆盖整个建筑顶部，严禁任何天花板空洞！
		for (int z = 0; z < d; z++) {
			for (int x = 0; x < w; x++) {
				plan.add(x, h, z, wall);
			}
		}

		// 2. 屋顶挑檐（Eaves Overhang）：沿顶层四周外扩 1 格
		for (int x = -1; x <= w; x++) {
			plan.add(x, h, -1, accent);
			plan.add(x, h, d, accent);
		}
		for (int z = -1; z <= d; z++) {
			plan.add(-1, h, z, accent);
			plan.add(w, h, z, accent);
		}

		// 3. 屋顶装饰行支持
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

		// 4. 屋顶造型
		if ("pyramid".equals(roofStyle)) {
			int layers = Math.min(5, Math.max(2, Math.min(w, d) / 2));
			for (int i = 1; i <= layers; i++) {
				int y = h + i;
				for (int z = i; z < d - i; z++) {
					for (int x = i; x < w - i; x++) {
						plan.add(x, y, z, (x == i || x == w - i - 1 || z == i || z == d - i - 1) ? accent : wall);
					}
				}
			}
			int peakX = w / 2;
			int peakZ = d / 2;
			plan.add(peakX, h + layers + 1, peakZ, accent);
			plan.add(peakX, h + layers + 2, peakZ, "sea_lantern");
			plan.add(peakX, h + layers + 3, peakZ, "lantern");
		} else if ("gabled".equals(roofStyle)) {
			int layers = Math.min(4, Math.max(2, d / 2));
			for (int i = 1; i <= layers; i++) {
				int y = h + i;
				for (int z = i; z < d - i; z++) {
					for (int x = -1; x <= w; x++) {
						boolean isRidge = (z == i || z == d - i - 1);
						plan.add(x, y, z, isRidge ? accent : wall);
					}
				}
			}
			int ridgeZ = d / 2;
			for (int x = -1; x <= w; x++) {
				plan.add(x, h + layers, ridgeZ, accent);
			}
			plan.add(-1, h + layers + 1, ridgeZ, "lantern");
			plan.add(w, h + layers + 1, ridgeZ, "lantern");
		} else if ("castle".equals(roofStyle)) {
			for (int x = 0; x < w; x++) {
				if (x % 2 == 0) {
					plan.add(x, h + 1, 0, accent);
					plan.add(x, h + 1, d - 1, accent);
				}
			}
			for (int z = 0; z < d; z++) {
				if (z % 2 == 0) {
					plan.add(0, h + 1, z, accent);
					plan.add(w - 1, h + 1, z, accent);
				}
			}
			plan.add(0, h + 2, 0, "lantern");
			plan.add(w - 1, h + 2, 0, "lantern");
			plan.add(0, h + 2, d - 1, "lantern");
			plan.add(w - 1, h + 2, d - 1, "lantern");
		} else {
			// flat / modern
			for (int x = 0; x < w; x++) {
				plan.add(x, h + 1, 0, accent);
				plan.add(x, h + 1, d - 1, accent);
			}
			for (int z = 0; z < d; z++) {
				plan.add(0, h + 1, z, accent);
				plan.add(w - 1, h + 1, z, accent);
			}
			for (int x = 2; x < w - 2; x += 4) {
				plan.add(x, h + 1, 0, "sea_lantern");
				plan.add(x, h + 1, d - 1, "sea_lantern");
			}
		}
	}

	private static String getFloorBlock(String wall, String accent) {
		if (wall == null) {
			return "oak_planks";
		}
		String w = wall.toLowerCase();
		if (w.contains("dark_oak")) {
			return "spruce_planks";
		}
		if (w.contains("spruce")) {
			return "oak_planks";
		}
		if (w.contains("birch")) {
			return "oak_planks";
		}
		if (w.contains("oak")) {
			return "birch_planks";
		}
		if (w.contains("concrete")) {
			return "birch_planks";
		}
		if (w.contains("terracotta")) {
			return "dark_oak_planks";
		}
		return "oak_planks";
	}

	private static final String[] BED_TYPES = {
		"red_bed", "blue_bed", "white_bed", "cyan_bed", "light_gray_bed", "yellow_bed", "black_bed"
	};

	private static String getBedType(String accent, int x, int z) {
		if (accent != null) {
			String a = accent.toLowerCase();
			if (a.contains("red") || a.contains("terracotta")) {
				return "red_bed";
			}
			if (a.contains("blue")) {
				return "blue_bed";
			}
			if (a.contains("dark")) {
				return "cyan_bed";
			}
			if (a.contains("white")) {
				return "white_bed";
			}
		}
		int idx = Math.abs((x * 19 + z * 7) % BED_TYPES.length);
		return BED_TYPES[idx];
	}

	private static String getCarpetColor(String accent) {
		if (accent != null) {
			String a = accent.toLowerCase();
			if (a.contains("red") || a.contains("terracotta")) {
				return "red_carpet";
			}
			if (a.contains("blue")) {
				return "blue_carpet";
			}
			if (a.contains("dark")) {
				return "light_gray_carpet";
			}
			if (a.contains("white")) {
				return "white_carpet";
			}
		}
		return "light_gray_carpet";
	}

	private static final String[] WORKSTATIONS = {
		"crafting_table", "cartography_table", "smithing_table", "loom"
	};

	private static String getWorkstation(int x, int z) {
		int idx = Math.abs((x * 13 + z * 29) % WORKSTATIONS.length);
		return WORKSTATIONS[idx];
	}

	private static String getSofa(String accent, String wall) {
		if (wall != null && wall.contains("birch")) {
			return "birch_stairs";
		}
		if (wall != null && wall.contains("spruce")) {
			return "spruce_stairs";
		}
		if (accent != null && accent.contains("dark_oak")) {
			return "dark_oak_stairs";
		}
		return "oak_stairs";
	}

	private static final String[] POTTED_PLANTS = {
		"potted_poppy",
		"potted_dandelion",
		"potted_azalea_bush",
		"potted_flowering_azalea_bush",
		"potted_cornflower",
		"potted_oak_sapling",
		"potted_fern",
		"potted_red_tulip",
		"potted_white_tulip",
		"potted_allium"
	};

	private static String getPottedPlant(int x, int y, int z) {
		int idx = Math.abs((x * 31 + y * 17 + z * 13) % POTTED_PLANTS.length);
		return POTTED_PLANTS[idx];
	}
}
