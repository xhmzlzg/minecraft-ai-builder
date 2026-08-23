package com.mcai.client.ai;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * 在后台线程执行，支持 SSE 流式实时进度回调，不阻塞游戏主线程。
 */
public class AiClient {
	public interface StreamCallback {
		void onUpdate(String thinking, String content);
	}

	private static final String SYSTEM_PROMPT = """
			你是 Minecraft 资深建筑设计师。玩家用一句话描述想要的建筑，你来构思立体的建筑造型并画出各层平面蓝图（字符画），输出 JSON。

			字符含义：
			# = 墙（主墙材质）
			W = 窗（玻璃+窗框）
			D = 门（门洞）
			P = 结构柱/立柱（强调材质，程序会自动生成 3D 浮雕柱体）
			S = 楼梯井/电梯井（程序自动生成脚手架贯穿全楼）
			. = 空地/室内地板/室外露台（室内铺设木地板；若上一层有建筑而本层为空，程序会自动生成带护栏的露天花园阳台/退台！）
			R = 卧室床铺（自动匹配床头地毯）
			B = 书架/雕纹书柜
			C = 工作台/制图桌/织布机
			F = 厨房灶台（烟熏炉/熔炉）
			K = 收纳储物（箱子/木桶）
			H = 沙发/休闲座椅
			M = 水槽/洗手台
			Y = 客厅地毯/茶几
			L = 灯笼/照明
			T = 绿植盆栽（带花草植物）
			X = 天花板嵌入式海晶灯

			JSON 格式（只输出 JSON，禁止任何其他文字，不要代码块）：
			{
			  "name": "建筑名",
			  "floors": 层数,
			  "layer_height": 层高（格，默认 3~5）,
			  "wall": "主墙方块id",
			  "accent": "强调方块id",
			  "roof": "gabled(中式飞檐/坡顶) 或 pyramid(宝塔/金字塔尖顶) 或 flat(现代天台花园) 或 castle(城堡垛口)",
			  "interiors": true,
			  "floors_map": {
			    "1": ["首层蓝图", ...],
			    "2": ["标准层蓝图", ...],
			    "top": ["顶层蓝图", ...]
			  }
			}

			建筑造型与轮廓设计（重要！绝不要画死板的单调实心方盒子）：
			1. 支持多边形与异形轮廓：善用 '.' 画出 L型别墅、U型合院、八角塔楼、凹凸飘窗、中庭院落。
			2. 阶梯退台与高楼造型（如台北101、摩天大楼、城堡塔楼）：
			   - 首层可以宽大（如大堂/基座），上层通过在外围画 '.' 缩小轮廓（程序会自动在露出的退台上生成精致露台、石砖地面、护栏和盆栽！）。
			   - 摩天大楼（如台北101）：可使用分段退台或立柱 P 装饰，顶层缩小并带天线/尖顶。
			3. 外立面立体层次：
			   - 拐角和承重处多放置 'P' 柱子，形成 3D 浮雕柱。
			   - 外墙 W 窗户与 P 柱错落有致，大落地窗与凸窗结合，避免单调的一整面大平墙。
			4. 室内房间与入口（重要！严禁密室）：
			   - 用 # 分隔出客厅、卧室、厨房、卫浴，合理布置生活家具。
			尺寸与大小自主规划规则（重要！）：
			- 当用户未强制指定空间范围时，由你根据建筑类型自主规划最完美的尺寸、进深与层数：
			  * 紧凑小屋/门卫室/林间小亭：宽 8~12格，进深 8~12格，1~2 层
			  * 现代别墅/中式庭院宅邸/温泉旅馆：宽 14~18格，进深 14~18格，2~3 层
			  * 宏伟城堡/中世纪教堂/摩天双子塔/高耸宝塔：宽 18~28格，进深 18~28格，3~12 层
			- 每一层蓝图字符画的行数（进深）与每行字符数（宽度）必须完全等长一致！

			可用方块：white_concrete、light_gray_concrete、stone_bricks、cobblestone、oak_planks、dark_oak_planks、spruce_planks、birch_planks、terracotta、red_terracotta、oak_log、dark_oak_log、spruce_log、glass_pane、oak_door、dark_oak_stairs、lantern、sea_lantern、bookshelf、chiseled_bookshelf、crafting_table、cartography_table、smithing_table、chest、barrel、furnace、smoker、cauldron、red_bed、blue_bed、white_bed、dark_oak_fence、oak_fence_gate、scaffolding
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
		return askPlan(description, posText, cfg, null);
	}

	/**
	 * 异步流式请求 AI 生成建筑方案，支持实时接收思考内容与蓝图内容。
	 */
	public static CompletableFuture<String> askPlan(String description, String posText, AiConfig cfg, StreamCallback callback) {
		return CompletableFuture.supplyAsync(() -> {
			String endpoint = cfg.chatEndpoint();

			JsonObject body = new JsonObject();
			body.addProperty("model", cfg.modelName());
			body.addProperty("stream", true);
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
				MinecraftAIMod.LOGGER.info("[Minecraft AI] 流式请求发出: model={} endpoint={}", cfg.modelName(), endpoint);
				HttpResponse<InputStream> response = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
				MinecraftAIMod.LOGGER.info("[Minecraft AI] 流式连接建立: HTTP {}", response.statusCode());
				if (response.statusCode() != 200) {
					String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
					throw new RuntimeException("AI 服务返回错误: HTTP " + response.statusCode()
							+ " " + truncate(errBody, 200) + "（请检查模型配置）");
				}

				StringBuilder contentBuilder = new StringBuilder();
				StringBuilder thinkingBuilder = new StringBuilder();
				boolean isOpenAI = "openai".equals(cfg.provider);

				try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						line = line.trim();
						if (line.isEmpty() || line.startsWith(":")) {
							continue;
						}
						if (line.equals("data: [DONE]")) {
							break;
						}

						String jsonStr = line;
						if (line.startsWith("data: ")) {
							jsonStr = line.substring(6).trim();
						}

						try {
							JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
							if (isOpenAI) {
								JsonArray choices = json.getAsJsonArray("choices");
								if (choices != null && !choices.isEmpty()) {
									JsonObject choice = choices.get(0).getAsJsonObject();
									JsonObject delta = choice.getAsJsonObject("delta");
									if (delta != null) {
										if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
											String reason = delta.get("reasoning_content").getAsString();
											thinkingBuilder.append(reason);
										}
										if (delta.has("content") && !delta.get("content").isJsonNull()) {
											String deltaContent = delta.get("content").getAsString();
											contentBuilder.append(deltaContent);
										}
									}
								}
							} else {
								// Ollama 格式
								if (json.has("message")) {
									JsonObject msg = json.getAsJsonObject("message");
									if (msg.has("content")) {
										String deltaContent = msg.get("content").getAsString();
										contentBuilder.append(deltaContent);
									}
									if (msg.has("thinking")) {
										String reason = msg.get("thinking").getAsString();
										thinkingBuilder.append(reason);
									}
								}
							}

							if (callback != null) {
								callback.onUpdate(thinkingBuilder.toString(), contentBuilder.toString());
							}
						} catch (Exception ignored) {
						}
					}
				}

				String full = contentBuilder.toString();
				if (full.isBlank() && !thinkingBuilder.isEmpty()) {
					full = thinkingBuilder.toString();
				}
				if (full.isBlank()) {
					throw new RuntimeException("AI 返回内容为空（请稍后重试）");
				}
				return full;
			} catch (java.net.ConnectException e) {
				throw new RuntimeException("无法连接 AI 服务 " + endpoint + "（请检查 Ollama 是否启动/网络）", e);
			} catch (java.net.http.HttpTimeoutException e) {
				throw new RuntimeException("AI 响应超时（免费模型高峰期较慢，可重试）", e);
			} catch (Exception e) {
				throw new RuntimeException("AI 请求失败: " + e.getMessage(), e);
			}
		}, EXECUTOR);
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