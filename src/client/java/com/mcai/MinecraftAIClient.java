package com.mcai;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import com.mcai.client.gui.AiBuildScreen;
import com.mcai.common.AiConfig;

public class MinecraftAIClient implements ClientModInitializer {
	public static AiConfig CONFIG;

	private static KeyMapping openScreenKey;

	@Override
	public void onInitializeClient() {
		CONFIG = AiConfig.load(FabricLoader.getInstance().getConfigDir());
		MinecraftAIMod.LOGGER.info("[Minecraft AI] 当前 AI 后端: {} / 模型: {} / 思考: {}",
				CONFIG.provider, CONFIG.modelName(), CONFIG.thinkingEnabled);

		openScreenKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minecraft-ai.open_screen",
				GLFW.GLFW_KEY_K,
				KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (openScreenKey.consumeClick() && client.player != null) {
				client.setScreen(new AiBuildScreen(CONFIG));
			}
		});
	}
}