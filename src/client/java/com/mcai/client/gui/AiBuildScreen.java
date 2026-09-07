package com.mcai.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mcai.MinecraftAIMod;
import com.mcai.client.ai.AiClient;
import com.mcai.client.ai.BuildTaskManager;
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
	private Button adjustButton;
	private Button buildButton;
	private Button undoButton;

	private String status;
	private boolean busy = false;
	private BuildingPlan currentPlan;
	private BlockPos currentOrigin;
	private BlockPos currentBound;
	private PreeditEvent lastPreedit;
	private BuildTaskManager.Phase lastPhase;
	/** 调整流程状态：0=普通模式，1=已载入上次方案等待输入意见 */
	private int adjustStage = 0;
	/** 打开时是否直接展示上一次的生成结果（点击悬浮球进入时为 true） */
	private final boolean showResult;
	private static final int STATUS_LINES = 3;
	private static final int STATUS_LINE_H = 14;
	private String lastStatus;
	private List<String> statusLines = List.of();
	private int statusScroll = 0;
	private boolean previewDragging = false;
	private boolean rotatePressed = false;
	private boolean rotateDragging = false;

	public AiBuildScreen(AiConfig config) {
		this(config, false);
	}

	public AiBuildScreen(AiConfig config, boolean showResult) {
		super(Component.literal("AI 建造助手"));
		this.config = config;
		this.showResult = showResult;
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
				.bounds(x0 + 10, y0 + PANEL_H - 26, 68, 20)
				.build();
		adjustButton = Button.builder(Component.literal("按意见调整"),
				b -> adjustPlan())
				.bounds(x0 + 82, y0 + PANEL_H - 26, 68, 20)
				.build();
		buildButton = Button.builder(Component.literal("确认建造"),
				b -> executeBuild())
				.bounds(x0 + 154, y0 + PANEL_H - 26, 68, 20)
				.build();
		undoButton = Button.builder(Component.literal("撤销上次"),
				b -> undoBuild())
				.bounds(x0 + 226, y0 + PANEL_H - 26, 68, 20)
				.build();
		Button configButton = Button.builder(Component.literal("设置"),
				b -> {
					if (this.minecraft != null) {
						this.minecraft.setScreen(new AiConfigScreen(config));
					}
				})
				.bounds(x0 + 298, y0 + PANEL_H - 26, 68, 20)
				.build();
		addRenderableWidget(generateButton);
		addRenderableWidget(adjustButton);
		addRenderableWidget(buildButton);
		addRenderableWidget(undoButton);
		addRenderableWidget(configButton);

		// 从全局任务管理器恢复：默认干净界面；生成中显示进度；
		// 点悬浮球进入(showResult)或有未查看的新结果(按K)时展示上次结果
		BuildTaskManager m = BuildTaskManager.INSTANCE;
		if (m.isGenerating()) {
			status = "AI 设计中…（已等待 " + m.elapsedSeconds() + " 秒，AI 正在画楼层蓝图）。可按 ESC 关闭面板，生成不会中断";
		} else if ((showResult || m.hasFreshResult()) && m.plan() != null) {
			currentPlan = m.plan();
			currentOrigin = m.origin() != null ? m.origin() : currentOrigin;
			currentBound = m.bound() != null ? m.bound() : currentBound;
			preview.setPlan(currentPlan);
			if (currentOrigin != null) {
				posXField.setValue(String.valueOf(currentOrigin.getX()));
				posYField.setValue(String.valueOf(currentOrigin.getY()));
				posZField.setValue(String.valueOf(currentOrigin.getZ()));
			}
			if (currentBound != null) {
				posX2Field.setValue(String.valueOf(currentBound.getX()));
				posY2Field.setValue(String.valueOf(currentBound.getY()));
				posZ2Field.setValue(String.valueOf(currentBound.getZ()));
			}
			status = "方案「" + currentPlan.name + "」" + (m.isAdjusted() ? "已按意见调整" : "已生成")
					+ "：" + currentPlan.width + "x" + currentPlan.height + "x" + currentPlan.depth
					+ "，共 " + currentPlan.size() + " 个方块。确认后建造于 "
					+ (currentOrigin == null ? "-" : currentOrigin.getX() + ", " + currentOrigin.getY() + ", " + currentOrigin.getZ());
			m.acknowledge();
		} else if (m.phase() == BuildTaskManager.Phase.FAILED) {
			status = "出错：" + m.errorMsg() + "（可重试）";
			m.acknowledge();
		}
		lastPhase = m.phase();
		updateButtons();
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

	/** 从玩家描述提取明确楼层数的逻辑已移至 BuildTaskManager */

	private void requestPlan() {
		if (BuildTaskManager.INSTANCE.isGenerating() || busy) {
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

		currentOrigin = origin;
		currentBound = bound;
		adjustStage = 0;
		adjustButton.setMessage(Component.literal("调整上次方案"));
		status = "AI 设计中…（约 10~90 秒，AI 正在画楼层蓝图）";
		lastPhase = BuildTaskManager.Phase.GENERATING;

		String posText = origin.getX() + " " + origin.getY() + " " + origin.getZ();
		if (bound != null) {
			int w = bound.getX() - origin.getX() + 1;
			int d = bound.getZ() - origin.getZ() + 1;
			int h = bound.getY() - origin.getY() + 1;
			posText += "，可用空间立方体：从 (" + origin.getX() + " " + origin.getY() + " " + origin.getZ()
					+ ") 到 (" + bound.getX() + " " + bound.getY() + " " + bound.getZ()
					+ ")（宽 " + w + " 深 " + d + " 高 " + h + "），建筑要尽量填满这个空间，但绝不能超出它的范围";
		}
		BuildTaskManager.INSTANCE.start(this.minecraft, desc, posText, origin, bound);
		updateButtons();
	}

	/** 按意见调整：第一次点击载入上次方案（坐标/预览），第二次点击（按钮变「确认调整」）发起调整 */
	private void adjustPlan() {
		BuildTaskManager m = BuildTaskManager.INSTANCE;
		if (m.isGenerating() || busy) {
			return;
		}
		if (adjustStage == 0) {
			if (m.plan() == null) {
				status = "还没有可调整的方案，请先用「生成方案」创建";
				return;
			}
			adjustStage = 1;
			currentPlan = m.plan();
			currentOrigin = m.origin() != null ? m.origin() : currentOrigin;
			currentBound = m.bound() != null ? m.bound() : currentBound;
			preview.setPlan(currentPlan);
			if (currentOrigin != null) {
				posXField.setValue(String.valueOf(currentOrigin.getX()));
				posYField.setValue(String.valueOf(currentOrigin.getY()));
				posZField.setValue(String.valueOf(currentOrigin.getZ()));
			}
			if (currentBound != null) {
				posX2Field.setValue(String.valueOf(currentBound.getX()));
				posY2Field.setValue(String.valueOf(currentBound.getY()));
				posZ2Field.setValue(String.valueOf(currentBound.getZ()));
			} else {
				posX2Field.setValue("");
				posY2Field.setValue("");
				posZ2Field.setValue("");
			}
			status = "已载入上次方案「" + currentPlan.name + "」。请在输入框输入要调整的内容，再点「确认调整」";
			adjustButton.setMessage(Component.literal("确认调整"));
			return;
		}
		String opinion = descField.getValue().trim();
		if (opinion.isEmpty()) {
			status = "请先在输入框输入要调整的内容（如：三层改成露台，窗户多一倍）";
			return;
		}
		adjustStage = 0;
		adjustButton.setMessage(Component.literal("调整上次方案"));
		status = "正在按意见调整方案…（约 10~90 秒）";
		lastPhase = BuildTaskManager.Phase.GENERATING;
		m.adjust(this.minecraft, opinion);
		updateButtons();
	}

	/** 每帧同步全局任务状态（生成完成/失败时更新界面） */
	private void syncManagerState() {
		BuildTaskManager m = BuildTaskManager.INSTANCE;
		BuildTaskManager.Phase p = m.phase();
		if (p != lastPhase) {
			lastPhase = p;
			switch (p) {
				case SUCCESS -> {
					adjustStage = 0;
					adjustButton.setMessage(Component.literal("调整上次方案"));
					currentPlan = m.plan();
					currentOrigin = m.origin() != null ? m.origin() : currentOrigin;
					currentBound = m.bound() != null ? m.bound() : currentBound;
					preview.setPlan(currentPlan);
					status = "方案「" + currentPlan.name + "」" + (m.isAdjusted() ? "已按意见调整" : "已生成")
							+ "：" + currentPlan.width + "x" + currentPlan.height + "x" + currentPlan.depth
							+ "，共 " + currentPlan.size() + " 个方块。确认后建造于 "
							+ currentOrigin.getX() + ", " + currentOrigin.getY() + ", " + currentOrigin.getZ();
					if (currentBound != null) {
						String over = overBoundText(currentPlan, currentOrigin, currentBound);
						if (over != null) {
							status += "。" + over + "（确认建造将按完整方案放置，不做裁剪）";
						}
					}
					m.acknowledge();
				}
				case FAILED -> {
					status = "出错：" + m.errorMsg() + "（可重试）";
					m.acknowledge();
				}
				default -> {
				}
			}
			updateButtons();
		}
	}

	private void executeBuild() {
		if (currentPlan == null || currentOrigin == null || busy) {
			return;
		}
		if (this.minecraft == null || this.minecraft.getSingleplayerServer() == null) {
			status = "仅支持单人游戏！多人服务器需要服务器端支持";
			return;
		}
		var server = this.minecraft.getSingleplayerServer();
		var world = server.getLevel(this.minecraft.level.dimension());
		if (world == null) {
			status = "建造失败：找不到当前维度";
			return;
		}
		// 调整后的方案 + 有已建造记录 = 替换：先自动撤销旧建筑再建新的
		boolean replacing = BuildTaskManager.INSTANCE.isAdjusted() && BuildingExecutor.canUndo();
		busy = true;
		updateButtons();
		if (replacing) {
			status = "正在替换原建筑…（先撤销旧建筑）";
			BuildingExecutor.undo(server).whenComplete((ok, error) -> {
				if (this.minecraft == null) {
					return;
				}
				this.minecraft.execute(() -> startBuild(server, world));
			});
		} else {
			startBuild(server, world);
		}
	}

	private void startBuild(net.minecraft.server.MinecraftServer server, net.minecraft.server.level.ServerLevel world) {
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
					// 已确认生成，右上角悬浮球消失，下次生成时才再次出现
					BuildTaskManager.INSTANCE.acknowledge();
				}
				updateButtons();
			});
		});
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
		boolean generating = BuildTaskManager.INSTANCE.isGenerating();
		// 调整按钮基于全局保存的上次方案（干净界面下也可载入调整）
		boolean hasLastPlan = BuildTaskManager.INSTANCE.plan() != null;
		generateButton.active = !generating && !busy;
		adjustButton.active = hasLastPlan && !generating && !busy;
		buildButton.active = currentPlan != null && !generating && !busy;
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
		syncManagerState();
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
		context.centeredText(this.font, "3D 预览（滚轮缩放 · 右键拖拽旋转 · 左键拖拽平移 · 双击复位）", cx, y0 + 100, 0xFFC0C0C0);

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

	/** 鼠标滚轮：预览区内缩放 3D 预览；预览区外滚动状态文本（向上滚看更早的内容） */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (preview.hasPlan() && preview.contains(mouseX, mouseY)) {
			preview.zoom(verticalAmount);
			return true;
		}
		int maxScroll = Math.max(0, statusLines.size() - STATUS_LINES);
		if (maxScroll > 0) {
			statusScroll = Math.max(0, Math.min(statusScroll + (int) verticalAmount, maxScroll));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	/** 预览区内：右键拖拽无级旋转（右键单击 = 旋转 90°），双击复位，左键拖拽平移 */
	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (preview.hasPlan() && preview.contains(event.x(), event.y())) {
			if (event.button() == 1) {
				rotatePressed = true;
				rotateDragging = false;
				return true;
			}
			if (doubleClick) {
				preview.resetView();
				return true;
			}
			if (event.button() == 0) {
				previewDragging = true;
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
		if (rotatePressed && event.button() == 1) {
			// 位移超过阈值才算拖拽旋转，否则松开时按 90° 步进处理
			if (Math.abs(dragX) + Math.abs(dragY) > 0.5) {
				rotateDragging = true;
			}
			if (rotateDragging) {
				preview.rotateBy(dragX * 0.012, dragY * 0.012);
			}
			return true;
		}
		if (previewDragging) {
			preview.pan(dragX, dragY);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
		if (rotatePressed && event.button() == 1 && !rotateDragging) {
			preview.rotateCw();
		}
		rotatePressed = false;
		rotateDragging = false;
		previewDragging = false;
		return super.mouseReleased(event);
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
		if (BuildTaskManager.INSTANCE.isGenerating()) {
			long s = BuildTaskManager.INSTANCE.elapsedSeconds();
			status = "AI 设计中…（已等待 " + s + " 秒，AI 正在画楼层蓝图）。可按 ESC 关闭面板，生成不会中断";
		}
	}

	@Override
	public void onClose() {
		// 生成任务在全局管理器中继续执行，关闭面板不中断；重开面板自动恢复进度与结果
		super.onClose();
	}
}