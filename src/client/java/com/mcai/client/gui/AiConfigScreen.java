package com.mcai.client.gui;

import com.mcai.MinecraftAIClient;
import com.mcai.MinecraftAIMod;
import com.mcai.common.AiConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * AI 配置界面：切换后端（OpenAI 兼容 / 本地 Ollama）、修改 API 地址/模型/Key、思考模式开关。
 * 保存后立即生效（下次生成方案即用新配置）。
 */
public class AiConfigScreen extends Screen {
	private static final int PANEL_W = 380;
	private static final int PANEL_H = 240;

	private final AiConfig config;
	private EditBox baseUrlField;
	private EditBox modelField;
	private EditBox apiKeyField;
	private EditBox ollamaUrlField;
	private EditBox ollamaModelField;
	private Button providerOaiButton;
	private Button providerOllamaButton;
	private Button thinkingButton;
	private boolean useOllama;
	private boolean thinkingEnabled;
	private String status = "";

	public AiConfigScreen(AiConfig config) {
		super(Component.literal("AI 配置"));
		this.config = config;
		this.useOllama = !"openai".equals(config.provider);
		this.thinkingEnabled = config.thinkingEnabled;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int cy = this.height / 2;
		int x0 = cx - PANEL_W / 2;
		int y0 = cy - PANEL_H / 2;

		providerOaiButton = Button.builder(Component.literal(""), b -> {
			useOllama = false;
			refreshButtons();
		}).bounds(x0 + 10, y0 + 22, 100, 20).build();
		providerOllamaButton = Button.builder(Component.literal(""), b -> {
			useOllama = true;
			refreshButtons();
		}).bounds(x0 + 120, y0 + 22, 90, 20).build();
		thinkingButton = Button.builder(Component.literal(""), b -> {
			thinkingEnabled = !thinkingEnabled;
			refreshButtons();
		}).bounds(x0 + 220, y0 + 22, 150, 20).build();
		addRenderableWidget(providerOaiButton);
		addRenderableWidget(providerOllamaButton);
		addRenderableWidget(thinkingButton);

		int fieldY = y0 + 52;
		int fieldW = PANEL_W - 20;
		baseUrlField = new EditBox(this.font, x0 + 10, fieldY, fieldW, 18, Component.literal("API地址"));
		baseUrlField.setValue(config.openaiBaseUrl);
		baseUrlField.setHint(Component.literal("API 地址（OpenAI 兼容，以 /v1 结尾，如 https://api.deepseek.com/v1）"));
		addRenderableWidget(baseUrlField);

		modelField = new EditBox(this.font, x0 + 10, fieldY + 20, fieldW, 18, Component.literal("模型名"));
		modelField.setValue(config.openaiModel);
		modelField.setHint(Component.literal("模型名（如 mimo-v2.5、mimo-v2.5-pro、deepseek-chat）"));
		addRenderableWidget(modelField);

		apiKeyField = new EditBox(this.font, x0 + 10, fieldY + 40, fieldW, 18, Component.literal("API Key"));
		apiKeyField.setValue(config.openaiApiKey);
		apiKeyField.setHint(Component.literal("API Key（sk-xxx，仅保存在本地配置文件）"));
		addRenderableWidget(apiKeyField);

		ollamaUrlField = new EditBox(this.font, x0 + 10, fieldY + 60, fieldW, 18, Component.literal("Ollama地址"));
		ollamaUrlField.setValue(config.ollamaUrl);
		ollamaUrlField.setHint(Component.literal("Ollama 地址（选择本地 Ollama 后端时使用）"));
		addRenderableWidget(ollamaUrlField);

		ollamaModelField = new EditBox(this.font, x0 + 10, fieldY + 80, fieldW, 18, Component.literal("Ollama模型"));
		ollamaModelField.setValue(config.ollamaModel);
		ollamaModelField.setHint(Component.literal("Ollama 模型名（选择本地 Ollama 后端时使用）"));
		addRenderableWidget(ollamaModelField);

		Button saveButton = Button.builder(Component.literal("保存"),
				b -> saveConfig())
				.bounds(x0 + 10, y0 + PANEL_H - 26, 80, 20)
				.build();
		Button backButton = Button.builder(Component.literal("返回"),
				b -> this.onClose())
				.bounds(x0 + 100, y0 + PANEL_H - 26, 80, 20)
				.build();
		addRenderableWidget(saveButton);
		addRenderableWidget(backButton);

		refreshButtons();
	}

	private void refreshButtons() {
		if (providerOaiButton != null) {
			providerOaiButton.setMessage(Component.literal((useOllama ? "  " : "✓ ") + "OpenAI 兼容"));
			providerOllamaButton.setMessage(Component.literal((useOllama ? "✓ " : "  ") + "本地 Ollama"));
			thinkingButton.setMessage(Component.literal("思考模式：" + (thinkingEnabled ? "开" : "关")));
		}
	}

	private void saveConfig() {
		config.provider = useOllama ? "ollama" : "openai";
		config.openaiBaseUrl = baseUrlField.getValue().trim();
		config.openaiModel = modelField.getValue().trim();
		config.openaiApiKey = apiKeyField.getValue().trim();
		config.ollamaUrl = ollamaUrlField.getValue().trim();
		config.ollamaModel = ollamaModelField.getValue().trim();
		config.thinkingEnabled = thinkingEnabled;
		if (config.openaiModel.isEmpty()) {
			status = "模型名不能为空，未保存";
			return;
		}
		config.save(FabricLoader.getInstance().getConfigDir());
		MinecraftAIClient.CONFIG = config;
		MinecraftAIMod.LOGGER.info("[Minecraft AI] 配置已保存: provider={} model={} thinking={}",
				config.provider, config.modelName(), config.thinkingEnabled);
		status = "已保存并生效（下次生成方案即用新配置）";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		int cx = this.width / 2;
		int cy = this.height / 2;
		int x0 = cx - PANEL_W / 2;
		int y0 = cy - PANEL_H / 2;

		context.fill(x0 - 4, y0 - 4, x0 + PANEL_W + 4, y0 + PANEL_H + 4, 0xC0101010);
		context.fill(x0, y0, x0 + PANEL_W, y0 + PANEL_H, 0xE62A2F38);
		context.fill(x0 + 8, y0 + 152, x0 + PANEL_W - 8, y0 + 172, 0xB0000000);

		super.extractRenderState(context, mouseX, mouseY, delta);

		context.centeredText(this.font, "AI 配置", cx, y0 + 4, 0xFFFFFFFF);
		String info = "当前生效：" + (useOllama ? config.ollamaModel : config.openaiModel)
				+ "（思考" + (thinkingEnabled ? "开" : "关") + "）";
		context.text(this.font, info, x0 + 12, y0 + 156, 0xFFC0C0C0);
		context.text(this.font, status, x0 + 12, y0 + 166, status.isEmpty() ? 0xFFC0C0C0 : 0xFF66FF66);
	}
}