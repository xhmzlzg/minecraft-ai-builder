package com.mcai.common;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析后的具体方块布局：每个方块相对建筑原点的偏移与方块 ID。
 */
public class BuildingPlan {
	public final String name;
	public final int width;
	public final int height;
	public final int depth;
	public final List<Entry> entries = new ArrayList<>();
	private final Set<Long> occupied = new HashSet<>();

	public record Entry(int x, int y, int z, String blockId) {}

	public BuildingPlan(String name, int width, int height, int depth) {
		this.name = name;
		this.width = width;
		this.height = height;
		this.depth = depth;
	}

	public void add(int x, int y, int z, String blockId) {
		if (!occupied.add(key(x, y, z))) {
			return;
		}
		entries.add(new Entry(x, y, z, blockId));
	}

	private static long key(int x, int y, int z) {
		return (((long) x & 0x3FF) << 20) | (((long) y & 0x3FF) << 10) | ((long) z & 0x3FF);
	}

	public int size() {
		return entries.size();
	}
}
