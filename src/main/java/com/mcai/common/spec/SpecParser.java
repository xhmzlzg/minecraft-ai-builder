package com.mcai.common.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 契约解析器：宽容地把 AI 回复的 JSON 读成 {@link BuildingSpec}（= 一份 {@link FreeformSpec}）。
 *
 * <p>容错要点（每条都是玩家实际踩过的坑，改动前务必保留）：
 * <ul>
 *   <li>兼容常见别名（size 的三种写法、block/material/id、props/state/blockstate…）</li>
 *   <li>兼容模型多嘴（前后有文字、代码块围栏、JSON 后面接着写说明、尾随逗号）</li>
 *   <li>兼容模型按 Minecraft 命令的写法把 blockstate 嵌在方块名里
 *       （{@code "oak_stairs[facing=east]"} / {@code "minecraft:oak_planks"}）——
 *       绝不能让它落到"非法方块"被整块丢弃，那会让方案缺料甚至变成 0 个方块</li>
 *   <li>多个 layer 都不写 y 时逐层递增，而不是全叠在 y=0 互相覆盖</li>
 * </ul>
 *
 * <p>模板已全部删除：不论 AI 写 kind="freeform" 还是（沿用旧习惯的）kind="building"，
 * 这里一律按自由形体解析 —— 程序不再提供任何模板。
 */
public final class SpecParser {

	private SpecParser() {
	}

	/**
	 * 抠出来的 JSON 对象是不是"一份可用的规格"（而不是模型思考里的 op 草稿 / 无关片段）。
	 *
	 * <p>为什么必须有这道闸：{@link #extractObject} 会从整段回复里抠出**第一个括号配对的对象**，
	 * 而思考型模型的 reasoning 里常常写着 {"op":"layer",...} 这种草稿。以前这里照单全收，
	 * 用一个"什么字段都没有"的对象构造出全默认的规格（kind=building / floors=null →
	 * 会落到默认的住宅楼），结果"解析失败"被伪装成"解析成功"，
	 * 绕开全部纠正逻辑，把玩家要的雕塑盖成二层小楼。
	 * 返回 null 才能让上层走到"回复不可用"的正确分支。
	 *
	 * <p>注意：这里**只认自由形体相关的字段**。旧版字符画蓝图（含 floors_map）与
	 * 建筑规格（archetype/floors/rooms）都不再算"可用的规格"——
	 * 它们会让 {@link #parse} 返回 null，上层据此明确要求模型改用 freeform 重画。
	 */
	private static boolean looksLikeSpec(JsonObject o) {
		// 单个 op 对象（{"op":"layer","rows":[...]}）一定不是规格 —— 它只是规格里的一条指令。
		if (o.has("op") && !o.has("ops")) {
			return false;
		}
		for (String k : new String[] { "spec_version", "specVersion", "kind", "mode",
				"ops", "shapes", "parts", "voxels",
				"palette", "chars", "blocks", "layers", "size", "mirror" }) {
			if (o.has(k)) {
				return true;
			}
		}
		return false;
	}

	public static BuildingSpec parse(String raw) {
		JsonObject o = extractObject(raw);
		if (o == null) {
			return null;
		}
		if (!looksLikeSpec(o)) {
			return null;
		}
		BuildingSpec s = new BuildingSpec();
		s.specVersion = intOr(o, "spec_version", intOr(o, "specVersion", 3));
		s.name = strOr(o, "name", "AI 作品");
		// ★ 模板已全部删除：AI 写 kind="building" / archetype=... 时**直接当成 freeform**
		//   （不报错、不特判）—— 它多半只是沿用了旧提示词的习惯写法。
		//   真正的判断标准是"它有没有画出 ops"：没画出来就会展开成 0 个方块，
		//   由上层带着原因要求模型重画，绝不静默兜底盖楼。
		s.kind = "freeform";
		s.freeform = parseFreeform(o);

		// size：[宽, 进深] 或 [宽, 高, 进深] 或 {"width":..,"depth":..}
		List<Integer> sz = sizeNumbers(o);
		if (sz.size() >= 3) {
			s.freeform.size = List.of(sz.get(0), sz.get(1), sz.get(2));
		} else if (sz.size() == 2) {
			s.freeform.size = List.of(sz.get(0), sz.get(1));
		}
		return s;
	}

	/** 解析自由形体契约（宽容：ops / shapes / layers 都认） */
	private static FreeformSpec parseFreeform(JsonObject o) {
		FreeformSpec f = new FreeformSpec();
		JsonElement pal = o.get("palette");
		if (pal == null) {
			pal = o.get("chars");
		}
		if (pal == null) {
			pal = o.get("blocks");
		}
		if (pal != null && pal.isJsonObject()) {
			for (Map.Entry<String, JsonElement> en : pal.getAsJsonObject().entrySet()) {
				if (en.getValue().isJsonPrimitive()) {
					// 字符表里也可能嵌 blockstate：{"S":"oak_stairs[facing=east]"}
					// 直接存会把整块丢掉，所以拆成 id + 属性分开存。
					Map<String, String> ps = new LinkedHashMap<>();
					String id = splitState(en.getValue().getAsString(), ps);
					f.palette.put(en.getKey(), id);
					if (!ps.isEmpty()) {
						f.paletteProps.put(en.getKey(), ps);
					}
				}
			}
		}
		f.mirror = strOr(o, "mirror", strOr(o, "symmetry", strOr(o, "mirror_axis", "none")));
		f.notes = strOr(o, "notes", "");

		JsonElement ops = o.get("ops");
		if (ops == null) {
			ops = o.get("shapes");
		}
		if (ops == null) {
			ops = o.get("parts");
		}
		if (ops != null && ops.isJsonArray()) {
			int autoY = 0;
			for (JsonElement e : ops.getAsJsonArray()) {
				if (e.isJsonObject()) {
					FreeformSpec.Op op = parseOp(e.getAsJsonObject(), null);
					// 多个 layer 都不写 y 时会全部叠在 y=0 上：后面的把前面的整层覆盖掉，
					// 最后只剩一层（模型写"逐层描述"却忘了 y 是很常见的事）。
					// 按"逐层往上"的直觉给缺 y 的 layer 递增分配。
					if (op.y == null && isLayerOp(op.op)) {
						op.y = autoY;
					}
					if (op.y != null) {
						autoY = op.y + 1;
					}
					f.ops.add(op);
				} else if (e.isJsonArray()) {
					// 简写：[["...","..."], ...] = 第 n 层
					f.ops.add(layerOp(e.getAsJsonArray(), autoY++));
				}
			}
		}
		// 另一种常见写法：{"layers": [ {...}, [...] ]}
		JsonElement layers = o.get("layers");
		if (layers != null && layers.isJsonArray()) {
			int idx = 0;
			for (JsonElement e : layers.getAsJsonArray()) {
				if (e.isJsonObject()) {
					f.ops.add(parseOp(e.getAsJsonObject(), idx));
				} else if (e.isJsonArray()) {
					f.ops.add(layerOp(e.getAsJsonArray(), idx));
				}
				idx++;
			}
		}
		return f;
	}

	/** 是不是"分层字符画"类的 op（layer/layers/plane/row 在展开器里走同一条路） */
	private static boolean isLayerOp(String op) {
		if (op == null) {
			return false;
		}
		String k = op.toLowerCase().trim();
		return k.equals("layer") || k.equals("layers") || k.equals("plane") || k.equals("row");
	}

	private static FreeformSpec.Op layerOp(JsonArray rows, int defaultY) {
		FreeformSpec.Op op = new FreeformSpec.Op();
		op.op = "layer";
		op.y = defaultY;
		op.rows = new ArrayList<>();
		for (JsonElement r : rows) {
			if (r.isJsonPrimitive()) {
				op.rows.add(r.getAsString());
			}
		}
		return op;
	}

	private static FreeformSpec.Op parseOp(JsonObject o, Integer defaultY) {
		FreeformSpec.Op op = new FreeformSpec.Op();
		op.op = strOr(o, "op", strOr(o, "type", strOr(o, "kind", "")));
		if (op.op.isBlank()) {
			op.op = o.has("rows") ? "layer" : (o.has("from") || o.has("to") ? "box" : "");
		}
		op.y = intOrNull(o, "y");
		if (op.y == null) {
			op.y = defaultY;
		}
		op.y0 = intOrNull(o, "y0", "from_y");
		op.y1 = intOrNull(o, "y1", "to_y");
		op.x = intOrNull(o, "x", "cx");
		op.z = intOrNull(o, "z", "cz");
		op.r = intOrNull(o, "r", "radius");
		op.rx = intOrNull(o, "rx");
		op.ry = intOrNull(o, "ry");
		op.rz = intOrNull(o, "rz");
		op.block = strOr(o, "block", strOr(o, "material", strOr(o, "id", "")));
		op.props = propsOf(o);
		// 模型经常按 Minecraft 命令的写法把 blockstate 直接嵌在方块名里
		// （"oak_stairs[facing=east]"）—— 那不是合法方块 id，展开器的 sanitize() 会整块丢弃，
		// 严重时整个方案变成 0 个方块。这里先拆成 id + 属性。
		op.block = splitState(op.block, op.props);
		op.from = intArray(o.get("from"));
		op.to = intArray(o.get("to"));
		if (op.from == null) {
			op.from = intArray(o.get("start"));
		}
		if (op.to == null) {
			op.to = intArray(o.get("end"));
		}
		JsonElement h = o.get("hollow");
		if (h != null && h.isJsonPrimitive()) {
			try {
				op.hollow = h.getAsBoolean();
			} catch (Exception ignored) {
				// 非布尔值就当没写
			}
		}
		op.rows = new ArrayList<>();
		JsonElement rows = o.get("rows");
		if (rows == null) {
			rows = o.get("map");
		}
		if (rows != null && rows.isJsonArray()) {
			for (JsonElement r : rows.getAsJsonArray()) {
				if (r.isJsonPrimitive()) {
					op.rows.add(r.getAsString());
				}
			}
		}
		return op;
	}

	/**
	 * 把 {@code "oak_stairs[facing=east,half=bottom]"} 拆成方块 id + 属性表。
	 *
	 * <p>为什么必须拆：模型大量按 Minecraft 命令的写法把 blockstate 嵌在方块名里，
	 * 而 {@code "oak_stairs[facing=east]"} 不是合法方块 id —— 展开器的 sanitize()
	 * 只认 {@code [a-z0-9_]+}，会把整块当成"非法方块"丢弃。模型整层这么写时，
	 * 玩家会拿到一个 0 方块的方案（面板上还看不出原因）。
	 *
	 * @param props 拆出来的属性写进这里；已存在的键不覆盖（显式写在 props 字段里的更明确）
	 * @return 去掉中括号部分并 trim 过的方块 id（原本就不带中括号时原样返回）
	 */
	private static String splitState(String raw, Map<String, String> props) {
		if (raw == null) {
			return "";
		}
		String s = raw.trim();
		int br = s.indexOf('[');
		if (br < 0 || !s.endsWith("]")) {
			return s;
		}
		String state = s.substring(br + 1, s.length() - 1);
		String id = s.substring(0, br).trim();
		if (props != null) {
			for (String kv : state.split("[,;]")) {
				int eq = kv.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				String k = kv.substring(0, eq).trim().toLowerCase();
				String v = kv.substring(eq + 1).trim().toLowerCase();
				if (!k.isEmpty() && !v.isEmpty()) {
					props.putIfAbsent(k, v);
				}
			}
		}
		return id;
	}

	/** op 的 blockstate 属性表（props / state / blockstate 都认；值统一转成字符串） */
	private static Map<String, String> propsOf(JsonObject o) {
		JsonElement e = o.get("props");
		if (e == null) {
			e = o.get("state");
		}
		if (e == null) {
			e = o.get("blockstate");
		}
		Map<String, String> m = new LinkedHashMap<>();
		if (e != null && e.isJsonObject()) {
			for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
				JsonElement v = en.getValue();
				if (v != null && v.isJsonPrimitive()) {
					m.put(en.getKey(), v.getAsString());
				}
			}
		}
		return m;
	}

	private static int[] intArray(JsonElement e) {
		if (e == null || !e.isJsonArray()) {
			return null;
		}
		JsonArray a = e.getAsJsonArray();
		if (a.size() < 3) {
			return null;
		}
		int[] out = new int[3];
		for (int i = 0; i < 3; i++) {
			JsonElement v = a.get(i);
			if (!v.isJsonPrimitive()) {
				return null;
			}
			try {
				out[i] = v.getAsInt();
			} catch (Exception ex) {
				return null;
			}
		}
		return out;
	}

	/** size 字段的数字列表（[宽,进深] / [宽,高,进深] / {"width":..,"depth":..}） */
	private static List<Integer> sizeNumbers(JsonObject o) {
		List<Integer> out = new ArrayList<>();
		JsonElement sizeEl = o.get("size");
		if (sizeEl != null && sizeEl.isJsonArray()) {
			for (JsonElement e : sizeEl.getAsJsonArray()) {
				if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
					out.add(e.getAsInt());
				}
			}
		} else if (sizeEl != null && sizeEl.isJsonObject()) {
			JsonObject so = sizeEl.getAsJsonObject();
			int w = intOr(so, "width", intOr(so, "w", intOr(so, "x", 0)));
			int h = intOr(so, "height", intOr(so, "h", intOr(so, "y", 0)));
			int d = intOr(so, "depth", intOr(so, "d", intOr(so, "length", intOr(so, "z", 0))));
			if (h > 0) {
				out.add(w);
				out.add(h);
				out.add(d);
			} else {
				out.add(w);
				out.add(d);
			}
		}
		return out;
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
		if (start < 0) {
			return null;
		}
		// 先按"括号配对"抠出第一个完整对象：模型常在 JSON 后面接着写说明，
		// 说明里一旦出现 '}'，用"取最后一个 }"的老办法就会把后面的文字一起吞进来，
		// 于是本来合法的 JSON 反而解析失败。
		String cand = firstBalanced(s, start);
		if (cand == null) {
			int last = s.lastIndexOf('}');
			if (last <= start) {
				return null;
			}
			cand = s.substring(start, last + 1);   // 没闭合（多半被截断），只能按老办法试
		}
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

	/**
	 * 从 start 处的 '{' 开始，找出第一个括号配对的完整片段。
	 * 字符串内的括号与转义字符都不计入 —— 否则方块名/说明文字里的括号会数错。
	 *
	 * @return 配对成功的片段；一直没闭合（多半是被输出长度截断）时返回 null
	 */
	private static String firstBalanced(String s, int start) {
		int depth = 0;
		boolean inStr = false;
		boolean esc = false;
		for (int i = start; i < s.length(); i++) {
			char c = s.charAt(i);
			if (esc) {
				esc = false;
				continue;
			}
			if (c == '\\') {
				esc = true;
				continue;
			}
			if (c == '"') {
				inStr = !inStr;
				continue;
			}
			if (inStr) {
				continue;
			}
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return s.substring(start, i + 1);
				}
			}
		}
		return null;
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
}
