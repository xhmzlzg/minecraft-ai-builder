package com.mcai.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mcai.MinecraftAIMod;
import com.mcai.client.ai.AiClient;
import com.mcai.client.render.PlanPreviewWidget;
import com.mcai.common.AiConfig;
import com.mcai.common.BuildingExecutor;
import com.mcai.common.BuildingPlan;
import com.mcai.common.PlanGenerator;
import com.mcai.common.PlanParser;
import com.mcai.common.PlanSpec;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * AI 建造主界面：输入描述与坐标，生成方案，预览，确认建造。
 */
public class AiBuildScreen extends Screen {
	private static final int PANEL_W = 380;
	private static final int PANEL_H = 240;

	private final PlanPreviewWidget preview = new PlanPreviewWidget();
	private final AiConfig config;
	private EditBox descField;
	private EditBox posXField;
	private EditBox posYField;
	private EditBox posZField;
	private EditBox posX2Field;
	private EditBox posY2Field;
	private EditBox posZ2Field;
	private Button generateButton;
	private Button buildButton;
	private Button undoButton;

	private String status;
	private boolean busy = false;
	private BuildingPlan currentPlan;
	private BlockPos currentOrigin;
	private BlockPos currentBound;
	private CompletableFuture<String> pendingTask;
	private PreeditEvent lastPreedit;
	private long requestStartMs;
	private static final int STATUS_LINES = 3;
	private static final int STATUS_LINE_H = 14;
	private String lastStatus;
	private List<String> statusLines = List.of();
	private int statusScroll = 0;

	public AiBuildScreen(AiConfig config) {
		super(Component.literal("AI 建造助手"));
		this.config = config;
		this.status = "输入建筑描述，点击「生成方案」（当前模型：" + config.modelName() + "）";
	}

	@Override
	protected void init() {
		AiClient.isAvailable(config).thenAcceptAsync(ok -> {
			if (!ok && this.minecraft != null) {
				this.minecraft.execute(() -> {
					status = "警告：AI 后端不可达（" + config.chatEndpoint() + "），请检查配置或 Ollama 是否启动";
				});
			}
		}, Runnable::run);
		int cx = this.width / 2;
		int cy = this.height / 2;

		int x0 = cx - PANEL_W / 2;
		int y0 = cy - PANEL_H / 2;

		// 默认位置：玩家面前 10 格
		BlockPos start = defaultOrigin();

		descField = new EditBox(this.font, x0 + 10, y0 + 22, PANEL_W - 20, 20,
				Component.literal("建筑描述"));
		descField.setMaxLength(10000);
		descField.setHint(Component.literal("例：在河边建一座两层中式小楼，带院子"));
		addRenderableWidget(descField);
		setInitialFocus(descField);

		int fieldY = y0 + 54;
		int fieldW = 60;
		posXField = new EditBox(this.font, x0 + 70, fieldY, fieldW, 16, Component.literal("X"));
		posYField = new EditBox(this.font, x0 + 140, fieldY, fieldW, 16, Component.literal("Y"));
		posZField = new EditBox(this.font, x0 + 210, fieldY, fieldW, 16, Component.literal("Z"));
		posXField.setValue(String.valueOf(start.getX()));
		posYField.setValue(String.valueOf(start.getY()));
		posZField.setValue(String.valueOf(start.getZ()));
		posXField.setMaxLength(10);
		posYField.setMaxLength(10);
		posZField.setMaxLength(10);
		addRenderableWidget(posXField);
		addRenderableWidget(posYField);
		addRenderableWidget(posZField);

		// 第二组坐标：空间终点（可留空 = 不限空间）
		int fieldY2 = y0 + 74;
		posX2Field = new EditBox(this.font, x0 + 70, fieldY2, fieldW, 16, Component.literal("X2"));
		posY2Field = new EditBox(this.font, x0 + 140, fieldY2, fieldW, 16, Component.literal("Y2"));
		posZ2Field = new EditBox(this.font, x0 + 210, fieldY2, fieldW, 16, Component.literal("Z2"));
		posX2Field.setHint(Component.literal("终点X"));
		posY2Field.setHint(Component.literal("终点Y"));
		posZ2Field.setHint(Component.literal("终点Z"));
		posX2Field.setMaxLength(10);
		posY2Field.setMaxLength(10);
		posZ2Field.setMaxLength(10);
		addRenderableWidget(posX2Field);
		addRenderableWidget(posY2Field);
		addRenderableWidget(posZ2Field);

		// 预览区域
		preview.setViewport(x0 + PANEL_W / 2, y0 + 131, PANEL_W - 16, 56);

		generateButton = Button.builder(Component.literal("生成方案"),
				b -> requestPlan())
				.bounds(x0 + 10, y0 + PANEL_H - 26, 80, 20)
				.build();
		buildButton = Button.builder(Component.literal("确认建造"),
				b -> executeBuild())
				.bounds(x0 + 100, y0 + PANEL_H - 26, 80, 20)
				.build();
		undoButton = Button.builder(Component.literal("撤销上次"),
				b -> undoBuild())
				.bounds(x0 + 190, y0 + PANEL_H - 26, 80, 20)
				.build();
		Button configButton = Button.builder(Component.literal("设置"),
				b -> {
					if (this.minecraft != null) {
						this.minecraft.setScreen(new AiConfigScreen(config));
					}
				})
				.bounds(x0 + 290, y0 + PANEL_H - 26, 80, 20)
				.build();
		addRenderableWidget(generateButton);
		addRenderableWidget(buildButton);
		addRenderableWidget(undoButton);
		addRenderableWidget(configButton);
	}

	private BlockPos defaultOrigin() {
		if (this.minecraft != null && this.minecraft.player != null) {
			Vec3 pos = this.minecraft.player.getPosition(1.0f);
			Vec3 look = this.minecraft.player.getViewVector(1.0f).normalize();
			BlockPos base = BlockPos.containing(pos);
			int x = base.getX() + (int) Math.round(look.x * 10);
			int y = (int) Math.round(pos.y);
			int z = base.getZ() + (int) Math.round(look.z * 10);
			return new BlockPos(x, y, z);
		}
		return new BlockPos(0, 64, 0);
	}

	/** 从玩家描述提取明确楼层数（如"20 层"、"至少 15 层"、"层数 8"）；没有则 null */
	private static Integer extractFloorHint(String description) {
		if (description == null) {
			return null;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("(\\d+)\\s*(?:楼)?层|层(?:数)?\\s*(?:为|=)?\\s*(\\d+)").matcher(description);
		if (m.find()) {
			String v = m.group(1) != null ? m.group(1) : m.group(2);
			if (v != null) {
				return Integer.parseInt(v);
			}
		}
		return null;
	}

	private void requestPlan() {
		if (busy) {
			return;
		}
		String desc = descField.getValue().trim();
		if (desc.isEmpty()) {
			status = "请先输入建筑描述";
			return;
		}
		BlockPos origin = readOrigin();
		if (origin == null) {
			status = "坐标格式错误，请输入整数 X Y Z";
			return;
		}
		BlockPos bound = readBound();
		if (bound != null) {
			// 归一化：无论先输入哪一角，都取 min 为起点、max 为终点
			BlockPos lo = new BlockPos(Math.min(origin.getX(), bound.getX()),
					Math.min(origin.getY(), bound.getY()), Math.min(origin.getZ(), bound.getZ()));
			BlockPos hi = new BlockPos(Math.max(origin.getX(), bound.getX()),
					Math.max(origin.getY(), bound.getY()), Math.max(origin.getZ(), bound.getZ()));
			origin = lo;
			bound = hi;
		}

		busy = true;
		updateButtons();
		currentOrigin = origin;
		currentBound = bound;
		requestStartMs = System.currentTimeMillis();
		status = "AI 设计中…（约 10~90 秒，AI 正在画楼层蓝图）";

		String posText = origin.getX() + " " + origin.getY() + " " + origin.getZ();
		if (bound != null) {
			int w = bound.getX() - origin.getX() + 1;
			int d = bound.getZ() - origin.getZ() + 1;
			int h = bound.getY() - origin.getY() + 1;
			posText += "，可用空间立方体：从 (" + origin.getX() + " " + origin.getY() + " " + origin.getZ()
					+ ") 到 (" + bound.getX() + " " + bound.getY() + " " + bound.getZ()
					+ ")（宽 " + w + " 深 " + d + " 高 " + h + "），建筑要尽量填满这个空间，但绝不能超出它的范围";
		}
		pendingTask = AiClient.askPlan(desc, posText, config);
		pendingTask.whenComplete((reply, error) -> {
			if (this.minecraft == null) {
				return;
			}
			this.minecraft.execute(() -> {
				busy = false;
				pendingTask = null;
				if (error != null) {
					status = "出错：" + rootMessage(error);
					updateButtons();
					return;
				}
try {
					PlanSpec spec = PlanParser.parse(reply);
					PlanParser.applyFloorHint(spec, extractFloorHint(desc));
					MinecraftAIMod.LOGGER.info("[Minecraft AI] AI 回复: {}", reply);
					MinecraftAIMod.LOGGER.info("[Minecraft AI] 解析方案: name={} floors={} wall={} accent={} roof={} interiors={} repeat={} maps={}",
							spec.name, spec.floors, spec.wall, spec.accent, spec.roof, spec.interiors, spec.repeat,
							spec.floorsMap == null ? 0 : spec.floorsMap.size());
					currentPlan = PlanGenerator.generate(spec, desc);
					preview.setPlan(currentPlan);
					status = "方案「" + currentPlan.name + "」已生成：" + currentPlan.width + "x" + currentPlan.height
							+ "x" + currentPlan.depth + "，共 " + currentPlan.size() + " 个方块。确认后建造于 "
							+ currentOrigin.getX() + ", " + currentOrigin.getY() + ", " + currentOrigin.getZ();
					if (currentBound != null) {
						String over = overBoundText(currentPlan, currentOrigin, currentBound);
						if (over != null) {
							status += "。" + over + "（确认建造将按完整方案放置，不做裁剪）";
						}
					}
				} catch (IllegalArgumentException e) {
					status = "AI 方案解析失败：" + e.getMessage() + "（可点「生成方案」重试）";
				}
				updateButtons();
			});
		});
		updateButtons();
	}

	private void executeBuild() {
		if (currentPlan == null || currentOrigin == null || busy) {
			return;
		}
		if (this.minecraft == null || this.minecraft.getSingleplayerServer() == null) {
			status = "仅支持单人游戏！多人服务器需要服务器端支持";
			return;
		}
		try {
			var server = this.minecraft.getSingleplayerServer();
			var world = server.getLevel(this.minecraft.level.dimension());
			if (world == null) {
				status = "建造失败：找不到当前维度";
				return;
			}
			busy = true;
			updateButtons();
			status = "正在建造「" + currentPlan.name + "」…";
			// 用户确认建造 = 接受可能超出空间，按 AI 完整方案放置，不做裁剪
			var future = BuildingExecutor.execute(server, world, currentOrigin, currentPlan, null);
			future.whenComplete((placed, error) -> {
				if (this.minecraft == null) {
					return;
				}
				this.minecraft.execute(() -> {
					busy = false;
					if (error != null) {
						status = "建造失败：" + rootMessage(error);
					} else {
						status = "建造完成！共放置 " + placed + " 个方块，可点「撤销上次」恢复";
					}
					updateButtons();
				});
			});
		} catch (Exception e) {
			status = "建造失败：" + e.getMessage();
		}
	}

	private void undoBuild() {
		if (this.minecraft == null || this.minecraft.getSingleplayerServer() == null) {
			return;
		}
		var server = this.minecraft.getSingleplayerServer();
		status = "撤销中…";
		var future = BuildingExecutor.undo(server);
		future.whenComplete((ok, error) -> {
			if (this.minecraft == null) {
				return;
			}
			this.minecraft.execute(() -> {
				if (error != null) {
					status = "撤销失败：" + rootMessage(error);
				} else if (Boolean.TRUE.equals(ok)) {
					status = "已撤销上次建造";
				} else {
					status = "没有可撤销的建造记录";
				}
				updateButtons();
			});
		});
	}

	private BlockPos readOrigin() {
		try {
			int x = Integer.parseInt(posXField.getValue().trim());
			int y = Integer.parseInt(posYField.getValue().trim());
			int z = Integer.parseInt(posZField.getValue().trim());
			return new BlockPos(x, y, z);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * 计算建筑相对空间约束 origin..bound 的超出量。
	 * 返回描述文本；不超出返回 null。
	 */
	private static String overBoundText(BuildingPlan plan, BlockPos origin, BlockPos bound) {
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (BuildingPlan.Entry e : plan.entries) {
			minX = Math.min(minX, e.x());
			maxX = Math.max(maxX, e.x());
			minY = Math.min(minY, e.y());
			maxY = Math.max(maxY, e.y());
			minZ = Math.min(minZ, e.z());
			maxZ = Math.max(maxZ, e.z());
		}
		StringBuilder over = new StringBuilder();
		int overWest = origin.getX() - (origin.getX() + minX);
		int overEast = (origin.getX() + maxX) - bound.getX();
		int overDown = origin.getY() - (origin.getY() + minY);
		int overUp = (origin.getY() + maxY) - bound.getY();
		int overNorth = origin.getZ() - (origin.getZ() + minZ);
		int overSouth = (origin.getZ() + maxZ) - bound.getZ();
		if (overWest > 0) over.append("西侧 ").append(overWest).append(" 格；");
		if (overEast > 0) over.append("东侧 ").append(overEast).append(" 格；");
		if (overDown > 0) over.append("下方 ").append(overDown).append(" 格；");
		if (overUp > 0) over.append("上方 ").append(overUp).append(" 格；");
		if (overNorth > 0) over.append("北侧 ").append(overNorth).append(" 格；");
		if (overSouth > 0) over.append("南侧 ").append(overSouth).append(" 格；");
		if (over.length() == 0) {
			return null;
		}
		return "建筑超出空间：" + over.substring(0, over.length() - 1);
	}

	/** 空间终点坐标；留空返回 null（不限空间） */
	private BlockPos readBound() {		String sx = posX2Field.getValue().trim();
		String sy = posY2Field.getValue().trim();
		String sz = posZ2Field.getValue().trim();
		if (sx.isEmpty() && sy.isEmpty() && sz.isEmpty()) {
			return null;
		}
		try {
			return new BlockPos(Integer.parseInt(sx), Integer.parseInt(sy), Integer.parseInt(sz));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void updateButtons() {
		generateButton.active = !busy;
		buildButton.active = currentPlan != null && !busy;
		undoButton.active = BuildingExecutor.canUndo();
	}

	private static String rootMessage(Throwable t) {
		Throwable cur = t;
		while (cur.getCause() != null) {
			cur = cur.getCause();
		}
		return cur.getMessage() == null ? cur.toString() : cur.getMessage();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		int cx = this.width / 2;
		int cy = this.height / 2;
		int x0 = cx - PANEL_W / 2;
		int y0 = cy - PANEL_H / 2;

		// 面板背景：画在 widgets 下层（先画），物品栏风格的深色半透明
		context.fill(x0 - 4, y0 - 4, x0 + PANEL_W + 4, y0 + PANEL_H + 4, 0xC0101010);
		context.fill(x0, y0, x0 + PANEL_W, y0 + PANEL_H, 0xE62A2F38);
		// 预览区背景
		context.fill(x0, y0 + 96, x0 + PANEL_W, y0 + 162, 0xB0000000);

		super.extractRenderState(context, mouseX, mouseY, delta);

		// 标题与标签（最后画，保证在最上层）。标题放在面板内顶部，避免窗口小时出屏
		context.centeredText(this.font, "AI 建造助手", cx, y0 + 4, 0xFFFFFFFF);
		context.text(this.font, "起点：", x0 + 10, y0 + 57, 0xFFE0E0E0);
		context.text(this.font, "终点：", x0 + 10, y0 + 77, 0xFFE0E0E0);
		context.centeredText(this.font, "3D 预览", cx, y0 + 100, 0xFFC0C0C0);

		preview.setViewport(x0 + PANEL_W / 2, y0 + 131, PANEL_W - 16, 56);
		preview.render(context);

		drawStatus(context, x0 + 10, y0 + 168, PANEL_W - 20);

		drawImePreview(context);
	}

	/** 状态文本：超宽时按字符宽度拆行，最多显示 3 行；行数更多时滚轮滚动查看 */
	private void drawStatus(GuiGraphicsExtractor context, int x, int y, int maxWidth) {
		if (!status.equals(lastStatus)) {
			lastStatus = status;
			statusLines = splitLines(status, maxWidth);
			statusScroll = 0;
		}
		int n = statusLines.size();
		if (n == 0) {
			return;
		}
		if (n <= STATUS_LINES) {
			for (int i = 0; i < n; i++) {
				context.text(this.font, statusLines.get(i), x, y + i * STATUS_LINE_H, statusColor());
			}
			return;
		}
		int maxScroll = n - STATUS_LINES;
		if (statusScroll > maxScroll) {
			statusScroll = maxScroll;
		}
		int start = n - STATUS_LINES - statusScroll;
		for (int i = 0; i < STATUS_LINES; i++) {
			context.text(this.font, statusLines.get(start + i), x, y + i * STATUS_LINE_H, statusColor());
		}
	}

	private List<String> splitLines(String s, int maxWidth) {
		List<String> lines = new ArrayList<>();
		if (s.isEmpty()) {
			lines.add("");
			return lines;
		}
		String rest = s;
		while (!rest.isEmpty()) {
			if (this.font.width(rest) <= maxWidth) {
				lines.add(rest);
				break;
			}
			int cut = 0;
			int w = 0;
			while (cut < rest.length()) {
				int cw = this.font.width(String.valueOf(rest.charAt(cut)));
				if (w + cw > maxWidth) {
					break;
				}
				w += cw;
				cut++;
			}
			if (cut == 0) {
				cut = 1;
			}
			lines.add(rest.substring(0, cut));
			rest = rest.substring(cut);
		}
		return lines;
	}

	/** 鼠标滚轮：状态文本超过 3 行时滚动查看（向上滚看更早的内容） */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int maxScroll = Math.max(0, statusLines.size() - STATUS_LINES);
		if (maxScroll > 0) {
			statusScroll = Math.max(0, Math.min(statusScroll + (int) verticalAmount, maxScroll));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	private int statusColor() {
		if (status.startsWith("出错") || status.startsWith("AI 方案解析失败") || status.startsWith("建造失败")
				|| status.startsWith("撤销失败")) {
			return 0xFFFF5555;
		}
		if (status.startsWith("AI 思考中") || status.startsWith("正在建造") || status.startsWith("撤销中")) {
			return 0xFFFFC94F;
		}
		if (status.startsWith("建造完成")) {
			return 0xFF4ADE80;
		}
		return 0xFF66FF66;
	}

	@Override
	public boolean preeditUpdated(PreeditEvent event) {
		MinecraftAIMod.LOGGER.info("[IME] preeditUpdated event={} descFocused={}",
				event == null ? "null" : event.toString(),
				descField != null && descField.isFocused());
		this.lastPreedit = event;
		if (descField != null && descField.isFocused()) {
			return descField.preeditUpdated(event);
		}
		return super.preeditUpdated(event);
	}

	/**
	 * 锚定系统候选窗口到输入框（拼音黑框已按用户要求移除）。
	 * MC 26.1.2 自带的 IMEPreeditOverlay 渲染链路在当前环境不显示，
	 * 只保留候选窗定位，让系统选字窗出现在输入框上方。
	 */
	private void drawImePreview(GuiGraphicsExtractor context) {
		if (descField == null || lastPreedit == null) {
			return;
		}
		String txt = lastPreedit.fullText();
		if (txt == null || txt.isEmpty()) {
			return;
		}
		int fx = descField.getX();
		int fy = descField.getY();
		if (this.minecraft != null) {
			this.minecraft.textInputManager().setTextInputArea(
					fx, fy, fx + descField.getWidth(), fy + descField.getHeight());
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	@Override
	public void tick() {
		super.tick();
		if (busy && pendingTask != null && !pendingTask.isDone()) {
			long s = (System.currentTimeMillis() - requestStartMs) / 1000;
			status = "AI 设计中…（已等待 " + s + " 秒，AI 正在画楼层蓝图）";
		}
	}

	@Override
	public void onClose() {
		if (pendingTask != null) {
			pendingTask.cancel(true);
		}
		super.onClose();
	}
}