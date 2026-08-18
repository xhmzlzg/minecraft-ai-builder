package com.mcai.common;

import java.util.List;
import java.util.Map;

/**
 * AI 输出的建筑方案：核心是「楼层平面蓝图」。
 * AI 用字符画画出每一层的布局（墙/窗/门/柱/家具位置），
 * 程序只负责把字符翻译成方块并堆叠成 3D 建筑。
 */
public class PlanSpec {
	/** 建筑名称 */
	public String name = "建筑";
	/** 层数；null = 1 */
	public Integer floors;
	/** 层高（格）；null = 3。玩家描述要求层高时 AI 照做，没要求时 AI 自主定 3~5 */
	@com.google.gson.annotations.SerializedName("layer_height")
	public Integer layerHeight;
	/** 蓝图尺寸 [宽, 深]，AI 参考值；实际以蓝图行宽/行数为准 */
	public List<Integer> size;
	/** 主墙材质 */
	public String wall;
	/** 强调材质（柱/窗框/门楣/屋顶） */
	public String accent;
	/** 屋顶样式 flat / pyramid / gabled */
	public String roof;
	/** 是否渲染家具字符（R/B/C/F/L/T） */
	public Boolean interiors;
	/** 从第几层开始重复第二张蓝图（顶层除外）；null = 全部用第 1 张。AI 可能给 "2-4" 这类写法 */
	public String repeat;
	/** 楼层蓝图："1"/"2"/"top" -> 字符画行数组（每行等长，第一行为南墙） */
	@com.google.gson.annotations.SerializedName("floors_map")
	public Map<String, List<String>> floorsMap;
}