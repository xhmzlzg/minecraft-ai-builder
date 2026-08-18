package com.mcai.client.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.mcai.MinecraftAIMod;
import com.mcai.common.AiConfig;

/**
 * AI 后端客户端：支持本地 Ollama 与任意 OpenAI 兼容 API（OpenRouter / DeepSeek 官方等）。
 * 在后台线程执行，不阻塞游戏主线程。
 */
public class AiClient {
	private static final String SYSTEM_PROMPT = """
			你是 Minecraft 建筑设计师。玩家用一句话描述想要的建筑，你来设计并画出每层楼的平面蓝图（字符画），输出 JSON。

			字符含义：
			# = 墙（主墙材质）
			W = 窗（玻璃+窗框）
			D = 门（门洞）
			P = 装饰柱（强调材质）
			S = 楼梯井/电梯井（程序自动生成脚手架柱贯穿全楼）
			. = 室内地板
			R = 床  B = 书柜  C = 工作台/柜台  F = 熔炉/灶台  L = 灯笼  T = 花盆  X = 海晶灯

			JSON 格式（只输出 JSON，禁止任何其他文字，不要代码块）：
			{
			  "name": "建筑名",
			  "floors": 层数,
			  "layer_height": 层高（格）,
			  "wall": "主墙方块id",
			  "accent": "强调方块id",
			  "roof": "flat 或 pyramid 或 gabled",
			  "interiors": true,
			  "floors_map": {
			    "1": ["首层蓝图", ...],
			    "2": ["标准层蓝图", ...],
			    "top": ["顶层蓝图", ...]
			  }
			}

			layer_height：玩家描述里提到层高（如"层高5格"）就严格照做；没提到就自主决定 3~5（普通住宅 3、大堂/教堂/城堡 5）

			floors_map 模板规则（重要！）：
			- "floors" 必须严格等于玩家要求的层数！玩家说 20 层就是 20，说 3 层就是 3，绝不擅自增减
			- 蓝图是模板不是楼层清单：只画 2~4 张图，"1" 首层、"2" 标准层、"top" 顶层（顶层和标准层一样就省略 top）
			- 程序会把 "2" 的标准层自动复制到所有中间楼层，楼层数只写进 floors 字段，蓝图张数与楼层数无关！
			- 20 层高楼和 3 层小楼都只需 2~3 张蓝图，严禁每层单独画图、严禁超过 4 张图
			- 只有某一层特别（如 3 层是露台）才补一张，key 写 "3"
			- "top" 顶层可以有天台/瞭望/花园特色

			蓝图绘制规则：
			1. 每行是一个字符串，第一行是南面外墙；所有行必须等长（宽度 8~24），行数 = 进深（8~24）
			2. 外墙以 # 为主墙，每面外墙开 2~4 个 W 窗（间距均匀）和 1~2 个 D 门，窗门交替排列，外墙不能全是窗
			3. 室内用 # 内墙把空间分隔成多个房间/户型，每户必须有：D 门、R 床、C 桌、F 灶、T 卫、L 灯
			4. 住宅每户：卧室 R、客厅 C、厨房 F、卫生间 T、门口 D、窗 W、室内灯 L 都要画
			5. 一楼每个单元必须有入口 D；层数多时楼梯井/电梯井用 S 画在每层同一位置
			6. 玩家提到庭院/花园/水池时，用 T/L/P 和 . 在第一层蓝图里画出院落布置
			7. 顶层"top"蓝图可以比其他层多画一行，这一行生成在屋顶表面（实心屋顶之上）作为屋顶装饰：'P' 避雷针/天线柱、'X' 海晶灯、'L' 灯笼；不画也行，程序会自动铺实心平顶。注意：屋顶本身永远是实心封顶，绝不能有玻璃、天窗或空隙
			8. 外墙避免单调：W 窗和 P 柱交错，窗要有规律
			9. 可用方块：white_concrete、light_gray_concrete、stone_bricks、cobblestone、oak_planks、dark_oak_planks、spruce_planks、terracotta、red_terracotta、oak_log、dark_oak_log、spruce_log、glass_pane、oak_door、dark_oak_stairs、red_wool、lantern、sea_lantern、flower_pot、bookshelf、crafting_table、chest、furnace、red_bed、dark_oak_fence、oak_fence_gate、scaffolding

			设计要点：
			1. 认真理解玩家描述：几层、河边/海景、院子、阳台、塔楼、飘窗、风格、颜色都要画进蓝图
			2. 中式建筑用深色木材(dark_oak_planks)+红色点缀(red_terracotta)；现代用混凝土(white_concrete)+浅灰；城堡用石砖(stone_bricks)；海边度假屋用浅色木+白色
			3. 描述说"精美/气派/豪华"时，多用 P 柱子、W 大窗、L 灯笼、T 花草，布局讲究对称
			4. 建筑要精致有设计感：门廊、柱廊、露台、错落的天台，绝不要只画一个空盒子
			5. 室内房间要分隔：用 # 做内墙，分出客厅/卧室/厨房/卫生间等区域
			""";

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "minecraft-ai-request");
		t.setDaemon(true);
		return t;
	});

	/**
	 * 异步请求 AI 生成建筑方案，返回 AI 的原始文本回复。
	 */
	public static CompletableFuture<String> askPlan(String description, String posText, AiConfig cfg) {
		return CompletableFuture.supplyAsync(() -> {
			String endpoint = cfg.chatEndpoint();

			JsonObject body = new JsonObject();
			body.addProperty("model", cfg.modelName());
			body.addProperty("stream", false);
			// 思考模式：推理模型（如 DeepSeek）不限制思考预算，保证任何提示词的设计质量。
			// 关闭时不传 thinking 字段，兼容不支持思考参数的供应商。
			if (cfg.thinkingEnabled) {
				JsonObject thinking = new JsonObject();
				thinking.addProperty("type", "enabled");
				body.add("thinking", thinking);
			}

			JsonArray messages = new JsonArray();
			JsonObject system = new JsonObject();
			system.addProperty("role", "system");
			system.addProperty("content", SYSTEM_PROMPT);
			messages.add(system);

			JsonObject user = new JsonObject();
			user.addProperty("role", "user");
			user.addProperty("content", "在坐标 " + posText + " 建造：" + description);
			messages.add(user);
			body.add("messages", messages);

			HttpRequest.Builder rb = HttpRequest.newBuilder()
					.uri(URI.create(endpoint))
					.timeout(Duration.ofMinutes(10))
					.header("Content-Type", "application/json")
					.header("User-Agent", "minecraft-ai-mod")
					.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
			if (!cfg.apiKey().isEmpty()) {
				rb.header("Authorization", "Bearer " + cfg.apiKey());
			}

			try {
				MinecraftAIMod.LOGGER.info("[Minecraft AI] 请求发出: model={} endpoint={}", cfg.modelName(), endpoint);
				HttpResponse<String> response = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
				MinecraftAIMod.LOGGER.info("[Minecraft AI] 请求返回: HTTP {}", response.statusCode());
				if (response.statusCode() != 200) {
					throw new RuntimeException("AI 服务返回错误: HTTP " + response.statusCode()
							+ " " + truncate(response.body(), 200) + "（请检查模型配置）");
				}
				return extractContent(response.body(), cfg);
			} catch (java.net.ConnectException e) {
				throw new RuntimeException("无法连接 AI 服务 " + endpoint + "（请检查 Ollama 是否启动/网络）", e);
			} catch (java.net.http.HttpTimeoutException e) {
				throw new RuntimeException("AI 响应超时（免费模型高峰期较慢，可重试）", e);
			} catch (Exception e) {
				throw new RuntimeException("AI 请求失败: " + e.getMessage(), e);
			}
		}, EXECUTOR);
	}

	private static String extractContent(String responseBody, AiConfig cfg) {
		JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
		if ("openai".equals(cfg.provider)) {
			JsonArray choices = json.getAsJsonArray("choices");
			if (choices == null || choices.isEmpty()) {
				throw new RuntimeException("AI 返回异常: " + truncate(responseBody, 200));
			}
			JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
			String content = message.get("content").getAsString();
			if (content == null || content.isBlank()) {
				throw new RuntimeException("AI 返回为空（免费模型可能被限流，请稍后重试）");
			}
			return content;
		}
		return json.getAsJsonObject("message").get("content").getAsString();
	}

	/**
	 * 检查 AI 后端是否可用。
	 */
	public static CompletableFuture<Boolean> isAvailable(AiConfig cfg) {
		return CompletableFuture.supplyAsync(() -> {
			HttpRequest.Builder rb = HttpRequest.newBuilder()
					.uri(URI.create(cfg.availabilityEndpoint()))
					.timeout(Duration.ofSeconds(4))
					.header("User-Agent", "minecraft-ai-mod")
					.GET();
			if (!cfg.apiKey().isEmpty()) {
				rb.header("Authorization", "Bearer " + cfg.apiKey());
			}
			try {
				HttpResponse<String> response = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
				return response.statusCode() == 200;
			} catch (Exception e) {
				MinecraftAIMod.LOGGER.warn("[Minecraft AI] 后端不可用: {}", e.getMessage());
				return false;
			}
		}, EXECUTOR);
	}

	private static String truncate(String s, int n) {
		if (s == null) {
			return "";
		}
		return s.length() <= n ? s : s.substring(0, n);
	}
}