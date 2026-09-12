package com.mcai.common.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1.0 新契约：AI 输出"设计规格"（DSL），而不是逐方块清单、也不是 8~24 的字符画蓝图。
 *
 * 设计原则：
 *   1. 模型只说"要什么"（层数、层高、户型、房间、材质、屋顶构件），
 *      具体每块砖由本地参数化构件库确定性地展开 —— 这是"听话"和"不单调"的关键；
 *   2. 字段全部可缺省：只给 floors 也能盖出一栋合理的楼（按 archetype 默认模板）；
 *   3. 尺寸缺省时自动贴合玩家框选的范围，不越界。
 */
public class BuildingSpec {
	public int specVersion = 2;
	public String name = "AI 建筑";
	/** 见 Archetypes：chinese_highrise / chinese_courtyard / modern_villa / castle / generic */
	public String archetype = "generic";
	public Integer floors;
	public Integer layerHeight;
	public Integer unitsPerFloor;
	/** [宽, 进深]，缺省 = 玩家框选范围 */
	public List<Integer> size;
	public List<String> features = new ArrayList<>();
	public Map<String, String> materials = new LinkedHashMap<>();
	/** 单户（或整层）房间表；为空则按 archetype 默认户型 / 自动分隔 */
	public List<RoomSpec> rooms = new ArrayList<>();
	public RoofSpec roof = new RoofSpec();
	public String notes = "";

	public static class RoomSpec {
		public String type = "room";
		public int x;
		public int z;
		public int w;
		public int d;
		/** auto / none / true */
		public String furnish = "auto";
	}

	public static class RoofSpec {
		public String style = "auto";   // auto / flat / pyramid / gabled
		public Boolean parapet;
		public Boolean machineRoom;
		public Boolean waterTank;
		public Boolean solar;
		public Boolean lightningRod;
		public Boolean garden;
	}

	public boolean has(String feature) {
		return features != null && features.contains(feature);
	}

	public String mat(String key, String def) {
		if (materials == null) {
			return def;
		}
		String v = materials.get(key);
		return v == null || v.isBlank() ? def : v;
	}

	/** 材质表：不同原型给不同默认值，模型可以用 materials 覆盖任意一项 */
	public static final class Pal {
		public String wall;
		public String accent;
		public String glass;
		public String frame;
		public String base;
		public String floorLiving;
		public String floorWet;
		public String floorPublic;
		public String ceiling;
		public String roofDeck;
		public String band;
		public String rail;
		public String wood;
		public String stone;
		public String lightBlock;

		public static Pal of(BuildingSpec s) {
			Pal p = new Pal();
			String arch = s.archetype == null ? "generic" : s.archetype;
			switch (arch) {
				case "chinese_highrise" -> {
					p.wall = "white_concrete";
					p.accent = "brown_terracotta";
					p.glass = "glass";
					p.frame = "light_gray_concrete";
					p.base = "polished_andesite";
					p.floorLiving = "birch_planks";
					p.floorWet = "polished_diorite";
					p.floorPublic = "polished_andesite";
					p.ceiling = "white_concrete";
					p.roofDeck = "light_gray_concrete";
					p.band = "brown_terracotta";
					p.rail = "iron_bars";
					p.wood = "birch_planks";
					p.stone = "smooth_stone";
				}
				case "chinese_courtyard" -> {
					p.wall = "white_concrete";
					p.accent = "dark_oak_planks";
					p.glass = "glass";
					p.frame = "dark_oak_planks";
					p.base = "stone_bricks";
					p.floorLiving = "dark_oak_planks";
					p.floorWet = "polished_diorite";
					p.floorPublic = "stone_bricks";
					p.ceiling = "white_concrete";
					p.roofDeck = "brown_terracotta";
					p.band = "red_terracotta";
					p.rail = "iron_bars";
					p.wood = "dark_oak_planks";
					p.stone = "stone_bricks";
				}
				case "castle" -> {
					p.wall = "stone_bricks";
					p.accent = "dark_oak_planks";
					p.glass = "glass_pane";
					p.frame = "stone_bricks";
					p.base = "cobblestone";
					p.floorLiving = "dark_oak_planks";
					p.floorWet = "stone_bricks";
					p.floorPublic = "stone_bricks";
					p.ceiling = "stone_bricks";
					p.roofDeck = "dark_oak_planks";
					p.band = "stone_bricks";
					p.rail = "iron_bars";
					p.wood = "dark_oak_planks";
					p.stone = "stone_bricks";
				}
				case "modern_villa" -> {
					p.wall = "white_concrete";
					p.accent = "gray_concrete";
					p.glass = "glass";
					p.frame = "light_gray_concrete";
					p.base = "polished_andesite";
					p.floorLiving = "birch_planks";
					p.floorWet = "polished_diorite";
					p.floorPublic = "polished_andesite";
					p.ceiling = "white_concrete";
					p.roofDeck = "light_gray_concrete";
					p.band = "gray_concrete";
					p.rail = "glass_pane";
					p.wood = "birch_planks";
					p.stone = "smooth_stone";
				}
				default -> {
					p.wall = "light_gray_concrete";
					p.accent = "oak_planks";
					p.glass = "glass";
					p.frame = "oak_planks";
					p.base = "stone_bricks";
					p.floorLiving = "oak_planks";
					p.floorWet = "polished_diorite";
					p.floorPublic = "smooth_stone";
					p.ceiling = "white_concrete";
					p.roofDeck = "light_gray_concrete";
					p.band = "oak_planks";
					p.rail = "iron_bars";
					p.wood = "oak_planks";
					p.stone = "smooth_stone";
				}
			}
			p.wall = s.mat("wall", p.wall);
			p.accent = s.mat("accent", p.accent);
			p.glass = s.mat("glass", p.glass);
			p.frame = s.mat("frame", p.frame);
			p.base = s.mat("base", p.base);
			p.floorLiving = s.mat("floor_living", p.floorLiving);
			p.floorWet = s.mat("floor_wet", p.floorWet);
			p.floorPublic = s.mat("floor_public", p.floorPublic);
			p.ceiling = s.mat("ceiling", p.ceiling);
			p.roofDeck = s.mat("roof", p.roofDeck);
			p.band = s.mat("band", p.band);
			p.rail = s.mat("rail", p.rail);
			p.wood = s.mat("wood", p.wood);
			p.stone = s.mat("stone", p.stone);
			return p;
		}
	}
}
