package com.mcai.common.spec;

/**
 * 契约外壳：AI 输出一份「自由形体规格」（{@link FreeformSpec}），由游戏内程序逐格照做。
 *
 * <p>为什么只剩这一种：模组原先还提供 kind="building"（archetype 参数化建筑模板）——
 * 一个"切房间 + 外墙 + 门窗 + 屋顶"的住宅方盒子。它把**形状**锁死在程序自带的那栋楼里，
 * 玩家要别墅 / 四合院 / 城堡 / 写字楼时全被套成同一栋住宅楼
 * （用户报过"要螺旋上升的雕塑，结果得到二层小楼"）。
 *
 * <p>2026-09-22 已把**全部建筑模板连同旧版字符画管线一起删除**：
 * 程序不再提供任何模板，形状完全由 AI 的 ops 决定。
 *
 * <p>因此本类只是 freeform 契约的外壳。AI 若仍写 kind="building"，
 * 解析层会**直接把它当成 freeform**（见 {@link SpecParser#parse}）；
 * 若它因此没给出任何 ops，展开结果就是 0 个方块，由上层带着原因要求模型重画 ——
 * 绝不静默兜底盖楼。
 */
public class BuildingSpec {
	public int specVersion = 3;
	/** 输出类型。模板已删除，这里恒为 "freeform"（保留字段是为了让模型沿用旧习惯的输出也能被解析） */
	public String kind = "freeform";
	public String name = "AI 作品";
	/** 形体描述（ops 绘制指令）；永远非 null */
	public FreeformSpec freeform = new FreeformSpec();
}
