package com.mcai.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * AI 后端配置（config/minecraft-ai.json）。
 * 支持两种后端：
 *  - ollama：本地 Ollama（默认）
 *  - openai：任何 OpenAI 兼容 API（OpenRouter / DeepSeek 官方 / 硅基流动等）
 */
public class AiConfig {
	/** ollama / openai */
	public String provider = "ollama";

	// ---- ollama ----
	public String ollamaUrl = "http://127.0.0.1:11434";
	public String ollamaModel = "qwen2.5:7b";

	// ---- openai 兼容 ----
	public String openaiBaseUrl = "https://openrouter.ai/api/v1";
	public String openaiModel = "deepseek/deepseek-v4-flash:free";
	public String openaiApiKey = "";

	/** 思考模式（推理模型开启后质量更高但更慢）；默认开启 */
	public boolean thinkingEnabled = true;

	public String modelName() {
		if ("openai".equals(provider)) {
			return openaiModel;
		}
		return ollamaModel;
	}

	public String chatEndpoint() {
		if ("openai".equals(provider)) {
			return openaiBaseUrl.replaceAll("/+$", "") + "/chat/completions";
		}
		return ollamaUrl.replaceAll("/+$", "") + "/api/chat";
	}

	public String availabilityEndpoint() {
		if ("openai".equals(provider)) {
			return openaiBaseUrl.replaceAll("/+$", "") + "/models";
		}
		return ollamaUrl.replaceAll("/+$", "") + "/api/tags";
	}

	public String apiKey() {
		if ("openai".equals(provider)) {
			return openaiApiKey;
		}
		return "";
	}

	// ==================== 读写 ====================

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static AiConfig load(Path configDir) {
		AiConfig cfg = new AiConfig();
		Path file = configDir.resolve("minecraft-ai.json");
		if (Files.exists(file)) {
			try {
				cfg = GSON.fromJson(Files.readString(file), AiConfig.class);
				if (cfg == null) {
					cfg = new AiConfig();
				}
			} catch (IOException | com.google.gson.JsonSyntaxException e) {
				System.err.println("[Minecraft AI] 配置文件解析失败，使用默认配置: " + e.getMessage());
				cfg = new AiConfig();
			}
		} else {
			cfg.save(configDir);
		}
		return cfg;
	}

	public void save(Path configDir) {
		try {
			Files.createDirectories(configDir);
			Files.writeString(configDir.resolve("minecraft-ai.json"), GSON.toJson(this));
		} catch (IOException e) {
			System.err.println("[Minecraft AI] 配置文件写入失败: " + e.getMessage());
		}
	}
}