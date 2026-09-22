package com.mcai.client.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mcai.MinecraftAIClient;
import com.mcai.MinecraftAIMod;
import com.mcai.common.BuildingPlan;
import com.mcai.common.spec.BuildingSpec;
import com.mcai.common.spec.SpecBuilder;
import com.mcai.common.spec.SpecParser;

import net.minecraft.client.Minecraft;

/**
 * 全局生成任务 + 对话会话管理器。
 * 界面关闭/重开不影响任务与结果；会话保存消息历史，同一会话继续发言 = 修改上次方案。
 */
public class BuildTaskManager {
	public enum Phase { IDLE, GENERATING, SUCCESS, FAILED }

	public static final BuildTaskManager INSTANCE = new BuildTaskManager();

	/** 聊天消息角色 */
	public enum MsgRole { USER, AI, SYSTEM, ERROR }

	/** 一条聊天消息（对话式界面用） */
	public static final class ChatMsg {
		public final MsgRole role;
		public String text;
		public String thinking = "";
		public boolean thinkingExpanded = false;
		/** AI 消息附带的方案（可展示 3D 预览 / 确认建造） */
		public BuildingPlan plan;
		public boolean planReady = false;

		public ChatMsg(MsgRole role, String text) {
			this.role = role;
			this.text = text == null ? "" : text;
		}
	}

	/** 一个对话会话：新会话 = 新建筑；同一会话继续发 = 修改 */
	public static final class ChatSession {
		public final String id = UUID.randomUUID().toString();
		public String title = "新会话";
		public final List<ChatMsg> messages = new ArrayList<>();
		/** 最近一次成功方案的原始 JSON（迭代调整的对话历史） */
		public String rawReply;
		public BuildingPlan plan;
		public String warnText = "";
		public boolean adjusted = false;
		public net.minecraft.core.BlockPos origin;
		public net.minecraft.core.BlockPos bound;
	}

	private final List<ChatSession> sessions = new ArrayList<>();
	private ChatSession current = new ChatSession();

	private Phase phase = Phase.IDLE;
	private String description = "";
	private String posText = "";
	private String errorMsg = "";
	private long startMs;
	private boolean freshResult = false;
	/** 流式思考实时文本（生成中界面轮询） */
	private volatile String liveThinking = "";
	private volatile String liveContent = "";

	public BuildTaskManager() {
		sessions.add(current);
	}

	// ==================== 会话 ====================

	public List<ChatSession> sessions() {
		return sessions;
	}

	public ChatSession session() {
		return current;
	}

	public void newSession() {
		// 保留空的旧会话列表；新建一个空会话
		current = new ChatSession();
		sessions.add(current);
		// 上限 10 个会话，丢掉最旧的
		while (sessions.size() > 10) {
			sessions.remove(0);
		}
		phase = Phase.IDLE;
		errorMsg = "";
		liveThinking = "";
		liveContent = "";
	}

	public void switchTo(ChatSession s) {
		if (s != null && sessions.contains(s)) {
			current = s;
			phase = Phase.IDLE;
			liveThinking = "";
			liveContent = "";
		}
	}

	// ==================== 状态 ====================

	public boolean hasFreshResult() {
		return freshResult;
	}

	public net.minecraft.core.BlockPos origin() {
		return current.origin;
	}

	public net.minecraft.core.BlockPos bound() {
		return current.bound;
	}

	public boolean isGenerating() {
		return phase == Phase.GENERATING;
	}

	public Phase phase() {
		return phase;
	}

	public BuildingPlan plan() {
		return current.plan;
	}

	public boolean isAdjusted() {
		return current.adjusted;
	}

	public String errorMsg() {
		return errorMsg;
	}

	public String warnText() {
		return current.warnText;
	}

	public long elapsedSeconds() {
		return (System.currentTimeMillis() - startMs) / 1000;
	}

	public String liveThinking() {
		return liveThinking;
	}

	public String liveContent() {
		return liveContent;
	}

	/** 发送聊天消息：会话里已有方案则视为「修改」，否则「新生成」 */
	public void send(Minecraft client, String desc, String pos,
			net.minecraft.core.BlockPos buildOrigin, net.minecraft.core.BlockPos buildBound) {
		this.description = desc;
		this.posText = pos;
		this.current.origin = buildOrigin;
		this.current.bound = buildBound;
		this.current.adjusted = current.rawReply != null;
		current.messages.add(new ChatMsg(MsgRole.USER, desc));
		if (current.title.equals("新会话") || current.messages.size() == 1) {
			current.title = desc.length() > 12 ? desc.substring(0, 12) + "…" : desc;
		}
		launch(client, current.rawReply, current.rawReply != null ? desc : null);
	}

	/** 兼容旧调用：全新设计 */
	public void start(Minecraft client, String desc, String pos,
			net.minecraft.core.BlockPos buildOrigin, net.minecraft.core.BlockPos buildBound) {
		send(client, desc, pos, buildOrigin, buildBound);
	}

	/** 兼容旧调用：基于当前方案按意见调整 */
	public void adjust(Minecraft client, String opinion) {
		current.adjusted = true;
		current.messages.add(new ChatMsg(MsgRole.USER, opinion));
		launch(client, current.rawReply, opinion);
	}

	public void cancel() {
		AiClient.cancelActive();
		// 文案由请求异常路径统一写入，避免重复气泡
	}

	private void launch(Minecraft client, String previousReply, String adjustment) {
		launch(client, previousReply, adjustment, null, 0);
	}

	/**
	 * @param repairHint 非空 = 上一轮结果被程序判定不合格，带着"错在哪"重做一次
	 * @param attempt    已重做次数（只允许重做 1 次，避免无限循环）
	 */
	private void launch(Minecraft client, String previousReply, String adjustment, String repairHint, int attempt) {
		phase = Phase.GENERATING;
		if (attempt == 0) {
			startMs = System.currentTimeMillis();
		}
		errorMsg = "";
		current.warnText = "";
		liveThinking = "";
		liveContent = "";
		String desc = description;

		AiClient.StreamListener listener = new AiClient.StreamListener() {
			@Override
			public void onThinking(String delta) {
				liveThinking = liveThinking + delta;
			}

			@Override
			public void onContent(String delta) {
				liveContent = liveContent + delta;
			}
		};

		// 新生成：两阶段（设计要点 → ops）。调整 / 自动重做：直接阶段二。
		if (previousReply == null && repairHint == null && attempt == 0) {
			current.messages.add(new ChatMsg(MsgRole.SYSTEM, "① 正在出设计要点…"));
			AiClient.askDesignBrief(desc, posText, MinecraftAIClient.CONFIG, listener)
					.thenAcceptAsync(briefReply -> {
						String brief = briefReply.content == null ? "" : briefReply.content.trim();
						String think1 = briefReply.thinking == null ? "" : briefReply.thinking;
						if (!think1.isEmpty()) {
							ChatMsg tmsg = new ChatMsg(MsgRole.AI, "设计思考（阶段1）");
							tmsg.thinking = think1;
							current.messages.add(tmsg);
						}
						if (brief.isEmpty()) {
							current.messages.add(new ChatMsg(MsgRole.ERROR, "设计要点为空，无法继续绘制"));
							phase = Phase.FAILED;
							errorMsg = "设计要点为空";
							return;
						}
						// 去掉可能的代码围栏
						String briefJson = brief.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
						ChatMsg bmsg = new ChatMsg(MsgRole.AI, "设计要点：\n" + truncateBrief(briefJson));
						current.messages.add(bmsg);
						current.messages.add(new ChatMsg(MsgRole.SYSTEM, "② 按设计要点绘制方块…"));
						liveThinking = "";
						// 阶段二：带着设计要点画 ops
						launchOps(client, desc, previousReply, adjustment, repairHint, attempt, briefJson, listener);
					}, client)
					.exceptionally(e -> {
						Throwable t = e;
						while (t.getCause() != null) {
							t = t.getCause();
						}
						String msg = t.getMessage() == null ? t.toString() : t.getMessage();
						if (t instanceof AiClient.CancelledException || "已取消本次生成".equals(msg)) {
							errorMsg = "已取消本次生成";
							current.messages.add(new ChatMsg(MsgRole.SYSTEM, "已取消本次生成"));
						} else {
							errorMsg = "设计要点失败：" + msg;
							current.messages.add(new ChatMsg(MsgRole.ERROR, errorMsg));
						}
						phase = Phase.FAILED;
						return null;
					});
			return;
		}
		launchOps(client, desc, previousReply, adjustment, repairHint, attempt, null, listener);
	}

	private static String truncateBrief(String s) {
		String one = s.replaceAll("\\s+", " ");
		return one.length() <= 180 ? one : one.substring(0, 180) + "…";
	}

	/** 阶段二：输出 freeform ops 并展开建造。designBrief 可空（调整/重做时靠 previousReply）。 */
	private void launchOps(Minecraft client, String desc, String previousReply, String adjustment,
			String repairHint, int attempt, String designBrief, AiClient.StreamListener listener) {
		String pos = posText;
		if (designBrief != null && !designBrief.isEmpty()) {
			pos = posText + "\n【设计要点（必须遵守）】\n" + designBrief
					+ "\n请按上述要点输出完整 freeform JSON（紧凑 ops：优先 box，总 ops≤25，能 mirror 就 mirror）。";
		}
		CompletableFuture<AiClient.AiReply> pending = AiClient.askPlan(desc, pos, MinecraftAIClient.CONFIG,
				previousReply, adjustment, repairHint, listener);
		pending.thenAcceptAsync(reply -> {
			try {
				String think = reply.thinking;
				String text = reply.content;
				MinecraftAIMod.LOGGER.info("[Minecraft AI] AI 思考: {}", think == null ? "" : think.substring(0, Math.min(think.length(), 200)));
				MinecraftAIMod.LOGGER.info("[Minecraft AI] AI 回复: {}", text);
				String hint = attempt < 1 ? mismatchHint(desc, text, previousReply, adjustment) : null;
				if (hint != null) {
					MinecraftAIMod.LOGGER.warn("[Minecraft AI] 结果不合格，自动重新生成一次：{}", hint);
					current.messages.add(new ChatMsg(MsgRole.SYSTEM, "结果不合格，自动重做一次…"));
					launchOps(client, desc, previousReply, adjustment, hint, attempt + 1, designBrief, listener);
					return;
				}
				BuildingSpec spec = SpecParser.parse(text);
				if (spec == null) {
					throw new IllegalStateException(
							"AI 的回复不是可用的规格 JSON（可能被输出长度截断、夹了说明文字，"
									+ "或还在用已删除的旧格式）。请重试；若反复如此，"
									+ "请在描述里要求画简单些，或在配置里关掉思考模式 / 换一个输出上限更大的模型。");
				}
				if (previousReply != null) {
					MinecraftAIMod.LOGGER.info("[Minecraft AI] 调整前后规格差异: {}", diffSpec(previousReply, text));
				}
				int[] box = boxSize();
				SpecBuilder.Result res = SpecBuilder.build(spec, box[0], box[1], box[2]);
				BuildingPlan plan = res.plan;
				if (plan.size() == 0 && attempt < 1) {
					MinecraftAIMod.LOGGER.warn("[Minecraft AI] 展开后 0 个方块，自动重新生成一次");
					current.messages.add(new ChatMsg(MsgRole.SYSTEM, "方案为空，自动重做一次…"));
					launchOps(client, desc, previousReply, adjustment,
							"你的 ops 一个方块都没画出来（方案是空的）。常见原因："
									+ "① layer 的 rows 用了 palette 里没定义的字符 —— 每个出现的字符都必须在 palette 里说明是什么方块；"
									+ "② 坐标超出 size 范围或写了负数 —— 坐标从 0 开始，要求 x<宽、y<高、z<进深；"
									+ "③ block 字段不是合法方块 id —— 只能写原版 id（如 oak_planks、stone_bricks），"
									+ "不要写中文、不要自造名字，blockstate 属性要单独放在 props 里。"
									+ "请修正后重新输出完整的 freeform JSON。",
							attempt + 1, designBrief, listener);
					return;
				}
				MinecraftAIMod.LOGGER.info(
						"[Minecraft AI] 自由形体展开: mirror={} ops={} 尺寸={}x{}x{} 方块数={} 孤立={}",
						spec.freeform.mirror, spec.freeform.ops.size(),
						plan.width, plan.height, plan.depth, plan.size(), res.isolated);
				for (String w : res.warnings) {
					MinecraftAIMod.LOGGER.warn("[Minecraft AI] 展开警告: {}", w);
				}
				current.warnText = summarizeWarnings(res.warnings);
				MinecraftAIMod.LOGGER.info(
						"[Minecraft AI] 跳过建筑体检（悬空件按设计保留）；孤立方块 {} 个", res.isolated);

				current.rawReply = text;
				current.plan = plan;
				current.adjusted = previousReply != null;

				ChatMsg aiMsg = new ChatMsg(MsgRole.AI, summarizePlan(plan, current.warnText));
				aiMsg.thinking = think == null ? "" : think;
				aiMsg.plan = plan;
				aiMsg.planReady = true;
				current.messages.add(aiMsg);

				liveThinking = "";
				liveContent = "";
				freshResult = true;
				phase = Phase.SUCCESS;
			} catch (Exception e) {
				String msg = e.getMessage() == null ? e.toString() : e.getMessage();
				if (e instanceof AiClient.CancelledException || "已取消本次生成".equals(msg)) {
					errorMsg = "已取消本次生成";
					current.messages.add(new ChatMsg(MsgRole.SYSTEM, "已取消本次生成"));
					phase = Phase.FAILED;
					return;
				}
				errorMsg = msg;
				current.warnText = "";
				current.messages.add(new ChatMsg(MsgRole.ERROR, errorMsg));
				phase = Phase.FAILED;
			}
		}, client).exceptionally(e -> {
			Throwable t = e;
			while (t.getCause() != null) {
				t = t.getCause();
			}
			String msg = t.getMessage() == null ? t.toString() : t.getMessage();
			if (t instanceof AiClient.CancelledException || "已取消本次生成".equals(msg)) {
				errorMsg = "已取消本次生成";
				current.messages.add(new ChatMsg(MsgRole.SYSTEM, "已取消本次生成"));
				phase = Phase.FAILED;
				return null;
			}
			errorMsg = msg;
			current.warnText = "";
			current.messages.add(new ChatMsg(MsgRole.ERROR, errorMsg));
			phase = Phase.FAILED;
			return null;
		});
	}

	private static String summarizePlan(BuildingPlan plan, String warn) {
		String s = "方案「" + plan.name + "」" + plan.width + "x" + plan.height + "x" + plan.depth
				+ "，共 " + plan.size() + " 个方块。确认后生成到世界里。";
		if (warn != null && !warn.isEmpty()) {
			s += " 注意：" + warn;
		}
		return s;
	}

	// ==================== 结果校验（"AI 不按提示词生成"的机械兜底） ====================

	private static String summarizeWarnings(java.util.List<String> warnings) {
		if (warnings == null || warnings.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		for (String w : warnings) {
			if (shown == 3) {
				break;
			}
			if (shown > 0) {
				sb.append("；");
			}
			sb.append(w);
			shown++;
		}
		if (warnings.size() > shown) {
			sb.append("；等共 ").append(warnings.size()).append(" 条（详见日志）");
		}
		String s = sb.toString();
		return s.length() <= 160 ? s : s.substring(0, 160) + "…";
	}

	private static String mismatchHint(String desc, String reply, String previousReply, String adjustment) {
		BuildingSpec spec = SpecParser.parse(reply);
		String low = reply == null ? "" : reply.toLowerCase();
		if (spec == null) {
			if (low.contains("floors_map")) {
				return "你输出的是【旧版字符画蓝图】格式（含 floors_map），本模组已经把它整条管线删除了。"
						+ "请改用 \"kind\":\"freeform\" + ops（layer 分层字符画 / box / cyl / sphere）"
						+ "亲手画出「" + desc + "」的外形。只输出 JSON，禁止任何其他文字，不要代码块。";
			}
			if (low.contains("\"building\"") || low.contains("archetype")) {
				return "本模组已删除**全部建筑模板**：kind=\"building\" / archetype 不再有任何实现，"
						+ "程序一格方块都盖不出来。请改用 \"kind\":\"freeform\"，"
						+ "用 ops（layer 分层字符画 / box / cyl / sphere）亲手把「" + desc + "」画出来："
						+ "地基/楼板用 box，墙体用 4 个 box（别用 hollow），门窗用 box 盖在墙上，"
						+ "楼梯用 *_stairs + props{\"facing\":...,\"half\":\"bottom\"}，屋顶用 box 铺。"
						+ "只输出 JSON，禁止任何其他文字，不要代码块。";
			}
			if (low.contains("\"ops\"") || low.contains("\"kind\"") || low.contains("freeform")) {
				return "你的回复不是一段完整的 JSON，程序解析失败。常见原因："
						+ "① 被输出长度截断 —— 请把作品画简单些（一艘船 10~15 个 op 就够，一栋小屋 20~30 个）；"
						+ "② JSON 前后夹了说明文字 —— 请只输出 JSON 本身，不要任何解释；"
						+ "③ 用了中文引号 / 中文逗号 —— 请全部用英文半角符号。"
						+ "请重新输出【完整且合法】的 JSON。";
			}
			return null;
		}
		if (spec.freeform.ops.isEmpty()) {
			return "你的规格里一个绘制指令（ops）都没有，程序会盖出 0 个方块。"
					+ "请用 \"kind\":\"freeform\" + ops（layer 分层字符画 / box / cyl / sphere）"
					+ "亲手把「" + desc + "」画出来：地基/楼板用 box，墙体用 4 个 box（别用 hollow），"
					+ "门窗用 box 盖在墙上，楼梯用 *_stairs + props{\"facing\":...,\"half\":\"bottom\"}，"
					+ "屋顶用 box 铺。只输出 JSON，禁止任何其他文字，不要代码块。";
		}
		if (previousReply != null && noChange(previousReply, reply)) {
			return "你这次输出的规格与上一次【完全相同】，等于没有做任何调整。"
					+ "请真的改动字段（ops / size / palette / mirror），"
					+ "或者如果玩家要的其实是另一种东西（例如从「楼」改成「船」），"
					+ "必须重新用 ops 描述外形。";
		}
		return null;
	}

	private static boolean noChange(String oldRaw, String newRaw) {
		return diffSpec(oldRaw, newRaw).startsWith("无变化");
	}

	private static String freeformSignature(com.mcai.common.spec.FreeformSpec f) {
		if (f == null) {
			return "-";
		}
		StringBuilder sb = new StringBuilder();
		sb.append(f.size).append('|').append(f.mirror).append('|').append(f.ops.size()).append('|');
		for (com.mcai.common.spec.FreeformSpec.Op o : f.ops) {
			sb.append(o.op).append(o.y).append(o.block).append(o.hollow)
					.append(java.util.Arrays.toString(o.from)).append(java.util.Arrays.toString(o.to))
					.append(o.x).append(o.z).append(o.y0).append(o.y1)
					.append(o.r).append(o.rx).append(o.ry).append(o.rz)
					.append(o.props)
					.append(o.rows == null ? "" : o.rows.toString())
					.append(';');
		}
		return sb.toString();
	}

	private int[] boxSize() {
		if (current.origin == null || current.bound == null) {
			return new int[] { 33, 19, 64 };
		}
		return new int[] {
				Math.abs(current.bound.getX() - current.origin.getX()) + 1,
				Math.abs(current.bound.getZ() - current.origin.getZ()) + 1,
				Math.abs(current.bound.getY() - current.origin.getY()) + 1 };
	}

	private static String diffSpec(String oldRaw, String newRaw) {
		BuildingSpec a = SpecParser.parse(oldRaw);
		BuildingSpec b = SpecParser.parse(newRaw);
		if (a == null || b == null) {
			return "旧/新规格无法解析（旧=" + (a != null) + ", 新=" + (b != null) + "）";
		}
		com.mcai.common.spec.FreeformSpec fa = a.freeform;
		com.mcai.common.spec.FreeformSpec fb = b.freeform;
		StringBuilder sb = new StringBuilder();
		if (!String.valueOf(fa.size).equals(String.valueOf(fb.size))) {
			sb.append("size: ").append(fa.size).append("→").append(fb.size).append("; ");
		}
		if (!String.valueOf(fa.mirror).equals(String.valueOf(fb.mirror))) {
			sb.append("mirror: ").append(fa.mirror).append("→").append(fb.mirror).append("; ");
		}
		if (fa.ops.size() != fb.ops.size()) {
			sb.append("ops 数量: ").append(fa.ops.size()).append("→").append(fb.ops.size()).append("; ");
		}
		if (!freeformSignature(fa).equals(freeformSignature(fb))) {
			sb.append("ops 内容有变化; ");
		}
		if (!String.valueOf(fa.palette).equals(String.valueOf(fb.palette))) {
			sb.append("palette 有变化; ");
		}
		if (!String.valueOf(fa.notes).equals(String.valueOf(fb.notes))) {
			sb.append("notes 有变化; ");
		}
		return sb.length() == 0 ? "无变化（模型原样返回了上一次的规格）" : sb.toString();
	}

	/** 结果已被查看（打开面板），悬浮球隐藏；方案数据保留供"调整"使用 */
	public void acknowledge() {
		freshResult = false;
		if (phase == Phase.SUCCESS || phase == Phase.FAILED) {
			phase = Phase.IDLE;
		}
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
