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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * AI 建筑工作室（AI Studio）全功能主界面。
 * 现代双栏工作台布局，自适应屏幕缩放，完整多语言（繁中 / 简中 / 英文）支持。
 */
public class AiBuildScreen extends Screen {
	private final PlanPreviewWidget preview = new PlanPreviewWidget();
	private final AiConfig config;

	// 左栏控件
	private EditBox descField;
	private EditBox posXField;
	private EditBox posYField;
	private EditBox posZField;
	private EditBox posX2Field;
	private EditBox posY2Field;
	private EditBox posZ2Field;
	private Button randomButton;
	private Button syncPosButton;
	private Button autoSizeButton;
	private boolean autoSize = true;

	// 右栏 3D 控制
	private Button rotLeftButton;
	private Button rotRightButton;
	private Button zoomInButton;
	private Button zoomOutButton;
	private Button resetViewButton;

	// 底部动作栏
	private Button generateButton;
	private Button buildButton;
	private Button undoButton;
	private Button configButton;

	private String status;
	private boolean busy = false;
	private BuildingPlan currentPlan;
	private PlanSpec currentSpec;
	private BlockPos currentOrigin;
	private BlockPos currentBound;
	private CompletableFuture<String> pendingTask;
	private PreeditEvent lastPreedit;
	private long requestStartMs;

	private static final int STATUS_LINES = 6;
	private static final int STATUS_LINE_H = 11;
	private String lastStatus;
	private List<String> statusLines = List.of();
	private int statusScroll = 0;
	private boolean hasStreamTokens = false;

	private static final String[] PROMPTS_ZH_TW = {
		"在河邊建一座兩層中式小樓，帶庭院枯山水與挑簷燈籠",
		"建一座三層現代極簡海景別墅，帶無邊際泳池與觀景天台",
		"建一座五層中式飛簷寶塔，帶八角挑簷與避雷尖頂",
		"建一座賽博龐克高科技實驗大樓，配玻璃帷幕與屋頂停機坪",
		"建一座中世紀石磚防禦城堡，帶四角箭塔與垛口天台",
		"建一座雙層溫馨北歐森林小木屋，帶壁爐煙囪與花園露台",
		"建一座帶鐘樓的歐式復古小教堂，配高聳拱門與彩色玻璃花窗",
		"建一座日式和風溫泉旅館，帶中庭院落與榻榻米茶室",
		"建一座宏偉的羅馬多立克柱廊神殿，帶三角山牆與露天祭壇",
		"建一座帶有全景落地窗的湖畔現代美術館，帶水上木棧道",
		"建一座八層階梯退台式現代雙子塔，帶空中觀景連廊",
		"建一座沙漠綠洲風情的砂岩宮殿，帶立柱迴廊與噴泉中庭",
		"建一座帶有空中花園的綠色環保摩天大樓，帶觀光電梯",
		"建一座三層蒸汽龐克機械鐘塔，帶齒輪外掛裝飾與瞭望台",
		"建一座林間樹屋別墅，帶吊橋連廊與露天觀星台",
		"建一座雙層歐式莊園大宅，帶迎賓噴泉門廊與馬廄後院"
	};

	private static final String[] PROMPTS_ZH_CN = {
		"在河边建一座两层中式小楼，带庭院枯山水与挑檐灯笼",
		"建一座三层现代极简海景别墅，带无边泳池与观景天台",
		"建一座五层中式飞檐宝塔，带八角挑檐与避雷尖顶",
		"建一座赛博朋克高科技实验大楼，配玻璃帷幕与屋顶停机坪",
		"建一座中世纪石砖防御城堡，带四角箭塔与垛口天台",
		"建一座双层温馨北欧森林小木屋，带壁炉烟囱与花园露台",
		"建一座带钟楼的欧式复古小教堂，配高耸拱门与彩色玻璃花窗",
		"建一座日式和风温泉旅馆，带中庭院落与榻榻米茶室",
		"建一座宏伟的罗马多立克柱廊神殿，带三角山墙与露天祭坛",
		"建一座带有全景落地窗的湖畔现代艺术馆，带水上木栈道",
		"建一座八层阶梯退台式现代双子塔，带空中观景连廊",
		"建一座沙漠绿洲风情的砂岩宫殿，带立柱回廊与喷泉中庭",
		"建一座带有空中花园的绿色环保摩天大楼，带观光电梯",
		"建一座三层蒸汽朋克机械钟塔，带齿轮外挂装饰与瞭望台",
		"建一座林间树屋别墅，带吊桥连廊与露天观星台",
		"建一座双层欧式庄园大宅，带迎宾喷泉门廊与马厩后院"
	};

	private static final String[] PROMPTS_EN = {
		"Build a 2-story riverside oriental pavilion with a zen garden and hanging lanterns",
		"Build a 3-story modern minimalist seaside villa with an infinity pool and rooftop deck",
		"Build a 5-story oriental pagoda with upturned eaves and lightning spire",
		"Build a cyberpunk sci-fi laboratory with glass curtains and a rooftop helipad",
		"Build a medieval stone fortress with four corner watchtowers and battlements",
		"Build a cozy 2-story Nordic forest cabin with a fireplace chimney and flower balcony",
		"Build a vintage European church with a clock tower, tall arches, and stained glass",
		"Build a Japanese onsen hot-spring inn with a courtyard garden and tatami tea room",
		"Build a majestic Roman Doric temple with pediments and an open-air altar",
		"Build a modern lakeside art gallery with floor-to-ceiling panoramic glass windows",
		"Build an 8-story stepped modern twin towers connected with a skybridge",
		"Build a desert oasis sandstone palace with pillared arcades and a fountain courtyard",
		"Build an eco-friendly green skyscraper with hanging sky gardens and glass elevators",
		"Build a 3-story steampunk clock tower with gear ornaments and an observatory",
		"Build a treetop forest villa with rope suspension bridges and a stargazing platform",
		"Build a 2-story European countryside estate with a fountain porch and horse stables"
	};

	private static final java.util.Random RNG = new java.util.Random();

	// 跨屏幕持久化状态（保证关闭后再打开界面，输入、蓝图、3D 预览、坐标与状态完全保留）
	private static String savedDesc = "";
	private static String savedX = null;
	private static String savedY = null;
	private static String savedZ = null;
	private static String savedX2 = "";
	private static String savedY2 = "";
	private static String savedZ2 = "";
	private static boolean savedAutoSize = true;
	private static BuildingPlan savedPlan = null;
	private static PlanSpec savedSpec = null;
	private static BlockPos savedOrigin = null;
	private static BlockPos savedBound = null;
	private static String savedStatus = null;

	private static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	private static String getRandomPrompt() {
		String lang = "zh_cn";
		if (net.minecraft.client.Minecraft.getInstance() != null && net.minecraft.client.Minecraft.getInstance().getLanguageManager() != null) {
			lang = net.minecraft.client.Minecraft.getInstance().getLanguageManager().getSelected().toLowerCase();
		}
		if (lang.contains("zh_tw") || lang.contains("zh_hk") || lang.contains("traditional")) {
			return PROMPTS_ZH_TW[RNG.nextInt(PROMPTS_ZH_TW.length)];
		} else if (lang.contains("zh")) {
			return PROMPTS_ZH_CN[RNG.nextInt(PROMPTS_ZH_CN.length)];
		} else {
			return PROMPTS_EN[RNG.nextInt(PROMPTS_EN.length)];
		}
	}

	public AiBuildScreen(AiConfig config) {
		super(Component.translatable("gui.minecraft-ai.studio.title"));
		this.config = config;
		this.status = savedStatus != null ? savedStatus : tr("gui.minecraft-ai.status.ready");
		this.autoSize = savedAutoSize;
		this.currentPlan = savedPlan;
		this.currentSpec = savedSpec;
		this.currentOrigin = savedOrigin;
		this.currentBound = savedBound;
	}

	private int getPanelW() {
		return Math.max(340, Math.min(410, this.width - 12));
	}

	private int getPanelH() {
		return Math.max(200, Math.min(226, this.height - 12));
	}

	@Override
	protected void init() {
		AiClient.isAvailable(config).thenAcceptAsync(ok -> {
			if (!ok && this.minecraft != null) {
				this.minecraft.execute(() -> {
					status = tr("gui.minecraft-ai.status.unreachable", config.chatEndpoint());
				});
			}
		}, Runnable::run);

		int panelW = getPanelW();
		int panelH = getPanelH();
		int x0 = (this.width - panelW) / 2;
		int y0 = (this.height - panelH) / 2;

		int colLeftW = (panelW - 20) * 48 / 100;
		int colRightW = (panelW - 20) - colLeftW;
		int leftX = x0 + 7;
		int rightX = leftX + colLeftW + 6;

		BlockPos start = defaultOrigin();

		// ===== 顶部设置按钮 =====
		configButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.settings"), b -> {
			if (this.minecraft != null) {
				saveState();
				ScreenCompat.setScreen(this.minecraft, new AiConfigScreen(config));
			}
		}).bounds(x0 + panelW - 52, y0 + 3, 46, 15).build();
		addRenderableWidget(configButton);

		// ===== 左栏：输入与控制 =====
		// 1. 建筑描述
		descField = new EditBox(this.font, leftX, y0 + 29, colLeftW - 44, 15, Component.literal("建筑描述"));
		descField.setMaxLength(10000);
		descField.setHint(Component.translatable("gui.minecraft-ai.hint.desc"));
		descField.setValue(savedDesc);
		addRenderableWidget(descField);
		setInitialFocus(descField);

		randomButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.inspiration"), b -> {
			String idea = getRandomPrompt();
			descField.setValue(idea);
			status = tr("gui.minecraft-ai.status.inspiration_picked", idea);
			saveState();
		}).bounds(leftX + colLeftW - 41, y0 + 29, 41, 15).build();
		addRenderableWidget(randomButton);

		// 2. 起点坐标
		int coordY = y0 + 57;
		int coordW = (colLeftW - 54) / 3;
		posXField = new EditBox(this.font, leftX, coordY, coordW, 14, Component.literal("X"));
		posYField = new EditBox(this.font, leftX + coordW + 2, coordY, coordW, 14, Component.literal("Y"));
		posZField = new EditBox(this.font, leftX + (coordW + 2) * 2, coordY, coordW, 14, Component.literal("Z"));
		posXField.setValue(savedX != null ? savedX : String.valueOf(start.getX()));
		posYField.setValue(savedY != null ? savedY : String.valueOf(start.getY()));
		posZField.setValue(savedZ != null ? savedZ : String.valueOf(start.getZ()));
		posXField.setMaxLength(10);
		posYField.setMaxLength(10);
		posZField.setMaxLength(10);
		addRenderableWidget(posXField);
		addRenderableWidget(posYField);
		addRenderableWidget(posZField);

		syncPosButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.feet"), b -> {
			BlockPos cur = defaultOrigin();
			posXField.setValue(String.valueOf(cur.getX()));
			posYField.setValue(String.valueOf(cur.getY()));
			posZField.setValue(String.valueOf(cur.getZ()));
			status = tr("gui.minecraft-ai.status.sync_pos", cur.getX(), cur.getY(), cur.getZ());
			saveState();
		}).bounds(leftX + colLeftW - 48, coordY, 48, 14).build();
		addRenderableWidget(syncPosButton);

		// 3. 空间尺寸规划
		int sizeY = y0 + 84;
		int autoBtnW = 84;
		autoSizeButton = Button.builder(Component.translatable(autoSize ? "gui.minecraft-ai.btn.autosize_on" : "gui.minecraft-ai.btn.autosize_off"), b -> {
			autoSize = !autoSize;
			updateAutoSizeState();
			saveState();
		}).bounds(leftX, sizeY, autoBtnW, 15).build();
		addRenderableWidget(autoSizeButton);

		int boundW = (colLeftW - autoBtnW - 8) / 3;
		posX2Field = new EditBox(this.font, leftX + autoBtnW + 2, sizeY, boundW, 15, Component.literal("X2"));
		posY2Field = new EditBox(this.font, leftX + autoBtnW + 2 + boundW + 2, sizeY, boundW, 15, Component.literal("Y2"));
		posZ2Field = new EditBox(this.font, leftX + autoBtnW + 2 + (boundW + 2) * 2, sizeY, colLeftW - (autoBtnW + 2 + (boundW + 2) * 2), 15, Component.literal("Z2"));
		posX2Field.setValue(savedX2);
		posY2Field.setValue(savedY2);
		posZ2Field.setValue(savedZ2);
		posX2Field.setMaxLength(10);
		posY2Field.setMaxLength(10);
		posZ2Field.setMaxLength(10);
		addRenderableWidget(posX2Field);
		addRenderableWidget(posY2Field);
		addRenderableWidget(posZ2Field);
		updateAutoSizeState();

		// ===== 右栏：3D 视口与控制 =====
		int viewH = panelH - 96;
		preview.setViewport(rightX + colRightW / 2, y0 + 19 + viewH / 2, colRightW - 4, viewH - 4);
		if (savedPlan != null) {
			preview.setPlan(savedPlan);
		}

		int bar3DY = y0 + panelH - 73;
		int btn3DW = (colRightW - 10) / 5;
		rotLeftButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.rot_ccw"), b -> preview.rotateCCW())
				.bounds(rightX, bar3DY, btn3DW, 15).build();
		rotRightButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.rot_cw"), b -> preview.rotateCW())
				.bounds(rightX + btn3DW + 2, bar3DY, btn3DW, 15).build();
		zoomInButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.zoom_in"), b -> preview.zoomIn())
				.bounds(rightX + (btn3DW + 2) * 2, bar3DY, btn3DW, 15).build();
		zoomOutButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.zoom_out"), b -> preview.zoomOut())
				.bounds(rightX + (btn3DW + 2) * 3, bar3DY, btn3DW, 15).build();
		resetViewButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.reset_view"), b -> preview.resetView())
				.bounds(rightX + (btn3DW + 2) * 4, bar3DY, colRightW - (btn3DW + 2) * 4, 15).build();

		addRenderableWidget(rotLeftButton);
		addRenderableWidget(rotRightButton);
		addRenderableWidget(zoomInButton);
		addRenderableWidget(zoomOutButton);
		addRenderableWidget(resetViewButton);

		// ===== 底部操作栏 =====
		int actionY = y0 + panelH - 24;
		int actionBtnW = (panelW - 20) / 3;
		generateButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.generate"), b -> requestPlan())
				.bounds(leftX, actionY, actionBtnW, 19).build();
		buildButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.build"), b -> executeBuild())
				.bounds(leftX + actionBtnW + 3, actionY, actionBtnW, 19).build();
		undoButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.undo"), b -> undoBuild())
				.bounds(leftX + (actionBtnW + 3) * 2, actionY, panelW - 14 - (actionBtnW + 3) * 2, 19).build();

		addRenderableWidget(generateButton);
		addRenderableWidget(buildButton);
		addRenderableWidget(undoButton);

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

	private void updateAutoSizeState() {
		if (posX2Field == null || posY2Field == null || posZ2Field == null) {
			return;
		}
		if (autoSize) {
			posX2Field.setValue("");
			posY2Field.setValue("");
			posZ2Field.setValue("");
			posX2Field.setEditable(false);
			posY2Field.setEditable(false);
			posZ2Field.setEditable(false);
			posX2Field.setHint(Component.literal("X2"));
			posY2Field.setHint(Component.literal("Y2"));
			posZ2Field.setHint(Component.literal("Z2"));
			if (autoSizeButton != null) {
				autoSizeButton.setMessage(Component.translatable("gui.minecraft-ai.btn.autosize_on"));
			}
		} else {
			posX2Field.setEditable(true);
			posY2Field.setEditable(true);
			posZ2Field.setEditable(true);
			posX2Field.setHint(Component.literal("X2"));
			posY2Field.setHint(Component.literal("Y2"));
			posZ2Field.setHint(Component.literal("Z2"));
			if (autoSizeButton != null) {
				autoSizeButton.setMessage(Component.translatable("gui.minecraft-ai.btn.autosize_off"));
			}
		}
	}

	private void requestPlan() {
		String desc = descField.getValue().trim();
		if (desc.isEmpty()) {
			status = tr("gui.minecraft-ai.status.prompt_empty");
			return;
		}

		BlockPos origin = readOrigin();
		if (origin == null) {
			status = tr("gui.minecraft-ai.status.invalid_origin");
			return;
		}
		BlockPos bound = autoSize ? null : readBound();
		if (bound != null) {
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
		status = tr("gui.minecraft-ai.status.sending");

		String posText = origin.getX() + " " + origin.getY() + " " + origin.getZ();
		if (!autoSize && bound != null) {
			int w = bound.getX() - origin.getX() + 1;
			int d = bound.getZ() - origin.getZ() + 1;
			int h = bound.getY() - origin.getY() + 1;
			posText += "，指定建筑空间范围：从 (" + origin.getX() + " " + origin.getY() + " " + origin.getZ()
					+ ") 到 (" + bound.getX() + " " + bound.getY() + " " + bound.getZ()
					+ ")（宽 " + w + " 深 " + d + " 高 " + h + "），建筑必须严格在此空间内设计";
		} else {
			posText += "，空间大小由你自主规划：请根据建筑类型与风格（例如小型住宅 8~12格、中型别墅 14~18格、宏伟城堡/高楼 20~28格、层高3~5格）自主决定最和谐的宽度、进深与层数！";
		}

		hasStreamTokens = false;
		pendingTask = AiClient.askPlan(desc, posText, config, (thinking, content) -> {
			if (this.minecraft == null) {
				return;
			}
			this.minecraft.execute(() -> {
				if (!busy) {
					return;
				}
				hasStreamTokens = true;
				long s = (System.currentTimeMillis() - requestStartMs) / 1000;
				if (content.isEmpty() && !thinking.isEmpty()) {
					String tail = getTail(thinking, 90).replace("\r", "").replace("\n", " ").trim();
					status = tr("gui.minecraft-ai.status.thinking", s, tail);
				} else if (!content.isEmpty()) {
					String tail = getTail(content, 90).replace("\r", "").replace("\n", " ").trim();
					status = tr("gui.minecraft-ai.status.drawing", s, content.length(), tail);
				}
			});
		});

		pendingTask.whenComplete((reply, error) -> {
			if (this.minecraft == null) {
				return;
			}
			this.minecraft.execute(() -> {
				busy = false;
				pendingTask = null;
				if (error != null) {
					status = tr("gui.minecraft-ai.status.parse_error", rootMessage(error));
					updateButtons();
					return;
				}
				try {
					PlanSpec spec = PlanParser.parse(reply);
					BuildingPlan plan = PlanGenerator.generate(spec, desc);
					this.currentPlan = plan;
					this.currentSpec = spec;
					preview.setPlan(plan);
					preview.resetView();
					long totalS = (System.currentTimeMillis() - requestStartMs) / 1000;
					status = tr("gui.minecraft-ai.status.plan_done", totalS);
					saveState();
				} catch (Exception e) {
					status = tr("gui.minecraft-ai.status.parse_error", e.getMessage());
				}
				updateButtons();
			});
		});
	}

	private void executeBuild() {
		if (currentPlan == null || currentOrigin == null || busy) {
			return;
		}
		if (this.minecraft == null || this.minecraft.getSingleplayerServer() == null) {
			status = tr("gui.minecraft-ai.status.singleplayer_only");
			return;
		}
		try {
			var server = this.minecraft.getSingleplayerServer();
			ResourceKey<Level> dimKey = Level.OVERWORLD;
			if (this.minecraft.player != null && this.minecraft.player.level() != null) {
				dimKey = this.minecraft.player.level().dimension();
			} else if (this.minecraft.level != null) {
				dimKey = this.minecraft.level.dimension();
			}
			ServerLevel world = server.getLevel(dimKey);
			if (world == null) {
				world = server.overworld();
			}
			if (world == null) {
				status = tr("gui.minecraft-ai.status.no_dimension");
				return;
			}
			busy = true;
			updateButtons();
			status = tr("gui.minecraft-ai.status.building", currentPlan.name);
			var future = BuildingExecutor.execute(server, world, currentOrigin, currentPlan, currentBound);
			future.whenComplete((placed, error) -> {
				if (this.minecraft == null) {
					return;
				}
				this.minecraft.execute(() -> {
					busy = false;
					if (error != null) {
						status = tr("gui.minecraft-ai.status.build_error", rootMessage(error));
					} else {
						status = tr("gui.minecraft-ai.status.build_success", placed);
					}
					saveState();
					updateButtons();
				});
			});
		} catch (Exception e) {
			status = tr("gui.minecraft-ai.status.build_error", e.getMessage());
		}
	}

	private void undoBuild() {
		if (this.minecraft == null || this.minecraft.getSingleplayerServer() == null) {
			return;
		}
		var server = this.minecraft.getSingleplayerServer();
		busy = true;
		updateButtons();
		status = tr("gui.minecraft-ai.status.undoing");
		var future = BuildingExecutor.undo(server);
		future.whenComplete((ok, error) -> {
			if (this.minecraft == null) {
				return;
			}
			this.minecraft.execute(() -> {
				busy = false;
				if (error != null) {
					status = tr("gui.minecraft-ai.status.undo_error", rootMessage(error));
				} else if (Boolean.TRUE.equals(ok)) {
					status = tr("gui.minecraft-ai.status.undo_success");
					currentPlan = null;
					currentSpec = null;
					preview.setPlan(null);
				} else {
					status = tr("gui.minecraft-ai.status.undo_empty");
				}
				saveState();
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
		generateButton.active = !busy;
		buildButton.active = currentPlan != null && !busy;
		undoButton.active = BuildingExecutor.canUndo() && !busy;
		if (randomButton != null) randomButton.active = !busy;
		if (syncPosButton != null) syncPosButton.active = !busy;
		if (autoSizeButton != null) autoSizeButton.active = !busy;
		if (configButton != null) configButton.active = !busy;
	}

	private static String rootMessage(Throwable t) {
		Throwable cur = t;
		while (cur.getCause() != null) {
			cur = cur.getCause();
		}
		return cur.getMessage() == null ? cur.toString() : cur.getMessage();
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		int keyCode = event.key();
		if (keyCode == 257 || keyCode == 335) { // Enter or Keypad Enter
			if (descField != null && descField.isFocused() && !busy) {
				requestPlan();
				return true;
			}
		}
		if (keyCode == 32) { // Space
			if ((descField == null || !descField.isFocused())
					&& (posXField == null || !posXField.isFocused())
					&& (posYField == null || !posYField.isFocused())
					&& (posZField == null || !posZField.isFocused())
					&& currentPlan != null && !busy) {
				executeBuild();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int panelW = getPanelW();
		int panelH = getPanelH();
		int x0 = (this.width - panelW) / 2;
		int y0 = (this.height - panelH) / 2;

		int colLeftW = (panelW - 20) * 48 / 100;
		int colRightW = (panelW - 20) - colLeftW;
		int leftX = x0 + 7;
		int rightX = leftX + colLeftW + 6;
		int viewH = panelH - 96;

		// 1. 如果在 3D 视口内：滚轮缩放
		if (mouseX >= rightX && mouseX <= rightX + colRightW && mouseY >= y0 + 19 && mouseY <= y0 + 19 + viewH) {
			if (verticalAmount > 0) {
				preview.zoomIn();
			} else if (verticalAmount < 0) {
				preview.zoomOut();
			}
			return true;
		}

		// 2. 如果在控制台区域内：滚轮滚动日志
		int consoleY = y0 + 112;
		int consoleH = panelH - 140;
		if (mouseX >= leftX && mouseX <= leftX + colLeftW && mouseY >= consoleY && mouseY <= consoleY + consoleH) {
			int maxScroll = Math.max(0, statusLines.size() - STATUS_LINES);
			if (maxScroll > 0) {
				statusScroll = Math.max(0, Math.min(statusScroll + (int) verticalAmount, maxScroll));
				return true;
			}
		}

		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		int panelW = getPanelW();
		int panelH = getPanelH();
		int x0 = (this.width - panelW) / 2;
		int y0 = (this.height - panelH) / 2;

		int colLeftW = (panelW - 20) * 48 / 100;
		int colRightW = (panelW - 20) - colLeftW;
		int leftX = x0 + 7;
		int rightX = leftX + colLeftW + 6;
		int viewH = panelH - 96;
		int consoleY = y0 + 112;
		int consoleH = panelH - 140;

		// 1. 面板深色亚克力背景与发光外框
		context.fill(x0 - 2, y0 - 2, x0 + panelW + 2, y0 + panelH + 2, 0xC00A0D12);
		context.fill(x0, y0, x0 + panelW, y0 + panelH, 0xEE1E2430);

		// 2. 左右双栏卡片背景
		// 左栏卡片：控制台背景
		context.fill(leftX, consoleY, leftX + colLeftW, consoleY + consoleH, 0xD00D1117);
		// 右栏卡片：3D 视口背景
		context.fill(rightX, y0 + 19, rightX + colRightW, y0 + 19 + viewH, 0xD00D1117);
		// 右栏卡片：蓝图信息卡背景
		int cardY = y0 + panelH - 54;
		context.fill(rightX, cardY, rightX + colRightW, cardY + 26, 0xD0161B22);

		super.extractRenderState(context, mouseX, mouseY, delta);

		// 3. 顶部 Header 渲染
		context.text(this.font, tr("gui.minecraft-ai.studio.title"), x0 + 8, y0 + 6, 0xFFFFFFFF);
		String modelBadge = "[" + config.modelName() + "]";
		int badgeW = this.font.width(modelBadge);
		context.text(this.font, modelBadge, x0 + panelW - 56 - badgeW, y0 + 6, 0xFF81D4FA);

		// 4. 左栏标签
		context.text(this.font, tr("gui.minecraft-ai.label.desc"), leftX, y0 + 20, 0xFFC0C0C0);
		context.text(this.font, tr("gui.minecraft-ai.label.origin"), leftX, y0 + 47, 0xFFC0C0C0);
		context.text(this.font, tr("gui.minecraft-ai.label.space"), leftX, y0 + 74, 0xFFC0C0C0);
		context.text(this.font, tr("gui.minecraft-ai.label.console"), leftX, y0 + 102, 0xFFC0C0C0);

		// 5. 3D 预览视口与蓝图信息
		preview.setViewport(rightX + colRightW / 2, y0 + 19 + viewH / 2, colRightW - 4, viewH - 4);
		preview.render(context);

		// 视口右上角角度提示
		if (preview.hasPlan()) {
			String angleText = preview.getRotationDegrees() + "°";
			context.text(this.font, angleText, rightX + colRightW - this.font.width(angleText) - 4, y0 + 23, 0xFF80DEEA);
		} else {
			context.centeredText(this.font, tr("gui.minecraft-ai.preview.empty"), rightX + colRightW / 2, y0 + 19 + viewH / 2 - 4, 0xFF556070);
		}

		// 蓝图信息卡渲染
		if (currentPlan != null && currentSpec != null) {
			String line1 = tr("gui.minecraft-ai.card.info1", currentSpec.name, currentPlan.width, currentPlan.depth, currentPlan.height);
			String line2 = tr("gui.minecraft-ai.card.info2", currentPlan.entries.size(), currentSpec.floors);
			context.text(this.font, line1, rightX + 4, cardY + 3, 0xFFFFFFFF);
			context.text(this.font, line2, rightX + 4, cardY + 14, 0xFF81C784);
		} else {
			context.text(this.font, tr("gui.minecraft-ai.card.empty"), rightX + 6, cardY + 9, 0xFF718096);
		}

		// 6. 控制台状态文本绘制
		drawStatus(context, leftX + 4, consoleY + 4, colLeftW - 8);

		drawImePreview(context);
	}

	private void drawStatus(GuiGraphicsExtractor context, int x, int y, int maxWidth) {
		if (!status.equals(lastStatus)) {
			lastStatus = status;
			statusLines = splitLines(status, maxWidth);
			statusScroll = 0;
		}
		int n = statusLines.size();
		int maxScroll = Math.max(0, n - STATUS_LINES);
		if (statusScroll > maxScroll) statusScroll = maxScroll;
		
		int start = Math.max(0, n - STATUS_LINES - statusScroll);
		int end = Math.min(n, start + STATUS_LINES);
		
		for (int i = start; i < end; i++) {
			context.text(this.font, statusLines.get(i), x, y + (i - start) * STATUS_LINE_H, statusColor());
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
				if (w + cw > maxWidth) break;
				w += cw;
				cut++;
			}
			if (cut == 0) cut = 1;
			lines.add(rest.substring(0, cut));
			rest = rest.substring(cut);
		}
		return lines;
	}

	private int statusColor() {
		if (status.contains("❌") || status.startsWith("出错") || status.startsWith("警告") || status.startsWith("建造失败")
				|| status.startsWith("撤销失败") || status.startsWith("Error") || status.startsWith("Warning")) {
			return 0xFFFF5555;
		}
		if (status.contains("⚡") || status.contains("✏️") || status.startsWith("正在") || status.startsWith("🔨")
				|| status.startsWith("AI") || status.startsWith("Building") || status.startsWith("Undoing")) {
			return 0xFFFFC94F;
		}
		if (status.contains("🎉") || status.contains("✅") || status.startsWith("建造完成") || status.startsWith("撤销成功")
				|| status.startsWith("Build") || status.startsWith("Undo")) {
			return 0xFF4ADE80;
		}
		return 0xFF66FF66;
	}

	private static String getTail(String s, int maxLen) {
		if (s == null) {
			return "";
		}
		if (s.length() <= maxLen) {
			return s;
		}
		return "…" + s.substring(s.length() - maxLen);
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
			if (!hasStreamTokens) {
				status = "AI 连接中…（已等待 " + s + " 秒，等待 AI 响应）";
			}
		}
	}

	private void saveState() {
		if (descField != null) savedDesc = descField.getValue();
		if (posXField != null) savedX = posXField.getValue();
		if (posYField != null) savedY = posYField.getValue();
		if (posZField != null) savedZ = posZField.getValue();
		if (posX2Field != null) savedX2 = posX2Field.getValue();
		if (posY2Field != null) savedY2 = posY2Field.getValue();
		if (posZ2Field != null) savedZ2 = posZ2Field.getValue();
		savedAutoSize = autoSize;
		savedPlan = currentPlan;
		savedSpec = currentSpec;
		savedOrigin = currentOrigin;
		savedBound = currentBound;
		savedStatus = status;
	}

	@Override
	public void onClose() {
		saveState();
		if (pendingTask != null) {
			pendingTask.cancel(true);
		}
		super.onClose();
	}
}