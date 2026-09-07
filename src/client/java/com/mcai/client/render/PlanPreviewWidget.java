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
	}

	private static final double TILE_W = 14.0;
	private static final int MAX_RENDER = 12000;
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

	/** 视空间中的一个方块：8 顶点（已居中+旋转）、中心深度、6 面法线（视空间）、颜色 */
	private static class RenderCube {
		final double[][] v;
		final double depth;
		final double[][] normals;
		final int color;

		RenderCube(double[][] vertices, double depth, double[][] normals, int color) {
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
		if (cachedList == null || cachedPlan != plan || cachedYaw != yaw || cachedPitch != pitch) {
			cachedPlan = plan;
			cachedYaw = yaw;
			cachedPitch = pitch;
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

		int total = plan.entries.size();
		int stride = total <= MAX_RENDER ? 1 : (int) Math.ceil((double) total / MAX_RENDER);
		List<RenderCube> list = new ArrayList<>(total / stride + 1);
		for (int i = 0; i < total; i += stride) {
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
			list.add(new RenderCube(verts, center[2], normals, colorOf(e.blockId())));
		}
		list.sort((a, b) -> Double.compare(a.depth, b.depth));
		return list;
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
		for (int f = 0; f < 6; f++) {
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
		if (key.contains("glass")) {
			return 0xFF81D4FA;
		}
		if (key.contains("wool")) {
			return 0xFF9E9E9E;
		}
		if (key.contains("concrete")) {
			return 0xFFCFD8DC;
		}
		if (key.contains("planks") || key.contains("log") || key.contains("stair") || key.contains("slab")) {
			return 0xFF795548;
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
