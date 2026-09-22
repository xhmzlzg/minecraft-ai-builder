package com.mcai.common.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mcai.common.BuildingPlan;

/**
 * 自由形体展开器：把 {@link FreeformSpec}（AI 描述的外形）逐格盖成 {@link BuildingPlan}。
 *
 * 与 SpecBuilder（建筑）的本质区别：这里**不做任何建筑逻辑** ——
 * 不铺楼板、不开窗、不摆家具、不加屋顶、不体检。
 * 因为自由形体的"悬空"是常态（船帆、桅杆、缆绳、机翼、雕像手臂），
 * 体检的"悬空方块 / 楼板空洞 / 堵门 / 房间可达"会直接把作品删掉。
 *
 * 安全阀（模型可能乱写，必须挡住，否则游戏会崩/卡死）：
 *   - 坐标必须落在 [0, 尺寸) 内：越界丢弃并记警告（负坐标还会破坏方案内部索引）；
 *   - 方块总数上限 {@link #MAX_BLOCKS}，超了直接报错而不是把游戏卡死；
 *   - 方块 id 只接受 [a-z0-9_]+ 形式，非法 id 丢弃；
 *   - 单边尺寸上限 {@link #MAX_SIDE}。
 *
 * 纯 Java（不认识 Minecraft 类），便于离线自检。
 */
public final class FreeformBuilder {

	/** 方块数上限：防止模型写出巨物把游戏卡死（正常载具/雕像在几千块量级） */
	public static final int MAX_BLOCKS = 100_000;
	/** 单边尺寸上限 */
	public static final int MAX_SIDE = 128;

	private FreeformBuilder() {
	}

	public static final class Result {
		public final BuildingPlan plan;
		public final List<String> warnings;
		/** 总方块数 */
		public final int blocks;
		/** 六面都没有邻居的孤立方块数（只报告，绝不修补） */
		public final int isolated;

		Result(BuildingPlan plan, List<String> warnings, int blocks, int isolated) {
			this.plan = plan;
			this.warnings = warnings;
			this.blocks = blocks;
			this.isolated = isolated;
		}
	}

	/**
	 * 一次"取方块"的结果：方块 id + 它自带的 blockstate。
	 *
	 * 为什么需要：模型常把状态嵌在方块名里（palette 写 {@code {"S":"oak_stairs[facing=east]"}}），
	 * 解析阶段会把中括号拆开存进 {@link FreeformSpec#paletteProps}，展开时要再合回去。
	 */
	private record BlockRef(String id, Map<String, String> props) {
	}

	public static Result build(FreeformSpec spec, String name, int availW, int availD, int availH) {
		List<String> warn = new ArrayList<>();
		Map<String, String> pal = new LinkedHashMap<>(FreeformSpec.DEFAULT_PALETTE);
		pal.putAll(spec.palette);
		// 字符自带的 blockstate（palette 里写成 "oak_stairs[facing=east]" 的那些），
		// 解析阶段已拆出来存在这里，展开 layer 时再合回去。
		Map<String, Map<String, String>> palProps =
				spec.paletteProps == null ? Map.of() : spec.paletteProps;

		int[] need = bounds(spec);
		int w = need[0];
		int h = need[1];
		int d = need[2];
		if (spec.size != null && spec.size.size() >= 3) {
			w = spec.size.get(0);
			h = spec.size.get(1);
			d = spec.size.get(2);
		} else if (spec.size != null && spec.size.size() == 2) {
			w = spec.size.get(0);
			d = spec.size.get(1);
			h = need[1];
		} else {
			// 没给 size：按 ops 范围自动算。注意 bounds() 已经把镜像那一半算进去了，
			// 否则"只写了左半边"的形状会被镜像折回自身，船就只剩一半宽。
			warn.add("没有给 size，已按 ops 的实际范围推断为 " + w + "x" + h + "x" + d
					+ "；建议显式写 size（用 mirror 时：宽 = 每行字符数 × 2 - 1）");
		}
		w = clamp(w, 1, MAX_SIDE);
		h = clamp(h, 1, MAX_SIDE);
		d = clamp(d, 1, MAX_SIDE);
		if (availW > 0 && availW < Integer.MAX_VALUE && availW < w) {
			warn.add("宽度 " + w + " 超过框选范围 " + availW + "（按完整方案放置，不裁剪）");
		}
		if (availD > 0 && availD < Integer.MAX_VALUE && availD < d) {
			warn.add("进深 " + d + " 超过框选范围 " + availD + "（按完整方案放置，不裁剪）");
		}
		// 高度也要提示：自由形体是"按完整方案放置"（建造时 bound 传的是 null，不做裁剪），
		// 比框选高就会往上长到地形里 —— 玩家需要知道"是我框小了，不是 AI 画错了"。
		if (availH > 0 && availH < Integer.MAX_VALUE && availH < h) {
			warn.add("高度 " + h + " 超过框选范围 " + availH + "（按完整方案放置，不裁剪；"
					+ "若不想长这么高，请在描述里要求做矮一点）");
		}

		BuildingPlan plan = new BuildingPlan(name, w, h, d);
		for (FreeformSpec.Op op : spec.ops) {
			apply(plan, op, pal, palProps, warn);
			if (plan.size() > MAX_BLOCKS) {
				throw new IllegalStateException("自由形体方块数超过上限 " + MAX_BLOCKS
						+ "（模型给的外形太大），请把尺寸改小或减少 ops");
			}
		}
		mirror(plan, spec.mirror);
		autoHanging(plan);
		connectPass(plan);
		if (plan.size() == 0) {
			warn.add("ops 没有生成任何方块（请检查 palette 字符与坐标是否在 size 范围内）");
		}
		int isolated = audit(plan, warn);
		return new Result(plan, warn, plan.size(), isolated);
	}

	// ==================== ops 执行 ====================

	private static void apply(BuildingPlan p, FreeformSpec.Op op, Map<String, String> pal,
			Map<String, Map<String, String>> palProps, List<String> warn) {
		String kind = op.op == null ? "" : op.op.toLowerCase().trim();
		switch (kind) {
			case "layer", "layers", "plane", "row" -> layer(p, op, pal, palProps, warn);
			case "box", "cube", "fill", "rect" -> box(p, op, pal, palProps, false, warn);
			case "clear", "carve", "hole", "air" -> box(p, op, pal, palProps, true, warn);
			case "cyl", "cylinder", "column", "pillar", "tube" -> cyl(p, op, pal, palProps, warn);
			case "sphere", "ball", "ellipsoid", "dome" -> sphere(p, op, pal, palProps, warn);
			default -> warn.add("未知 op 类型「" + op.op + "」（已跳过）");
		}
	}

	private static void layer(BuildingPlan p, FreeformSpec.Op op, Map<String, String> pal,
			Map<String, Map<String, String>> palProps, List<String> warn) {
		if (op.rows == null || op.rows.isEmpty()) {
			warn.add("layer 缺少 rows（已跳过）");
			return;
		}
		int y = op.y == null ? 0 : op.y;
		if (y < 0 || y >= p.height) {
			warn.add("layer 的 y=" + y + " 超出高度范围 0~" + (p.height - 1) + "（已跳过）");
			return;
		}
		boolean usedFallback = false;
		for (int z = 0; z < op.rows.size(); z++) {
			String row = op.rows.get(z);
			if (row == null) {
				continue;
			}
			for (int x = 0; x < row.length(); x++) {
				char ch = row.charAt(x);
				if (isBlankChar(ch)) {
					continue;   // '.' / 空格 / '_' = 这一格不放
				}
				String id;
				Map<String, String> base;
				if (inPalette(pal, ch)) {
					id = charBlock(pal, ch, warn);
					base = charProps(palProps, ch);
				} else if (op.block != null && !op.block.isBlank()) {
					// 兜底：模型有时写 {"op":"layer","rows":["#####"],"block":"stone"}，
					// 把 rows 当成"哪里放、哪里空"，字符本身并不在 palette 里。
					// 用 op.block 兜底远好过整层消失（注意留空字符已在上面跳过）。
					BlockRef fb = blockOf(op.block, pal, palProps, warn);
					if (fb == null) {
						continue;
					}
					id = fb.id();
					base = fb.props();
					usedFallback = true;
				} else {
					charBlock(pal, ch, warn);   // 只为产出"palette 里没有字符 X"的警告
					continue;
				}
				if (id == null) {
					continue;
				}
				put(p, x, y, z, id, merge(base, op.props), warn);
			}
		}
		if (usedFallback) {
			warn.add("layer 用到了 palette 里没有的字符，已用 op 的 block 兜底（建议把这些字符补进 palette）");
		}
	}

	private static void box(BuildingPlan p, FreeformSpec.Op op, Map<String, String> pal,
			Map<String, Map<String, String>> palProps, boolean clear, List<String> warn) {
		if (op.from == null || op.to == null) {
			warn.add((clear ? "clear" : "box") + " 缺少 from/to（已跳过）");
			return;
		}
		BlockRef ref = null;
		if (!clear) {
			ref = blockOf(op.block, pal, palProps, warn);
			if (ref == null) {
				return;
			}
		}
		int x0 = Math.min(op.from[0], op.to[0]);
		int x1 = Math.max(op.from[0], op.to[0]);
		int y0 = Math.min(op.from[1], op.to[1]);
		int y1 = Math.max(op.from[1], op.to[1]);
		int z0 = Math.min(op.from[2], op.to[2]);
		int z1 = Math.max(op.from[2], op.to[2]);
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					if (op.hollow && x != x0 && x != x1 && y != y0 && y != y1 && z != z0 && z != z1) {
						continue;   // 只要外壳
					}
					if (clear) {
						p.remove(x, y, z);
					} else {
						put(p, x, y, z, ref.id(), merge(ref.props(), op.props), warn);
					}
				}
			}
		}
	}

	private static void cyl(BuildingPlan p, FreeformSpec.Op op, Map<String, String> pal,
			Map<String, Map<String, String>> palProps, List<String> warn) {
		BlockRef ref = blockOf(op.block, pal, palProps, warn);
		if (ref == null) {
			return;
		}
		int cx = nz(op.x, 0);
		int cz = nz(op.z, 0);
		int rx = Math.abs(nz(op.rx, nz(op.r, 3)));
		int rz = Math.abs(nz(op.rz, nz(op.r, 3)));
		int y0 = nz(op.y0, 0);
		int y1 = nz(op.y1, y0);
		if (y1 < y0) {
			int t = y0;
			y0 = y1;
			y1 = t;
		}
		double inner = shellInner(Math.min(rx, rz));
		for (int y = y0; y <= y1; y++) {
			for (int x = cx - rx; x <= cx + rx; x++) {
				for (int z = cz - rz; z <= cz + rz; z++) {
					double dx = (x - cx) / (double) Math.max(1, rx);
					double dz = (z - cz) / (double) Math.max(1, rz);
					double dd = dx * dx + dz * dz;
					if (dd > 1.0) {
						continue;
					}
					if (op.hollow && dd < inner) {
						continue;
					}
					put(p, x, y, z, ref.id(), merge(ref.props(), op.props), warn);
				}
			}
		}
	}

	private static void sphere(BuildingPlan p, FreeformSpec.Op op, Map<String, String> pal,
			Map<String, Map<String, String>> palProps, List<String> warn) {
		BlockRef ref = blockOf(op.block, pal, palProps, warn);
		if (ref == null) {
			return;
		}
		int cx = nz(op.x, 0);
		int cz = nz(op.z, 0);
		int cy = nz(op.y, nz(op.y0, 0));
		int r = nz(op.r, 3);
		int rx = Math.abs(nz(op.rx, r));
		int ry = Math.abs(nz(op.ry, r));
		int rz = Math.abs(nz(op.rz, r));
		double inner = shellInner(Math.min(rx, Math.min(ry, rz)));
		for (int y = cy - ry; y <= cy + ry; y++) {
			for (int x = cx - rx; x <= cx + rx; x++) {
				for (int z = cz - rz; z <= cz + rz; z++) {
					double dx = (x - cx) / (double) Math.max(1, rx);
					double dy = (y - cy) / (double) Math.max(1, ry);
					double dz = (z - cz) / (double) Math.max(1, rz);
					double dd = dx * dx + dy * dy + dz * dz;
					if (dd > 1.0) {
						continue;
					}
					if (op.hollow && dd < inner) {
						continue;
					}
					put(p, x, y, z, ref.id(), merge(ref.props(), op.props), warn);
				}
			}
		}
	}

	/** 空心壳体：半径 r 的球/柱，去掉半径 r-1 以内的内芯 */
	private static double shellInner(int r) {
		if (r <= 1) {
			return -1;
		}
		double f = (r - 1) / (double) r;
		return f * f;
	}

	// ==================== 镜像 ====================

	/**
	 * 解析 mirror 字段 → {x 轴镜像?, z 轴镜像?}。
	 *
	 * 契约写的是 none / x / z / xz，但模型常写 "both" / "all" / "双向" / "左右" ——
	 * 这些词里一个 x / z 都没有，按"找字符 x/z"的老写法会被当成**不镜像**，
	 * 于是"只写了左半边"的形状就真的只剩左半边（船变成半条船）。
	 */
	private static boolean[] mirrorAxes(String m) {
		if (m == null) {
			return new boolean[] { false, false };
		}
		String s = m.toLowerCase().trim();
		if (s.isEmpty() || s.equals("none") || s.equals("no") || s.equals("off") || s.equals("false")
				|| s.equals("null") || s.equals("无") || s.contains("不镜像")) {
			return new boolean[] { false, false };
		}
		if (s.contains("both") || s.contains("all") || s.contains("双向") || s.contains("双轴")
				|| s.contains("左右前后")) {
			return new boolean[] { true, true };
		}
		boolean mx = s.indexOf('x') >= 0 || s.contains("左右") || s.contains("横");
		boolean mz = s.indexOf('z') >= 0 || s.contains("前后") || s.contains("纵");
		return new boolean[] { mx, mz };
	}

	/** 镜像复制：x → 宽-1-x，z → 进深-1-z。让模型只写一半，输出量减半。 */
	private static void mirror(BuildingPlan p, String m) {
		boolean[] ax = mirrorAxes(m);
		boolean mx = ax[0];
		boolean mz = ax[1];
		if (!mx && !mz) {
			return;
		}
		List<BuildingPlan.Entry> snap = new ArrayList<>(p.entries);
		int w = p.width;
		int d = p.depth;
		for (BuildingPlan.Entry e : snap) {
			Map<String, String> pr = flipProps(e.props(), mx, mz);
			if (mx) {
				p.set(w - 1 - e.x(), e.y(), e.z(), e.blockId(), pr);
			}
			if (mz) {
				p.set(e.x(), e.y(), d - 1 - e.z(), e.blockId(), pr);
			}
			if (mx && mz) {
				p.set(w - 1 - e.x(), e.y(), d - 1 - e.z(), e.blockId(), pr);
			}
		}
	}

	/**
	 * 镜像后修正 blockstate 里的"方向性"属性 —— 否则镜像出来的楼梯会朝错方向、门会反向开。
	 *
	 * 规则来自镜像的几何性质：
	 *   - facing：x 镜像翻转 东↔西，z 镜像翻转 南↔北；
	 *   - hinge / shape：**单轴**镜像会翻转左右手性（left↔right、inner_left↔inner_right…），
	 *     双轴镜像（xz）等于旋转 180°，手性不变；
	 *   - rotation（告示牌）：0=南 4=西 8=北 12=东，x 镜像 r→(16-r)%16，z 镜像 r→(8-r+16)%16。
	 */
	private static Map<String, String> flipProps(Map<String, String> props, boolean mx, boolean mz) {
		if (props == null || props.isEmpty() || (!mx && !mz)) {
			return props == null ? Map.of() : props;
		}
		Map<String, String> out = new LinkedHashMap<>(props);
		String facing = out.get("facing");
		if (facing != null) {
			String f = facing.toLowerCase();
			if (mx && "east".equals(f)) {
				out.put("facing", "west");
			} else if (mx && "west".equals(f)) {
				out.put("facing", "east");
			}
			if (mz && "north".equals(f)) {
				out.put("facing", "south");
			} else if (mz && "south".equals(f)) {
				out.put("facing", "north");
			}
		}
		if (mx != mz) {   // 单轴镜像 = 翻转手性
			String hinge = out.get("hinge");
			if (hinge != null) {
				out.put("hinge", "left".equalsIgnoreCase(hinge) ? "right" : "left");
			}
			String shape = out.get("shape");
			if (shape != null) {
				String s = shape.toLowerCase();
				if (s.startsWith("inner_") || s.startsWith("outer_")) {
					String head = s.substring(0, s.indexOf('_') + 1);
					String tail = s.endsWith("left") ? "right" : (s.endsWith("right") ? "left" : null);
					if (tail != null) {
						out.put("shape", head + tail);
					}
				}
			}
		}
		// 栏杆/玻璃板/矮墙的"连接方向"也是布尔属性，镜像后要跟着换边
		if (mx) {
			swapBool(out, "east", "west");
		}
		if (mz) {
			swapBool(out, "north", "south");
		}
		String rot = out.get("rotation");
		if (rot != null) {
			try {
				int r = Integer.parseInt(rot.trim());
				if (mx) {
					r = (16 - r) % 16;
				}
				if (mz) {
					r = ((8 - r) % 16 + 16) % 16;
				}
				out.put("rotation", String.valueOf(r));
			} catch (NumberFormatException ignored) {
				// 非数字就原样留着，交给建造器判非法后跳过
			}
		}
		return out;
	}

	/** 交换两个"连接方向"键（值必须是 true/false 才动，避免误伤同名非布尔属性） */
	private static void swapBool(Map<String, String> m, String a, String b) {
		String va = m.get(a);
		String vb = m.get(b);
		if (va == null && vb == null) {
			return;
		}
		if ((va != null && !isBool(va)) || (vb != null && !isBool(vb))) {
			return;
		}
		m.remove(a);
		m.remove(b);
		if (vb != null) {
			m.put(a, vb);
		}
		if (va != null) {
			m.put(b, va);
		}
	}

	private static boolean isBool(String s) {
		return "true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s);
	}

	// ==================== 后处理（只做"让成品更像样"的事，绝不删方块） ====================

	/** 灯笼：上方是实心块就自动改成吊灯（hanging=true），否则保持落地灯笼 */
	private static void autoHanging(BuildingPlan p) {
		for (BuildingPlan.Entry e : new ArrayList<>(p.entries)) {
			String id = e.blockId();
			if (!id.equals("lantern") && !id.equals("soul_lantern")) {
				continue;
			}
			if (e.props() != null && e.props().containsKey("hanging")) {
				continue;   // 模型自己指定了，尊重它
			}
			if (p.isSupporting(e.x(), e.y() + 1, e.z())) {
				p.set(e.x(), e.y(), e.z(), id, Map.of("hanging", "true"));
			}
		}
	}

	/**
	 * 栏杆/玻璃板/铁栏杆/矮墙：按实际邻居补连接属性。
	 *
	 * 不补的话它们会渲染成一根根孤立柱子（原建筑管线里的 PartLib.railRun 就是为此而写的，
	 * 但它已随全部建筑模板一起删除）。自由形体的形体是任意的，没法沿一条"栏杆线"推，
	 * 只能按格子逐面判断。
	 */
	private static void connectPass(BuildingPlan p) {
		for (BuildingPlan.Entry e : new ArrayList<>(p.entries)) {
			String id = e.blockId();
			boolean bar = id.endsWith("_fence") || id.endsWith("_pane") || id.equals("iron_bars");
			boolean wall = id.endsWith("_wall");
			if (!bar && !wall) {
				continue;
			}
			Map<String, String> pr = new LinkedHashMap<>(e.props());
			for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
				String side = d[0] == 1 ? "east" : (d[0] == -1 ? "west" : (d[1] == 1 ? "south" : "north"));
				if (pr.containsKey(side)) {
					continue;   // 模型显式写了（哪怕是 false），不覆盖
				}
				if (solidAt(p, e.x() + d[0], e.y(), e.z() + d[1])) {
					pr.put(side, wall ? "low" : "true");
				}
			}
			if (!pr.equals(e.props())) {
				p.set(e.x(), e.y(), e.z(), id, pr);
			}
		}
	}

	/**
	 * 轻量体检：**只报告，不修补**。
	 *
	 * 为什么不复用 PlanValidator：自由形体的"悬空"是常态（船帆、桅杆、机翼、雕像手臂），
	 * PlanValidator 的悬空修补会把这些部件直接删掉。这里只统计真正的异常 ——
	 * 六面都没有邻居的孤立方块（模型漏画连接件 / 坐标算错），以及整体规模。
	 */
	private static int audit(BuildingPlan p, List<String> warn) {
		int isolated = 0;
		for (BuildingPlan.Entry e : p.entries) {
			if (!hasNeighbor(p, e)) {
				isolated++;
			}
		}
		if (isolated > 0) {
			warn.add("有 " + isolated + " 个孤立方块（六个方向都没有邻居），可能是模型漏画的连接件或坐标算错");
		}
		if (p.size() > 20_000) {
			warn.add("方块数偏多（" + p.size() + "），建造时会明显卡顿，可考虑把尺寸改小或减少 ops");
		}
		return isolated;
	}

	private static final int[][] DIRS6 = {
			{ 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };

	private static boolean hasNeighbor(BuildingPlan p, BuildingPlan.Entry e) {
		for (int[] d : DIRS6) {
			if (solidAt(p, e.x() + d[0], e.y() + d[1], e.z() + d[2])) {
				return true;
			}
		}
		return false;
	}

	/** 越界安全的"这一格有方块吗"：BuildingPlan.key() 用位掩码打包坐标，越界会串格，必须先夹住 */
	private static boolean solidAt(BuildingPlan p, int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= p.width || y >= p.height || z >= p.depth) {
			return false;
		}
		return p.has(x, y, z);
	}

	// ==================== 工具 ====================

	/** 只写入范围内的格子：越界（尤其是负坐标）会破坏方案内部索引，必须丢弃 */
	private static void put(BuildingPlan p, int x, int y, int z, String id, Map<String, String> props,
			List<String> warn) {
		if (p.size() > MAX_BLOCKS) {
			// 必须在写入过程中就拦住：模型写一个 120³ 的实心 box 会直接吃光内存
			throw new IllegalStateException("自由形体方块数超过上限 " + MAX_BLOCKS
					+ "（模型给的外形太大），请把尺寸改小或减少 ops");
		}
		if (x < 0 || y < 0 || z < 0 || x >= p.width || y >= p.height || z >= p.depth) {
			if (warn.size() < 12) {
				warn.add("(" + x + "," + y + "," + z + ") 超出 " + p.width + "x" + p.height + "x" + p.depth
						+ " 范围（已丢弃）");
			}
			return;
		}
		if (isAir(id)) {
			p.remove(x, y, z);   // 模型用 air 掏空间 ⇒ 当成删除，别塞进方案
			return;
		}
		p.set(x, y, z, id, props == null || props.isEmpty() ? Map.of() : new LinkedHashMap<>(props));
	}

	/**
	 * 是不是"空气"类方块。模型很爱用 {"op":"box","block":"air"} 来掏空间 ——
	 * 那必须当成"删除"，不能真往方案里塞一个 air 条目：
	 * 它会让方块数虚高（甚至误触上限）、日志里的方块数也对不上实际建造数。
	 */
	private static boolean isAir(String id) {
		return "air".equals(id) || "cave_air".equals(id) || "void_air".equals(id);
	}

	/** 这一格是不是"留空"（不放置）：'.' / 空格 / '_' / NUL */
	private static boolean isBlankChar(char c) {
		return c == '.' || c == ' ' || c == '_' || c == '\0';
	}

	/** 字符在字符表里有没有定义（大小写都认） */
	private static boolean inPalette(Map<String, String> pal, char c) {
		return pal.containsKey(String.valueOf(c)) || pal.containsKey(String.valueOf(c).toLowerCase());
	}

	/** 字符在字符表里自带的 blockstate（palette 写成 "oak_stairs[facing=east]" 时解析出来的那些） */
	private static Map<String, String> charProps(Map<String, Map<String, String>> palProps, char c) {
		if (palProps == null || palProps.isEmpty()) {
			return Map.of();
		}
		Map<String, String> ps = palProps.get(String.valueOf(c));
		if (ps == null) {
			ps = palProps.get(String.valueOf(c).toLowerCase());
		}
		return ps == null ? Map.of() : ps;
	}

	/** 合并两组属性：later 覆盖 base（显式写在 op.props 里的比 palette 自带的更具体） */
	private static Map<String, String> merge(Map<String, String> base, Map<String, String> later) {
		boolean b = base == null || base.isEmpty();
		boolean l = later == null || later.isEmpty();
		if (b && l) {
			return Map.of();
		}
		Map<String, String> out = new LinkedHashMap<>();
		if (!b) {
			out.putAll(base);
		}
		if (!l) {
			out.putAll(later);
		}
		return out;
	}

	/** 字符 → 方块 id：留空字符返回 null；palette 里没有该字符也返回 null（并记警告） */
	private static String charBlock(Map<String, String> pal, char c, List<String> warn) {
		if (isBlankChar(c)) {
			return null;
		}
		String v = pal.get(String.valueOf(c));
		if (v == null) {
			v = pal.get(String.valueOf(c).toLowerCase());
		}
		if (v == null) {
			String w = "palette 里没有字符「" + c + "」（该格跳过）";
			if (warn != null && !warn.contains(w)) {
				warn.add(w);
			}
			return null;
		}
		return sanitize(v, warn);
	}

	/** op 的 block 字段 → 方块（单字符先查 palette，否则按方块 id 处理） */
	private static BlockRef blockOf(String s, Map<String, String> pal,
			Map<String, Map<String, String>> palProps, List<String> warn) {
		if (s == null || s.isBlank()) {
			warn.add("op 缺少 block（已跳过）");
			return null;
		}
		String v = s.trim();
		if (v.length() == 1) {
			String key = v;
			String p = pal.get(key);
			if (p == null) {
				key = v.toLowerCase();
				p = pal.get(key);
			}
			if (p != null) {
				String id = sanitize(p, warn);
				return id == null ? null : new BlockRef(id, charProps(palProps, key.charAt(0)));
			}
		}
		String id = sanitize(v, warn);
		return id == null ? null : new BlockRef(id, Map.of());
	}

	/** 方块 id 白名单：只接受 [a-z0-9_]+（可带 minecraft: 前缀），非法 id 会让游戏抛异常 */
	private static String sanitize(String id, List<String> warn) {
		if (id == null) {
			return null;
		}
		String s = id.toLowerCase().trim();
		if (s.startsWith("minecraft:")) {
			s = s.substring("minecraft:".length());
		}
		// 最后兜底：万一还有哪条路径漏拆了 "oak_stairs[facing=east]"，
		// 剥掉中括号部分保住方块本身，总好过整块被当成非法 id 丢弃。
		int br = s.indexOf('[');
		if (br >= 0) {
			s = s.substring(0, br).trim();
		}
		if (s.isEmpty() || !s.matches("[a-z0-9_]{1,64}")) {
			if (warn != null) {
				String w = "非法方块 id「" + id + "」（已丢弃）";
				if (!warn.contains(w)) {
					warn.add(w);
				}
			}
			return null;
		}
		return s;
	}

	private static int nz(Integer v, int def) {
		return v == null ? def : v;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** ops 实际需要的包围盒 [宽, 高, 进深]（size 缺省时用） */
	private static int[] bounds(FreeformSpec spec) {
		int mx = 0;
		int my = 0;
		int mz = 0;
		for (FreeformSpec.Op op : spec.ops) {
			String kind = op.op == null ? "" : op.op.toLowerCase().trim();
			switch (kind) {
				case "layer", "layers", "plane", "row" -> {
					int y = nz(op.y, 0) + 1;
					my = Math.max(my, y);
					if (op.rows != null) {
						mz = Math.max(mz, op.rows.size());
						for (String row : op.rows) {
							if (row != null) {
								mx = Math.max(mx, row.length());
							}
						}
					}
				}
				case "box", "cube", "fill", "rect", "clear", "carve", "hole", "air" -> {
					if (op.from != null && op.to != null) {
						mx = Math.max(mx, Math.max(op.from[0], op.to[0]) + 1);
						my = Math.max(my, Math.max(op.from[1], op.to[1]) + 1);
						mz = Math.max(mz, Math.max(op.from[2], op.to[2]) + 1);
					}
				}
				case "cyl", "cylinder", "column", "pillar", "tube" -> {
					int rx = Math.abs(nz(op.rx, nz(op.r, 3)));
					int rz = Math.abs(nz(op.rz, nz(op.r, 3)));
					mx = Math.max(mx, nz(op.x, 0) + rx + 1);
					mz = Math.max(mz, nz(op.z, 0) + rz + 1);
					my = Math.max(my, nz(op.y1, nz(op.y0, 0)) + 1);
				}
				case "sphere", "ball", "ellipsoid", "dome" -> {
					int r = nz(op.r, 3);
					int rx = Math.abs(nz(op.rx, r));
					int ry = Math.abs(nz(op.ry, r));
					int rz = Math.abs(nz(op.rz, r));
					mx = Math.max(mx, nz(op.x, 0) + rx + 1);
					mz = Math.max(mz, nz(op.z, 0) + rz + 1);
					my = Math.max(my, nz(op.y, nz(op.y0, 0)) + ry + 1);
				}
				default -> {
				}
			}
		}
		mx = Math.max(1, mx);
		my = Math.max(1, my);
		mz = Math.max(1, mz);
		// 开了镜像时，ops 只描述了"一半"，整个形体是它的两倍：
		// 每行 N 个字符 ⇒ 宽 = 2N-1（N=1 时就是 1）。不这样补，镜像会把形状折回自身。
		boolean[] ax = mirrorAxes(spec.mirror);
		if (ax[0]) {
			mx = mx <= 1 ? 1 : mx * 2 - 1;
		}
		if (ax[1]) {
			mz = mz <= 1 ? 1 : mz * 2 - 1;
		}
		return new int[] { mx, my, mz };
	}
}
