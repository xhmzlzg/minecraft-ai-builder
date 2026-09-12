package com.mcai.common.spec;

import java.util.ArrayList;
import java.util.List;

/** 平面布局基础类型（纯 Java，便于离线自测） */
public final class Layout {

	private Layout() {
	}

	/** 闭区间矩形（含 x1/z1） */
	public record Rect(int x0, int z0, int x1, int z1) {
		public int w() {
			return x1 - x0 + 1;
		}

		public int d() {
			return z1 - z0 + 1;
		}

		public int area() {
			return w() * d();
		}

		public boolean contains(int x, int z) {
			return x >= x0 && x <= x1 && z >= z0 && z <= z1;
		}

		public boolean overlaps(Rect o) {
			return x0 <= o.x1 && o.x0 <= x1 && z0 <= o.z1 && o.z0 <= z1;
		}

		/** 是否紧贴（共享一条边，中间没有墙格） */
		public boolean touching(Rect o) {
			boolean xAdj = (x1 + 1 == o.x0) || (o.x1 + 1 == x0);
			boolean zAdj = (z1 + 1 == o.z0) || (o.z1 + 1 == z0);
			boolean xOverlap = x0 <= o.x1 && o.x0 <= x1;
			boolean zOverlap = z0 <= o.z1 && o.z0 <= z1;
			return (xAdj && zOverlap) || (zAdj && xOverlap);
		}

		public Rect clamp(int maxX, int maxZ) {
			int nx0 = Math.max(0, Math.min(x0, maxX));
			int nx1 = Math.max(0, Math.min(x1, maxX));
			int nz0 = Math.max(0, Math.min(z0, maxZ));
			int nz1 = Math.max(0, Math.min(z1, maxZ));
			if (nx1 < nx0) {
				nx1 = nx0;
			}
			if (nz1 < nz0) {
				nz1 = nz0;
			}
			return new Rect(nx0, nz0, nx1, nz1);
		}
	}

	public record Room(String type, Rect r) {
		public String type() {
			return type == null || type.isBlank() ? "room" : type;
		}
	}

	/** 把一列矩形按比例缩放到目标尺寸（用于把标准户型套到任意大小的框里） */
	public static Rect scale(Rect src, int srcW, int srcD, int dstW, int dstD) {
		double fx = dstW <= 1 ? 0 : (double) (src.x0()) / Math.max(1, srcW - 1);
		double fz = dstD <= 1 ? 0 : (double) (src.z0()) / Math.max(1, srcD - 1);
		double fx2 = dstW <= 1 ? 0 : (double) (src.x1()) / Math.max(1, srcW - 1);
		double fz2 = dstD <= 1 ? 0 : (double) (src.z1()) / Math.max(1, srcD - 1);
		int x0 = (int) Math.round(fx * (dstW - 1));
		int z0 = (int) Math.round(fz * (dstD - 1));
		int x1 = (int) Math.round(fx2 * (dstW - 1));
		int z1 = (int) Math.round(fz2 * (dstD - 1));
		if (x1 < x0) {
			x1 = x0;
		}
		if (z1 < z0) {
			z1 = z0;
		}
		return new Rect(x0, z0, x1, z1);
	}

	/** 修正房间表：裁进边界、消除重叠、保证最小 2×2 */
	public static List<Room> fix(List<Room> rooms, int maxX, int maxZ) {
		List<Room> out = new ArrayList<>();
		for (Room r : rooms) {
			Rect rr = r.r().clamp(maxX, maxZ);
			if (rr.w() < 2 || rr.d() < 2) {
				continue;
			}
			Rect fixed = rr;
			for (Room o : out) {
				if (!fixed.overlaps(o.r())) {
					continue;
				}
				// 把当前房间往下/往左缩，尽量保留面积
				Rect other = o.r();
				int nx0 = fixed.x0();
				int nz0 = fixed.z0();
				int nx1 = fixed.x1();
				int nz1 = fixed.z1();
				if (other.x0() > nx0) {
					nx1 = Math.min(nx1, other.x0() - 1);
				} else if (other.x1() < nx1) {
					nx0 = Math.max(nx0, other.x1() + 1);
				}
				if (other.z0() > nz0) {
					nz1 = Math.min(nz1, other.z0() - 1);
				} else if (other.z1() < nz1) {
					nz0 = Math.max(nz0, other.z1() + 1);
				}
				if (nx1 - nx0 + 1 < 2 || nz1 - nz0 + 1 < 2) {
					fixed = null;
					break;
				}
				fixed = new Rect(nx0, nz0, nx1, nz1);
			}
			if (fixed != null && fixed.w() >= 2 && fixed.d() >= 2) {
				out.add(new Room(r.type(), fixed));
			}
		}
		return out;
	}
}
