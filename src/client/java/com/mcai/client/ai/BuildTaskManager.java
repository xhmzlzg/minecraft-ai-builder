package com.mcai.client.ai;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mcai.MinecraftAIClient;
import com.mcai.MinecraftAIMod;
import com.mcai.common.BuildingPlan;
import com.mcai.common.PlanGenerator;
import com.mcai.common.PlanParser;
import com.mcai.common.PlanSpec;
import com.mcai.common.spec.BuildingSpec;
import com.mcai.common.spec.SpecBuilder;
import com.mcai.common.spec.SpecParser;

import net.minecraft.client.Minecraft;

/**
 * 全局生成任务管理器：AI 生成在后台线程执行，界面关闭/重开不影响任务与结果。
 * 状态仅保存在这里（不在 Screen 实例里），面板每次打开从这里恢复。
 */
public class BuildTaskManager {
	public enum Phase { IDLE, GENERATING, SUCCESS, FAILED }

	public static final BuildTaskManager INSTANCE = new BuildTaskManager();

	private Phase phase = Phase.IDLE;
	private String description = "";
	private String posText = "";
	/** 最近一次成功方案的原始 JSON（迭代调整的对话历史） */
	private String rawReply = null;
	private BuildingPlan plan = null;
	private String errorMsg = "";
	private long startMs;
	/** 当前方案是否由"按意见调整"生成（确认建造时自动替换旧建筑） */
	private boolean adjusted = false;
	/** 有未被查看过的新结果（球提示 / 按K直接展示；查看后清除） */
	private boolean freshResult = false;
	/** 生成时的建造原点与空间约束（重开面板恢复显示用） */
	private net.minecraft.core.BlockPos origin;
	private net.minecraft.core.BlockPos bound;

	private BuildTaskManager() {
	}

	public boolean hasFreshResult() {
		return freshResult;
	}

	public net.minecraft.core.BlockPos origin() {
		return origin;
	}

	public net.minecraft.core.BlockPos bound() {
		return bound;
	}

	public boolean isGenerating() {
		return phase == Phase.GENERATING;
	}

	public Phase phase() {
		return phase;
	}

	public BuildingPlan plan() {
		return plan;
	}

	public boolean isAdjusted() {
		return adjusted;
	}

	public String errorMsg() {
		return errorMsg;
	}

	public long elapsedSeconds() {
		return (System.currentTimeMillis() - startMs) / 1000;
	}

	/** 全新设计 */
	public void start(Minecraft client, String desc, String pos,
			net.minecraft.core.BlockPos buildOrigin, net.minecraft.core.BlockPos buildBound) {
		this.description = desc;
		this.posText = pos;
		this.origin = buildOrigin;
		this.bound = buildBound;
		this.adjusted = false;
		launch(client, null, null);
	}

	/** 基于当前方案按意见调整 */
	public void adjust(Minecraft client, String opinion) {
		launch(client, rawReply, opinion);
	}

	private void launch(Minecraft client, String previousReply, String adjustment) {
		phase = Phase.GENERATING;
		startMs = System.currentTimeMillis();
		errorMsg = "";
		String desc = description;
		CompletableFuture<String> pending = AiClient.askPlan(desc, posText, MinecraftAIClient.CONFIG,
				previousReply, adjustment);
		pending.thenAcceptAsync(reply -> {
			try {
				MinecraftAIMod.LOGGER.info("[Minecraft AI] AI 回复: {}", reply);
				if (SpecParser.looksLegacy(reply)) {
					plan = buildLegacy(reply, desc);
				} else {
					BuildingSpec spec = SpecParser.parse(reply);
					if (spec == null) {
						// 兼容：模型仍按旧契约回答（字符画蓝图）时走旧管线
						plan = buildLegacy(reply, desc);
					} else {
						java.util.List<String> applied = com.mcai.common.spec.NotesParser.apply(spec);
						if (!applied.isEmpty()) {
							MinecraftAIMod.LOGGER.info("[Minecraft AI] notes 落实: {}", applied);
						}
						if (previousReply != null) {
							// 记录"调整前后规格差异"，否则玩家会觉得"改了跟没改一样"
							MinecraftAIMod.LOGGER.info("[Minecraft AI] 调整前后规格差异: {}",
									diffSpec(previousReply, reply));
						}
						int[] box = boxSize();
						SpecBuilder.Result res = SpecBuilder.build(spec, box[0], box[1], box[2]);
						plan = res.plan;
						MinecraftAIMod.LOGGER.info(
								"[Minecraft AI] 规格展开: archetype={} floors={} layerHeight={} 方块数={}",
								spec.archetype, res.floors, res.layerHeight, plan.size());
						MinecraftAIMod.LOGGER.info("[Minecraft AI] 体检: {}", res.report.summary());
						for (String p : res.report.problems) {
							MinecraftAIMod.LOGGER.warn("[Minecraft AI] 遗留问题: {}", p);
						}
					}
				}
				rawReply = reply;
				freshResult = true;
				phase = Phase.SUCCESS;
			} catch (Exception e) {
				errorMsg = e.getMessage() == null ? e.toString() : e.getMessage();
				phase = Phase.FAILED;
			}
		}, client).exceptionally(e -> {
			Throwable t = e;
			while (t.getCause() != null) {
				t = t.getCause();
			}
			errorMsg = t.getMessage() == null ? t.toString() : t.getMessage();
			phase = Phase.FAILED;
			return null;
		});
	}

	/** 旧契约（字符画蓝图）管线，保留兼容 */
	private BuildingPlan buildLegacy(String reply, String desc) {
		PlanSpec spec = PlanParser.parse(reply);
		PlanParser.applyFloorHint(spec, extractFloorHint(desc));
		MinecraftAIMod.LOGGER.info("[Minecraft AI] 旧契约蓝图: name={} floors={} wall={} accent={} roof={} maps={}",
				spec.name, spec.floors, spec.wall, spec.accent, spec.roof,
				spec.floorsMap == null ? 0 : spec.floorsMap.size());
		return PlanGenerator.generate(spec, desc);
	}

	/** 玩家框选范围 → [宽, 进深, 高] */
	private int[] boxSize() {
		if (origin == null || bound == null) {
			return new int[] { 33, 19, 64 };
		}
		return new int[] {
				Math.abs(bound.getX() - origin.getX()) + 1,
				Math.abs(bound.getZ() - origin.getZ()) + 1,
				Math.abs(bound.getY() - origin.getY()) + 1 };
	}

	/** 比较两次规格的关键字段，输出人类可读的差异（用于日志诊断"调整没生效"） */
	private static String diffSpec(String oldRaw, String newRaw) {
		BuildingSpec a = SpecParser.parse(oldRaw);
		BuildingSpec b = SpecParser.parse(newRaw);
		if (a == null || b == null) {
			return "旧/新规格无法解析（旧=" + (a != null) + ", 新=" + (b != null) + "）";
		}
		StringBuilder sb = new StringBuilder();
		if (!String.valueOf(a.archetype).equals(String.valueOf(b.archetype))) {
			sb.append("archetype: ").append(a.archetype).append("→").append(b.archetype).append("; ");
		}
		if (!String.valueOf(a.floors).equals(String.valueOf(b.floors))) {
			sb.append("floors: ").append(a.floors).append("→").append(b.floors).append("; ");
		}
		if (!String.valueOf(a.layerHeight).equals(String.valueOf(b.layerHeight))) {
			sb.append("layer_height: ").append(a.layerHeight).append("→").append(b.layerHeight).append("; ");
		}
		if (!String.valueOf(a.size).equals(String.valueOf(b.size))) {
			sb.append("size: ").append(a.size).append("→").append(b.size).append("; ");
		}
		if (a.rooms.size() != b.rooms.size()) {
			sb.append("rooms 数量: ").append(a.rooms.size()).append("→").append(b.rooms.size()).append("; ");
		} else {
			for (int i = 0; i < a.rooms.size(); i++) {
				BuildingSpec.RoomSpec ra = a.rooms.get(i);
				BuildingSpec.RoomSpec rb = b.rooms.get(i);
				if (!ra.type.equals(rb.type) || ra.x != rb.x || ra.z != rb.z || ra.w != rb.w || ra.d != rb.d) {
					sb.append("房间[").append(i).append("]: ").append(ra.type).append(" ").append(ra.w).append("x")
							.append(ra.d).append("@").append(ra.x).append(",").append(ra.z).append(" → ")
							.append(rb.type).append(" ").append(rb.w).append("x").append(rb.d).append("@")
							.append(rb.x).append(",").append(rb.z).append("; ");
					break;
				}
			}
		}
		if (!String.valueOf(a.features).equals(String.valueOf(b.features))) {
			sb.append("features: ").append(a.features).append("→").append(b.features).append("; ");
		}
		if (!String.valueOf(a.materials).equals(String.valueOf(b.materials))) {
			sb.append("materials 有变化; ");
		}
		if (!String.valueOf(a.roof != null ? a.roof.style : null)
				.equals(String.valueOf(b.roof != null ? b.roof.style : null))) {
			sb.append("roof.style: ").append(a.roof != null ? a.roof.style : null).append("→")
					.append(b.roof != null ? b.roof.style : null).append("; ");
		}
		if (!String.valueOf(a.notes).equals(String.valueOf(b.notes))) {
			sb.append("notes 有变化（程序暂不解析 notes 内容）; ");
		}
		return sb.length() == 0 ? "无变化（模型原样返回了上一次的规格）" : sb.toString();
	}

	/** 结果已被查看（打开面板），悬浮球隐藏；方案数据保留供"调整上次方案"使用 */
	public void acknowledge() {
		freshResult = false;
		if (phase == Phase.SUCCESS || phase == Phase.FAILED) {
			phase = Phase.IDLE;
		}
	}

	/** 从玩家描述提取明确楼层数（如"20 层"、"至少 15 层"、"层数 8"）；没有则 null */
	private static Integer extractFloorHint(String description) {
		if (description == null) {
			return null;
		}
		Matcher m = Pattern.compile("(\\d+)\\s*(?:楼)?层|层(?:数)?\\s*(?:为|=)?\\s*(\\d+)").matcher(description);
		if (m.find()) {
			String v = m.group(1) != null ? m.group(1) : m.group(2);
			if (v != null) {
				return Integer.parseInt(v);
			}
		}
		return null;
	}

	// ==================== 悬浮球（MC 像素风格） ====================

	private static final String[] BALL_MASK = {
			"..XXXX..",
			".XXXXXX.",
			"XXXXXXXX",
			"XXXXXXXX",
			"XXXXXXXX",
			"XXXXXXXX",
			".XXXXXX.",
			"..XXXX..",
	};

	/** 右上角悬浮球：生成中金色+呼吸+秒数；完成绿色闪"!"；失败红色"X"。点击打开面板 */
	public static void renderBall(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		Minecraft client = Minecraft.getInstance();
		if (client.screen != null || client.player == null) {
			return;
		}
		Phase p = INSTANCE.phase;
		if (p == Phase.IDLE) {
			return;
		}
		int main = 0xFFFFC94F;
		int hi = 0xFFFFE082;
		int lo = 0xFFC79B2B;
		if (p == Phase.SUCCESS) {
			main = 0xFF4ADE80;
			hi = 0xFF8FF0B0;
			lo = 0xFF2FA35C;
		} else if (p == Phase.FAILED) {
			main = 0xFFFF5555;
			hi = 0xFFFF9090;
			lo = 0xFFB03030;
		}
		long ms = System.currentTimeMillis();
		int bob = (int) Math.round(Math.sin(ms * 0.005) * 1.5);
		int x = g.guiWidth() - 26;
		int y = 8 + bob;
		if (p == Phase.GENERATING && (ms / 400) % 2 == 0) {
			g.fill(x - 1, y - 1, x + 9, y + 9, 0x50FFC94F);
		}
		for (int row = 0; row < 8; row++) {
			String m = BALL_MASK[row];
			for (int col = 0; col < 8; col++) {
				if (m.charAt(col) != 'X') {
					continue;
				}
				int c = main;
				if (row <= 1 && col <= 3) {
					c = hi;
				} else if (row >= 5 && col >= 4) {
					c = lo;
				}
				g.fill(x + col, y + row, x + col + 1, y + row + 1, c);
			}
		}
		if (p == Phase.SUCCESS && (ms / 300) % 2 == 0) {
			g.fill(x + 3, y + 1, x + 4, y + 5, 0xFFFFFFFF);
			g.fill(x + 3, y + 6, x + 4, y + 7, 0xFFFFFFFF);
		}
		if (p == Phase.FAILED) {
			for (int i = 0; i < 4; i++) {
				g.fill(x + 2 + i, y + 2 + i, x + 3 + i, y + 3 + i, 0xFFFFFFFF);
				g.fill(x + 5 - i, y + 2 + i, x + 6 - i, y + 3 + i, 0xFFFFFFFF);
			}
		}
		String label = switch (p) {
			case GENERATING -> "AI " + INSTANCE.elapsedSeconds() + "s";
			case SUCCESS -> "AI 完成!";
			case FAILED -> "AI 失败";
			default -> "";
		};
		int tw = client.font.width(label);
		g.fill(x - tw - 7, y, x - 2, y + 9, 0x90000000);
		g.text(client.font, label, x - tw - 5, y + 1, 0xFFFFFFFF);
	}
}
