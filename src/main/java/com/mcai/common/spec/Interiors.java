package com.mcai.common.spec;

import java.util.List;
import java.util.Map;

import com.mcai.common.BuildingPlan;

/**
 * 室内家具库：按房间类型摆放"看得出功能"的家具，全部带正确的 blockstate，
 * 并且绝不挡住门口通路（门口两格保持空）。
 */
public final class Interiors {

	private Interiors() {
	}

	public static void furnish(BuildingPlan p, Layout.Room room, int floorY, int layerH,
			BuildingSpec.Pal pal, int doorX, int doorZ) {
		String t = room.type().toLowerCase();
		Layout.Rect r = room.r();
		int y = floorY + 1;
		int ceilY = floorY + layerH;
		int cx = (r.x0() + r.x1()) / 2;
		int cz = (r.z0() + r.z1()) / 2;

		switch (t) {
			case "living" -> living(p, r, y, ceilY, pal, doorX, doorZ);
			case "master", "bedroom", "bed" -> bedroom(p, r, y, ceilY, pal, true, doorX, doorZ);
			case "bed2" -> bedroom(p, r, y, ceilY, pal, false, doorX, doorZ);
			case "bed3" -> bedroom(p, r, y, ceilY, pal, false, doorX, doorZ);
			case "kitchen" -> kitchen(p, r, y, ceilY, pal);
			case "bath", "bathroom", "toilet" -> bath(p, r, y, ceilY, pal);
			case "dining" -> dining(p, r, y, ceilY, pal);
			case "entry", "foyer" -> entry(p, r, y, ceilY, pal);
			case "hall", "corridor" -> hall(p, r, y, ceilY, pal);
			case "study", "office" -> study(p, r, y, ceilY, pal);
			case "storage", "closet" -> storage(p, r, y, ceilY);
			case "balcony", "terrace" -> balcony(p, r, y, ceilY, pal);
			case "shop" -> shop(p, r, y, ceilY, pal);
			case "pool" -> pool(p, r, floorY);
			case "courtyard" -> courtyard(p, r, floorY, pal);
			case "garden" -> garden(p, r, floorY);
			case "none" -> {
				// 不装修
			}
			default -> generic(p, r, y, ceilY, pal);
		}
		if (layerH > 0 && !t.equals("pool") && !t.equals("courtyard") && !t.equals("garden")) {
			PartLib.hangLantern(p, cx, ceilY, cz);
			// 隐藏光源只放房间空气格（顶格），不会造成楼板空洞
			for (int x = r.x0() + 1; x < r.x1(); x += 3) {
				for (int z = r.z0() + 1; z < r.z1(); z += 3) {
					PartLib.hiddenLight(p, x, ceilY - 1, z, layerH);
				}
			}
		}
	}

	// ---------------- 各房间 ----------------

	private static void living(BuildingPlan p, Layout.Rect r, int y, int ceilY, BuildingSpec.Pal pal,
			int doorX, int doorZ) {
		int x0 = r.x0();
		int z0 = r.z0();
		// 电视墙（贴西墙）
		if (r.w() >= 4 && r.d() >= 4) {
			p.set(x0, y, z0 + 1, "dark_oak_slab", Map.of("type", "bottom"));
			p.set(x0, y, z0 + 2, "dark_oak_slab", Map.of("type", "bottom"));
			p.set(x0, y + 1, z0 + 1, "black_concrete");
			p.set(x0, y + 1, z0 + 2, "black_concrete");
		}
		// 沙发（贴东墙，面朝电视）
		if (r.w() >= 5) {
			int sx = r.x1() - 1;
			for (int z = z0 + 1; z <= Math.min(z0 + 3, r.z1() - 1); z++) {
				p.set(sx, y, z, "light_gray_wool");
				p.set(sx - 1, y, z, "gray_wool");
			}
		}
		// 茶几 + 地毯
		int tx = r.x0() + Math.max(2, r.w() / 2 - 1);
		if (tx > x0 + 1 && tx < r.x1()) {
			p.set(tx, y, z0 + 2, "dark_oak_slab", Map.of("type", "bottom"));
			p.set(tx, y, z0 + 3, "dark_oak_slab", Map.of("type", "bottom"));
		}
		for (int x = r.x0() + 1; x <= r.x1() - 1; x++) {
			for (int z = r.z0() + 1; z <= r.z1() - 1; z++) {
				if (Math.abs(x - (r.x0() + r.x1()) / 2) <= 1 && Math.abs(z - (r.z0() + r.z1()) / 2) <= 1) {
					p.add(x, y, z, "gray_carpet");
				}
			}
		}
		plant(p, r, y, pal);
	}

	private static void bedroom(BuildingPlan p, Layout.Rect r, int y, int ceilY, BuildingSpec.Pal pal,
			boolean big, int doorX, int doorZ) {
		String bed = big ? "red_bed" : "blue_bed";
		int x0 = r.x0();
		int z0 = r.z0();
		if (big && r.w() >= 4 && r.d() >= 4) {
			// 双人床：床头贴北墙（z 大的一侧）
			int hz = r.z1();
			p.set(x0, y, hz, bed, Map.of("facing", "south", "part", "head", "occupied", "false"));
			p.set(x0 + 1, y, hz, bed, Map.of("facing", "south", "part", "head", "occupied", "false"));
			p.set(x0, y, hz - 1, bed, Map.of("facing", "south", "part", "foot", "occupied", "false"));
			p.set(x0 + 1, y, hz - 1, bed, Map.of("facing", "south", "part", "foot", "occupied", "false"));
			p.set(x0, y, hz - 2, "birch_planks");
			p.set(x0 + 2, y, hz, "birch_planks");
			p.set(x0, y + 1, hz - 2, "lantern", Map.of("hanging", "false"));
			p.set(r.x1(), y, z0, "birch_planks");
			p.set(r.x1(), y + 1, z0, "birch_planks");
		} else if (r.w() >= 3 && r.d() >= 3) {
			int hz = r.z1();
			p.set(x0, y, hz, bed, Map.of("facing", "south", "part", "head", "occupied", "false"));
			p.set(x0, y, hz - 1, bed, Map.of("facing", "south", "part", "foot", "occupied", "false"));
			p.set(x0 + 2, y, z0, "birch_planks");
		}
		// 衣柜（贴东墙，2 格高）
		if (r.w() >= 4) {
			p.set(r.x1(), y, r.z1(), "birch_planks");
			p.set(r.x1(), y + 1, r.z1(), "birch_planks");
		}
		plant(p, r, y, pal);
	}

	private static void kitchen(BuildingPlan p, Layout.Rect r, int y, int ceilY, BuildingSpec.Pal pal) {
		if (r.w() < 3 || r.d() < 3) {
			return;
		}
		int z = r.z1();
		for (int x = r.x0(); x <= r.x1(); x++) {
			p.set(x, y, z, "polished_diorite");
		}
		p.set(r.x0(), y, z, "cauldron");
		p.set(Math.min(r.x0() + 1, r.x1()), y, z, "smoker", Map.of("facing", "south", "lit", "false"));
		p.set(Math.min(r.x0() + 1, r.x1()), y + 1, z, "hopper", Map.of("facing", "down", "enabled", "true"));
		p.set(r.x1(), y, r.z0(), "white_concrete");
		p.set(r.x1(), y + 1, r.z0(), "white_concrete");
		p.set(Math.min(r.x0() + 2, r.x1()), y + 1, z, "light_gray_concrete");
		p.set(r.x0(), y + 1, z, "light_gray_concrete");
	}

	private static void bath(BuildingPlan p, Layout.Rect r, int y, int ceilY, BuildingSpec.Pal pal) {
		if (r.w() < 2 || r.d() < 2) {
			return;
		}
		p.set(r.x0(), y, r.z1(), "white_concrete");       // 马桶水箱
		p.set(r.x0(), y, r.z1() - 1, "cauldron");         // 马桶
		p.set(r.x1() - 1, y, r.z0(), "polished_diorite"); // 洗手台
		p.set(r.x1() - 1, y + 1, r.z0(), "water_cauldron", Map.of("level", "3"));
		if (r.w() >= 3 && r.d() >= 3) {
			p.set(r.x0() + 1, y, r.z1(), "glass");        // 淋浴隔断
			p.set(r.x0() + 1, y + 1, r.z1(), "glass");
			p.set(r.x0() + 1, y + 2, r.z1(), "hopper", Map.of("facing", "down", "enabled", "true"));
			p.set(r.x0() + 1, y, r.z0(), "cauldron");     // 地漏
		}
	}

	private static void dining(BuildingPlan p, Layout.Rect r, int y, int ceilY, BuildingSpec.Pal pal) {
		if (r.w() < 3 || r.d() < 3) {
			return;
		}
		int cx = r.x0() + r.w() / 2;
		int cz = r.z0() + r.d() / 2;
		p.set(cx, y, cz, "dark_oak_slab", Map.of("type", "bottom"));
		p.set(cx + 1 <= r.x1() ? cx + 1 : cx, y, cz, "dark_oak_slab", Map.of("type", "bottom"));
		p.set(cx, y, cz - 1 >= r.z0() ? cz - 1 : cz, y == 0 ? "dark_oak_stairs" : "dark_oak_stairs",
				Map.of("facing", "south", "half", "bottom", "shape", "straight"));
		p.set(cx, y, Math.min(cz + 1, r.z1()), "dark_oak_stairs",
				Map.of("facing", "north", "half", "bottom", "shape", "straight"));
		p.add(r.x0(), y, r.z1(), "barrel", Map.of("facing", "up"));
	}

	private static void entry(BuildingPlan p, Layout.Rect r, int y, int ceilY, BuildingSpec.Pal pal) {
		for (int x = r.x0(); x <= Math.min(r.x0() + 1, r.x1()); x++) {
			p.set(x, y, r.z1(), "birch_planks");
		}
		p.set(r.x0(), y, r.z1() - 1, "light_gray_carpet");
		p.set(Math.min(r.x0() + 1, r.x1()), y, r.z1() - 1, "light_gray_carpet");
	}

	private static void hall(BuildingPlan p, Layout.Rect r, int y, int ceilY, BuildingSpec.Pal pal) {
		p.set(r.x1(), y, r.z0(), "potted_fern");
		if (r.d() >= 5) {
			p.set(r.x1(), y, (r.z0() + r.z1()) / 2, "bookshelf");
		}
	}

	private static void study(BuildingPlan p, Layout.Rect r, int y, int ceilY, BuildingSpec.Pal pal) {
		p.set(r.x0(), y, r.z0(), "birch_planks");
		p.set(r.x0() + 1 <= r.x1() ? r.x0() + 1 : r.x0(), y, r.z0(), "birch_planks");
		p.set(r.x0(), y, Math.min(r.z0() + 1, r.z1()), "birch_stairs",
				Map.of("facing", "east", "half", "bottom", "shape", "straight"));
		p.set(r.x1(), y, r.z1(), "bookshelf");
		p.set(r.x1(), y + 1, r.z1(), "bookshelf");
	}

	private static void storage(BuildingPlan p, Layout.Rect r, int y, int ceilY) {
		p.set(r.x0(), y, r.z0(), "barrel", Map.of("facing", "up"));
		p.set(Math.min(r.x0() + 1, r.x1()), y, r.z0(), "barrel", Map.of("facing", "up"));
	}

	private static void balcony(BuildingPlan p, Layout.Rect r, int y, int ceilY, BuildingSpec.Pal pal) {
		p.set(r.x0(), y, r.z1(), "white_concrete");            // 洗衣机
		p.set(r.x0(), y + 1, r.z1(), "light_gray_concrete");
		p.set(r.x1(), y, r.z1(), "cauldron");                  // 拖把池
		// 晾衣杆 + 晾晒衣物
		for (int x = r.x0() + 1; x <= r.x1() - 1; x++) {
			p.add(x, ceilY - 1, r.z0(), "iron_chain", Map.of("axis", "x"));
		}
		p.add(r.x0() + 1, y, r.z0(), "blue_banner", Map.of("rotation", "8"));
		if (r.w() >= 5) {
			p.add(r.x1() - 1, y, r.z0(), "red_banner", Map.of("rotation", "8"));
		}
		p.add(r.x1(), y, r.z0(), "potted_fern");
	}

	private static void shop(BuildingPlan p, Layout.Rect r, int y, int ceilY, BuildingSpec.Pal pal) {
		for (int x = r.x0(); x <= r.x1(); x++) {
			if (x == (r.x0() + r.x1()) / 2) {
				continue; // 留出入口
			}
			p.set(x, y, r.z1(), "smooth_stone");
		}
		p.set(r.x0(), y, r.z1() - 1, "barrel", Map.of("facing", "up"));
	}

	private static void pool(BuildingPlan p, Layout.Rect r, int floorY) {
		for (int x = r.x0() + 1; x < r.x1(); x++) {
			for (int z = r.z0() + 1; z < r.z1(); z++) {
				p.set(x, floorY, z, "water_cauldron");
			}
		}
	}

	private static void courtyard(BuildingPlan p, Layout.Rect r, int floorY, BuildingSpec.Pal pal) {
		// 庭院：铺地 + 绿植点缀（不盖屋顶）
		for (int x = r.x0(); x <= r.x1(); x++) {
			for (int z = r.z0(); z <= r.z1(); z++) {
				p.set(x, floorY, z, "mossy_stone_bricks");
			}
		}
		for (int x = r.x0() + 1; x < r.x1(); x += 2) {
			for (int z = r.z0() + 1; z < r.z1(); z += 2) {
				p.set(x, floorY + 1, z, "potted_fern");
			}
		}
	}

	private static void garden(BuildingPlan p, Layout.Rect r, int floorY) {
		for (int x = r.x0(); x <= r.x1(); x++) {
			for (int z = r.z0(); z <= r.z1(); z++) {
				p.set(x, floorY, z, "grass_block");
			}
		}
		for (int x = r.x0() + 1; x < r.x1(); x += 3) {
			p.set(x, floorY + 1, r.z0() + 1, "oak_leaves", Map.of("persistent", "true"));
		}
	}

	private static void generic(BuildingPlan p, Layout.Rect r, int y, int ceilY, BuildingSpec.Pal pal) {
		if (r.w() >= 4 && r.d() >= 4) {
			p.set(r.x0(), y, r.z0(), "bookshelf");
			p.set(r.x1(), y, r.z1(), "barrel", Map.of("facing", "up"));
		}
		plant(p, r, y, pal);
	}

	private static void plant(BuildingPlan p, Layout.Rect r, int y, BuildingSpec.Pal pal) {
		int gx = r.x1();
		int gz = r.z0();
		if (p.get(gx, y, gz) == null) {
			p.add(gx, y, gz, "potted_fern");
		}
	}
}
