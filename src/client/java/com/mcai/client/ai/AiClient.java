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

	/**
	 * v1.1.0 新契约：AI 输出"设计规格 JSON"（DSL），由 SpecBuilder 参数化展开成方块。
	 * 相比旧版字符画蓝图（8~24 格、13 个字符），规格可以表达层高、户型、房间、
	 * 材质、屋顶构件、楼梯电梯等细节，而且输出很短 —— 细节不再被输出长度挤掉。
	 */
	private static final String SYSTEM_PROMPT_V2 = """
			你是 Minecraft 建筑设计师兼规格工程师。玩家描述想要的建筑，你输出【设计规格 JSON】，
			由游戏内的参数化构件库确定性地展开成方块（门窗、家具、楼梯、电梯、屋面都由程序按规格生成）。

			只输出 JSON，禁止任何解释文字，禁止代码块。

			JSON 结构（除 name 外都可省略，程序会补合理默认值）：
			{
			  "spec_version": 2,
			  "name": "建筑名",
			  "archetype": "chinese_highrise|chinese_courtyard|modern_villa|castle|generic",
			  "floors": 层数,
			  "layer_height": 层高(3~8，住宅默认4 = 1格楼板 + 3格净高),
			  "size": [宽, 进深],
			  "features": ["stairs","elevator","balcony","roof_equipment","parapet","garden","no_ac"],
			  "materials": {"wall":"方块id","accent":"方块id","glass":"方块id","frame":"方块id",
			                "base":"方块id","floor_living":"方块id","floor_wet":"方块id","roof":"方块id"},
			  "rooms": [{"type":"living","x":0,"z":0,"w":6,"d":5}],
			  "roof": {"style":"flat|pyramid|gabled","parapet":true,"water_tank":true,"solar":true,"garden":false},
			  "notes": "玩家提到的其它细节"
			}

			房间 type 只能取：living(客厅) master(主卧) bed2/bed3(次卧) kitchen(厨房) bath(卫生间)
			dining(餐厅) entry(玄关) hall(走道) balcony(阳台) study(书房) storage(储物)
			stairs(楼梯间) lobby(候梯厅) courtyard(庭院) pool(水池) garden(花园)
			每个房间会自动配家具：客厅沙发电视茶几、主卧双人床+衣柜、厨房灶台抽油烟机水槽冰箱、
			卫生间马桶洗手台淋浴、餐厅餐桌椅、玄关鞋柜、阳台晾衣杆+洗衣机、书房书桌书柜。

			硬性规则：
			1. floors 必须严格等于玩家说的层数；没说就按原型默认（中式高层8、别墅2、城堡3）。
			2. archetype 判断：中国城市住宅楼/公寓/居民楼 → chinese_highrise；中式院落/四合院 → chinese_courtyard；
			   别墅/现代住宅 → modern_villa；城堡/要塞 → castle；其它 → generic。
			3. chinese_highrise 由程序自动生成"一梯两户 + 双跑楼梯 + 脚手架电梯井 + 候梯厅"，
			   你不必也无法用房间表描述核心筒，只需让 features 含 stairs 和 elevator。
			4. 屋顶必须封顶（程序自动铺平屋面 + 女儿墙），可用 roof.style 选 flat/pyramid/gabled。
			5. 窗由程序按房间类型自动开（客厅卧室大窗、厨卫小高窗），不要试图自己排窗。
			6. size 一般省略（用玩家框选的范围）；只有玩家明确要求尺寸时才填。房间表同理，只有自定义户型时才给。
			7. materials 只填确实要指定的项，其余省略用原型默认。
			8. notes 写玩家提到的其它细节（颜色、风格、特殊要求），程序会尽量落实。
			9. 被要求"按意见调整"时：你会看到自己上一次的规格 JSON 和玩家的修改意见，
			   请输出【完整的新规格 JSON】（结构完全一致），只改需要改的字段，其余原样保留。
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
		return askPlan(description, posText, cfg, null, null);
	}

	/**
	 * 带对话历史的请求：previousReply 非空时为"按意见调整"模式——
	 * 把上次的原始方案作为 assistant 消息发回，AI 在自己方案的基础上修改。
	 */
	public static CompletableFuture<String> askPlan(String description, String posText, AiConfig cfg,
			String previousReply, String adjustment) {
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
			system.addProperty("content", SYSTEM_PROMPT_V2);
			messages.add(system);

			JsonObject user = new JsonObject();
			user.addProperty("role", "user");
			user.addProperty("content", "在坐标 " + posText + " 建造：" + description);
			messages.add(user);

			if (previousReply != null && adjustment != null) {
				// 迭代调整：带上自己上次的方案，只改需要调整的部分
				JsonObject assistant = new JsonObject();
				assistant.addProperty("role", "assistant");
				assistant.addProperty("content", previousReply);
				messages.add(assistant);

				JsonObject feedback = new JsonObject();
				feedback.addProperty("role", "user");
				feedback.addProperty("content", "调整意见：" + adjustment
						+ "\n请基于你上面的规格输出【完整的新规格 JSON】（spec_version/archetype/floors/layer_height/"
						+ "features/materials/roof 等字段都要有，结构完全一致），只改需要调整的字段，其余原样保留。"
						+ "只输出 JSON，禁止任何其他文字，不要代码块。");
				messages.add(feedback);
			}
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
				MinecraftAIMod.LOGGER.error("[Minecraft AI] 连接失败", e);
				throw new RuntimeException("无法连接 AI 服务 " + endpoint + "（请检查 Ollama 是否启动/网络）", e);
			} catch (java.net.http.HttpTimeoutException e) {
				MinecraftAIMod.LOGGER.error("[Minecraft AI] 响应超时", e);
				throw new RuntimeException("AI 响应超时（免费模型高峰期较慢，可重试）", e);
			} catch (Exception e) {
				MinecraftAIMod.LOGGER.error("[Minecraft AI] 请求失败: {}", e.getMessage(), e);
				throw new RuntimeException("AI 请求失败: " + e.getMessage(), e);
			}
		}, EXECUTOR);
	}

	private static String extractContent(String responseBody, AiConfig cfg) {
		JsonObject json;
		try {
			json = JsonParser.parseString(responseBody).getAsJsonObject();
		} catch (Exception e) {
			MinecraftAIMod.LOGGER.error("[Minecraft AI] AI 响应不是有效 JSON: {}", truncate(responseBody, 500));
			throw new RuntimeException("AI 返回的不是有效 JSON: " + truncate(responseBody, 200));
		}
		if ("openai".equals(cfg.provider)) {
			JsonArray choices = json.getAsJsonArray("choices");
			if (choices == null || choices.isEmpty()) {
				MinecraftAIMod.LOGGER.error("[Minecraft AI] AI 响应缺少 choices: {}", truncate(responseBody, 500));
				throw new RuntimeException("AI 返回异常: " + truncate(responseBody, 200));
			}
			JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
			if (message == null) {
				throw new RuntimeException("AI 返回异常（缺少 message）: " + truncate(responseBody, 200));
			}
			String content = getNonNullString(message, "content");
			// 思考型模型可能只输出到 reasoning 字段，content 为空时兜底取推理文本
			if (content == null || content.isBlank()) {
				content = getNonNullString(message, "reasoning_content");
			}
			if (content == null || content.isBlank()) {
				content = getNonNullString(message, "reasoning");
			}
			if (content == null || content.isBlank()) {
				MinecraftAIMod.LOGGER.error("[Minecraft AI] AI 回复为空: {}", truncate(responseBody, 500));
				throw new RuntimeException("AI 返回为空（模型可能被限流或不支持当前参数，请重试或换个模型）");
			}
			return content;
		}
		return getNonNullString(json.getAsJsonObject("message"), "content");
	}

	private static String getNonNullString(JsonObject obj, String key) {
		if (obj == null || obj.get(key) == null || obj.get(key).isJsonNull()) {
			return "";
		}
		try {
			return obj.get(key).getAsString();
		} catch (Exception e) {
			return "";
		}
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
