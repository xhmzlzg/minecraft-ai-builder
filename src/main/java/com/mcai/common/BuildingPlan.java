package com.mcai.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析后的具体方块布局：每个方块相对建筑原点的偏移与方块 ID。
 *
 * v1.1.0：Entry 增加 blockstate 属性（props），用于楼梯朝向、半砖、脚手架 distance、
 * 门/床的朝向、压力板/按钮等精细方块；blockId 始终保存"基础方块 id"（不含属性），
 * 因此 3D 预览的颜色表 / 越界提示等旧逻辑无需改动。
 */
public class BuildingPlan {
	public final String name;
	public final int width;
	public final int height;
	public final int depth;
	public final List<Entry> entries = new ArrayList<>();
	private final Set<Long> occupied = new HashSet<>();
	private final Map<Long, Integer> index = new HashMap<>();

	public record Entry(int x, int y, int z, String blockId, Map<String, String> props) {
		public Entry(int x, int y, int z, String blockId) {
			this(x, y, z, blockId, Map.of());
		}
	}

	public BuildingPlan(String name, int width, int height, int depth) {
		this.name = name;
		this.width = width;
		this.height = height;
		this.depth = depth;
	}

	/** 首次写入生效（不覆盖已有方块，保持旧行为） */
	public void add(int x, int y, int z, String blockId) {
		add(x, y, z, blockId, Map.of());
	}

	public void add(int x, int y, int z, String blockId, Map<String, String> props) {
		long k = key(x, y, z);
		if (!occupied.add(k)) {
			return;
		}
		index.put(k, entries.size());
		entries.add(new Entry(x, y, z, blockId, props == null ? Map.of() : props));
	}

	/** 覆盖写入（用于开洞、换材质） */
	public void set(int x, int y, int z, String blockId) {
		set(x, y, z, blockId, Map.of());
	}

	public void set(int x, int y, int z, String blockId, Map<String, String> props) {
		long k = key(x, y, z);
		Entry e = new Entry(x, y, z, blockId, props == null ? Map.of() : props);
		Integer i = index.get(k);
		if (i == null) {
			occupied.add(k);
			index.put(k, entries.size());
			entries.add(e);
		} else {
			entries.set(i, e);
		}
	}

	/** 删除（用于掏空、开敞口） */
	public void remove(int x, int y, int z) {
		long k = key(x, y, z);
		Integer i = index.remove(k);
		if (i == null) {
			return;
		}
		occupied.remove(k);
		int last = entries.size() - 1;
		if (i != last) {
			Entry moved = entries.get(last);
			entries.set(i, moved);
			index.put(key(moved.x(), moved.y(), moved.z()), i);
		}
		entries.remove(last);
	}

	public Entry get(int x, int y, int z) {
		Integer i = index.get(key(x, y, z));
		return i == null ? null : entries.get(i);
	}

	public boolean has(int x, int y, int z) {
		return index.containsKey(key(x, y, z));
	}

	/** 该位置是否为"实心可站/可支撑"方块（非空气、非隐形光源） */
	public boolean isSupporting(int x, int y, int z) {
		Entry e = get(x, y, z);
		if (e == null) {
			return false;
		}
		return PlanValidator.isSupportingBlock(e.blockId());
	}

	private static long key(int x, int y, int z) {
		return (((long) x & 0x3FF) << 20) | (((long) y & 0x3FF) << 10) | ((long) z & 0x3FF);
	}

	public int size() {
		return entries.size();
	}
}
