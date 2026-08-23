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
 * 完整多语言支持（繁中 / 简中 / 英文）。
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

	private static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	public AiConfigScreen(AiConfig config) {
		super(Component.translatable("gui.minecraft-ai.config.title"));
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
		int labelW = 86;
		int fieldW = PANEL_W - 20 - labelW;
		baseUrlField = new EditBox(this.font, x0 + 10 + labelW, fieldY, fieldW, 18, Component.literal("Base URL"));
		baseUrlField.setValue(config.openaiBaseUrl);
		baseUrlField.setMaxLength(10000);
		baseUrlField.setHint(Component.translatable("gui.minecraft-ai.hint.base_url"));
		addRenderableWidget(baseUrlField);

		modelField = new EditBox(this.font, x0 + 10 + labelW, fieldY + 20, fieldW, 18, Component.literal("Model"));
		modelField.setValue(config.openaiModel);
		modelField.setMaxLength(10000);
		modelField.setHint(Component.translatable("gui.minecraft-ai.hint.model"));
		addRenderableWidget(modelField);

		apiKeyField = new EditBox(this.font, x0 + 10 + labelW, fieldY + 40, fieldW, 18, Component.literal("API Key"));
		apiKeyField.setValue(config.openaiApiKey);
		apiKeyField.setMaxLength(10000);
		apiKeyField.setHint(Component.translatable("gui.minecraft-ai.hint.api_key"));
		addRenderableWidget(apiKeyField);

		ollamaUrlField = new EditBox(this.font, x0 + 10 + labelW, fieldY + 60, fieldW, 18, Component.literal("Ollama URL"));
		ollamaUrlField.setValue(config.ollamaUrl);
		ollamaUrlField.setMaxLength(10000);
		ollamaUrlField.setHint(Component.translatable("gui.minecraft-ai.hint.ollama_url"));
		addRenderableWidget(ollamaUrlField);

		ollamaModelField = new EditBox(this.font, x0 + 10 + labelW, fieldY + 80, fieldW, 18, Component.literal("Ollama Model"));
		ollamaModelField.setValue(config.ollamaModel);
		ollamaModelField.setMaxLength(10000);
		ollamaModelField.setHint(Component.translatable("gui.minecraft-ai.hint.ollama_model"));
		addRenderableWidget(ollamaModelField);

		Button saveButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.save"),
				b -> saveConfig())
				.bounds(x0 + 10, y0 + PANEL_H - 26, 80, 20)
				.build();
		Button backButton = Button.builder(Component.translatable("gui.minecraft-ai.btn.back"),
				b -> this.onClose())
				.bounds(x0 + 100, y0 + PANEL_H - 26, 80, 20)
				.build();
		addRenderableWidget(saveButton);
		addRenderableWidget(backButton);

		refreshButtons();
	}

	private void refreshButtons() {
		if (providerOaiButton != null) {
			providerOaiButton.setMessage(Component.literal((useOllama ? "  " : "✓ ") + tr("gui.minecraft-ai.btn.openai")));
			providerOllamaButton.setMessage(Component.literal((useOllama ? "✓ " : "  ") + tr("gui.minecraft-ai.btn.ollama")));
			String stateStr = thinkingEnabled ? tr("gui.minecraft-ai.label.on") : tr("gui.minecraft-ai.label.off");
			thinkingButton.setMessage(Component.literal(tr("gui.minecraft-ai.label.thinking", stateStr)));
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
			status = tr("gui.minecraft-ai.status.model_empty");
			return;
		}
		config.save(FabricLoader.getInstance().getConfigDir());
		MinecraftAIClient.CONFIG = config;
		MinecraftAIMod.LOGGER.info("[Minecraft AI] 配置已保存: provider={} model={} thinking={}",
				config.provider, config.modelName(), config.thinkingEnabled);
		status = tr("gui.minecraft-ai.status.config_saved");
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

		context.centeredText(this.font, tr("gui.minecraft-ai.config.title"), cx, y0 + 4, 0xFFFFFFFF);
		int fieldY = y0 + 52;
		context.text(this.font, tr("gui.minecraft-ai.label.base_url"), x0 + 12, fieldY + 3, 0xFFC0C0C0);
		context.text(this.font, tr("gui.minecraft-ai.label.model_name"), x0 + 12, fieldY + 23, 0xFFC0C0C0);
		context.text(this.font, tr("gui.minecraft-ai.label.api_key"), x0 + 12, fieldY + 43, 0xFFC0C0C0);
		context.text(this.font, tr("gui.minecraft-ai.label.ollama_url"), x0 + 12, fieldY + 63, 0xFFC0C0C0);
		context.text(this.font, tr("gui.minecraft-ai.label.ollama_model"), x0 + 12, fieldY + 83, 0xFFC0C0C0);

		String stateStr = thinkingEnabled ? tr("gui.minecraft-ai.label.on") : tr("gui.minecraft-ai.label.off");
		String activeModel = useOllama ? config.ollamaModel : config.openaiModel;
		String info = tr("gui.minecraft-ai.label.current_active", activeModel, stateStr);
		context.text(this.font, info, x0 + 12, y0 + 156, 0xFFC0C0C0);
		if (!status.isEmpty()) {
			context.text(this.font, status, x0 + 12, y0 + 176, status.contains("❌") || status.contains("未保存") ? 0xFFFF5555 : 0xFF66FF66);
		}
	}
}