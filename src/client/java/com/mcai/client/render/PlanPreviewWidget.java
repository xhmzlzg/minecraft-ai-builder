package com.mcai.client.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mcai.common.BuildingPlan;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 等轴测 3D 方块预览渲染器。
 * 具备遮挡剔除（Occlusion Culling）、精准画家算法（Depth Sorting）与自适应缩放居中。
 * 跨版本稳定高清渲染，无噪点、无乱码穿透。
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

	private static final double BASE_TILE_W = 14.0;
	private BuildingPlan plan;
	private int centerX;
	private int centerY;
	private int viewWidth;
	private int viewHeight;
	private int clipLeft;
	private int clipRight;
	private int clipTop;
	private int clipBottom;

	private int rotationIndex = 0; // 0: 0°, 1: 90°, 2: 180°, 3: 270°
	private double zoomMultiplier = 1.0;

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

	public void rotateCW() {
		rotationIndex = (rotationIndex + 1) % 4;
	}

	public void rotateCCW() {
		rotationIndex = (rotationIndex + 3) % 4;
	}

	public void zoomIn() {
		zoomMultiplier = Math.min(2.5, zoomMultiplier * 1.25);
	}

	public void zoomOut() {
		zoomMultiplier = Math.max(0.4, zoomMultiplier / 1.25);
	}

	public void resetView() {
		rotationIndex = 0;
		zoomMultiplier = 1.0;
	}

	public int getRotationDegrees() {
		return rotationIndex * 90;
	}

	public void render(GuiGraphicsExtractor context) {
		if (plan == null || plan.size() == 0) {
			return;
		}

		// 1. 收集建筑真实外接包围盒与实体方块集合
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		Set<Long> solid = new HashSet<>(plan.entries.size() * 2);
		for (BuildingPlan.Entry e : plan.entries) {
			minX = Math.min(minX, e.x());
			maxX = Math.max(maxX, e.x());
			minY = Math.min(minY, e.y());
			maxY = Math.max(maxY, e.y());
			minZ = Math.min(minZ, e.z());
			maxZ = Math.max(maxZ, e.z());
			solid.add(posKey(e.x(), e.y(), e.z()));
		}

		int spanX = Math.max(1, maxX - minX + 1);
		int spanY = Math.max(1, maxY - minY + 1);
		int spanZ = Math.max(1, maxZ - minZ + 1);

		// 2. 自适应缩放：留出适当边距
		double availW = Math.max(20, viewWidth - 16);
		double availH = Math.max(20, viewHeight - 12);

		double tw = BASE_TILE_W;
		double projectedW = (spanX + spanZ) * tw / 2.0;
		double projectedH = (spanX + spanZ) * tw / 4.0 + spanY * tw * 0.8;
		double scale = Math.min(1.0, Math.min(availW / projectedW, availH / projectedH)) * zoomMultiplier;
		tw = Math.max(1.0, tw * scale);
		double th = tw / 2.0;
		double bh = tw * 0.8;

		// 3. 几何中心对齐视口中心（结合旋转）
		double midX = (minX + maxX) / 2.0;
		double midZ = (minZ + maxZ) / 2.0;
		double midY = (minY + maxY) / 2.0;

		double rMidX = rotX(midX, midZ, rotationIndex);
		double rMidZ = rotZ(midX, midZ, rotationIndex);

		double projCenterX = (rMidX - rMidZ) * tw / 2.0;
		double projCenterY = (rMidX + rMidZ) * th / 2.0 - midY * bh;
		int cx = (int) Math.round(centerX - projCenterX);
		int cy = (int) Math.round(centerY - projCenterY);

		// 4. 遮挡剔除与画家算法排序
		List<RotatedEntry> visible = new ArrayList<>();
		for (BuildingPlan.Entry e : plan.entries) {
			boolean topOpen = !solid.contains(posKey(e.x(), e.y() + 1, e.z()));
			boolean face1Open = isFace1Open(solid, e.x(), e.y(), e.z(), rotationIndex);
			boolean face2Open = isFace2Open(solid, e.x(), e.y(), e.z(), rotationIndex);

			if (topOpen || face1Open || face2Open) {
				double rx = rotX(e.x(), e.z(), rotationIndex);
				double rz = rotZ(e.x(), e.z(), rotationIndex);
				visible.add(new RotatedEntry(e, rx, rz, topOpen, face1Open, face2Open));
			}
		}

		// 深度排序：由远及近（rx + rz 从小到大）、由低到高（y 从小到大）
		visible.sort((a, b) -> {
			double depthA = a.rx + a.rz;
			double depthB = b.rx + b.rz;
			if (Math.abs(depthA - depthB) > 0.001) {
				return Double.compare(depthA, depthB);
			}
			return Integer.compare(a.entry.y(), b.entry.y());
		});

		// 5. 渲染各可见表面
		for (RotatedEntry re : visible) {
			renderRotatedBlock(context, cx, cy, tw, th, bh, re.rx, re.entry.y(), re.rz, colorOf(re.entry.blockId()),
					re.topOpen, re.face1Open, re.face2Open);
		}
	}

	private static double rotX(double x, double z, int rot) {
		return switch (rot) {
			case 1 -> -z;
			case 2 -> -x;
			case 3 -> z;
			default -> x;
		};
	}

	private static double rotZ(double x, double z, int rot) {
		return switch (rot) {
			case 1 -> x;
			case 2 -> -z;
			case 3 -> -x;
			default -> z;
		};
	}

	private static boolean isFace1Open(Set<Long> solid, int x, int y, int z, int rot) {
		return switch (rot) {
			case 1 -> !solid.contains(posKey(x, y, z - 1));
			case 2 -> !solid.contains(posKey(x - 1, y, z));
			case 3 -> !solid.contains(posKey(x, y, z + 1));
			default -> !solid.contains(posKey(x + 1, y, z));
		};
	}

	private static boolean isFace2Open(Set<Long> solid, int x, int y, int z, int rot) {
		return switch (rot) {
			case 1 -> !solid.contains(posKey(x + 1, y, z));
			case 2 -> !solid.contains(posKey(x, y, z - 1));
			case 3 -> !solid.contains(posKey(x - 1, y, z));
			default -> !solid.contains(posKey(x, y, z + 1));
		};
	}

	private record RotatedEntry(BuildingPlan.Entry entry, double rx, double rz, boolean topOpen, boolean face1Open, boolean face2Open) {}

	private static long posKey(int x, int y, int z) {
		return (((long) (x + 200000) & 0x1FFFFFL) << 42)
				| (((long) (y + 200000) & 0x1FFFFFL) << 21)
				| (((long) (z + 200000) & 0x1FFFFFL));
	}

	private void renderRotatedBlock(GuiGraphicsExtractor context, int cx, int cy, double tw, double th, double bh,
			double rx, int y, double rz, int baseColor, boolean topOpen, boolean face1Open, boolean face2Open) {
		// 顶面
		if (topOpen) {
			int[][] top = new int[][] {
				p(cx, cy, tw, th, bh, rx, rz, y + 1),
				p(cx, cy, tw, th, bh, rx + 1, rz, y + 1),
				p(cx, cy, tw, th, bh, rx + 1, rz + 1, y + 1),
				p(cx, cy, tw, th, bh, rx, rz + 1, y + 1),
			};
			fillPolygon(context, top, lighten(baseColor, 35));
		}

		// 左前侧面
		if (face1Open) {
			int[][] left = new int[][] {
				p(cx, cy, tw, th, bh, rx, rz, y),
				p(cx, cy, tw, th, bh, rx + 1, rz, y),
				p(cx, cy, tw, th, bh, rx + 1, rz, y + 1),
				p(cx, cy, tw, th, bh, rx, rz, y + 1),
			};
			fillPolygon(context, left, darken(baseColor, 25));
		}

		// 右前侧面
		if (face2Open) {
			int[][] right = new int[][] {
				p(cx, cy, tw, th, bh, rx + 1, rz, y),
				p(cx, cy, tw, th, bh, rx + 1, rz + 1, y),
				p(cx, cy, tw, th, bh, rx + 1, rz + 1, y + 1),
				p(cx, cy, tw, th, bh, rx + 1, rz, y + 1),
			};
			fillPolygon(context, right, darken(baseColor, 50));
		}
	}

	private static int[] p(int cx, int cy, double tw, double th, double bh, double x, double z, int y) {
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
		if (key.startsWith("potted_")) {
			if (key.contains("poppy") || key.contains("tulip")) return 0xFFE53935;
			if (key.contains("dandelion")) return 0xFFFDD835;
			if (key.contains("cornflower")) return 0xFF1E88E5;
			if (key.contains("flowering")) return 0xFFE91E63;
			return 0xFF43A047;
		}
		if (key.contains("carpet")) {
			if (key.contains("red")) return 0xFFD32F2F;
			if (key.contains("blue")) return 0xFF1976D2;
			if (key.contains("white")) return 0xFFFAFAFA;
			if (key.contains("yellow")) return 0xFFFBC02D;
			return 0xFFB0BEC5;
		}
		if (key.contains("bed")) {
			if (key.contains("blue")) return 0xFF1976D2;
			if (key.contains("cyan")) return 0xFF0097A7;
			if (key.contains("white")) return 0xFFF5F5F5;
			if (key.contains("yellow")) return 0xFFFBC02D;
			if (key.contains("black")) return 0xFF212121;
			return 0xFFD32F2F;
		}
		if (key.contains("glass")) {
			return 0xFF81D4FA;
		}
		if (key.contains("cauldron") || key.contains("smoker") || key.contains("anvil")) {
			return 0xFF455A64;
		}
		if (key.contains("barrel") || key.contains("chest") || key.contains("bookshelf")) {
			return 0xFF8D6E63;
		}
		if (key.contains("table") || key.contains("loom")) {
			return 0xFF9C7A4D;
		}
		if (key.contains("wool")) {
			return 0xFF9E9E9E;
		}
		if (key.contains("concrete")) {
			return 0xFFCFD8DC;
		}
		if (key.contains("planks") || key.contains("log") || key.contains("stair") || key.contains("slab")) {
			if (key.contains("birch")) return 0xFFD7CCC8;
			if (key.contains("spruce")) return 0xFF6D4C41;
			if (key.contains("dark_oak")) return 0xFF4E342E;
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