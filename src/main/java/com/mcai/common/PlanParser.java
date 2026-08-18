package com.mcai.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 AI 回复中提取蓝图 JSON 并解析为 PlanSpec。
 * 容错：markdown 代码块、尺寸不一致、外墙不闭合、行宽不齐。
 * 归一化后的蓝图统一补齐为 maxW x maxD，边缘补墙保证闭合。
 */
public class PlanParser {
	private static final Gson GSON = new GsonBuilder().create();

	/** 归一化后的蓝图：每层 = 行数组，每行等长（maxW），总行数 maxD。
	 * 顶层蓝图若比其他层多画一行，多出的行作为屋顶装饰行（ceilingRow）单独存放。 */
	public record Blueprint(List<String> rows, String ceilingRow) {
		public Blueprint(List<String> rows) {
			this(rows, null);
		}

		public int width() {
			return rows.isEmpty() ? 0 : rows.get(0).length();
		}

		public int depth() {
			return rows.size();
		}
	}

	public static PlanSpec parse(String aiReply) throws IllegalArgumentException {
		if (aiReply == null || aiReply.isBlank()) {
			throw new IllegalArgumentException("AI 返回为空");
		}
		String json = extractJson(aiReply);
		PlanSpec spec;
		try {
			spec = GSON.fromJson(json, PlanSpec.class);
		} catch (JsonSyntaxException e) {
			throw new IllegalArgumentException("无法解析 AI 返回的方案 JSON: " + e.getMessage(), e);
		}
		if (spec == null) {
			throw new IllegalArgumentException("方案格式不完整");
		}
		validate(spec);
		return spec;
	}

	private static String extractJson(String text) {
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start < 0 || end <= start) {
			throw new IllegalArgumentException("AI 返回中没有找到 JSON 对象");
		}
		return text.substring(start, end + 1);
	}

	private static void validate(PlanSpec spec) {
		if (spec.name == null || spec.name.isBlank()) {
			spec.name = "建筑";
		}
		spec.floors = spec.floors == null ? 1 : Math.max(1, Math.min(30, spec.floors));
		spec.layerHeight = spec.layerHeight == null ? 3 : Math.max(2, Math.min(8, spec.layerHeight));
		if (spec.wall == null || spec.wall.isBlank()) {
			spec.wall = "oak_planks";
		}
		if (spec.accent == null || spec.accent.isBlank()) {
			spec.accent = "dark_oak_log";
		}
		if (spec.roof == null || spec.roof.isBlank()) {
			spec.roof = "gabled";
		}
		if (spec.floorsMap == null || spec.floorsMap.isEmpty()) {
			throw new IllegalArgumentException("AI 没有提供楼层蓝图（floors_map 缺失）");
		}
		// repeat 容错：AI 可能写 "2-4" / "2" / null
		if (spec.repeat != null) {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(spec.repeat);
			int r = m.find() ? Integer.parseInt(m.group()) : 1;
			spec.repeat = String.valueOf(Math.max(1, r));
		}
	}

	/** 取第 k 层（0 起）的原始字符画行数组。
	 * 优先顺序：
	 * 1. 精确单层 key（"1"/"3"/"20"）：只有该层用它
	 * 2. 区间 key（"2-19"）：区间内楼层用它
	 * 3. 固定模板：首层 "1"、第 2 层可选 "3"（特殊层）、顶层 "top"、其余 "2" 标准层
	 * 4. 旧协议 repeat 字段兼容
	 * 未命中任何 key 的层退回首层蓝图。 */
	public static List<String> rawLayer(PlanSpec spec, int k, int floors) {
		int layerNo = k + 1;
		// 1. 精确单层 key
		List<String> exact = spec.floorsMap.get(String.valueOf(layerNo));
		if (exact != null) {
			return exact;
		}
		// 2. 区间 key "a-b"（含两端）
		for (Map.Entry<String, List<String>> e : spec.floorsMap.entrySet()) {
			String rk = e.getKey();
			if (rk == null || rk.isBlank() || "top".equals(rk)) {
				continue;
			}
			java.util.regex.Matcher m = RANGE.matcher(rk.trim());
			if (m.matches()) {
				int a = Integer.parseInt(m.group(1));
				int b = Integer.parseInt(m.group(2));
				if (a > b) {
					int t = a;
					a = b;
					b = t;
				}
				if (layerNo >= a && layerNo <= b) {
					return e.getValue();
				}
			}
		}
		// 3. 固定模板
		if (k == 0) {
			List<String> f = spec.floorsMap.get("1");
			if (f != null) {
				return f;
			}
		}
		if (k == 1) {
			List<String> s3 = spec.floorsMap.get("3");
			if (s3 != null) {
				return s3;
			}
		}
		if (k == floors - 1) {
			List<String> top = spec.floorsMap.get("top");
			if (top != null) {
				return top;
			}
		}
		List<String> second = spec.floorsMap.get("2");
		if (second != null) {
			return second;
		}
		// 4. 旧协议 repeat 兼容
		List<String> first = spec.floorsMap.get("1");
		if (first != null && k == 0) {
			return first;
		}
		int rep = 1;
		if (spec.repeat != null) {
			try {
				rep = Integer.parseInt(spec.repeat);
			} catch (NumberFormatException ignored) {
			}
		}
		if (k + 1 >= rep) {
			List<String> s2 = spec.floorsMap.get("2");
			if (s2 != null) {
				return s2;
			}
		}
		if (first != null) {
			return first;
		}
		if (!spec.floorsMap.isEmpty()) {
			return spec.floorsMap.values().iterator().next();
		}
		return List.of();
	}

	/** 强制修正楼层数：AI 常把模板图数量误解为楼层数而缩水。
	 * 玩家描述中明确提到层数时（如"20 层"），以此为准，只升不降。 */
	public static void applyFloorHint(PlanSpec spec, Integer floorHint) {
		if (floorHint != null && floorHint >= 1 && spec.floors < floorHint) {
			spec.floors = Math.min(30, floorHint);
		}
	}

	private static final java.util.regex.Pattern RANGE = java.util.regex.Pattern.compile("(\\d+)-(\\d+)");

	/**
	 * 归一化所有层蓝图：统一尺寸（各层取最大行宽/行数），
	 * 短行右侧补墙，边缘（首尾行/列）非门窗柱字符补墙保证闭合。
	 */
	public static List<Blueprint> normalize(PlanSpec spec) {
		List<Blueprint> out = new ArrayList<>();
		int floors = spec.floors;
		List<List<String>> raws = new ArrayList<>();
		String ceiling = null;
		List<Integer> allLens = new ArrayList<>();
		int maxD = 0;
		// 先归一化普通层（不含顶层）取基准行数
		for (int k = 0; k < floors - 1; k++) {
			List<String> rows = rawLayer(spec, k, floors);
			raws.add(rows);
			if (rows != null) {
				for (String r : rows) {
					allLens.add(r.replace(" ", "").length());
				}
				maxD = Math.max(maxD, rows.size());
			}
		}
		// 顶层：若比其他层多画了一行，多出的行是屋顶装饰行（天花板层）
		List<String> topRows = rawLayer(spec, floors - 1, floors);
		if (topRows != null && maxD > 0 && topRows.size() == maxD + 1) {
			ceiling = topRows.get(topRows.size() - 1);
			topRows = topRows.subList(0, maxD);
		}
		raws.add(topRows);
		if (topRows != null) {
			for (String r : topRows) {
				allLens.add(r.replace(" ", "").length());
			}
			maxD = Math.max(maxD, topRows.size());
		}
		if (allLens.isEmpty() || maxD == 0) {
			throw new IllegalArgumentException("蓝图内容为空");
		}
		// 行宽容错：AI 有时空格排版不一致（部分行去空格后仍很长）。
		// 取去空格后长度的中位数；若最长行明显偏离（>1.3 倍中位），按中位数截断补齐。
		allLens.sort(Integer::compareTo);
		int median = allLens.get(allLens.size() / 2);
		int maxW = allLens.get(allLens.size() - 1);
		int targetW = maxW > median * 1.3 ? median : maxW;
		targetW = Math.max(4, Math.min(targetW, 64));
		maxD = Math.max(4, Math.min(maxD, 64));
		for (int k = 0; k < raws.size(); k++) {
			List<String> rows = raws.get(k);
			List<String> norm = new ArrayList<>();
			for (int z = 0; z < maxD; z++) {
				String src = (rows != null && z < rows.size()) ? rows.get(z) : "";
				// 去掉 AI 常加的格间空格（"# . W ." -> "#.W."）
				String cleaned = src.replace(" ", "").replace("\t", "");
				StringBuilder sb = new StringBuilder();
				for (int x = 0; x < targetW; x++) {
					char c = x < cleaned.length() ? cleaned.charAt(x) : '#';
					if (x == 0 || x == targetW - 1 || z == 0 || z == maxD - 1) {
						if (!isOpening(c)) {
							c = '#';
						}
					}
					sb.append(c);
				}
				norm.add(sb.toString());
			}
			boolean isTop = k == raws.size() - 1;
			out.add(new Blueprint(norm, isTop ? ceiling : null));
		}
		return out;
	}

	/** 允许出现在外墙边缘的字符（门窗柱开口） */
	private static boolean isOpening(char c) {
		return c == '#' || c == 'W' || c == 'D' || c == 'P';
	}
}