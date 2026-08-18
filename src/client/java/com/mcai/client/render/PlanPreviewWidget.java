package com.mcai.client.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mcai.common.BuildingPlan;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 等轴测 3D 方块预览渲染器。
 * 通过 fill() 扫描线填充多边形实现，不依赖 Minecraft 的方块渲染管线，跨版本稳定。
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
	private BuildingPlan plan;
	private int centerX;
	private int centerY;
	private int viewWidth;
	private int viewHeight;
	private int clipLeft;
	private int clipRight;
	private int clipTop;
	private int clipBottom;

	public void setPlan(BuildingPlan plan) {
		this.plan = plan;
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

	public void render(GuiGraphicsExtractor context) {
		if (plan == null || plan.size() == 0) {
			return;
		}

		int w = plan.width;
		int h = plan.height;
		int d = plan.depth;

		// 计算缩放：保证建筑整体放进视口
		double tw = TILE_W;
		double projectedW = (w + d) * tw / 2.0;
		double projectedH = (w + d) * tw / 4.0 + (h + 1) * tw * 0.8;
		double scale = Math.min(1.0, Math.min(viewWidth / projectedW, viewHeight / projectedH));
		tw = Math.max(3, tw * scale);

		int cx = centerX;
		int cy = centerY;

		// 画家算法：先画远（x+z 大），再画近；同列先画低层
		List<BuildingPlan.Entry> sorted = new ArrayList<>(plan.entries);
		sorted.sort((a, b) -> {
			int farA = a.x() + a.z();
			int farB = b.x() + b.z();
			if (farA != farB) {
				return Integer.compare(farB, farA);
			}
			return Integer.compare(a.y(), b.y());
		});

		// 方块过多时均匀抽稀，保证预览流畅（大建筑细节靠轮廓保留）
		int total = sorted.size();
		int maxRender = 6000;
		int stride = total <= maxRender ? 1 : (int) Math.ceil((double) total / maxRender);
		for (int i = 0; i < total; i += stride) {
			BuildingPlan.Entry e = sorted.get(i);
			renderBlock(context, cx, cy, tw, e.x(), e.y(), e.z(), colorOf(e.blockId()));
		}
	}

	private void renderBlock(GuiGraphicsExtractor context, int cx, int cy, double tw, int x, int y, int z, int baseColor) {
		double th = tw / 2.0;
		double bh = tw * 0.8;

		// 顶面（亮）
		int[][] top = new int[][] {
			p(cx, cy, tw, th, bh, x, z, y + 1),
			p(cx, cy, tw, th, bh, x + 1, z, y + 1),
			p(cx, cy, tw, th, bh, x + 1, z + 1, y + 1),
			p(cx, cy, tw, th, bh, x, z + 1, y + 1),
		};
		fillPolygon(context, top, lighten(baseColor, 40));

		// 左面
		int[][] left = new int[][] {
			p(cx, cy, tw, th, bh, x, z, y),
			p(cx, cy, tw, th, bh, x + 1, z, y),
			p(cx, cy, tw, th, bh, x + 1, z, y + 1),
			p(cx, cy, tw, th, bh, x, z, y + 1),
		};
		fillPolygon(context, left, darken(baseColor, 30));

		// 右面
		int[][] right = new int[][] {
			p(cx, cy, tw, th, bh, x + 1, z, y),
			p(cx, cy, tw, th, bh, x + 1, z + 1, y),
			p(cx, cy, tw, th, bh, x + 1, z + 1, y + 1),
			p(cx, cy, tw, th, bh, x + 1, z, y + 1),
		};
		fillPolygon(context, right, darken(baseColor, 60));
	}

	private static int[] p(int cx, int cy, double tw, double th, double bh, int x, int z, int y) {
		double sx = cx + (x - z) * tw / 2.0;
		double sy = cy + (x + z) * th / 2.0 - y * bh;
		return new int[] { (int) Math.round(sx), (int) Math.round(sy) };
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