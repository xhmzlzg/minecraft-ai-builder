package com.mcai.common.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mcai.common.BuildingPlan;
import com.mcai.common.PlanValidator;

/**
 * 规格展开器：把 {@link BuildingSpec}（AI 说的"要什么"）确定性地盖成 {@link BuildingPlan}。
 *
 * 两类布局：
 *   - chinese_highrise：一梯两户板楼（中间核心筒：双跑楼梯 + 脚手架电梯 + 候梯厅），
 *     户型沿用在此存档里验证过的 11×15 布局，按框选大小等比缩放；
 *   - generic：任意建筑（别墅/城堡/小楼）——外壳 + 分格房间 + 自动门洞/窗 + 家具 + 楼梯 + 屋顶。
 *
 * 几何保证：灯笼吊在实心天花板下、隐形光源不进楼板、门不被家具堵、双开门朝两侧开、
 * 楼梯 8 个半格踏步且楼梯井贯通。
 */
public final class SpecBuilder {

	private SpecBuilder() {
	}

	/** 标准户型（11×15）—— 与已验证的存档方案一致 */
	private static final Object[][] UNIT_11x15 = {
			{ "balcony", 0, 0, 5, 1 },
			{ "living", 0, 3, 5, 6 },
			{ "master", 7, 0, 10, 4 },
			{ "dining", 0, 8, 3, 10 },
			{ "bath", 4, 8, 6, 10 },
			{ "hall", 8, 6, 10, 10 },
			{ "kitchen", 0, 12, 2, 14 },
			{ "bed2", 4, 12, 6, 14 },
			{ "entry", 8, 12, 10, 14 },
	};

	public static BuildingPlan build(BuildingSpec spec, int availW, int availD) {
		return build(spec, availW, availD, Integer.MAX_VALUE).plan;
	}

	/** 构建结果：方案 + 体检报告 */
	public static final class Result {
		public final BuildingPlan plan;
		public final com.mcai.common.PlanValidator.Report report;
		public final int floors;
		public final int layerHeight;

		Result(BuildingPlan plan, com.mcai.common.PlanValidator.Report report, int floors, int layerHeight) {
			this.plan = plan;
			this.report = report;
			this.floors = floors;
			this.layerHeight = layerHeight;
		}
	}

	/**
	 * @param availW/availD 玩家框选的可用宽/进深（缺省时按此贴合）
	 * @param availH        玩家框选的可用高度（用来限制层数，避免超出范围）
	 */
	public static Result build(BuildingSpec spec, int availW, int availD, int availH) {
		BuildingSpec.Pal pal = BuildingSpec.Pal.of(spec);
		int W = spec.size != null ? spec.size.get(0) : availW;
		int D = spec.size != null ? spec.size.get(1) : availD;
		W = clamp(W, 7, 96);
		D = clamp(D, 7, 96);
		String arch = spec.archetype == null ? "generic" : spec.archetype;
		int lh = spec.layerHeight != null ? spec.layerHeight : defaultLayer(arch);
		int floors = spec.floors != null ? spec.floors : defaultFloors(arch);
		floors = clamp(floors, 1, 60);
		if (floors > 1 && (W < 9 || D < 11)) {
			floors = 1;
		}
		if (availH > 0 && availH < Integer.MAX_VALUE) {
			int fit = Math.max(1, (availH - 5) / Math.max(3, lh));
			floors = Math.min(floors, fit);
		}
		int H = floors * lh + 5;
		BuildingPlan plan = new BuildingPlan(spec.name, W, H, D);

		boolean highrise = "chinese_highrise".equals(arch) && W >= 25 && D >= 17;
		LayoutModel model = highrise ? highriseModel(W, D) : null;
		if (model == null) {
			model = genericModel(W, D, floors > 1);
		}
		if (model == null) {
			// 极端小尺寸：至少给一块地板，避免空方案
			PartLib.fill(plan, 0, 0, 0, W - 1, 0, D - 1, pal.base);
			return new Result(plan, new com.mcai.common.PlanValidator.Report(), floors, lh);
		}

		PartLib.fill(plan, 0, 0, 0, W - 1, 0, D - 1, pal.base);
		// ---- 第一遍：先把所有楼层的"壳"（楼板/墙/门窗/门洞）盖完 ----
		// 这样第二遍装修时，天花板一定已经存在，吊灯才不会因为缺支撑被跳过。
		List<int[]> doorCells = new ArrayList<>();
		for (int k = 0; k < floors; k++) {
			buildShell(plan, model, spec, pal, k, lh, floors, W, D, doorCells);
		}
		if (model.stairs != null && floors > 1) {
			buildStairs(plan, model, lh, floors);
		}
		if (model.elevator != null && floors > 1) {
			// 先把井道各层楼板掏空（贯通），再让 PartLib 铺脚手架/轿厢/门槛 —— 顺序反了会把门槛删掉
			for (int k = 1; k < floors; k++) {
				for (int x = model.elevator.x0(); x <= model.elevator.x1(); x++) {
					for (int z = model.elevator.z0(); z <= model.elevator.z1(); z++) {
						plan.remove(x, k * lh, z);
					}
				}
			}
			PartLib.elevator(plan, model.elevator.x0(), model.elevator.z0(), 0, floors, lh,
					"iron_door", Math.max(1, floors - 3), floors * lh);
		}
		buildRoof(plan, spec, pal, W, D, floors, lh);
		buildFacade(plan, spec, pal, floors, lh, W, D);
		// ---- 第二遍：装修 + 照明 ----
		for (int k = 0; k < floors; k++) {
			furnishFloor(plan, model, pal, k, lh);
		}
		// ---- 第三遍：把门口前后一格清出来（家具不许堵门）----
		int cleared = clearDoorways(plan, doorCells);
		com.mcai.common.PlanValidator.Report report =
				com.mcai.common.PlanValidator.check(plan, lh, floors, model.seeds);
		report.doorsUnblocked += cleared;   // 生成期主动清障，计入统计但不当作"遗留问题"
		return new Result(plan, report, floors, lh);
	}

	// ==================== 布局模型 ====================

	private static final class LayoutModel {
		List<Layout.Room> rooms = new ArrayList<>();
		Layout.Rect stairs;
		Layout.Rect elevator;
		List<int[]> seeds = new ArrayList<>();
	}

	/** 一梯两户板楼：西户 / 核心筒 / 东户 */
	private static LayoutModel highriseModel(int W, int D) {
		int coreW = 7;
		int uD = D - 2;
		int uBase = (W - coreW - 4) / 2;
		if (uBase < 7) {
			return null;
		}
		int extra = W - (2 * uBase + coreW + 4);
		int uW1 = uBase + (extra > 0 ? 1 : 0);
		int uW2 = uBase + (extra > 1 ? 1 : 0);
		LayoutModel m = new LayoutModel();

		int ax0 = 1;
		int coreX0 = ax0 + uW1 + 1;
		int bx0 = coreX0 + coreW + 1;

		m.rooms.addAll(unitRooms(uW1, uD, ax0, 1, false));
		m.rooms.addAll(unitRooms(uW2, uD, bx0, 1, true));

		int cx = coreX0;
		int cz = 1;
		m.stairs = new Layout.Rect(cx, cz, cx + 2, cz + 7);
		m.rooms.add(new Layout.Room("stairs", m.stairs));
		m.elevator = new Layout.Rect(cx + 4, cz + 2, cx + 6, cz + 4);
		m.rooms.add(new Layout.Room("elevator", m.elevator));
		m.rooms.add(new Layout.Room("lobby", new Layout.Rect(cx + 4, cz + 6, cx + 6, cz + 8)));
		m.rooms.add(new Layout.Room("pipe", new Layout.Rect(cx + 4, cz, cx + 6, cz)));
		int hallTop = D - 2;
		if (hallTop >= cz + 10) {
			m.rooms.add(new Layout.Room("hall", new Layout.Rect(cx, cz + 10, cx + 6, hallTop)));
		}
		m.seeds.add(new int[] { cx + 5, cz + 7 });
		return m;
	}

	/** 一个单元的 9 个房间（11×15 模板等比缩放到 uW×uD） */
	private static List<Layout.Room> unitRooms(int uW, int uD, int ox, int oz, boolean mirror) {
		List<Layout.Room> out = new ArrayList<>();
		for (Object[] row : UNIT_11x15) {
			String type = (String) row[0];
			Layout.Rect src = new Layout.Rect((int) row[1], (int) row[2], (int) row[3], (int) row[4]);
			Layout.Rect sc = Layout.scale(src, 11, 15, uW, uD);
			int x0 = mirror ? (uW - 1 - sc.x1()) : sc.x0();
			int x1 = mirror ? (uW - 1 - sc.x0()) : sc.x1();
			out.add(new Layout.Room(type, new Layout.Rect(x0 + ox, sc.z0() + oz, x1 + ox, sc.z1() + oz)));
		}
		out = Layout.fix(out, ox + uW - 1, oz + uD - 1);
		return separate(out);
	}

	/** 通用布局：把内部切成 2×2 / 3×2 / 3×3 房间，西北角留作楼梯间 */
	private static LayoutModel genericModel(int W, int D, boolean needStairs) {
		LayoutModel m = new LayoutModel();
		int ix0 = 1;
		int iz0 = 1;
		int ix1 = W - 2;
		int iz1 = D - 2;
		int inW = ix1 - ix0 + 1;
		int inD = iz1 - iz0 + 1;
		if (inW < 4 || inD < 4) {
			return null;
		}
		int cols = inW >= 18 ? 3 : 2;
		int rows = inD >= 18 ? 3 : 2;
		int[] xs = split(ix0, ix1, cols);
		int[] zs = split(iz0, iz1, rows);
		List<Layout.Room> rooms = new ArrayList<>();
		for (int i = 0; i < rows; i++) {
			for (int j = 0; j < cols; j++) {
				int x0 = (j == 0) ? xs[0] : xs[j] + 1;
				int x1 = (j == cols - 1) ? xs[cols] : xs[j + 1] - 1;
				int z0 = (i == 0) ? zs[0] : zs[i] + 1;
				int z1 = (i == rows - 1) ? zs[rows] : zs[i + 1] - 1;
				if (x1 - x0 + 1 < 2 || z1 - z0 + 1 < 2) {
					continue;
				}
				String type = pickType(i, j, rows, cols, x0, x1, z0, z1, ix0, ix1, iz0, iz1);
				rooms.add(new Layout.Room(type, new Layout.Rect(x0, z0, x1, z1)));
			}
		}
		rooms = separate(Layout.fix(rooms, ix1, iz1));
		if (needStairs && !rooms.isEmpty()) {
			Layout.Room first = rooms.get(0);
			Layout.Rect r = first.r();
			if (r.w() >= 3 && r.d() >= 7) {
				rooms.set(0, new Layout.Room("stairs", r));
				m.stairs = r;
			} else {
				int sx = Math.max(ix0, Math.min(r.x0(), ix1 - 2));
				int sz = Math.max(iz0, Math.min(r.z0(), iz1 - 6));
				Layout.Rect sr = new Layout.Rect(sx, sz, sx + 2, sz + 6);
				rooms.removeIf(x -> x.r().overlaps(sr));
				rooms.add(0, new Layout.Room("stairs", sr));
				m.stairs = sr;
			}
			rooms = separate(rooms);
		}
		m.rooms = rooms;
		m.seeds.add(new int[] { ix0, iz0 });
		return m;
	}

	private static int[] split(int from, int to, int parts) {
		int[] cuts = new int[parts + 1];
		int len = to - from + 1;
		int per = Math.max(1, len / parts);
		cuts[0] = from;
		for (int i = 1; i < parts; i++) {
			cuts[i] = Math.min(to - 1, from + i * per);
		}
		cuts[parts] = to;
		return cuts;
	}

	private static String pickType(int row, int col, int rows, int cols, int x0, int x1, int z0, int z1,
			int ix0, int ix1, int iz0, int iz1) {
		boolean south = z0 == iz0;
		boolean north = z1 == iz1;
		if (south && (x1 - x0) >= 4) {
			return "living";
		}
		if (south) {
			return col == 0 ? "master" : "dining";
		}
		if (north && col == 0) {
			return "kitchen";
		}
		if (north && col == cols - 1) {
			return "entry";
		}
		if (row == rows - 1 && col == cols - 1) {
			return "bath";
		}
		return "bed2";
	}

	/** 保证房间之间至少留 1 格墙 */
	private static List<Layout.Room> separate(List<Layout.Room> rooms) {
		List<Layout.Room> out = new ArrayList<>(rooms);
		boolean changed = true;
		int guard = 0;
		while (changed && guard++ < 60) {
			changed = false;
			for (int i = 0; i < out.size() && !changed; i++) {
				for (int j = i + 1; j < out.size() && !changed; j++) {
					Layout.Room a = out.get(i);
					Layout.Room b = out.get(j);
					Layout.Rect ra = a.r();
					Layout.Rect rb = b.r();
					if (!ra.touching(rb)) {
						continue;
					}
					Layout.Room small = ra.area() <= rb.area() ? a : b;
					Layout.Rect rs = small.r();
					Layout.Rect rbig = (small == a ? b : a).r();
					int nx0 = rs.x0();
					int nx1 = rs.x1();
					int nz0 = rs.z0();
					int nz1 = rs.z1();
					if (rs.x1() + 1 == rbig.x0()) {
						nx1--;
					} else if (rbig.x1() + 1 == rs.x0()) {
						nx0++;
					} else if (rs.z1() + 1 == rbig.z0()) {
						nz1--;
					} else if (rbig.z1() + 1 == rs.z0()) {
						nz0++;
					}
					if (nx1 - nx0 + 1 < 2 || nz1 - nz0 + 1 < 2) {
						continue;
					}
					out.set(i == out.indexOf(small) ? i : out.indexOf(small),
							new Layout.Room(small.type(), new Layout.Rect(nx0, nz0, nx1, nz1)));
					changed = true;
				}
			}
		}
		return out;
	}

	// ==================== 单层施工 ====================

	/** 第一遍：楼板 + 墙体 + 门窗 + 门洞 */
	private static void buildShell(BuildingPlan plan, LayoutModel model, BuildingSpec spec,
			BuildingSpec.Pal pal, int k, int lh, int floors, int W, int D, List<int[]> doorCells) {
		int f = k * lh;
		boolean ground = k == 0;

		for (Layout.Room room : model.rooms) {
			Layout.Rect r = room.r();
			String type = room.type();
			String mat = isOpenAir(type) ? pal.base : floorMat(type, pal);
			if ("stairs".equals(type) || "elevator".equals(type) || "lobby".equals(type)
					|| "pipe".equals(type) || "hall".equals(type)) {
				mat = pal.floorPublic;
			}
			PartLib.fill(plan, r.x0(), f, r.z0(), r.x1(), f, r.z1(), mat);
		}

		for (int x = 0; x < W; x++) {
			for (int z = 0; z < D; z++) {
				if (inAnyRoom(model.rooms, x, z)) {
					continue;
				}
				boolean outer = x == 0 || z == 0 || x == W - 1 || z == D - 1;
				String mat = outer ? facadeMat(pal, ground, k, f) : pal.wall;
				PartLib.fill(plan, x, f, z, x, f + lh - 1, z, mat);
			}
		}

		for (Layout.Room room : model.rooms) {
			String type = room.type();
			if ("stairs".equals(type) || "elevator".equals(type) || "pipe".equals(type)) {
				continue;
			}
			List<int[]> ext = exteriorCells(room.r(), W, D);
			for (List<int[]> run : runs(ext, W, D)) {
				if (("balcony".equals(type) || "terrace".equals(type)) && f == 0 || ("balcony".equals(type))) {
					for (int[] c : run) {
						plan.set(c[0], f + 1, c[1], pal.wall);
						for (int y = f + 2; y <= f + lh - 1; y++) {
							plan.remove(c[0], y, c[1]);
						}
					}
					for (int[] c : new int[][] { run.get(0), run.get(run.size() - 1) }) {
						for (int y = f + 2; y <= f + lh - 1; y++) {
							plan.set(c[0], y, c[1], pal.frame);
						}
					}
				} else if (isSmallWindow(type)) {
					for (int i = 0; i < run.size(); i++) {
						int[] c = run.get(i);
						boolean keep = run.size() < 3 || i == run.size() / 2;
						plan.set(c[0], f + lh - 1, c[1], keep ? pal.glass : pal.wall);
					}
				} else {
					PartLib.windowRun(plan, run, f + 2, f + lh - 1, pal.glass, pal.frame);
				}
			}
		}

		carveDoors(plan, model.rooms, f, lh, ground, doorCells);
	}

	/** 第二遍：家具 + 灯光（此时所有天花板都已存在） */
	private static void furnishFloor(BuildingPlan plan, LayoutModel model, BuildingSpec.Pal pal, int k, int lh) {
		int f = k * lh;
		for (Layout.Room room : model.rooms) {
			String type = room.type();
			if ("stairs".equals(type) || "elevator".equals(type) || "pipe".equals(type)) {
				continue;
			}
			Interiors.furnish(plan, room, f, lh, pal, 0, 0);
		}
	}

	/** 门口前后一格（含上方一格）清成空气，保证人能进出 */
	private static int clearDoorways(BuildingPlan plan, List<int[]> doorCells) {
		int cleared = 0;
		for (int[] d : doorCells) {
			int x = d[0];
			int y = d[1];
			int z = d[2];
			String facing = facingOf(plan, x, y, z);
			int dx = 0;
			int dz = 0;
			if ("east".equals(facing)) {
				dx = 1;
			} else if ("west".equals(facing)) {
				dx = -1;
			} else if ("south".equals(facing)) {
				dz = 1;
			} else if ("north".equals(facing)) {
				dz = -1;
			}
			if (dx == 0 && dz == 0) {
				continue;
			}
			for (int s : new int[] { 1, -1 }) {
				int cx = x + dx * s;
				int cz = z + dz * s;
				for (int cy = y; cy <= y + 1; cy++) {
					BuildingPlan.Entry e = plan.get(cx, cy, cz);
					if (e == null) {
						continue;
					}
					String id = PlanValidator.baseId(e.blockId());
					boolean passable = id.equals("air") || id.equals("light") || id.endsWith("_carpet")
							|| id.endsWith("_door") || id.endsWith("_pressure_plate") || id.endsWith("_button");
					if (!passable) {
						plan.remove(cx, cy, cz);
						cleared++;
					}
				}
			}
		}
		return cleared;
	}

	private static String facingOf(BuildingPlan plan, int x, int y, int z) {
		BuildingPlan.Entry e = plan.get(x, y, z);
		if (e == null || e.props() == null) {
			return null;
		}
		return e.props().get("facing");
	}

	private static String floorMat(String type, BuildingSpec.Pal pal) {
		return switch (type) {
			case "kitchen", "bath", "bathroom", "toilet" -> pal.floorWet;
			case "living", "master", "bed2", "bed3", "bedroom", "dining", "study", "entry" -> pal.floorLiving;
			default -> pal.floorPublic;
		};
	}

	private static boolean isOpenAir(String type) {
		return "courtyard".equals(type) || "garden".equals(type) || "pool".equals(type);
	}

	private static boolean isSmallWindow(String type) {
		return switch (type) {
			case "kitchen", "bath", "bathroom", "toilet", "entry", "storage", "pipe" -> true;
			default -> false;
		};
	}

	private static String facadeMat(BuildingSpec.Pal pal, boolean ground, int k, int f) {
		if (ground) {
			return pal.base;
		}
		return (k % 2 == 1) ? pal.band : pal.wall;
	}

	private static boolean inAnyRoom(List<Layout.Room> rooms, int x, int z) {
		for (Layout.Room r : rooms) {
			if (r.r().contains(x, z)) {
				return true;
			}
		}
		return false;
	}

	private static List<int[]> exteriorCells(Layout.Rect r, int W, int D) {
		List<int[]> out = new ArrayList<>();
		if (r.x0() == 1) {
			for (int z = r.z0(); z <= r.z1(); z++) {
				out.add(new int[] { 0, z });
			}
		}
		if (r.x1() == W - 2) {
			for (int z = r.z0(); z <= r.z1(); z++) {
				out.add(new int[] { W - 1, z });
			}
		}
		if (r.z0() == 1) {
			for (int x = r.x0(); x <= r.x1(); x++) {
				out.add(new int[] { x, 0 });
			}
		}
		if (r.z1() == D - 2) {
			for (int x = r.x0(); x <= r.x1(); x++) {
				out.add(new int[] { x, D - 1 });
			}
		}
		return out;
	}

	private static List<List<int[]>> runs(List<int[]> cells, int W, int D) {
		List<List<int[]>> out = new ArrayList<>();
		Map<String, List<int[]>> bySide = new LinkedHashMap<>();
		for (int[] c : cells) {
			String side = (c[0] == 0) ? "W" : (c[0] == W - 1 ? "E" : (c[1] == 0 ? "S" : "N"));
			bySide.computeIfAbsent(side, k -> new ArrayList<>()).add(c);
		}
		for (List<int[]> list : bySide.values()) {
			list.sort((a, b) -> (a[0] != b[0]) ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
			List<int[]> cur = new ArrayList<>();
			cur.add(list.get(0));
			for (int i = 1; i < list.size(); i++) {
				int[] prev = cur.get(cur.size() - 1);
				int[] c = list.get(i);
				if (Math.abs(c[0] - prev[0]) + Math.abs(c[1] - prev[1]) == 1) {
					cur.add(c);
				} else {
					out.add(cur);
					cur = new ArrayList<>();
					cur.add(c);
				}
			}
			out.add(cur);
		}
		return out;
	}

	/** 两间房间之间可开门的墙格：[x, z, facing]，不相邻返回 null */
	private static Object[] doorwayBetween(Layout.Rect a, Layout.Rect b) {
		if (a.x1() + 2 == b.x0()) {
			int z = Math.max(a.z0(), Math.max(b.z0(), Math.min((Math.max(a.z0(), b.z0())
					+ Math.min(a.z1(), b.z1())) / 2, Math.min(a.z1(), b.z1()))));
			return new Object[] { a.x1() + 1, z, "east" };
		}
		if (b.x1() + 2 == a.x0()) {
			int z = Math.max(a.z0(), Math.max(b.z0(), Math.min((Math.max(a.z0(), b.z0())
					+ Math.min(a.z1(), b.z1())) / 2, Math.min(a.z1(), b.z1()))));
			return new Object[] { a.x0() - 1, z, "west" };
		}
		if (a.z1() + 2 == b.z0()) {
			int x = Math.max(a.x0(), Math.max(b.x0(), Math.min((Math.max(a.x0(), b.x0())
					+ Math.min(a.x1(), b.x1())) / 2, Math.min(a.x1(), b.x1()))));
			return new Object[] { x, a.z1() + 1, "south" };
		}
		if (b.z1() + 2 == a.z0()) {
			int x = Math.max(a.x0(), Math.max(b.x0(), Math.min((Math.max(a.x0(), b.x0())
					+ Math.min(a.x1(), b.x1())) / 2, Math.min(a.x1(), b.x1()))));
			return new Object[] { x, a.z0() - 1, "north" };
		}
		return null;
	}

	/**
	 * 开门：对"房间相邻图"求一棵生成树，只在这些边上开门 —— 这样保证每个房间都连通，
	 * 又不会每面墙都开洞。门口位置记录到 doorCells，供后面清理家具堵门。
	 */
	private static void carveDoors(BuildingPlan plan, List<Layout.Room> rooms, int f, int lh, boolean ground,
			List<int[]> doorCells) {
		int n = rooms.size();
		List<List<Integer>> adj = new ArrayList<>();
		Map<String, Object[]> cand = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			adj.add(new ArrayList<>());
		}
		for (int i = 0; i < n; i++) {
			if ("elevator".equals(rooms.get(i).type())) {
				continue;
			}
			for (int j = i + 1; j < n; j++) {
				if ("elevator".equals(rooms.get(j).type()) || "pipe".equals(rooms.get(j).type())) {
					continue;
				}
				Object[] d = doorwayBetween(rooms.get(i).r(), rooms.get(j).r());
				if (d == null) {
					continue;
				}
				cand.put(i + "|" + j, d);
				adj.get(i).add(j);
				adj.get(j).add(i);
			}
		}
		// 根节点：优先门厅/候梯厅/玄关，保证公共区是连通核心
		int root = 0;
		for (int i = 0; i < n; i++) {
			String t = rooms.get(i).type();
			if ("lobby".equals(t) || "hall".equals(t) || "entry".equals(t) || "living".equals(t)) {
				root = i;
				break;
			}
		}
		boolean[] vis = new boolean[n];
		java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
		vis[root] = true;
		q.add(root);
		while (!q.isEmpty()) {
			int a = q.poll();
			for (int b : adj.get(a)) {
				if (vis[b]) {
					continue;
				}
				vis[b] = true;
				q.add(b);
				Object[] d = cand.get(Math.min(a, b) + "|" + Math.max(a, b));
				int dx = (int) d[0];
				int dz = (int) d[1];
				String facing = (String) d[2];
				String ta = rooms.get(a).type();
				String tb = rooms.get(b).type();
				boolean iron = "stairs".equals(ta) || "stairs".equals(tb)
						|| "lobby".equals(ta) || "lobby".equals(tb);
				PartLib.clear(plan, dx, f + 1, dz, dx, f + lh - 1, dz);
				PartLib.door(plan, dx, f + 1, dz, iron ? "iron_door" : "oak_door", facing, "left");
				doorCells.add(new int[] { dx, f + 1, dz });
			}
		}
		// 一层：给玄关/客厅开一个对外的单元门
		if (ground) {
			for (Layout.Room room : rooms) {
				String type = room.type();
				if (!"entry".equals(type) && !"hall".equals(type) && !"living".equals(type)) {
					continue;
				}
				Layout.Rect r = room.r();
				if (r.z0() == 1) {
					int dx = Math.max(r.x0(), Math.min((r.x0() + r.x1()) / 2, r.x1()));
					PartLib.clear(plan, dx, f + 1, 0, dx, f + 2, 0);
					PartLib.door(plan, dx, f + 1, 0, "dark_oak_door", "south", "left");
					doorCells.add(new int[] { dx, f + 1, 0 });
					break;
				}
			}
		}
	}

	private static void buildStairs(BuildingPlan plan, LayoutModel model, int lh, int floors) {
		Layout.Rect s = model.stairs;
		// 先掏空各层楼板（梯段区贯通），再逐层放踏步 —— 否则会把上一层的踏步删掉
		for (int k = 1; k < floors; k++) {
			for (int x = s.x0(); x <= s.x1(); x++) {
				for (int z = s.z0(); z <= s.z0() + 6; z++) {
					// 门下面的楼板不能掏（否则门会掉）——门通常在梯段区外，这里兜底保护
					BuildingPlan.Entry above = plan.get(x, k * lh + 1, z);
					if (above != null && PlanValidator.baseId(above.blockId()).endsWith("_door")) {
						continue;
					}
					plan.remove(x, k * lh, z);
				}
			}
		}
		for (int k = 0; k < floors - 1; k++) {
			int f = k * lh;
			PartLib.switchbackStair(plan, s.x0(), s.z0(), f, lh, "polished_andesite");
			PartLib.hangLantern(plan, s.x0() + 1, f + lh, s.z1());
		}
	}

	private static void buildRoof(BuildingPlan plan, BuildingSpec spec, BuildingSpec.Pal pal,
			int W, int D, int floors, int lh) {
		int deck = floors * lh;
		PartLib.fill(plan, 0, deck, 0, W - 1, deck, D - 1, pal.roofDeck);
		for (int x = 0; x < W; x++) {
			plan.set(x, deck, 0, pal.band);
			plan.set(x, deck, D - 1, pal.band);
		}
		for (int z = 0; z < D; z++) {
			plan.set(0, deck, z, pal.band);
			plan.set(W - 1, deck, z, pal.band);
		}
		String style = spec.roof == null ? "auto" : spec.roof.style;
		boolean flat = style == null || style.isBlank() || "auto".equals(style) || "flat".equals(style);
		if (flat) {
			PartLib.parapet(plan, W, D, deck + 1, pal.wall, pal.band);
			PartLib.roofKit(plan, W, D, deck, spec.roof, pal.frame, pal.stone);
			if (spec.roof != null && Boolean.TRUE.equals(spec.roof.garden)) {
				PartLib.roofGarden(plan, W, D, deck);
			}
		} else {
			int layers = Math.min(4, Math.max(2, Math.min(W, D) / 6));
			for (int i = 0; i < layers; i++) {
				int x0 = i;
				int z0 = i;
				int x1 = W - 1 - i;
				int z1 = D - 1 - i;
				if (x1 - x0 < 1 || z1 - z0 < 1) {
					break;
				}
				PartLib.fill(plan, x0, deck + 1 + i, z0, x1, deck + 1 + i, z1, i % 2 == 0 ? pal.roofDeck : pal.band);
			}
			plan.set(W / 2, deck + 1 + Math.max(0, layers - 1) + 1, D / 2, "lightning_rod", Map.of("facing", "up"));
		}
	}

	private static void buildFacade(BuildingPlan plan, BuildingSpec spec, BuildingSpec.Pal pal,
			int floors, int lh, int W, int D) {
		if (spec != null && spec.has("no_ac")) {
			return;
		}
		for (int k = 0; k < floors; k++) {
			int f = k * lh;
			for (int z : new int[] { 0, D - 1 }) {
				for (int x = 3; x < W - 3; x += 6) {
					BuildingPlan.Entry e1 = plan.get(x, f + 2, z);
					if (e1 == null) {
						continue;
					}
					String id = PlanValidator.baseId(e1.blockId());
					if (id.equals(pal.wall) || id.equals(pal.band) || id.equals(pal.base)) {
						plan.set(x, f + 2, z, "gray_concrete");
						plan.set(x, f + 3, z, "iron_bars");
					}
				}
			}
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static int defaultLayer(String arch) {
		return "castle".equals(arch) ? 5 : 4;
	}

	private static int defaultFloors(String arch) {
		return switch (arch) {
			case "chinese_highrise" -> 8;
			case "chinese_courtyard" -> 1;
			case "modern_villa" -> 2;
			case "castle" -> 3;
			default -> 2;
		};
	}
}
