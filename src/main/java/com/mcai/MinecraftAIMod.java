package com.mcai;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MinecraftAIMod implements ModInitializer {
	public static final String MOD_ID = "minecraft-ai";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("[Minecraft AI] 模组已加载，AI 建造助手就绪");
	}
}
