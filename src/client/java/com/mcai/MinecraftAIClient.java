package com.mcai;

import com.mcai.client.ai.BuildTaskManager;
import com.mcai.client.gui.AiBuildScreen;
import com.mcai.common.AiConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class MinecraftAIClient implements ClientModInitializer {
	public static AiConfig CONFIG;

	private static KeyMapping openScreenKey;

	@Override
	public void onInitializeClient() {
		CONFIG = AiConfig.load(FabricLoader.getInstance().getConfigDir());
		MinecraftAIMod.LOGGER.info("[Minecraft AI] 当前 AI 后端: {} / 模型: {} / 思考: {}",
				CONFIG.provider, CONFIG.modelName(), CONFIG.thinkingEnabled);
		// 只记长度不记内容：一旦 key 被截断（历史 bug：EditBox 默认 maxLength=32）能立刻看出来
		MinecraftAIMod.LOGGER.info("[Minecraft AI] API Key 长度: {} 字符（正常应为厂商完整长度，过短说明被截断）",
				CONFIG.openaiApiKey == null ? 0 : CONFIG.openaiApiKey.length());

		openScreenKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minecraft-ai.open_screen",
				GLFW.GLFW_KEY_K,
				KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (openScreenKey.consumeClick() && client.player != null) {
				client.setScreen(new AiBuildScreen(CONFIG));
			}
			// 游戏内鼠标被捕获无法点击 HUD，悬浮球仅作状态提示，按 K 查看结果
		});

		HudElementRegistry.addLast(Identifier.tryParse("minecraft-ai:floating_ball"),
				(graphics, deltaTracker) -> BuildTaskManager.renderBall(graphics));
	}
}
