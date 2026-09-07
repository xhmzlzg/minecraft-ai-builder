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
				PlanSpec spec = PlanParser.parse(reply);
				PlanParser.applyFloorHint(spec, extractFloorHint(desc));
				MinecraftAIMod.LOGGER.info("[Minecraft AI] AI 回复: {}", reply);
				MinecraftAIMod.LOGGER.info("[Minecraft AI] 解析方案: name={} floors={} wall={} accent={} roof={} interiors={} repeat={} maps={}",
						spec.name, spec.floors, spec.wall, spec.accent, spec.roof, spec.interiors, spec.repeat,
						spec.floorsMap == null ? 0 : spec.floorsMap.size());
				plan = PlanGenerator.generate(spec, desc);
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
