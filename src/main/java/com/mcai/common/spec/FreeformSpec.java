package com.mcai.common.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自由形体契约（kind = "freeform"）：AI 直接描述"外形"，程序逐格照做。
 *
 * 为什么只剩这一种：本模组原先还有"建筑契约"（archetype 参数化模板）——
 * 一个"切房间 + 外墙 + 门窗 + 屋顶"的住宅方盒子。玩家要船、飞机、雕像、别墅、城堡时，
 * 硬塞进去只会被套成那栋住宅楼（用户报过"要螺旋雕塑却得到二层小楼"），
 * 所以 2026-09-22 已把**全部建筑模板删除**：把"外形"本身交给模型表达，
 * 程序不再做任何建筑假设。
 *
 * 设计原则：
 *   1. 输出要短：用分层字符画（layer）+ 镜像（mirror）就够画一艘船；
 *   2. 允许混用几何原语（box / cyl / sphere）做桅杆、柱子、球体等部件；
 *   3. 坐标从 0 开始、非负；越界的格子直接丢弃，绝不让方案里出现负坐标。
 */
public class FreeformSpec {
	/** [宽, 高, 进深]；也可只给 [宽, 进深]（高度按 ops 实际范围自动算） */
	public List<Integer> size;
	/** 单字符 → 方块 id（如 {"h":"dark_oak_planks"}）；缺省用 {@link #DEFAULT_PALETTE} */
	public Map<String, String> palette = new LinkedHashMap<>();
	/**
	 * 字符 → 该字符自带的 blockstate。
	 *
	 * 模型常按 Minecraft 命令的写法把状态直接嵌在方块名里：
	 * {@code {"S":"oak_stairs[facing=east,half=bottom]"}}。
	 * 那不是合法方块 id（展开器的 sanitize 会把整块丢掉），所以解析时在这里拆开存着，
	 * 展开 layer 时再合回 {@link Op#props}。
	 */
	public Map<String, Map<String, String>> paletteProps = new LinkedHashMap<>();
	/** none / x / z / xz —— 镜像复制，只需写一半 */
	public String mirror = "none";
	public List<Op> ops = new ArrayList<>();
	public String notes = "";

	/** 一个绘制指令。字段按 op 类型取用，未用到的保持 null。 */
	public static final class Op {
		/** layer / box / clear / cyl / sphere */
		public String op = "";
		/** layer：层号；sphere：中心 y */
		public Integer y;
		/** layer：rows[z] 的第 x 个字符决定该格方块 */
		public List<String> rows;
		/** box / clear：两个对角点 */
		public int[] from;
		public int[] to;
		/** 方块 id 或调色板字符 */
		public String block;
		/** cyl / sphere 的中心（水平） */
		public Integer x;
		public Integer z;
		/** cyl：纵向范围 */
		public Integer y0;
		public Integer y1;
		/** 半径（rx/ry/rz 可分别覆盖） */
		public Integer r;
		public Integer rx;
		public Integer ry;
		public Integer rz;
		/** 只做外壳（空心） */
		public boolean hollow;
		/**
		 * blockstate 属性，如 {"facing":"east","half":"bottom","type":"top"}。
		 * 用来画"朝向正确的楼梯 / 半砖 / 栅栏门 / 挂灯 / 横梁原木"这类光靠方块 id 表达不了的东西。
		 * 缺省时展开器会补合理默认（栏杆自动连片、灯笼上方实心就自动吊挂），镜像时自动翻转朝向。
		 */
		public Map<String, String> props = new LinkedHashMap<>();
	}

	/** 内置字符表：palette 缺省时使用，让模型少写 palette（只读，展开器会另建副本） */
	public static final Map<String, String> DEFAULT_PALETTE =
			java.util.Collections.unmodifiableMap(defaultPalette());

	private static Map<String, String> defaultPalette() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("#", "stone_bricks");
		m.put("O", "oak_planks");
		m.put("o", "oak_log");
		m.put("T", "dark_oak_planks");
		m.put("t", "dark_oak_log");
		m.put("P", "spruce_planks");
		m.put("p", "spruce_log");
		m.put("S", "stone");
		m.put("I", "iron_block");
		m.put("G", "glass");
		m.put("W", "white_wool");
		m.put("B", "blue_wool");
		m.put("R", "red_wool");
		m.put("Y", "yellow_wool");
		m.put("E", "gray_wool");
		m.put("K", "black_wool");
		m.put("L", "lantern");
		m.put("X", "sea_lantern");
		return m;
	}
}
