package com.mcai.client.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mcai.common.BuildingPlan;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 3D 方块预览渲染器：正交投影 + 任意角度旋转（偏航 yaw / 俯仰 pitch）+ 背面剔除 + 画家算法深度排序。
 * 通过 fill() 扫描线填充多边形实现，不依赖 Minecraft 的方块渲染管线，跨版本稳定。
 * 交互：滚轮缩放、右键拖拽无级旋转（右键单击 = 旋转 90°）、左键拖拽平移、双击复位。
 */
public class PlanPreviewWidget {
	private static final Map<String, Integer> BLOCK_COLORS = new HashMap<>();

	static {
		BLOCK_COLORS.put("oak_planks", 0xFFB08D57);
		BLOCK_COLORS.put("oak_log", 0xFF8D6E63);
		BLOCK_COLORS.put("dark_oak_planks", 0xFF5D4037);
		BLOCK_COLORS.put("dark_oak_log", 0xFF4E342E);
		BLOCK_COLORS.put("dark_oak_stairs", 0xFF4E342E);
		BLOCK_COLORS.put("spruce_planks", 0xFF6D4C41);
		BLOCK_COLORS.put("spruce_log", 0xFF5D4037);
		BLOCK_COLORS.put("birch_planks", 0xFFD7CCC8);
		BLOCK_COLORS.put("birch_log", 0xFFBCAAA4);
		BLOCK_COLORS.put("stone_bricks", 0xFF9E9E9E);
		BLOCK_COLORS.put("cobblestone", 0xFF757575);
		BLOCK_COLORS.put("stone", 0xFF8D8D8D);
		BLOCK_COLORS.put("white_concrete", 0xFFECEFF1);
		BLOCK_COLORS.put("light_gray_concrete", 0xFFB0BEC5);
		BLOCK_COLORS.put("glass", 0xFF81D4FA);
		BLOCK_COLORS.put("glass_pane", 0xFF81D4FA);
		BLOCK_COLORS.put("red_wool", 0xFFC62828);
		BLOCK_COLORS.put("blue_wool", 0xFF1565C0);
		BLOCK_COLORS.put("yellow_wool", 0xFFF9A825);
		BLOCK_COLORS.put("terracotta", 0xFFA1887F);
		BLOCK_COLORS.put("red_terracotta", 0xFFB71C1C);
		BLOCK_COLORS.put("oak_door", 0xFF8D6E63);
		BLOCK_COLORS.put("torch", 0xFFFFD54F);
		BLOCK_COLORS.put("lantern", 0xFFFFB300);
		BLOCK_COLORS.put("furnace", 0xFF616161);
		BLOCK_COLORS.put("flower_pot", 0xFF8D6E63);
		BLOCK_COLORS.put("chest", 0xFFA1887F);
		BLOCK_COLORS.put("bookshelf", 0xFFB08D57);
		BLOCK_COLORS.put("crafting_table", 0xFF9C7A4D);
		BLOCK_COLORS.put("sea_lantern", 0xFF80DEEA);
		BLOCK_COLORS.put("red_bed", 0xFFC62828);
		BLOCK_COLORS.put("red_bed_foot", 0xFFC62828);
		BLOCK_COLORS.put("red_bed_head", 0xFFC62828);
		BLOCK_COLORS.put("dark_oak_fence", 0xFF4E342E);
		BLOCK_COLORS.put("oak_fence_gate", 0xFF8D6E63);
		// ---- 自由形体（船/飞机/雕像…）常用方块：颜色对了才看得出是什么东西 ----
		BLOCK_COLORS.put("white_wool", 0xFFF2F2F2);
		BLOCK_COLORS.put("light_gray_wool", 0xFFB0B0B0);
		BLOCK_COLORS.put("gray_wool", 0xFF6E6E6E);
		BLOCK_COLORS.put("black_wool", 0xFF2B2B2B);
		BLOCK_COLORS.put("brown_wool", 0xFF7B4A2B);
		BLOCK_COLORS.put("green_wool", 0xFF3B6B2B);
		BLOCK_COLORS.put("lime_wool", 0xFF6EBE3B);
		BLOCK_COLORS.put("orange_wool", 0xFFD9722B);
		BLOCK_COLORS.put("light_blue_wool", 0xFF5FA9D9);
		BLOCK_COLORS.put("cyan_wool", 0xFF2B7B8B);
		BLOCK_COLORS.put("purple_wool", 0xFF7B3BBE);
		BLOCK_COLORS.put("magenta_wool", 0xFFB03BB0);
		BLOCK_COLORS.put("pink_wool", 0xFFD97BA0);
		BLOCK_COLORS.put("gray_concrete", 0xFF5A5A5F);
		BLOCK_COLORS.put("black_concrete", 0xFF1D1D22);
		BLOCK_COLORS.put("blue_concrete", 0xFF2B4BA0);
		BLOCK_COLORS.put("red_concrete", 0xFF8E2B2B);
		BLOCK_COLORS.put("green_concrete", 0xFF3B5B2B);
		BLOCK_COLORS.put("brown_concrete", 0xFF6B4326);
		BLOCK_COLORS.put("white_terracotta", 0xFFD8C4B4);
		BLOCK_COLORS.put("brown_terracotta", 0xFF8C5A3C);
		BLOCK_COLORS.put("brick", 0xFF9A5B45);
		BLOCK_COLORS.put("nether_bricks", 0xFF33202A);
		BLOCK_COLORS.put("smooth_stone", 0xFFA0A0A0);
		BLOCK_COLORS.put("polished_andesite", 0xFF848A84);
		BLOCK_COLORS.put("polished_diorite", 0xFFC8C8C4);
		BLOCK_COLORS.put("iron_block", 0xFFD8D8D8);
		BLOCK_COLORS.put("glowstone", 0xFFF6D67A);
		BLOCK_COLORS.put("water", 0xFF3B5FD9);
		BLOCK_COLORS.put("scaffolding", 0xFFC8A24A);
		BLOCK_COLORS.put("lightning_rod", 0xFFB0603A);
		BLOCK_COLORS.put("cauldron", 0xFF4A4A4A);
		BLOCK_COLORS.put("potted_fern", 0xFF4E7B32);
		BLOCK_COLORS.put("spruce_door", 0xFF6D4C41);
		BLOCK_COLORS.put("dark_oak_door", 0xFF4E342E);
		// ---- v1.1.1：默认由 AI 自己画之后，材质由模型自由挑，下面这批是它最常用的 ----
		BLOCK_COLORS.put("deepslate", 0xFF4F4F55);
		BLOCK_COLORS.put("deepslate_bricks", 0xFF47474D);
		BLOCK_COLORS.put("deepslate_tiles", 0xFF3B3B40);
		BLOCK_COLORS.put("cobbled_deepslate", 0xFF54545A);
		BLOCK_COLORS.put("polished_deepslate", 0xFF43434A);
		BLOCK_COLORS.put("sandstone", 0xFFE0D5A8);
		BLOCK_COLORS.put("smooth_sandstone", 0xFFE8DDB4);
		BLOCK_COLORS.put("cut_sandstone", 0xFFDCCF9E);
		BLOCK_COLORS.put("red_sandstone", 0xFFC57A38);
		BLOCK_COLORS.put("quartz_block", 0xFFEDE9E0);
		BLOCK_COLORS.put("smooth_quartz", 0xFFE6E1D7);
		BLOCK_COLORS.put("quartz_bricks", 0xFFE2DDD3);
		BLOCK_COLORS.put("prismarine", 0xFF5AA79B);
		BLOCK_COLORS.put("prismarine_bricks", 0xFF63B3A4);
		BLOCK_COLORS.put("dark_prismarine", 0xFF33605A);
		BLOCK_COLORS.put("mossy_cobblestone", 0xFF6B7A5A);
		BLOCK_COLORS.put("mossy_stone_bricks", 0xFF7E8C6B);
		BLOCK_COLORS.put("cracked_stone_bricks", 0xFF8E8E8E);
		BLOCK_COLORS.put("chiseled_stone_bricks", 0xFF949494);
		BLOCK_COLORS.put("andesite", 0xFF8A8A8A);
		BLOCK_COLORS.put("diorite", 0xFFCFCFCB);
		BLOCK_COLORS.put("granite", 0xFF9A6A56);
		BLOCK_COLORS.put("polished_granite", 0xFF9E6C57);
		BLOCK_COLORS.put("tuff", 0xFF6C6E62);
		BLOCK_COLORS.put("calcite", 0xFFE2E1DC);
		BLOCK_COLORS.put("dripstone_block", 0xFF8B6E5C);
		BLOCK_COLORS.put("blackstone", 0xFF2E2A2E);
		BLOCK_COLORS.put("basalt", 0xFF4B4A52);
		BLOCK_COLORS.put("mud_bricks", 0xFF8C6B52);
		BLOCK_COLORS.put("packed_mud", 0xFF96735A);
		BLOCK_COLORS.put("bamboo_planks", 0xFFD6BE6A);
		BLOCK_COLORS.put("bamboo_block", 0xFFA9B24A);
		BLOCK_COLORS.put("cherry_planks", 0xFFE8BEB0);
		BLOCK_COLORS.put("mangrove_planks", 0xFF7A4234);
		BLOCK_COLORS.put("acacia_planks", 0xFFBA6337);
		BLOCK_COLORS.put("jungle_planks", 0xFFA87B57);
		BLOCK_COLORS.put("crimson_planks", 0xFF6A3A4C);
		BLOCK_COLORS.put("warped_planks", 0xFF2C6A6A);
		BLOCK_COLORS.put("oak_leaves", 0xFF4E7B32);
		BLOCK_COLORS.put("spruce_leaves", 0xFF3A5F2B);
		BLOCK_COLORS.put("copper_block", 0xFFB0603A);
		BLOCK_COLORS.put("oxidized_copper", 0xFF53A08A);
		BLOCK_COLORS.put("weathered_copper", 0xFF6E9A76);
		BLOCK_COLORS.put("iron_bars", 0xFFB8B8B8);
		BLOCK_COLORS.put("soul_lantern", 0xFF6FC7D8);
		BLOCK_COLORS.put("redstone_lamp", 0xFFB08040);
		BLOCK_COLORS.put("barrel", 0xFF8A6440);
		BLOCK_COLORS.put("hay_block", 0xFFC9A62B);
		BLOCK_COLORS.put("obsidian", 0xFF1A1226);
		BLOCK_COLORS.put("snow_block", 0xFFF5F8FA);
		BLOCK_COLORS.put("ice", 0xFFA8CBE8);
		BLOCK_COLORS.put("dirt", 0xFF79553A);
		BLOCK_COLORS.put("grass_block", 0xFF6A9A45);
		BLOCK_COLORS.put("gravel", 0xFF8A8580);
		BLOCK_COLORS.put("ladder", 0xFFA87B57);
		BLOCK_COLORS.put("chain", 0xFF4A4A52);
		BLOCK_COLORS.put("smooth_stone_slab", 0xFFA0A0A0);
		BLOCK_COLORS.put("stone_slab", 0xFF8D8D8D);
		BLOCK_COLORS.put("oak_stairs", 0xFFB08D57);
		BLOCK_COLORS.put("spruce_stairs", 0xFF6D4C41);
		BLOCK_COLORS.put("birch_stairs", 0xFFD7CCC8);
		BLOCK_COLORS.put("stone_bricks_stairs", 0xFF9E9E9E);
		BLOCK_COLORS.put("cobblestone_stairs", 0xFF757575);
	}

	/** 形状后缀：去掉之后剩下的就是"基材"，颜色应该跟基材一致 */
	private static final String[] SHAPE_SUFFIXES = {
			"_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_trapdoor", "_door", "_pane",
			"_pressure_plate", "_button", "_hanging_sign", "_sign", "_carpet", "_bed_foot", "_bed_head", "_bed" };

	private static final double TILE_W = 14.0;
	private static final int MAX_RENDER = 6000;
	/** 正在输入文字时的降级预算：配合"粗格合并"仍能看出外形，但绘制量足够小，打字不卡 */
	private static final int LOW_DETAIL_RENDER = 600;
	// 光照方向（视线空间），归一化
	private static final double LIGHT_X = -0.35;
	private static final double LIGHT_Y = 0.72;
	private static final double LIGHT_Z = -0.59;

	private BuildingPlan plan;
	private int centerX;
	private int centerY;
	private int viewWidth;
	private int viewHeight;
	private int clipLeft;
	private int clipRight;
	private int clipTop;
	private int clipBottom;
	// 视图交互状态
	private double userScale = 1.0;
	private int panX = 0;
	private int panY = 0;
	private double yaw = Math.PI / 4;
	private double pitch = Math.toRadians(30);
	// 变换+排序缓存：视角或方案变化时才重算
	private BuildingPlan cachedPlan;
	private double cachedYaw = Double.NaN;
	private double cachedPitch = Double.NaN;
	private List<RenderCube> cachedList;
	/** 低画质模式（输入框获得焦点时为 true）：只画少量方块，保证打字流畅 */
	private boolean lowDetail;
	private boolean cachedLowDetail;

	/** 由界面调用：有输入框聚焦（正在打字）时降级绘制 */
	public void setLowDetail(boolean low) {
		if (this.lowDetail != low) {
			this.lowDetail = low;
			this.cachedList = null;   // 下次渲染重建列表
		}
	}

	/** 视空间中的一个方块：8 顶点（已居中+旋转）、中心深度、6 面法线（视空间）、颜色 */
	private static class RenderCube {
		final double[][] v;
		final double depth;
		final double[][] normals;
		final int color;

		final boolean[] faces;

		RenderCube(double[][] vertices, double depth, double[][] normals, int color, boolean[] faces) {
			this.faces = faces;
			this.v = vertices;
			this.depth = depth;
			this.normals = normals;
			this.color = color;
		}
	}

	public void setPlan(BuildingPlan plan) {
		this.plan = plan;
		this.cachedPlan = null;
		this.cachedList = null;
	}

	public void setViewport(int centerX, int centerY, int width, int height) {
		this.centerX = centerX;
		this.centerY = centerY;
		this.viewWidth = width;
		this.viewHeight = height;
		this.clipLeft = centerX - width / 2;
		this.clipRight = centerX + width / 2;
		this.clipTop = centerY - height / 2;
		this.clipBottom = centerY + height / 2;
	}

	public boolean hasPlan() {
		return plan != null;
	}

	/** 鼠标是否在预览区内 */
	public boolean contains(double mx, double my) {
		return mx >= clipLeft && mx < clipRight && my >= clipTop && my < clipBottom;
	}

	/** 滚轮缩放 */
	public void zoom(double amount) {
		userScale = Math.max(0.4, Math.min(10.0, userScale * (amount > 0 ? 1.25 : 0.8)));
	}

	/** 旋转 90°（右键单击） */
	public void rotateCw() {
		yaw += Math.PI / 2;
	}

	/** 无级旋转（右键拖拽）：dYaw 水平 / dPitch 俯仰 */
	public void rotateBy(double dYaw, double dPitch) {
		yaw += dYaw;
		pitch = Math.max(-1.4, Math.min(1.4, pitch + dPitch));
	}

	/** 拖拽平移 */
	public void pan(double dx, double dy) {
		panX += (int) Math.round(dx);
		panY += (int) Math.round(dy);
	}

	/** 双击复位（缩放/平移/视角归位） */
	public void resetView() {
		userScale = 1.0;
		panX = 0;
		panY = 0;
		yaw = Math.PI / 4;
		pitch = Math.toRadians(30);
	}

	public void render(GuiGraphicsExtractor context) {
		if (plan == null || plan.size() == 0) {
			return;
		}

		// 视角或方案变化时重算变换与排序（拖拽旋转时每帧重算，最多 12000 块，流畅）
		if (cachedList == null || cachedPlan != plan || cachedYaw != yaw || cachedPitch != pitch
				|| cachedLowDetail != lowDetail) {
			cachedPlan = plan;
			cachedYaw = yaw;
			cachedPitch = pitch;
			cachedLowDetail = lowDetail;
			cachedList = buildRenderList();
		}

		// 计算缩放：保证建筑整体放进视口（再叠加用户缩放）
		double tw = TILE_W;
		double diagonal = Math.sqrt((double) plan.width * plan.width + (double) plan.depth * plan.depth);
		double projectedW = diagonal * tw;
		double projectedH = diagonal * tw * 0.5 + (plan.height + 1) * tw;
		double scale = Math.min(1.0, Math.min(viewWidth / projectedW, viewHeight / projectedH));
		tw = Math.max(1.0, tw * scale) * userScale;

		int cx = centerX + panX;
		int cy = centerY + panY;

		for (RenderCube cube : cachedList) {
			drawCube(context, cube, cx, cy, tw);
		}
	}

	/** 抽稀 + 坐标变换 + 深度排序 */
	private List<RenderCube> buildRenderList() {
		double sinY = Math.sin(yaw);
		double cosY = Math.cos(yaw);
		double sinP = Math.sin(pitch);
		double cosP = Math.cos(pitch);
		double mx = plan.width / 2.0;
		double my = plan.height / 2.0;
		double mz = plan.depth / 2.0;

		// v1.1.0 预览改进：
		//   1) 先只保留"有暴露面"的方块（被包在里面的方块本来也看不见）——保证外形完整、不出现抽稀窟窿；
		//   2) 仍然太多时按 2×2×2 / 3×3×3 粗化（每个粗格合并成一个方块），而不是随机抽稀；
		//   3) 每块只画真正可见的面，绘制量再降一大截，这样打字时也不用把画质砍到看不清。
		int budget = lowDetail ? LOW_DETAIL_RENDER : MAX_RENDER;
		int lod = 1;
		Map<Long, String> grid = null;
		for (; lod <= 3; lod++) {
			grid = buildVoxelGrid(lod);
			int visible = 0;
			for (Map.Entry<Long, String> en : grid.entrySet()) {
				if (cellExposed(grid, en.getKey(), lod)) {
					visible++;
					if (visible > budget) {
						break;
					}
				}
			}
			if (visible <= budget) {
				break;
			}
		}
		if (lod > 3) {
			lod = 3;
			grid = buildVoxelGrid(lod);
		}
		List<RenderCube> list = new ArrayList<>();
		for (Map.Entry<Long, String> en : grid.entrySet()) {
			long k = en.getKey();
			int gx = (int) ((k >> 40) & 0xFFF);
			int gy = (int) ((k >> 20) & 0xFFF);
			int gz = (int) (k & 0xFFF);
			if (!cellExposed(grid, k, lod)) {
				continue;   // 完全被包住，画了也看不见
			}
			double[][] verts = new double[8][3];
			int idx = 0;
			for (int dy = 0; dy <= 1; dy++) {
				for (int dz = 0; dz <= 1; dz++) {
					for (int dx = 0; dx <= 1; dx++) {
						verts[idx++] = transform(gx * lod + dx * lod - mx, gy * lod + dy * lod - my,
								gz * lod + dz * lod - mz, sinY, cosY, sinP, cosP);
					}
				}
			}
			double[] center = transform(gx * lod + lod / 2.0 - mx, gy * lod + lod / 2.0 - my,
					gz * lod + lod / 2.0 - mz, sinY, cosY, sinP, cosP);
			double[][] modelNormals = { { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
			double[][] normals = new double[6][3];
			for (int n = 0; n < 6; n++) {
				normals[n] = transform(modelNormals[n][0], modelNormals[n][1], modelNormals[n][2], sinY, cosY, sinP, cosP);
			}
			boolean[] faces = new boolean[6];
			faces[0] = emptyAt(grid, gx, gy + 1, gz);
			faces[1] = emptyAt(grid, gx, gy - 1, gz);
			faces[2] = emptyAt(grid, gx, gy, gz - 1);
			faces[3] = emptyAt(grid, gx, gy, gz + 1);
			faces[4] = emptyAt(grid, gx - 1, gy, gz);
			faces[5] = emptyAt(grid, gx + 1, gy, gz);
			list.add(new RenderCube(verts, center[2], normals, colorOf(en.getValue()), faces));
		}
		if (list.isEmpty()) {
			// 兜底：极端情况下至少画一层，避免预览全空
			for (int i = 0; i < Math.min(64, plan.entries.size()); i++) {
				BuildingPlan.Entry e = plan.entries.get(i);
			double[][] verts = new double[8][3];
			int idx = 0;
			for (int dy = 0; dy <= 1; dy++) {
				for (int dz = 0; dz <= 1; dz++) {
					for (int dx = 0; dx <= 1; dx++) {
						verts[idx++] = transform(e.x() + dx - mx, e.y() + dy - my, e.z() + dz - mz, sinY, cosY, sinP, cosP);
					}
				}
			}
			// 中心深度：z'' 越大越近，排序升序（远的先画）
			double[] center = transform(e.x() + 0.5 - mx, e.y() + 0.5 - my, e.z() + 0.5 - mz, sinY, cosY, sinP, cosP);
			// 6 面法线（模型空间）：top/bottom/north/south/west/east
			double[][] modelNormals = { { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
			double[][] normals = new double[6][3];
			for (int n = 0; n < 6; n++) {
				normals[n] = transform(modelNormals[n][0], modelNormals[n][1], modelNormals[n][2], sinY, cosY, sinP, cosP);
			}
			boolean[] allFaces = { true, true, true, true, true, true };
			list.add(new RenderCube(verts, center[2], normals, colorOf(e.blockId()), allFaces));
			}
		}
		list.sort((a, b) -> Double.compare(a.depth, b.depth));
		return list;
	}

	/** 按 lod 倍率把方案压成粗格：(gx,gy,gz) -> 该格的代表方块 id */
	private Map<Long, String> buildVoxelGrid(int lod) {
		Map<Long, String> grid = new java.util.HashMap<>();
		Map<Long, Integer> counts = new java.util.HashMap<>();
		for (BuildingPlan.Entry e : plan.entries) {
			String id = e.blockId();
			// 只排除"看不见的东西"：air 家族与隐形光源 light。
			// 注意别用 endsWith("light") —— 那会把 shroomlight / froglight 这些真能看见的灯也一起藏掉，
			// 而 AI 自己画的时候正是会挑这些发光方块。
			if (id == null || id.endsWith("air") || "light".equals(id)) {
				continue;
			}
			long k = key(e.x() / lod, e.y() / lod, e.z() / lod);
			int c = counts.merge(k, 1, Integer::sum);
			if (c == 1) {
				grid.put(k, id);
			}
		}
		return grid;
	}

	private static long key(int x, int y, int z) {
		return ((((long) x) & 0xFFF) << 40) | ((((long) y) & 0xFFF) << 20) | (((long) z) & 0xFFF);
	}

	private static boolean emptyAt(Map<Long, String> grid, int gx, int gy, int gz) {
		return !grid.containsKey(key(gx, gy, gz));
	}

	/** 粗格是否至少有一面朝空（否则是被包住的内部格） */
	private boolean cellExposed(Map<Long, String> grid, long k, int lod) {
		int gx = (int) ((k >> 40) & 0xFFF);
		int gy = (int) ((k >> 20) & 0xFFF);
		int gz = (int) (k & 0xFFF);
		return emptyAt(grid, gx, gy + 1, gz) || emptyAt(grid, gx, gy - 1, gz)
				|| emptyAt(grid, gx, gy, gz - 1) || emptyAt(grid, gx, gy, gz + 1)
				|| emptyAt(grid, gx - 1, gy, gz) || emptyAt(grid, gx + 1, gy, gz);
	}

	/** 模型坐标 -> 视空间：先绕 Y 轴偏航，再绕 X 轴俯仰 */
	private static double[] transform(double x, double y, double z, double sinY, double cosY, double sinP, double cosP) {
		double x1 = x * cosY + z * sinY;
		double z1 = -x * sinY + z * cosY;
		double y2 = y * cosP - z1 * sinP;
		double z2 = y * sinP + z1 * cosP;
		return new double[] { x1, y2, z2 };
	}

	/** 面顶点索引（对应 buildRenderList 的顶点顺序 y,z,x 循环：idx = dy*4 + dz*2 + dx） */
	private static final int[][] FACES = {
			{ 4, 5, 7, 6 }, // top (y+1)
			{ 0, 1, 3, 2 }, // bottom (y)
			{ 0, 1, 5, 4 }, // north (z)
			{ 2, 3, 7, 6 }, // south (z+1)
			{ 0, 2, 6, 4 }, // west (x)
			{ 1, 3, 7, 5 }, // east (x+1)
	};

	private void drawCube(GuiGraphicsExtractor context, RenderCube cube, int cx, int cy, double tw) {
		// 屏幕上不足半个像素的方块直接跳过：看不见，但很吃绘制时间（缩小时尤其明显）
		if (tw < 0.5) {
			return;
		}
		for (int f = 0; f < 6; f++) {
			if (cube.faces != null && !cube.faces[f]) {
				continue;   // 该面被相邻方块挡住，不用画
			}
			double nx = cube.normals[f][0];
			double ny = cube.normals[f][1];
			double nz = cube.normals[f][2];
			// 背面剔除：法线朝向相机（+z''，z'' 大 = 近）才可见
			if (nz <= 0) {
				continue;
			}
			int[] face = FACES[f];
			int[][] pts = new int[4][2];
			for (int i = 0; i < 4; i++) {
				double[] v = cube.v[face[i]];
				pts[i][0] = (int) Math.round(cx + v[0] * tw);
				pts[i][1] = (int) Math.round(cy - v[1] * tw);
			}
			// 按面法线与光照夹角调明暗
			double dot = nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z;
			int bright = (int) (90 * Math.max(0, dot));
			int color = bright > 0 ? lighten(cube.color, bright) : darken(cube.color, -bright / 2);
			fillPolygon(context, pts, color);
		}
	}

	private int colorOf(String blockId) {
		String key = blockId == null ? "" : blockId.toLowerCase();
		Integer c = BLOCK_COLORS.get(key);
		if (c != null) {
			return c;
		}
		// 楼梯/半砖/栏杆/门/地毯… 只是"形状"，颜色应该跟基材一样：
		// 去掉形状后缀再查一次（AI 自己画的房子会挑各种材质的楼梯与栏杆）。
		String base = key;
		for (String sfx : SHAPE_SUFFIXES) {
			if (base.endsWith(sfx)) {
				base = base.substring(0, base.length() - sfx.length());
				break;
			}
		}
		if (!base.equals(key)) {
			c = BLOCK_COLORS.get(base);
			if (c != null) {
				return c;
			}
		}
		// 长尾材质：按关键词给个像样的颜色，别让整栋房子都是一个颜色。
		// 顺序要紧 —— 例如 sandstone 必须排在 stone 之前。
		if (base.contains("glass")) {
			return 0xFF81D4FA;
		}
		if (base.contains("wool") || base.contains("carpet")) {
			return 0xFFE8E8E8;   // 未登记的羊毛色：按浅色处理（自由形体里羊毛多用于船帆/机翼）
		}
		if (base.contains("concrete") || base.contains("terracotta") || base.contains("glazed")) {
			return 0xFFCFD8DC;
		}
		if (base.contains("sand") || base.contains("sandstone")) {
			return 0xFFE0D5A8;
		}
		if (base.contains("copper")) {
			return 0xFFB0603A;
		}
		if (base.contains("prismarine")) {
			return 0xFF5AA79B;
		}
		if (base.contains("water") || base.contains("ice")) {
			return 0xFF3B5FD9;
		}
		if (base.contains("leaves")) {
			return 0xFF4E7B32;
		}
		if (base.contains("planks") || base.contains("log") || base.contains("wood") || base.contains("stem")
				|| base.contains("hyphae") || base.contains("bamboo") || base.contains("oak")
				|| base.contains("spruce") || base.contains("birch") || base.contains("jungle")
				|| base.contains("acacia") || base.contains("cherry") || base.contains("mangrove")
				|| base.contains("crimson") || base.contains("warped") || base.contains("mud")) {
			return 0xFF795548;
		}
		if (base.contains("stone") || base.contains("deepslate") || base.contains("cobble")
				|| base.contains("brick") || base.contains("andesite") || base.contains("diorite")
				|| base.contains("granite") || base.contains("tuff") || base.contains("calcite")
				|| base.contains("basalt") || base.contains("blackstone") || base.contains("quartz")
				|| base.contains("nether") || base.contains("end_stone")) {
			return 0xFF9E9E9E;
		}
		if (base.contains("iron") || base.contains("steel")) {
			return 0xFFD8D8D8;
		}
		if (base.contains("gold")) {
			return 0xFFF6D67A;
		}
		if (base.contains("lamp") || base.contains("lantern") || base.contains("glow") || base.contains("torch")) {
			return 0xFFFFB300;
		}
		return 0xFF8D6E63;
	}

	private static int lighten(int color, int amt) {
		int a = (color >>> 24) & 0xFF;
		int r = Math.min(255, ((color >> 16) & 0xFF) + amt);
		int g = Math.min(255, ((color >> 8) & 0xFF) + amt);
		int b = Math.min(255, (color & 0xFF) + amt);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static int darken(int color, int amt) {
		int a = (color >>> 24) & 0xFF;
		int r = Math.max(0, ((color >> 16) & 0xFF) - amt);
		int g = Math.max(0, ((color >> 8) & 0xFF) - amt);
		int b = Math.max(0, (color & 0xFF) - amt);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/**
	 * 多边形扫描线填充。points: {x,y} 数组。
	 */
	private void fillPolygon(GuiGraphicsExtractor context, int[][] points, int color) {
		if (points.length < 3) {
			return;
		}
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int[] p : points) {
			minY = Math.min(minY, p[1]);
			maxY = Math.max(maxY, p[1]);
		}
		if (maxY < clipTop || minY > clipBottom) {
			return;
		}
		minY = Math.max(clipTop, minY);
		maxY = Math.min(clipBottom, maxY);

		int n = points.length;
		for (int y = minY; y <= maxY; y++) {
			List<Double> xs = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				int[] p1 = points[i];
				int[] p2 = points[(i + 1) % n];
				if ((p1[1] <= y && p2[1] > y) || (p2[1] <= y && p1[1] > y)) {
					double t = (double) (y - p1[1]) / (p2[1] - p1[1]);
					xs.add(p1[0] + t * (p2[0] - p1[0]));
				}
			}
			xs.sort(Double::compare);
			for (int i = 0; i + 1 < xs.size(); i += 2) {
				int x1 = Math.max(clipLeft, (int) Math.round(xs.get(i)));
				int x2 = Math.min(clipRight, (int) Math.round(xs.get(i + 1)));
				if (x2 > x1) {
					context.fill(x1, y, x2, y + 1, color);
				}
			}
		}
	}
}
