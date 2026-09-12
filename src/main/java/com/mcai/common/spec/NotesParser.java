package com.mcai.common.spec;

import java.util.ArrayList;
import java.util.List;

// 把规格里的 notes（AI 写的自由文字细节）解析成真正生效的字段。
// 为什么需要：AI 面对"加阳台/坡屋顶/外墙换灰色"这类要求时，常常只把话写进 notes 而不动结构化字段；
// 之前程序不读 notes，结果就是玩家说的"改了跟没改一样"。这里用一组确定性的关键词规则把它落实。
public final class NotesParser {

	private NotesParser() {
	}

	private static final String[][] COLORS = {
			{ "白色", "white_concrete" }, { "米白", "white_concrete" }, { "奶油", "white_concrete" },
			{ "浅灰", "light_gray_concrete" }, { "灰色", "gray_concrete" }, { "深灰", "gray_concrete" },
			{ "砖红", "brown_terracotta" }, { "棕红", "brown_terracotta" }, { "红砖", "brick" },
			{ "木色", "oak_planks" }, { "深木", "dark_oak_planks" }, { "原木", "oak_log" },
			{ "石砖", "stone_bricks" }, { "石材", "polished_andesite" },
	};

	/** 解析并应用；返回"实际做了哪些改动"（空表示没识别到可落实的要求） */
	public static List<String> apply(BuildingSpec s) {
		List<String> done = new ArrayList<>();
		StringBuilder sb = new StringBuilder();
		if (s.notes != null) {
			sb.append(s.notes).append(' ');
		}
		if (s.features != null) {
			for (String f : s.features) {
				sb.append(f).append(' ');
			}
		}
		String text = sb.toString();
		if (text.isBlank()) {
			return done;
		}

		if (contains(text, "坡顶", "坡屋顶", "尖顶", "瓦顶", "坡屋面")) {
			s.roof.style = "gabled";
			done.add("屋顶→坡屋顶");
		}
		if (contains(text, "金字塔顶")) {
			s.roof.style = "pyramid";
			done.add("屋顶→金字塔顶");
		}
		if (contains(text, "平顶", "平屋面")) {
			s.roof.style = "flat";
			done.add("屋顶→平屋顶");
		}
		if (contains(text, "屋顶花园", "楼顶花园", "天台花园")) {
			s.roof.garden = Boolean.TRUE;
			done.add("屋顶花园");
		}
		if (contains(text, "太阳能")) {
			s.roof.solar = Boolean.TRUE;
			done.add("屋顶太阳能");
		}
		if (contains(text, "水箱")) {
			s.roof.waterTank = Boolean.TRUE;
			done.add("屋顶水箱");
		}
		if (contains(text, "电梯")) {
			addFeature(s, "elevator");
			done.add("电梯");
		}
		if (contains(text, "楼梯")) {
			addFeature(s, "stairs");
			done.add("楼梯");
		}
		if (contains(text, "阳台")) {
			addFeature(s, "balcony");
			done.add("阳台");
		}
		if (contains(text, "庭院", "院子", "内院")) {
			addFeature(s, "courtyard");
			done.add("庭院");
		}
		if (contains(text, "水池", "泳池", "池塘")) {
			addFeature(s, "pool");
			done.add("水池");
		}
		if (contains(text, "屋顶设备", "机房")) {
			addFeature(s, "roof_equipment");
			done.add("屋顶设备");
		}

		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("(\\d{1,2})\\s*(?:楼)?层|层数\\s*[:：=]?\\s*(\\d{1,2})").matcher(text);
		if (m.find()) {
			String v = m.group(1) != null ? m.group(1) : m.group(2);
			try {
				int f = Integer.parseInt(v);
				if (f >= 1 && f <= 60 && (s.floors == null || s.floors != f)) {
					s.floors = f;
					done.add("层数→" + f);
				}
			} catch (NumberFormatException ignored) {
				// 忽略无法解析的数字
			}
		}

		if (s.materials == null || !s.materials.containsKey("wall")) {
			for (String[] c : COLORS) {
				if (text.contains(c[0])) {
					s.materials.put("wall", c[1]);
					done.add("外墙材质→" + c[1]);
					break;
				}
			}
		}
		return done;
	}

	private static boolean contains(String text, String... keys) {
		for (String k : keys) {
			if (text.contains(k)) {
				return true;
			}
		}
		return false;
	}

	private static void addFeature(BuildingSpec s, String f) {
		if (s.features == null) {
			s.features = new ArrayList<>();
		}
		if (!s.features.contains(f)) {
			s.features.add(f);
		}
	}
}
