package com.mcai.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.mcai.MinecraftAIMod;
import com.mcai.client.ai.AiClient;
import com.mcai.client.ai.BuildTaskManager;
import com.mcai.client.ai.BuildTaskManager.ChatMsg;
import com.mcai.client.ai.BuildTaskManager.ChatSession;
import com.mcai.client.ai.BuildTaskManager.MsgRole;
import com.mcai.client.render.PlanPreviewWidget;
import com.mcai.common.AiConfig;
import com.mcai.common.BuildingExecutor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * AI 建造对话界面（Minecraft 像素风格聊天 UI）。
 * 底部输入提示词发送；上方是坐标范围；生成后展示思考过程（可折叠）与 3D 预览；
 * 确认后生成到世界；同一会话继续发言 = 修改；新建会话 = 新建筑。
 */
public class AiBuildScreen extends Screen {

	// ---- 配色（MC 像素风） ----
	private static final int COL_PANEL = 0xF01A1D24;
	private static final int COL_PANEL_BORDER = 0xFF0A0A0A;
	private static final int COL_CHAT_BG = 0xE612151A;
	private static final int COL_USER_BUBBLE = 0xFF3D4450;
	private static final int COL_AI_BUBBLE = 0xFF243024;
	private static final int COL_SYS_BUBBLE = 0xFF2A2A2A;
	private static final int COL_ERR_BUBBLE = 0xFF3A2020;
	private static final int COL_THINK_BG = 0xFF2A2818;
	private static final int COL_THINK_BORDER = 0xFF8A7020;
	private static final int COL_TEXT = 0xFFE8E8E8;
	private static final int COL_DIM = 0xFF9A9A9A;
	private static final int COL_ACCENT = 0xFF66FF66;
	private static final int COL_WARN = 0xFFFFC94F;
	private static final int COL_ERR = 0xFFFF5555;
	private static final int COL_OK = 0xFF4ADE80;
	private static final int COL_INPUT_BG = 0xFF0E1014;
	private static final int COL_INPUT_BORDER = 0xFF555555;
	private static final int COL_BTN = 0xFF8B8B8B;
	private static final int COL_BTN_EDGE = 0xFF3A3A3A;

	private final PlanPreviewWidget preview = new PlanPreviewWidget();
	private final AiConfig config;
	private final boolean showResult;

	private EditBox inputField;
	private EditBox posXField, posYField, posZField;
	private EditBox posX2Field, posY2Field, posZ2Field;
	private Button sendButton;
	private Button buildButton;
	private Button undoButton;
	private Button settingsButton;
	private Button newSessionButton;
	private Button cancelButton;

	// 布局
	private int panelX, panelY, panelW, panelH;
	private int chatX, chatY, chatW, chatH;
	private int inputY, inputH;

	// 消息滚动（像素）
	private int scrollY = 0;
	private int contentH = 0;

	// 思考折叠点击区（最近若干条）
	private final List<int[]> thinkHitboxes = new ArrayList<>(); // x,y,w,h, msgIndex
	private final List<int[]> planButtonBoxes = new ArrayList<>(); // build/undo buttons on plan msgs

	private boolean previewDragging = false;
	private boolean rotatePressed = false;
	private boolean rotateDragging = false;
	private int previewMsgIndex = -1; // 当前预览绑定到哪条消息
	private PreeditEvent lastPreedit;
	private String lastErrorStatus = "";
	private BuildTaskManager.Phase lastPhase = BuildTaskManager.Phase.IDLE;

	public AiBuildScreen(AiConfig config) {
		this(config, false);
	}

	public AiBuildScreen(AiConfig config, boolean showResult) {
		super(Component.literal("AI 建造助手"));
		this.config = config;
		this.showResult = showResult;
	}

	@Override
	protected void init() {
		layout();
		BuildTaskManager m = BuildTaskManager.INSTANCE;

		BlockPos start = defaultOrigin();
		if (m.session().origin != null) {
			start = m.session().origin;
		}

		// 坐标：起点 X Y Z | 终点 X Y Z（标签画在控件上层，见 extractRenderState）
		int cw = 42;
		int gap = 4;
		// [起点] 30px | X Y Z | 间距+标签 | X Y Z
		int labelW = 30;
		int midGap = 40; // 「终点」标签宽度
		int fx = panelX + 8 + labelW;
		posXField = new EditBox(this.font, fx, coordY, cw, 14, Component.literal("X"));
		posXField.setMaxLength(10);
		posXField.setValue(String.valueOf(start.getX()));
		fx += cw + gap;
		posYField = new EditBox(this.font, fx, coordY, cw, 14, Component.literal("Y"));
		posYField.setMaxLength(10);
		posYField.setValue(String.valueOf(start.getY()));
		fx += cw + gap;
		posZField = new EditBox(this.font, fx, coordY, cw, 14, Component.literal("Z"));
		posZField.setMaxLength(10);
		posZField.setValue(String.valueOf(start.getZ()));
		addRenderableWidget(posXField);
		addRenderableWidget(posYField);
		addRenderableWidget(posZField);

		fx += cw + midGap; // 留给「终点」二字
		posX2Field = new EditBox(this.font, fx, coordY, cw, 14, Component.literal("X2"));
		posX2Field.setMaxLength(10);
		posX2Field.setHint(Component.literal("X"));
		fx += cw + gap;
		posY2Field = new EditBox(this.font, fx, coordY, cw, 14, Component.literal("Y2"));
		posY2Field.setMaxLength(10);
		posY2Field.setHint(Component.literal("Y"));
		fx += cw + gap;
		posZ2Field = new EditBox(this.font, fx, coordY, cw, 14, Component.literal("Z2"));
		posZ2Field.setMaxLength(10);
		posZ2Field.setHint(Component.literal("Z"));
		if (m.session().bound != null) {
			posX2Field.setValue(String.valueOf(m.session().bound.getX()));
			posY2Field.setValue(String.valueOf(m.session().bound.getY()));
			posZ2Field.setValue(String.valueOf(m.session().bound.getZ()));
		}
		addRenderableWidget(posX2Field);
		addRenderableWidget(posY2Field);
		addRenderableWidget(posZ2Field);

		int btnH = 20;
		int inputW = panelW - 20 - 70 - 8;
		inputField = new EditBox(this.font, panelX + 10, inputY, inputW, inputH, Component.literal("提示词"));
		inputField.setMaxLength(10000);
		inputField.setHint(Component.literal("输入提示词，发送生成/修改…"));
		addRenderableWidget(inputField);
		setInitialFocus(inputField);

		sendButton = Button.builder(Component.literal("发送"), b -> doSend())
				.bounds(panelX + 10 + inputW + 8, inputY, 62, inputH)
				.build();
		addRenderableWidget(sendButton);

		// 底栏按钮：左侧三个、右侧两个，互不重叠
		int toolY = panelY + panelH - btnH - 4;
		buildButton = Button.builder(Component.literal("确认建造"), b -> executeBuild())
				.bounds(panelX + 8, toolY, 70, btnH)
				.build();
		undoButton = Button.builder(Component.literal("撤销上次"), b -> undoBuild())
				.bounds(panelX + 82, toolY, 70, btnH)
				.build();
		cancelButton = Button.builder(Component.literal("取消"), b -> BuildTaskManager.INSTANCE.cancel())
				.bounds(panelX + 156, toolY, 50, btnH)
				.build();
		newSessionButton = Button.builder(Component.literal("新建会话"), b -> {
					BuildTaskManager.INSTANCE.newSession();
					scrollY = 0;
					preview.setPlan(null);
					previewMsgIndex = -1;
					updateButtons();
				})
				.bounds(panelX + panelW - 158, toolY, 74, btnH)
				.build();
		settingsButton = Button.builder(Component.literal("设置"), b -> {
					if (this.minecraft != null) {
						this.minecraft.setScreen(new AiConfigScreen(config));
					}
				})
				.bounds(panelX + panelW - 80, toolY, 70, btnH)
				.build();
		addRenderableWidget(buildButton);
		addRenderableWidget(undoButton);
		addRenderableWidget(cancelButton);
		addRenderableWidget(newSessionButton);
		addRenderableWidget(settingsButton);

		// 恢复展示结果
		if ((showResult || m.hasFreshResult()) && m.plan() != null) {
			scrollToBottom();
			preview.setPlan(m.plan());
			m.acknowledge();
		}
		if (m.isGenerating()) {
			scrollToBottom();
		}
		updateButtons();
	}

	private void layout() {
		panelW = Math.min(760, this.width - 16);
		panelH = Math.min(520, this.height - 16);
		panelX = (this.width - panelW) / 2;
		panelY = (this.height - panelH) / 2;

		int headerH = 24;
		int toolH = 24;
		inputH = 22;
		int coordH = 22;
		int statusH = 16;
		int pad = 6;

		// 自下而上：工具栏 → 输入 → 坐标 → 状态 → 聊天
		int toolY = panelY + panelH - pad - toolH;
		inputY = toolY - pad - inputH;
		int coordY = inputY - pad - coordH;
		int statusY = coordY - 4 - statusH;
		chatX = panelX + 8;
		chatY = panelY + headerH;
		chatW = panelW - 16;
		chatH = Math.max(40, statusY - chatY - 4);
		// 供绘制用
		this.coordY = coordY;
		this.statusY = statusY;
	}

	private int coordY;
	private int statusY;

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

	private void doSend() {
		BuildTaskManager m = BuildTaskManager.INSTANCE;
		if (m.isGenerating()) {
			lastErrorStatus = "正在生成中，可点「取消」";
			return;
		}
		String desc = inputField.getValue().trim();
		if (desc.isEmpty()) {
			lastErrorStatus = "请先输入提示词";
			return;
		}
		BlockPos origin = readOrigin();
		if (origin == null) {
			lastErrorStatus = "起点坐标格式错误";
			return;
		}
		BlockPos bound = readBound();
		if (bound != null) {
			BlockPos lo = new BlockPos(Math.min(origin.getX(), bound.getX()),
					Math.min(origin.getY(), bound.getY()), Math.min(origin.getZ(), bound.getZ()));
			BlockPos hi = new BlockPos(Math.max(origin.getX(), bound.getX()),
					Math.max(origin.getY(), bound.getY()), Math.max(origin.getZ(), bound.getZ()));
			origin = lo;
			bound = hi;
		}
		String posText = origin.getX() + " " + origin.getY() + " " + origin.getZ();
		if (bound != null) {
			int w = bound.getX() - origin.getX() + 1;
			int d = bound.getZ() - origin.getZ() + 1;
			int h = bound.getY() - origin.getY() + 1;
			posText += "，可用空间立方体：从 (" + origin.getX() + " " + origin.getY() + " " + origin.getZ()
					+ ") 到 (" + bound.getX() + " " + bound.getY() + " " + bound.getZ()
					+ ")（宽 " + w + " 深 " + d + " 高 " + h + "），建筑要尽量填满这个空间，但绝不能超出它的范围";
		}
		inputField.setValue("");
		lastErrorStatus = "";
		m.send(this.minecraft, desc, posText, origin, bound);
		scrollToBottom();
		updateButtons();
	}

	private void executeBuild() {
		BuildTaskManager m = BuildTaskManager.INSTANCE;
		BuildingPlanRef planRef = currentPlanForBuild();
		if (planRef == null || busy()) {
			return;
		}
		if (this.minecraft == null || this.minecraft.getSingleplayerServer() == null) {
			lastErrorStatus = "仅支持单人游戏！";
			return;
		}
		var server = this.minecraft.getSingleplayerServer();
		var world = server.getLevel(this.minecraft.level.dimension());
		if (world == null) {
			lastErrorStatus = "找不到当前维度";
			return;
		}
		BlockPos origin = m.origin() != null ? m.origin() : defaultOrigin();
		boolean replacing = m.isAdjusted() && com.mcai.common.BuildingExecutor.canUndo();
		if (replacing) {
			lastErrorStatus = "正在替换原建筑…";
			BuildingExecutor.undo(server).whenComplete((ok, error) -> {
				if (this.minecraft != null) {
					this.minecraft.execute(() -> startBuild(server, world, origin, planRef.planName));
				}
			});
		} else {
			startBuild(server, world, origin, planRef.planName);
		}
	}

	private static final class BuildingPlanRef {
		final com.mcai.common.BuildingPlan plan;
		final String planName;

		BuildingPlanRef(com.mcai.common.BuildingPlan plan) {
			this.plan = plan;
			this.planName = plan.name;
		}
	}

	private BuildingPlanRef currentPlanForBuild() {
		com.mcai.common.BuildingPlan p = BuildTaskManager.INSTANCE.plan();
		return p == null ? null : new BuildingPlanRef(p);
	}

	private boolean busy() {
		return BuildTaskManager.INSTANCE.isGenerating();
	}

	private void startBuild(net.minecraft.server.MinecraftServer server, net.minecraft.server.level.ServerLevel world,
			BlockPos origin, String name) {
		com.mcai.common.BuildingPlan plan = BuildTaskManager.INSTANCE.plan();
		if (plan == null) {
			return;
		}
		lastErrorStatus = "";
		var future = BuildingExecutor.execute(server, world, origin, plan, null);
		future.whenComplete((placed, error) -> {
			if (this.minecraft == null) {
				return;
			}
			this.minecraft.execute(() -> {
				ChatSession s = BuildTaskManager.INSTANCE.session();
				if (error != null) {
					String msg = "建造失败：" + rootMessage(error);
					s.messages.add(new ChatMsg(MsgRole.ERROR, msg));
					lastErrorStatus = msg;
				} else {
					s.messages.add(new ChatMsg(MsgRole.SYSTEM,
							"建造完成！共放置 " + placed + " 个方块，可点「撤销上次」恢复"));
					BuildTaskManager.INSTANCE.acknowledge();
				}
				scrollToBottom();
				updateButtons();
			});
		});
	}

	private void undoBuild() {
		if (this.minecraft == null || this.minecraft.getSingleplayerServer() == null) {
			return;
		}
		var server = this.minecraft.getSingleplayerServer();
		BuildingExecutor.undo(server).whenComplete((ok, error) -> {
			if (this.minecraft == null) {
				return;
			}
			this.minecraft.execute(() -> {
				ChatSession s = BuildTaskManager.INSTANCE.session();
				if (error != null) {
					s.messages.add(new ChatMsg(MsgRole.ERROR, "撤销失败：" + rootMessage(error)));
				} else if (Boolean.TRUE.equals(ok)) {
					s.messages.add(new ChatMsg(MsgRole.SYSTEM, "已撤销上次建造"));
				} else {
					s.messages.add(new ChatMsg(MsgRole.SYSTEM, "没有可撤销的建造记录"));
				}
				scrollToBottom();
				updateButtons();
			});
		});
	}

	private static String rootMessage(Throwable t) {
		Throwable cur = t;
		while (cur.getCause() != null) {
			cur = cur.getCause();
		}
		return cur.getMessage() == null ? cur.toString() : cur.getMessage();
	}

	private BlockPos readOrigin() {
		try {
			return new BlockPos(
					Integer.parseInt(posXField.getValue().trim()),
					Integer.parseInt(posYField.getValue().trim()),
					Integer.parseInt(posZField.getValue().trim()));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private BlockPos readBound() {
		String sx = posX2Field.getValue().trim();
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
		BuildTaskManager m = BuildTaskManager.INSTANCE;
		boolean generating = m.isGenerating();
		sendButton.active = !generating;
		cancelButton.active = generating;
		buildButton.active = m.plan() != null && !generating;
		undoButton.active = BuildingExecutor.canUndo();
	}

	private void scrollToBottom() {
		scrollY = Integer.MAX_VALUE / 4;
	}

	// ==================== 渲染 ====================

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		layout();
		BuildTaskManager m = BuildTaskManager.INSTANCE;
		if (m.phase() != lastPhase) {
			lastPhase = m.phase();
			updateButtons();
			scrollToBottom();
		}

		// 面板外遮罩
		g.fill(0, 0, this.width, this.height, 0x80000000);
		// 像素风面板：外框 + 内底
		g.fill(panelX - 2, panelY - 2, panelX + panelW + 2, panelY + panelH + 2, COL_PANEL_BORDER);
		g.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL);

		// 顶栏：会话
		drawHeader(g, mouseX, mouseY);

		// 聊天区（带裁剪，气泡不得溢出到会话标签/坐标行）
		g.fill(chatX - 1, chatY - 1, chatX + chatW + 1, chatY + chatH + 1, COL_PANEL_BORDER);
		g.fill(chatX, chatY, chatX + chatW, chatY + chatH, COL_CHAT_BG);
		g.enableScissor(chatX, chatY, chatX + chatW, chatY + chatH);
		drawChat(g, mouseX, mouseY);
		g.disableScissor();

		// 坐标行 + 输入行
		g.fill(panelX + 8, inputY - 2, panelX + panelW - 8, inputY + inputH + 2, COL_INPUT_BORDER);
		g.fill(panelX + 10, inputY, panelX + panelW - 10, inputY + inputH, COL_INPUT_BG);

		// 状态提示（聊天框与坐标之间，不重叠）
		String tip;
		int tipCol;
		if (m.isGenerating()) {
			String live = m.liveThinking();
			tip = "AI 思考中… " + m.elapsedSeconds() + "s"
					+ (live.isEmpty() ? "" : "（思考已 " + live.length() + " 字）");
			tipCol = COL_WARN;
		} else if (!lastErrorStatus.isEmpty()) {
			tip = lastErrorStatus;
			tipCol = COL_ERR;
		} else if (m.plan() != null) {
			tip = "已有方案，继续输入可修改；「确认建造」放入世界";
			tipCol = COL_ACCENT;
		} else {
			tip = "输入提示词后点「发送」开始生成（模型：" + config.modelName() + "）";
			tipCol = COL_DIM;
		}
		g.text(this.font, tip, panelX + 10, statusY + 2, tipCol);

		super.extractRenderState(g, mouseX, mouseY, delta);
		// 标签画在控件之上；终点标签与 X2 之间留有 midGap，不再重叠
		g.text(this.font, "起点", panelX + 8, coordY + 3, COL_DIM);
		g.text(this.font, "终点", panelX + 8 + 30 + 3 * (42 + 4) + 8, coordY + 3, COL_DIM);
		drawImePreview(g);
	}

	private void drawHeader(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		BuildTaskManager m = BuildTaskManager.INSTANCE;
		g.fill(panelX, panelY, panelX + panelW, panelY + 22, 0xFF252830);
		g.text(this.font, "AI 建造", panelX + 8, panelY + 7, COL_ACCENT);

		// 会话标签（最多显示 4 个）
		List<ChatSession> sessions = m.sessions();
		int tx = panelX + 70;
		int shown = 0;
		for (int i = Math.max(0, sessions.size() - 4); i < sessions.size(); i++) {
			ChatSession s = sessions.get(i);
			boolean active = s == m.session();
			String label = (s.title.length() > 8 ? s.title.substring(0, 8) + "…" : s.title);
			int tw = this.font.width(label) + 10;
			int ty = panelY + 3;
			g.fill(tx, ty, tx + tw, ty + 16, active ? 0xFF3A4050 : 0xFF2A2E36);
			g.fill(tx, ty + 16, tx + tw, ty + 17, active ? COL_ACCENT : 0xFF444444);
			g.text(this.font, label, tx + 5, ty + 4, active ? COL_TEXT : COL_DIM);
			tx += tw + 4;
			shown++;
			if (shown >= 4) {
				break;
			}
		}
	}

	private void drawChat(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		thinkHitboxes.clear();
		planButtonBoxes.clear();
		BuildTaskManager m = BuildTaskManager.INSTANCE;
		ChatSession s = m.session();

		// 计算内容高度并绘制（裁剪到 chat 区域由 fill 背景近似；消息超出则滚）
		int pad = 6;
		int y = chatY + pad - scrollY;
		int maxW = chatW - 16;
		List<String> wrapCache = new ArrayList<>();

		// 先粗算 contentH
		contentH = measureContent(maxW) + pad * 2;

		// 限制 scrollY
		int maxScroll = Math.max(0, contentH - chatH);
		if (scrollY > maxScroll) {
			scrollY = maxScroll;
		}
		if (scrollY < 0) {
			scrollY = 0;
		}
		y = chatY + pad - scrollY;

		if (s.messages.isEmpty()) {
			g.text(this.font, "开始一场建造对话：输入「造一艘帆船」「河边两层中式小楼」…",
					chatX + 12, chatY + 16, COL_DIM);
			g.text(this.font, "同一会话继续说 = 修改；「新建会话」= 新建筑",
					chatX + 12, chatY + 32, COL_DIM);
		}

		for (int i = 0; i < s.messages.size(); i++) {
			ChatMsg msg = s.messages.get(i);
			y = drawMessage(g, msg, i, chatX + pad, y, maxW, mouseX, mouseY);
			y += 6;
		}

		// 生成中的实时思考气泡
		if (m.isGenerating()) {
			String think = m.liveThinking();
			if (!think.isEmpty()) {
				y = drawLiveThinking(g, think, chatX + pad, y, maxW);
			} else {
				g.text(this.font, "◆ AI 正在生成…", chatX + pad + 4, y + 2, COL_WARN);
				y += 16;
			}
		}

		// 绑定预览到最近的 plan 消息
		int bind = -1;
		for (int i = s.messages.size() - 1; i >= 0; i--) {
			if (s.messages.get(i).plan != null) {
				bind = i;
				break;
			}
		}
		if (bind != previewMsgIndex) {
			previewMsgIndex = bind;
			if (bind >= 0) {
				preview.setPlan(s.messages.get(bind).plan);
			} else if (m.plan() == null) {
				preview.setPlan(null);
			}
		}

		// 底部提示滚动条
		if (contentH > chatH) {
			g.fill(chatX + chatW - 3, chatY + 2, chatX + chatW - 2, chatY + chatH - 2, 0xFF333333);
			int thumbH = Math.max(12, chatH * chatH / contentH);
			int thumbY = chatY + 2 + (chatH - thumbH - 4) * scrollY / Math.max(1, maxScroll);
			g.fill(chatX + chatW - 3, thumbY, chatX + chatW - 2, thumbY + thumbH, 0xFF888888);
		}
	}

	private int measureContent(int maxW) {
		BuildTaskManager m = BuildTaskManager.INSTANCE;
		ChatSession s = m.session();
		int h = 0;
		for (ChatMsg msg : s.messages) {
			h += measureMessage(msg, maxW) + 6;
		}
		if (m.isGenerating()) {
			h += 40;
		}
		return h;
	}

	private int measureMessage(ChatMsg msg, int maxW) {
		int bubbleW = maxW - (msg.role == MsgRole.USER ? 36 : 0);
		int textW = Math.max(40, bubbleW - 12);
		int lines = splitLines(msg.text, textW).size();
		int h = 16 + lines * 12;
		if (!msg.thinking.isEmpty()) {
			h += 16;
			if (msg.thinkingExpanded) {
				h += Math.min(80, splitLines(msg.thinking, textW).size() * 11) + 4;
			}
		}
		if (msg.plan != null && msg.planReady) {
			h += 90; // 预览区
			h += 22; // 按钮行
		}
		return h;
	}

	private int drawMessage(GuiGraphicsExtractor g, ChatMsg msg, int index, int x, int y, int maxW,
			int mouseX, int mouseY) {
		int padUser = msg.role == MsgRole.USER ? 36 : 0;
		int bx = msg.role == MsgRole.USER ? x + padUser : x;
		int bw = maxW - padUser;
		int textW = Math.max(40, bw - 12);
		List<String> lines = splitLines(msg.text, textW);
		int bubbleH = 16 + lines.size() * 12;
		if (!msg.thinking.isEmpty()) {
			bubbleH += 16;
			if (msg.thinkingExpanded) {
				bubbleH += Math.min(80, splitLines(msg.thinking, textW).size() * 11) + 4;
			}
		}
		boolean hasPlan = msg.plan != null && msg.planReady;
		if (hasPlan) {
			bubbleH += 90 + 22;
		}

		int bubbleCol = switch (msg.role) {
			case USER -> COL_USER_BUBBLE;
			case AI -> COL_AI_BUBBLE;
			case ERROR -> COL_ERR_BUBBLE;
			default -> COL_SYS_BUBBLE;
		};
		int textColor = switch (msg.role) {
			case ERROR -> COL_ERR;
			case SYSTEM -> COL_DIM;
			case AI -> COL_TEXT;
			default -> COL_TEXT;
		};

		// 像素气泡：主体 + 下边/右边 2px 硬阴影
		g.fill(bx + 2, y + 2, bx + bw + 2, y + bubbleH + 2, 0x60000000);
		g.fill(bx, y, bx + bw, y + bubbleH, bubbleCol);
		// 角标
		String who = switch (msg.role) {
			case USER -> "你";
			case AI -> "AI";
			case ERROR -> "!";
			default -> "·";
		};
		int whoCol = switch (msg.role) {
			case USER -> 0xFF8AB4FF;
			case AI -> COL_ACCENT;
			case ERROR -> COL_ERR;
			default -> COL_DIM;
		};
		g.text(this.font, who, bx + 4, y + 3, whoCol);

		int ty = y + 14;
		for (String line : lines) {
			g.text(this.font, line, bx + 4, ty, textColor);
			ty += 12;
		}

		// 思考折叠条
		if (!msg.thinking.isEmpty()) {
			String arrow = msg.thinkingExpanded ? "▼" : "▶";
			String label = arrow + " 思考过程";
			g.fill(bx + 4, ty, bx + 4 + this.font.width(label) + 10, ty + 14, COL_THINK_BG);
			g.fill(bx + 4, ty + 14, bx + 4 + this.font.width(label) + 10, ty + 15, COL_THINK_BORDER);
			g.text(this.font, label, bx + 8, ty + 2, COL_WARN);
			thinkHitboxes.add(new int[] { bx + 4, ty, this.font.width(label) + 14, 14, index });
			ty += 16;
			if (msg.thinkingExpanded) {
				int th = Math.min(80, splitLines(msg.thinking, textW).size() * 11);
				g.fill(bx + 8, ty, bx + bw - 4, ty + th, 0x801A1800);
				int ly = ty + 2;
				int maxLy = ty + th - 2;
				int drawn = 0;
				for (String tl : splitLines(msg.thinking, textW)) {
					if (ly + 10 > maxLy) {
						g.text(this.font, "…", bx + 12, ly, COL_DIM);
						break;
					}
					g.text(this.font, tl, bx + 12, ly, 0xFFD0C080);
					ly += 11;
					drawn++;
					if (drawn > 20) {
						break;
					}
				}
				ty += th + 4;
			}
		}

		// 方案预览 + 按钮
		if (hasPlan) {
			int px = bx + 6;
			int py = ty + 2;
			int pw = bw - 12;
			int ph = 84;
			g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, COL_INPUT_BORDER);
			g.fill(px, py, px + pw, py + ph, 0xB0000000);
			g.text(this.font, "3D 预览（滚轮缩放 · 右键旋转 · 左键平移 · 双击复位）",
					px + 4, py + 2, COL_DIM);
			preview.setViewport(px + pw / 2, py + 48, pw - 8, ph - 22);
			preview.setLowDetail(this.getFocused() != null);
			preview.render(g);
			ty += ph + 4;

			int btnY = ty;
			// 确认建造 / 撤销 由底部工具栏承担；这里放「确认建造」快捷键
			int bwBtn = 72;
			g.fill(px, btnY, px + bwBtn, btnY + 18, COL_BTN);
			g.fill(px, btnY + 18, px + bwBtn, btnY + 20, COL_BTN_EDGE);
			g.text(this.font, "确认建造", px + 8, btnY + 5, 0xFFFFFFFF);
			planButtonBoxes.add(new int[] { px, btnY, bwBtn, 18, index });
			ty += 22;
		}

		return y + bubbleH;
	}

	private int drawLiveThinking(GuiGraphicsExtractor g, String think, int x, int y, int maxW) {
		int textW = maxW - 16;
		List<String> lines = splitLines(think, textW);
		// 只显示最后几行
		int show = Math.min(3, lines.size());
		int h = 16 + show * 11;
		g.fill(x + 2, y + 2, x + maxW + 2, y + h + 2, 0x50000000);
		g.fill(x, y, x + maxW, y + h, COL_THINK_BG);
		g.text(this.font, "▸ 思考中…", x + 6, y + 2, COL_WARN);
		int ly = y + 14;
		for (int i = lines.size() - show; i < lines.size(); i++) {
			if (i < 0) {
				continue;
			}
			g.text(this.font, lines.get(i), x + 8, ly, 0xFFD0C080);
			ly += 11;
		}
		return y + h;
	}

	private List<String> splitLines(String s, int maxWidth) {
		List<String> lines = new ArrayList<>();
		if (s == null || s.isEmpty()) {
			lines.add("");
			return lines;
		}
		// 先按换行再按宽度
		for (String para : s.split("\n", -1)) {
			String rest = para;
			if (rest.isEmpty()) {
				lines.add("");
				continue;
			}
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
		}
		return lines;
	}

	// ==================== 交互 ====================

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();

		// 预览交互
		if (preview.hasPlan() && preview.contains(mx, my)) {
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

		// 思考折叠
		for (int[] box : thinkHitboxes) {
			if (mx >= box[0] && mx < box[0] + box[2] && my >= box[1] && my < box[1] + box[3]) {
				ChatSession s = BuildTaskManager.INSTANCE.session();
				int idx = box[4];
				if (idx >= 0 && idx < s.messages.size()) {
					ChatMsg msg = s.messages.get(idx);
					msg.thinkingExpanded = !msg.thinkingExpanded;
					return true;
				}
			}
		}

		// 消息内「确认建造」
		for (int[] box : planButtonBoxes) {
			if (mx >= box[0] && mx < box[0] + box[2] && my >= box[1] && my < box[1] + box[3]) {
				executeBuild();
				return true;
			}
		}

		// 会话标签点击
		if (my >= panelY && my < panelY + 22) {
			BuildTaskManager m = BuildTaskManager.INSTANCE;
			List<ChatSession> sessions = m.sessions();
			int tx = panelX + 70;
			for (int i = Math.max(0, sessions.size() - 4); i < sessions.size(); i++) {
				ChatSession s = sessions.get(i);
				String label = (s.title.length() > 8 ? s.title.substring(0, 8) + "…" : s.title);
				int tw = this.font.width(label) + 10;
				if (mx >= tx && mx < tx + tw && my >= panelY + 3 && my < panelY + 19) {
					m.switchTo(s);
					previewMsgIndex = -1;
					scrollY = 0;
					updateButtons();
					return true;
				}
				tx += tw + 4;
			}
		}

		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (rotatePressed && event.button() == 1) {
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
	public boolean mouseReleased(MouseButtonEvent event) {
		if (rotatePressed && event.button() == 1 && !rotateDragging) {
			preview.rotateCw();
		}
		rotatePressed = false;
		rotateDragging = false;
		previewDragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (preview.hasPlan() && preview.contains(mouseX, mouseY)) {
			preview.zoom(verticalAmount);
			return true;
		}
		if (mouseX >= chatX && mouseX <= chatX + chatW && mouseY >= chatY && mouseY <= chatY + chatH) {
			scrollY -= (int) (verticalAmount * 16);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean preeditUpdated(PreeditEvent event) {
		this.lastPreedit = event;
		if (inputField != null && inputField.isFocused()) {
			return inputField.preeditUpdated(event);
		}
		return super.preeditUpdated(event);
	}

	/**
	 * 锚定系统候选窗口到输入框（拼音黑框已按用户要求移除）。
	 */
	private void drawImePreview(GuiGraphicsExtractor g) {
		if (inputField == null || lastPreedit == null) {
			return;
		}
		String txt = lastPreedit.fullText();
		if (txt == null || txt.isEmpty()) {
			return;
		}
		int fx = inputField.getX();
		int fy = inputField.getY();
		if (this.minecraft != null) {
			this.minecraft.textInputManager().setTextInputArea(
					fx, fy, fx + inputField.getWidth(), fy + inputField.getHeight());
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (BuildTaskManager.INSTANCE.isGenerating()) {
			updateButtons();
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	@Override
	public void onClose() {
		super.onClose();
	}
}
