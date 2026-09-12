package com.mcai.common.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 新契约（v1.1.0）解析器：宽容地把 AI 回复的 JSON 读成 {@link BuildingSpec}。
 *
 * - 兼容常见别名（layer_height / layerHeight / floors / floor 等）
 * - 兼容模型多嘴（前后有文字、代码块围栏、注释）
 * - 识别 "旧版字符画蓝图"（含 floors_map）并返回 null，让调用方走旧管线
 */
public final class SpecParser {

	private SpecParser() {
	}

	/** 是否为"旧版蓝图"回复（含 floors_map / 字符画） */
	public static boolean looksLegacy(String raw) {
		if (raw == null) {
			return false;
		}
		String s = raw.toLowerCase();
		return s.contains("floors_map") || s.contains("\"rows\"") || s.contains("layer_height")
				&& !s.contains("\"spec_version\"") && s.contains("\"wall\"") && s.contains("\"floors\"")
				&& !s.contains("\"rooms\"");
	}

	public static BuildingSpec parse(String raw) {
		JsonObject o = extractObject(raw);
		if (o == null) {
			return null;
		}
		BuildingSpec s = new BuildingSpec();
		s.specVersion = intOr(o, "spec_version", intOr(o, "specVersion", 2));
		s.name = strOr(o, "name", "AI 建筑");
		s.archetype = normalizeArchetype(strOr(o, "archetype", strOr(o, "style", "generic")));
		s.floors = intOrNull(o, "floors", "floor", "levels", "层数");
		s.layerHeight = intOrNull(o, "layer_height", "layerHeight", "story_height", "层高");
		s.unitsPerFloor = intOrNull(o, "units_per_floor", "unitsPerFloor", "units", "每层户数");
		s.notes = strOr(o, "notes", strOr(o, "detail", strOr(o, "description", "")));

		// size: [宽, 进深] 或 [宽, 高, 进深] 或 {"width":..,"depth":..}
		JsonElement sizeEl = o.get("size");
		if (sizeEl != null && sizeEl.isJsonArray()) {
			JsonArray a = sizeEl.getAsJsonArray();
			List<Integer> sz = new ArrayList<>();
			for (JsonElement e : a) {
				if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
					sz.add(e.getAsInt());
				}
			}
			if (sz.size() >= 3) {
				s.size = List.of(sz.get(0), sz.get(2));
			} else if (sz.size() == 2) {
				s.size = List.of(sz.get(0), sz.get(1));
			}
		} else if (sizeEl != null && sizeEl.isJsonObject()) {
			JsonObject so = sizeEl.getAsJsonObject();
			int w = intOr(so, "width", intOr(so, "w", 0));
			int d = intOr(so, "depth", intOr(so, "d", 0));
			if (w > 0 && d > 0) {
				s.size = List.of(w, d);
			}
		}

		JsonElement feats = o.get("features");
		if (feats != null && feats.isJsonArray()) {
			for (JsonElement e : feats.getAsJsonArray()) {
				if (e.isJsonPrimitive()) {
					s.features.add(e.getAsString());
				}
			}
		} else if (feats != null && feats.isJsonPrimitive() && feats.getAsJsonPrimitive().isString()) {
			for (String f : feats.getAsString().split("[,，;；\\s]+")) {
				if (!f.isBlank()) {
					s.features.add(f.trim());
				}
			}
		}

		JsonElement mats = o.get("materials");
		if (mats != null && mats.isJsonObject()) {
			for (Map.Entry<String, JsonElement> en : mats.getAsJsonObject().entrySet()) {
				if (en.getValue().isJsonPrimitive()) {
					s.materials.put(en.getKey(), en.getValue().getAsString());
				}
			}
		}
		// 也接受顶层直接写 wall/accent/roof（兼容旧习惯）
		for (String k : new String[] { "wall", "accent", "glass", "frame", "base", "roof", "band", "rail", "wood", "stone" }) {
			JsonElement e = o.get(k);
			if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
				s.materials.putIfAbsent(k, e.getAsString());
			}
		}

		JsonElement rooms = o.get("rooms");
		if (rooms != null && rooms.isJsonArray()) {
			for (JsonElement e : rooms.getAsJsonArray()) {
				if (!e.isJsonObject()) {
					continue;
				}
				JsonObject ro = e.getAsJsonObject();
				BuildingSpec.RoomSpec rs = new BuildingSpec.RoomSpec();
				rs.type = strOr(ro, "type", strOr(ro, "kind", "room"));
				rs.x = intOr(ro, "x", 0);
				rs.z = intOr(ro, "z", 0);
				rs.w = intOr(ro, "w", intOr(ro, "width", 0));
				rs.d = intOr(ro, "d", intOr(ro, "depth", 0));
				rs.furnish = strOr(ro, "furnish", "auto");
				if (rs.w > 0 && rs.d > 0) {
					s.rooms.add(rs);
				}
			}
		}

		JsonElement roof = o.get("roof");
		if (roof != null && roof.isJsonObject()) {
			JsonObject r = roof.getAsJsonObject();
			s.roof.style = strOr(r, "style", "auto");
			s.roof.parapet = boolOrNull(r, "parapet");
			s.roof.machineRoom = boolOrNull(r, "machine_room", "machineRoom");
			s.roof.waterTank = boolOrNull(r, "water_tank", "waterTank");
			s.roof.solar = boolOrNull(r, "solar");
			s.roof.lightningRod = boolOrNull(r, "lightning_rod", "lightningRod");
			s.roof.garden = boolOrNull(r, "garden", "roof_garden");
		} else if (roof != null && roof.isJsonPrimitive() && roof.getAsJsonPrimitive().isString()) {
			// "roof": "gabled" 这种简写
			s.roof.style = roof.getAsString();
		}

		normalize(s);
		return s;
	}

	/** 取值域修正：层数/层高/尺寸给一个合理范围，避免模型乱写把游戏搞崩 */
	public static void normalize(BuildingSpec s) {
		if (s.floors == null || s.floors < 1) {
			s.floors = null;          // 交给 Builder 用原型默认值
		} else {
			s.floors = Math.min(s.floors, 60);
		}
		if (s.layerHeight == null || s.layerHeight < 3) {
			s.layerHeight = null;     // 默认按原型（一般 4：1 楼板 + 3 净高）
		} else {
			s.layerHeight = Math.min(s.layerHeight, 8);
		}
		if (s.size != null) {
			int w = Math.max(9, Math.min(96, s.size.get(0)));
			int d = Math.max(9, Math.min(96, s.size.get(1)));
			s.size = List.of(w, d);
		}
		if (s.unitsPerFloor != null) {
			s.unitsPerFloor = Math.max(1, Math.min(8, s.unitsPerFloor));
		}
		if (s.archetype == null || s.archetype.isBlank()) {
			s.archetype = "generic";
		}
	}

	private static String normalizeArchetype(String raw) {
		if (raw == null) {
			return "generic";
		}
		String s = raw.toLowerCase().trim();
		if (s.contains("high") || s.contains("公寓") || s.contains("居民") || s.contains("住宅") || s.contains("apartment")) {
			return "chinese_highrise";
		}
		if (s.contains("courtyard") || s.contains("四合") || s.contains("院")) {
			return "chinese_courtyard";
		}
		if (s.contains("villa") || s.contains("别墅")) {
			return "modern_villa";
		}
		if (s.contains("castle") || s.contains("城堡")) {
			return "castle";
		}
		if (s.contains("chinese") || s.contains("中式")) {
			return "chinese_courtyard";
		}
		return "generic";
	}

	/** 从 AI 回复里抠出最外层 JSON 对象 */
	public static JsonObject extractObject(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.trim();
		int fence = s.indexOf("```");
		if (fence >= 0) {
			int nl = s.indexOf('\n', fence);
			int end = s.indexOf("```", nl + 1);
			if (nl > 0 && end > nl) {
				s = s.substring(nl + 1, end).trim();
			}
		}
		int start = s.indexOf('{');
		int last = s.lastIndexOf('}');
		if (start < 0 || last <= start) {
			return null;
		}
		String cand = s.substring(start, last + 1);
		try {
			JsonElement e = JsonParser.parseString(cand);
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (Exception ex) {
			// 再试一次：去掉尾随逗号
			try {
				JsonElement e = JsonParser.parseString(cand.replaceAll(",\\s*([}\\]])", "$1"));
				return e.isJsonObject() ? e.getAsJsonObject() : null;
			} catch (Exception ex2) {
				return null;
			}
		}
	}

	private static String strOr(JsonObject o, String k, String def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
	}

	private static Integer intOrNull(JsonObject o, String... keys) {
		for (String k : keys) {
			JsonElement e = o.get(k);
			if (e != null && e.isJsonPrimitive()) {
				try {
					return e.getAsInt();
				} catch (Exception ignored) {
					// 继续找下一个别名
				}
			}
		}
		return null;
	}

	private static int intOr(JsonObject o, String k, int def) {
		Integer v = intOrNull(o, k);
		return v == null ? def : v;
	}

	private static Boolean boolOrNull(JsonObject o, String... keys) {
		for (String k : keys) {
			JsonElement e = o.get(k);
			if (e != null && e.isJsonPrimitive()) {
				try {
					return e.getAsBoolean();
				} catch (Exception ignored) {
					// 继续找下一个别名
				}
			}
		}
		return null;
	}
}
