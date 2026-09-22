package com.mcai.common.spec;

import java.util.ArrayList;
import java.util.List;

import com.mcai.common.BuildingPlan;

/**
 * 规格展开器：把 {@link BuildingSpec}（AI 说的"要什么"）确定性地盖成 {@link BuildingPlan}。
 *
 * <p>v1.1.1 起**只剩一条路径**：AI 逐格画（{@link FreeformBuilder}）。
 * 原先的 kind="building"（archetype 参数化建筑模板）已随全部模板一起删除 ——
 * 它把形状锁死在程序自带的方盒子里，玩家要别墅 / 城堡 / 船 / 雕塑时
 * 全被套成同一栋住宅楼（用户报过"要螺旋雕塑却得到二层小楼"）。
 *
 * <p>AI 若仍写 kind="building"，解析层会把它当成 freeform；
 * 若它因此没给出 ops，这里就会展开成 0 个方块 ——
 * 由上层（{@code BuildTaskManager}）带着原因要求模型重画，**绝不静默兜底盖楼**。
 */
public final class SpecBuilder {

	private SpecBuilder() {
	}

	/** 构建结果：方案 + 生成期提醒 + 孤立方块统计 */
	public static final class Result {
		public final BuildingPlan plan;
		/** 生成期的提醒（越界坐标 / 非法方块 id / 超框选 / 空方案…），面板会择要展示 */
		public final List<String> warnings;
		/** 孤立方块数（只统计，不修补） */
		public final int isolated;

		Result(BuildingPlan plan, List<String> warnings, int isolated) {
			this.plan = plan;
			this.warnings = warnings == null ? new ArrayList<>() : warnings;
			this.isolated = isolated;
		}
	}

	/**
	 * @param availW/availD/availH 玩家框选的可用宽 / 进深 / 高（缺省时按此贴合）
	 */
	public static Result build(BuildingSpec spec, int availW, int availD, int availH) {
		FreeformSpec f = spec == null || spec.freeform == null ? new FreeformSpec() : spec.freeform;
		String name = spec == null || spec.name == null ? "AI 作品" : spec.name;
		FreeformBuilder.Result fr = FreeformBuilder.build(f, name, availW, availD, availH);
		return new Result(fr.plan, fr.warnings, fr.isolated);
	}

	/** 无高度约束的便捷重载 */
	public static BuildingPlan build(BuildingSpec spec, int availW, int availD) {
		return build(spec, availW, availD, Integer.MAX_VALUE).plan;
	}
}
