package com.mcai.client.ai;

import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.mcai.MinecraftAIMod;
import com.mcai.common.AiConfig;

/**
 * AI 后端客户端：支持本地 Ollama 与任意 OpenAI 兼容 API（OpenRouter / DeepSeek 官方等）。
 * 在后台线程执行，不阻塞游戏主线程。
 *
 * v1.1.1 起改为【流式 stream:true + 空闲超时】：
 * - HttpRequest.timeout() 只保护到响应头，响应体阶段靠「多久没收到数据」判定卡死；
 * - 同时捕获思考流（reasoning / thinking），供界面折叠展示；
 * - 正文仍只用 content / message.content，绝不把半截思考当成品规格。
 */
public class AiClient {

	/** 一次 AI 回复：正文 JSON + 思考过程（可为空）。 */
	public static final class AiReply {
		public final String content;
		public final String thinking;

		public AiReply(String content, String thinking) {
			this.content = content == null ? "" : content;
			this.thinking = thinking == null ? "" : thinking;
		}
	}

	/** 流式进度回调：思考增量会实时推给界面。 */
	public interface StreamListener {
		void onThinking(String delta);

		void onContent(String delta);
	}

	/**
	 * 唯一契约：AI 输出【自由形体规格 JSON】（ops 绘制指令），由游戏内程序逐格照做。
	 * 形状完全由 AI 的 ops 决定，程序不提供任何建筑模板。
	 */
	private static final String SYSTEM_PROMPT_V2 = """
			你是 Minecraft 建造设计师。玩家描述想要的东西，**你亲手把它一格一格画出来**：
			输出【自由形体规格 JSON】（ops 绘制指令），由游戏内的程序照做放方块。
			程序不提供任何建筑模板 —— 形状完全由你的 ops 决定。

			只输出 JSON，禁止任何解释文字，禁止代码块。

			★★ 唯一契约：kind="freeform" ★★
			房子、别墅、四合院、城堡、塔、庙、教堂、商店、商场、学校、写字楼、车库、
			桥、船、飞机、汽车、雕像、树、家具……**一切**都用 kind="freeform"，
			用 ops 亲手画出它的外形与内部。
			★ 本模组曾经有过 kind="building"（archetype 参数化建筑模板），现已**全部删除**：
			  写它程序一格方块都盖不出来。所以**不要写 kind="building"、不要写 archetype** ——
			  哪怕玩家点名要"标准住宅楼 / 公寓楼"，也要你自己用 ops 把户型、楼梯、电梯画出来。

			======= 契约：自由形体（kind="freeform"）=======
			{
			  "spec_version": 3,
			  "kind": "freeform",
			  "name": "作品名",
			  "size": [宽X, 高Y, 进深Z],
			  "palette": {"h":"dark_oak_planks","d":"spruce_planks","m":"oak_log","s":"white_wool"},
			  "mirror": "x",
			  "ops": [ ... ],
			  "notes": "玩家提到的其它细节"
			}
			- size：可选。给了就按它定外框；不给就按 ops 的实际范围自动算。
			- palette：单字符 → 方块 id。不写的话可用内置字符表（见下）。
			- mirror：可选 none(默认) / x / z / xz。★强烈建议用★ ——
			  开了以后 ops 只需要画"一半"（x 小于 宽/2 的那半），程序自动镜像出另一半，
			  输出量减半、左右绝对对称，程序还会把朝向/铰链/楼梯形状一起翻好。
			- ops：按顺序执行，后面的覆盖前面的。坐标从 0 开始，x = 东西、y = 上下、z = 南北，y=0 是底部（贴地）。

			ops 支持 5 种：
			1. {"op":"layer","y":0,"rows":["..hhh..",".hhhhh."]}
			   单层字符画：rows[z] 的第 x 个字符决定该格方块。'.' 或空格 = 不放置。
			   ★这是画外形的主力，尽量用它画轮廓★
			2. {"op":"box","from":[x,y,z],"to":[x,y,z],"block":"stone_bricks","hollow":false}
			   长方体（两个对角点）；hollow=true 只做六面外壳。
			   ★大面积平面 / 墙体 / 楼板都用它，比一层层写 layer 省事得多★
			3. {"op":"cyl","x":5,"z":5,"r":3,"y0":0,"y1":6,"block":"oak_log","hollow":false}
			   竖直圆柱（柱子、烟囱、树干）；可用 rx / rz 分别给两个方向的半径。
			4. {"op":"sphere","x":5,"y":10,"z":5,"r":4,"block":"white_wool","hollow":true}
			   球/椭球（气球、圆顶、头）；可用 rx / ry / rz。
			5. {"op":"clear","from":[x,y,z],"to":[x,y,z]}   掏空一个长方体（船舱、门洞、窗户、室内空间）。

			ops 的可选 props（blockstate 属性）—— 用来画楼梯朝向、半砖、栅栏门、挂灯、横梁原木：
			{"op":"box","from":[1,1,5],"to":[1,1,5],"block":"dark_oak_stairs",
			 "props":{"facing":"north","half":"bottom"}}
			常用属性：facing(north/south/east/west) · half(top/bottom) · type(top/bottom/double，半砖)
			          axis(x/y/z，原木/柱子/锁链) · hinge(left/right，门) · open(true/false，栅栏门/活板门)
			          shape(straight/inner_left/inner_right/outer_left/outer_right，楼梯) · hanging(true/false，灯笼)
			· 不写 props 时程序会自动补：栏杆/玻璃板/铁栏杆自动连成一片；灯笼上方是实心块就自动变成吊灯。
			· mirror 时程序会自动翻转 facing/hinge/shape/rotation，你不用管镜像后的朝向。
			· block 直接写成原版带状态的形式（"oak_stairs[facing=east]"）或带 minecraft: 前缀，程序一样认；
			  但镜像时用 props 分开写更可靠。

			内置字符表（不写 palette 时可用）：
			# 石砖  O 橡木板  o 橡木原木  T 深色橡木板  t 深色橡木原木  P 云杉木板  p 云杉原木
			S 石头  I 铁块  G 玻璃  W 白色羊毛  B 蓝色羊毛  R 红色羊毛  Y 黄色羊毛  E 灰色羊毛  K 黑色羊毛
			L 灯笼  X 海晶灯

			自由形体硬性规则：
			0. **输出要紧凑**（省 token = 更快出结果）：
			   - ops 总数尽量 ≤25（能一个 box 解决的绝不用十个）
			   - **优先 box / cyl / sphere**，layer 只用来画轮廓剪影
			   - 尺寸尽量 ≤32；能 mirror 就 mirror，只画一半
			   - palette 3~6 种材质
			1. 尺寸别超过 48 格（长/宽/高任一边）；正常作品 8~30 格就很漂亮。
			2. 从下往上画：y=0 先画船底/地基/底盘/底座，再往上堆甲板、桅杆、帆、楼层。
			3. 外形必须有辨识度 —— 只画一个方盒子是失败的：
			   - 船：尖船头（前几层用 '.' 收窄成尖角）+ 弧形船底 + 平整甲板 + 桅杆 + 船帆（白羊毛）+ 船尾楼
			   - 飞机：细长机身 + 左右机翼 + 尾翼 + 驾驶舱玻璃
			   - 汽车：底盘 + 车身 + 车顶 + 车轮（黑羊毛）
			   - 雕像：头 / 身体 / 四肢分段，姿态要看得出来
			   - 桥：桥面 + 两侧栏杆 + 桥墩
			   - 房子：地基 → 四面墙 → 楼板 → 上层墙 → 屋顶 → 掏门窗 → 室内楼梯与家具（见示例 2）
			4. 不要给非建筑物体加"房间、门、窗、屋顶"那套建筑逻辑；它是物体，不是房子。
			5. mirror 的写法要点：开了 mirror="x" 后，layer 的每一行**只写左半边**（长度 ≈ 宽/2），
			   程序会把右半边镜像出来；各行长度可以不一样（短行 = 该处收窄，船头船尾就是这么收尖的）。
			   rows 的行数 = 进深（第 0 行在 z=0），rows 越多船越长。
			   用 mirror 时最好同时写 size，其中 宽 = 每行字符数 × 2 - 1（例：每行 8 个字符 → 宽 15）；
			   不写 size 程序也会按这个规则推断，但显式写更保险。
			6. 细长部件（桅杆、旗杆、烟囱、柱子、天线）直接用 box 从 (x,y0,z) 到 (x,y1,z) 画一条线，
			   别用 cyl 去凑；大面积的平面（帆、甲板、机翼、楼板）也用 box。
			7. 控制输出长度：一艘船 10~15 个 op 够了，一栋 2 层小屋 20~30 个 op 够了。
			   能用一个 box 解决的，绝不用十个 box。
			8. palette 里出现的每个字符都必须在 palette 里定义（区分大小写），否则那些格子会被跳过；
			   坐标从 0 开始、且必须小于 size，越界的格子会被丢弃。
			   这两条是最容易让"整件作品变成 0 个方块"的原因。

			示例 1（一艘 15×11×7 的帆船。注意：mirror=x 时每行只写左半边 8 个字符 = x 0~7，
			rows[0] 是船头方向 z=0，rows 有几行就是进深几格）：
			{"spec_version":3,"kind":"freeform","name":"帆船","size":[15,11,7],"mirror":"x",
			 "palette":{"h":"dark_oak_planks","d":"spruce_planks","m":"oak_log","s":"white_wool"},
			 "ops":[
			  {"op":"layer","y":0,"rows":["......hh",".....hhh","....hhhh","...hhhhh","....hhhh",".....hhh","......hh"]},
			  {"op":"layer","y":1,"rows":[".....hhh","....hhhh","...hhhhh","..hhhhhh","...hhhhh","....hhhh",".....hhh"]},
			  {"op":"layer","y":2,"rows":["......dd",".....ddd","....dddd","...ddddd","....dddd",".....ddd","......dd"]},
			  {"op":"box","from":[4,4,1],"to":[7,7,4],"block":"s"},
			  {"op":"box","from":[7,2,3],"to":[7,9,3],"block":"m"}]}
			这艘船的样子：y=0/y=1 是两头收尖的船体，y=2 是甲板，y=4~7 是一整片方帆（box 一次搞定），
			y=2~9 的桅杆用 box 从下到上一条直线（细长部件用 box 最不容易写错，别用 cyl 去凑）。

			示例 2（一栋 9×9×7 的两层木屋 —— "用 freeform 画房子"的标准套路：
			地基 → 四壁 → 楼板 → 上层四壁 → 屋顶 → 掏门窗 → 室内楼梯）：
			{"spec_version":3,"kind":"freeform","name":"两层木屋","size":[9,9,7],
			 "palette":{"w":"oak_planks","s":"cobblestone","g":"glass_pane","d":"dark_oak_door","r":"dark_oak_stairs"},
			 "ops":[
			  {"op":"box","from":[0,0,0],"to":[8,0,6],"block":"s"},
			  {"op":"box","from":[0,1,0],"to":[8,3,0],"block":"w"},
			  {"op":"box","from":[0,1,6],"to":[8,3,6],"block":"w"},
			  {"op":"box","from":[0,1,1],"to":[0,3,5],"block":"w"},
			  {"op":"box","from":[8,1,1],"to":[8,3,5],"block":"w"},
			  {"op":"box","from":[0,4,0],"to":[8,4,6],"block":"w"},
			  {"op":"box","from":[0,5,0],"to":[8,7,0],"block":"w"},
			  {"op":"box","from":[0,5,6],"to":[8,7,6],"block":"w"},
			  {"op":"box","from":[0,5,1],"to":[0,7,5],"block":"w"},
			  {"op":"box","from":[8,5,1],"to":[8,7,5],"block":"w"},
			  {"op":"box","from":[0,8,0],"to":[8,8,6],"block":"s"},
			  {"op":"box","from":[4,1,0],"to":[4,2,0],"block":"d","props":{"facing":"south"}},
			  {"op":"box","from":[2,2,0],"to":[3,2,0],"block":"g"},
			  {"op":"box","from":[6,2,0],"to":[7,2,0],"block":"g"},
			  {"op":"box","from":[1,1,3],"to":[1,1,5],"block":"w"},
			  {"op":"box","from":[1,2,3],"to":[1,2,4],"block":"w"},
			  {"op":"box","from":[1,3,3],"to":[1,3,3],"block":"w"},
			  {"op":"box","from":[1,1,6],"to":[1,1,6],"block":"r","props":{"facing":"north","half":"bottom"}},
			  {"op":"box","from":[1,2,5],"to":[1,2,5],"block":"r","props":{"facing":"north","half":"bottom"}},
			  {"op":"box","from":[1,3,4],"to":[1,3,4],"block":"r","props":{"facing":"north","half":"bottom"}},
			  {"op":"box","from":[1,4,3],"to":[1,4,3],"block":"r","props":{"facing":"north","half":"bottom"}}]}
			这栋房子的要点：四面墙是 4 个 box，**不要**用 hollow=true 的 box 画墙
			（hollow 会把地板和天花板一起填满）；门和窗直接用 box 盖在墙上（后面的 op 覆盖前面的）；
			楼梯每前进一格抬高 1 格，下方必须有实心方块垫着（那 3 个 w 的 box 就是垫脚）。

			======= 画房子（住宅 / 写字楼 / 别墅 / 商场…都适用）的套路 =======
			见上面的示例 2：地基 → 四面墙（4 个 box）→ 楼板 → 上层墙 → 屋顶 → 掏门窗 → 室内楼梯。
			要多层、要户型、要电梯井，全都由你自己用 ops 画：
			· 楼板：每层一个 box 铺满（y = 层号 × 层高）
			· 隔墙：每个房间 4 个 box（或 1 个 box 的墙面 + 用 clear 掏门洞）
			· 楼梯：*_stairs 每前进一格抬高 1 格，下方用 box 垫实心；楼梯井上下贯通（用 clear 掏穿）
			· 电梯井：四面围一圈墙 + 井道上下用 clear 掏穿 + 井里放 scaffolding 当轿厢
			· 门窗：用 box 盖在墙上（后面的 op 覆盖前面的），门用 *_door + props{"facing":...}
			★ 别指望程序给你补任何东西：房间、家具、门窗、楼梯、照明全在你的 ops 里。

			======= 调整模式 =======
			你会看到自己上一次的规格 JSON 和玩家的修改意见，请输出【完整的新规格 JSON】：
			1. 只改需要改的字段，其余原样保留；结构必须与上一次一致（字段不能丢）。
			2. 如果玩家说"这不是我要的 / 我要的是一艘船 / 你做成房子了"，
			   就按新外形重画 ops（改 ops / size / palette / mirror）。
			3. 玩家说"加层/改户型/加房间/换材质/封阳台"就改对应的 ops
			   （加层 = 加一组 box + 楼板；改户型 = 改隔墙的 box；换材质 = 改 palette 或 block）。
			4. 严禁原样返回上一次的规格：如果实在无法表达，就把要求写进 notes，并至少调整一个相关字段。
			""";

	/** 阶段一：只出【设计要点】，不画方块。刻意压短输出，让思考更快结束。 */
	private static final String DESIGN_BRIEF_SYSTEM = """
			你是 Minecraft 建筑设计师。根据玩家描述，先给出【设计要点 JSON】，**不要画方块、不要输出 ops**。
			只输出 JSON，禁止任何解释文字，禁止代码块。

			{
			  "name": "作品名",
			  "size": [宽X, 高Y, 进深Z],
			  "mirror": "none 或 x 或 z 或 xz",
			  "palette": {"h": "dark_oak_planks", "s": "white_wool"},
			  "outline": "一句话外形轮廓（辨识度要高）",
			  "parts": ["y=0~2:船底收尖用深色木板", "y=3:甲板", "y=4~7:方帆白羊毛+桅杆"],
			  "avoid": "不要做成二层小楼/不要屋顶平台"
			}

			硬性要求：
			1. size 尽量小而有辨识度：任一边 ≤32，正常 8~24 最好。
			2. 能对称就写 mirror（x/z/xz），这样 ops 只画一半。
			3. palette 只用 3~6 种材质，字符用单字母。
			4. parts 按从下到上列 5~10 条，每条说清形状与材质，**不要逐格坐标**。
			5. 外形必须像目标物体（船要尖船头，飞机要有机翼），不要方盒子。
			6. 这一步**不要**输出 freeform/ops，只输出上面这份要点。
			""";

	/**
	 * 阶段一：设计要点（短 JSON）。
	 */
	public static CompletableFuture<AiReply> askDesignBrief(String description, String posText, AiConfig cfg,
			StreamListener listener) {
		return CompletableFuture.supplyAsync(() -> {
			CANCELLED.set(false);
			String endpoint = cfg.chatEndpoint();
			JsonObject body = new JsonObject();
			body.addProperty("model", cfg.modelName());
			body.addProperty("stream", true);
			boolean useThinking = cfg.thinkingEnabled;
			JsonObject thinkingObj = new JsonObject();
			thinkingObj.addProperty("type", "enabled");

			JsonArray messages = new JsonArray();
			JsonObject system = new JsonObject();
			system.addProperty("role", "system");
			system.addProperty("content", DESIGN_BRIEF_SYSTEM);
			messages.add(system);
			JsonObject user = new JsonObject();
			user.addProperty("role", "user");
			user.addProperty("content", "在坐标 " + posText + " 建造：" + description
					+ "\n请只输出设计要点 JSON。");
			messages.add(user);
			body.add("messages", messages);

			final int maxAttempts = 3;
			final int idleTimeoutMs = Math.max(10, cfg.idleTimeoutSeconds) * 1000;
			final int briefTotalMs = Math.max(30, cfg.designBriefSeconds) * 1000;
			final int thinkingBudgetMs = Math.max(15, cfg.thinkingBudgetSeconds) * 1000;
			String lastReason = null;
			for (int attempt = 1; attempt <= maxAttempts; attempt++) {
				applyThinkingFlags(body, thinkingObj, useThinking);
				HttpRequest.Builder rb = HttpRequest.newBuilder()
						.uri(URI.create(endpoint))
						.timeout(Duration.ofSeconds(30))
						.header("Content-Type", "application/json")
						.header("Accept", "text/event-stream, application/json, application/x-ndjson")
						.header("User-Agent", "minecraft-ai-mod")
						.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
				if (!cfg.apiKey().isEmpty()) {
					rb.header("Authorization", "Bearer " + cfg.apiKey());
				}
				try {
					MinecraftAIMod.LOGGER.info("[Minecraft AI] 阶段1设计要点: model={} thinking={} (第 {} 次)",
							cfg.modelName(), useThinking, attempt);
					HttpResponse<InputStream> response = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
					if (response.statusCode() != 200) {
						String errBody = readAllQuietly(response.body());
						lastReason = describeError(response.statusCode(), errBody);
						if (attempt < maxAttempts && useThinking) {
							useThinking = false;
							continue;
						}
						throw new RuntimeException(lastReason);
					}
					return readStream(response.body(), cfg, listener, idleTimeoutMs, briefTotalMs, useThinking);
				} catch (ThinkingBudgetException e) {
					lastReason = e.getMessage();
					if (attempt < maxAttempts && useThinking) {
						useThinking = false;
						MinecraftAIMod.LOGGER.warn("[Minecraft AI] 设计要点思考超预算，关思考重试");
						continue;
					}
					throw new RuntimeException(lastReason, e);
				} catch (CancelledException e) {
					throw e;
				} catch (Exception e) {
					if (CANCELLED.get()) {
						throw new CancelledException("已取消本次生成");
					}
					lastReason = e.getMessage() == null ? e.toString() : e.getMessage();
					if (attempt < maxAttempts) {
						sleepQuietly(800L * attempt);
						continue;
					}
					throw new RuntimeException(lastReason, e);
				}
			}
			throw new RuntimeException(lastReason == null ? "设计要点生成失败" : lastReason);
		}, EXECUTOR);
	}

	/** 思考超预算（一直思考、正文为空）。 */
	private static final class ThinkingBudgetException extends RuntimeException {
		ThinkingBudgetException(String msg) {
			super(msg);
		}
	}

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "minecraft-ai-request");
		t.setDaemon(true);
		return t;
	});

	/** 当前进行中的流式请求的中断标记（取消用）。 */
	private static final AtomicReference<InputStream> ACTIVE_STREAM = new AtomicReference<>();
	private static final AtomicBoolean CANCELLED = new AtomicBoolean(false);

	public static void cancelActive() {
		CANCELLED.set(true);
		InputStream in = ACTIVE_STREAM.getAndSet(null);
		if (in != null) {
			try {
				in.close();
			} catch (IOException ignored) {
			}
		}
	}

	/** 用户点过取消 */
	public static final class CancelledException extends RuntimeException {
		CancelledException(String msg) {
			super(msg);
		}
	}

	public static CompletableFuture<AiReply> askPlan(String description, String posText, AiConfig cfg) {
		return askPlan(description, posText, cfg, null, null, null, null);
	}

	public static CompletableFuture<AiReply> askPlan(String description, String posText, AiConfig cfg,
			String previousReply, String adjustment) {
		return askPlan(description, posText, cfg, previousReply, adjustment, null, null);
	}

	public static CompletableFuture<AiReply> askPlan(String description, String posText, AiConfig cfg,
			String previousReply, String adjustment, String repairHint) {
		return askPlan(description, posText, cfg, previousReply, adjustment, repairHint, null);
	}

	/**
	 * 带对话历史的流式请求。
	 * @param listener 可选：实时推送思考/正文增量
	 */
	public static CompletableFuture<AiReply> askPlan(String description, String posText, AiConfig cfg,
			String previousReply, String adjustment, String repairHint, StreamListener listener) {
		return CompletableFuture.supplyAsync(() -> {
			CANCELLED.set(false);
			String endpoint = cfg.chatEndpoint();

			JsonObject body = new JsonObject();
			body.addProperty("model", cfg.modelName());
			body.addProperty("stream", true);
			boolean useThinking = cfg.thinkingEnabled;
			JsonObject thinkingObj = new JsonObject();
			thinkingObj.addProperty("type", "enabled");

			JsonArray messages = new JsonArray();
			JsonObject system = new JsonObject();
			system.addProperty("role", "system");
			system.addProperty("content", SYSTEM_PROMPT_V2);
			messages.add(system);

			JsonObject user = new JsonObject();
			user.addProperty("role", "user");
			user.addProperty("content", "在坐标 " + posText + " 建造：" + description);
			messages.add(user);

			if (previousReply != null) {
				JsonObject assistant = new JsonObject();
				assistant.addProperty("role", "assistant");
				assistant.addProperty("content", previousReply);
				messages.add(assistant);
			}
			if (previousReply != null && adjustment != null) {
				JsonObject feedback = new JsonObject();
				feedback.addProperty("role", "user");
				feedback.addProperty("content", "调整意见：" + adjustment
						+ "\n请基于你上面的规格输出【完整的新规格 JSON】，只改需要调整的字段，其余原样保留，"
						+ "结构必须与上一次完全一致（字段不能丢）：保留 spec_version/kind/name/"
						+ "size/palette/mirror/ops。只输出 JSON，禁止任何其他文字，不要代码块。");
				messages.add(feedback);
			}
			if (repairHint != null) {
				JsonObject fix = new JsonObject();
				fix.addProperty("role", "user");
				fix.addProperty("content", "⚠ 你上面的输出不合格，被程序拒绝：\n" + repairHint
						+ "\n请重新输出【完整的新规格 JSON】。只输出 JSON，禁止任何其他文字，不要代码块。");
				messages.add(fix);
			}
			body.add("messages", messages);

			// 空闲超时最多重试 1 次（避免 4 次重试把等待放大到不可接受）；
			// thinking 被拒 / 5xx 仍可走原有重试，但总次数仍受 maxAttempts 约束。
			final int maxAttempts = 4;
			final int idleTimeoutMs = Math.max(10, cfg.idleTimeoutSeconds) * 1000;
			String lastReason = null;
			for (int attempt = 1; attempt <= maxAttempts; attempt++) {
				applyThinkingFlags(body, thinkingObj, useThinking);
				HttpRequest.Builder rb = HttpRequest.newBuilder()
						.uri(URI.create(endpoint))
						// 只保护到响应头；响应体阶段用空闲超时
						.timeout(Duration.ofSeconds(30))
						.header("Content-Type", "application/json")
						.header("Accept", "text/event-stream, application/json, application/x-ndjson")
						.header("User-Agent", "minecraft-ai-mod")
						.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
				if (!cfg.apiKey().isEmpty()) {
					rb.header("Authorization", "Bearer " + cfg.apiKey());
				}
				try {
					MinecraftAIMod.LOGGER.info("[Minecraft AI] 流式请求发出: model={} endpoint={} thinking={} (第 {} 次, 空闲超时={}s)",
							cfg.modelName(), endpoint, useThinking, attempt, cfg.idleTimeoutSeconds);
					HttpResponse<InputStream> response = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
					int code = response.statusCode();
					MinecraftAIMod.LOGGER.info("[Minecraft AI] 响应头返回: HTTP {}", code);
					if (code == 200) {
						return readStream(response.body(), cfg, listener, idleTimeoutMs,
								Math.max(60, cfg.maxGenerateSeconds) * 1000, useThinking);
					}
					// 非 200：读错误体
					String errBody = readAllQuietly(response.body());
					lastReason = describeError(code, errBody);
					MinecraftAIMod.LOGGER.error("[Minecraft AI] 请求失败: {}", lastReason);
					boolean retryable = code == 408 || code == 429 || code >= 500;
					// 开思考被拒 → 关思考重试
					if (attempt < maxAttempts && useThinking && (code == 400 || code == 422)) {
						useThinking = false;
						MinecraftAIMod.LOGGER.warn("[Minecraft AI] 服务端拒绝思考参数，关思考后重试");
						continue;
					}
					// 关思考的附加方言字段被拒 → 只省略 thinking，再试
					if (attempt < maxAttempts && !useThinking && (code == 400 || code == 422)) {
						body.remove("thinking");
						body.remove("enable_thinking");
						body.remove("chat_template_kwargs");
						MinecraftAIMod.LOGGER.warn("[Minecraft AI] 服务端拒绝思考禁用字段，改用最小请求体重试");
						continue;
					}
					if (attempt < maxAttempts && retryable) {
						long waitMs = 1200L * attempt;
						MinecraftAIMod.LOGGER.warn("[Minecraft AI] {} ms 后重试", waitMs);
						sleepQuietly(waitMs);
						continue;
					}
					throw new RuntimeException(lastReason);
				} catch (IdleTimeoutException e) {
					lastReason = "AI 响应空闲超时（" + cfg.idleTimeoutSeconds
							+ " 秒没有新数据）。后端可能卡住，已自动断开。";
					MinecraftAIMod.LOGGER.error("[Minecraft AI] {}", lastReason);
					// 空闲超时最多再试 1 次
					if (attempt < 2) {
						sleepQuietly(800L);
						continue;
					}
					throw new RuntimeException(lastReason, e);
				} catch (java.net.ConnectException e) {
					lastReason = "无法连接 AI 服务 " + endpoint + "（请检查 Ollama 是否启动 / 网络是否可用）";
					MinecraftAIMod.LOGGER.error("[Minecraft AI] 连接失败", e);
					if (attempt < maxAttempts) {
						sleepQuietly(1500L * attempt);
						continue;
					}
					throw new RuntimeException(lastReason, e);
				} catch (java.net.http.HttpTimeoutException e) {
					lastReason = "AI 响应头超时（连接或首包太慢）";
					MinecraftAIMod.LOGGER.error("[Minecraft AI] 响应头超时", e);
					if (attempt < maxAttempts) {
						sleepQuietly(1500L * attempt);
						continue;
					}
					throw new RuntimeException(lastReason, e);
				} catch (TruncatedReplyException te) {
					if (attempt < maxAttempts && useThinking) {
						useThinking = false;
						MinecraftAIMod.LOGGER.warn("[Minecraft AI] 回复被截断且正文为空，关掉思考模式后重试");
						continue;
					}
					throw new RuntimeException(te.getMessage());
				} catch (ThinkingBudgetException tbe) {
					if (attempt < maxAttempts && useThinking) {
						useThinking = false;
						MinecraftAIMod.LOGGER.warn("[Minecraft AI] {}", tbe.getMessage());
						continue;
					}
					throw new RuntimeException(tbe.getMessage());
				} catch (CancelledException ce) {
					throw ce;
				} catch (RuntimeException re) {
					throw re;
				} catch (Exception e) {
					if (CANCELLED.get()) {
						throw new CancelledException("已取消本次生成");
					}
					MinecraftAIMod.LOGGER.error("[Minecraft AI] 请求失败: {}", e.getMessage(), e);
					throw new RuntimeException("AI 请求失败: " + e.getMessage(), e);
				}
			}
			throw new RuntimeException(lastReason == null ? "AI 请求失败" : lastReason);
		}, EXECUTOR);
	}

	/** 空闲超时（响应体阶段太久没数据）。 */
	private static final class IdleTimeoutException extends IOException {
		IdleTimeoutException(String msg) {
			super(msg);
		}
	}

	/** 回复被输出长度截断、且正文为空时抛出。 */
	private static final class TruncatedReplyException extends RuntimeException {
		TruncatedReplyException(String msg) {
			super(msg);
		}
	}

	/**
	 * 边读流式响应边解析，带空闲看门狗 + 总时长上限。
	 * 支持 OpenAI SSE（data: {...} / data: [DONE]）与 Ollama NDJSON（整行 JSON）。
	 *
	 * 注意：思考流会持续刷新 lastActivity，空闲超时拦不住「一直思考、正文永远不来」。
	 * 所以另加 maxTotalMs 总时长硬顶（默认 5 分钟），到点强制断开。
	 */
	private static AiReply readStream(InputStream raw, AiConfig cfg, StreamListener listener, int idleTimeoutMs)
			throws Exception {
		return readStream(raw, cfg, listener, idleTimeoutMs,
				Math.max(60, cfg.maxGenerateSeconds) * 1000, true);
	}

	private static AiReply readStream(InputStream raw, AiConfig cfg, StreamListener listener, int idleTimeoutMs,
			int maxTotalMs, boolean thinkingRequested) throws Exception {
		final int thinkingBudgetMs = Math.max(15, cfg.thinkingBudgetSeconds) * 1000;
		final long startMs = System.currentTimeMillis();
		ACTIVE_STREAM.set(raw);
		StringBuilder content = new StringBuilder();
		StringBuilder thinking = new StringBuilder();
		String finishReason = null;
		AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
		AtomicBoolean done = new AtomicBoolean(false);
		AtomicReference<Exception> killError = new AtomicReference<>();

		Thread watchdog = new Thread(() -> {
			try {
				while (!done.get()) {
					Thread.sleep(400);
					if (CANCELLED.get()) {
						killError.set(new CancelledException("已取消本次生成"));
						closeQuietly(raw);
						break;
					}
					long now = System.currentTimeMillis();
					if (now - startMs > maxTotalMs) {
						killError.set(new IOException("生成总超时（" + (maxTotalMs / 1000)
								+ " 秒）。可简化提示词、降低尺寸，或换更快的模型后重试。"));
						closeQuietly(raw);
						break;
					}
					// 思考预算：只在【本次请求开启了思考】时才掐断。
					// 用户关了思考但模型仍吐 reasoning 时，应用总超时/空闲超时，而不是误判成思考超预算。
					if (thinkingRequested && content.length() == 0 && thinking.length() > 0
							&& now - startMs > thinkingBudgetMs) {
						killError.set(new ThinkingBudgetException(
								"思考超过预算（" + (thinkingBudgetMs / 1000)
										+ " 秒）仍没有给出正文，将关掉思考模式重试"));
						closeQuietly(raw);
						break;
					}
					if (now - lastActivity.get() > idleTimeoutMs) {
						killError.set(new IdleTimeoutException("idle " + idleTimeoutMs + "ms"));
						closeQuietly(raw);
						break;
					}
				}
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}, "minecraft-ai-idle-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				lastActivity.set(System.currentTimeMillis());
				if (line.isEmpty()) {
					continue;
				}
				String payload = line;
				if (payload.startsWith("data:")) {
					payload = payload.substring(5).trim();
				}
				if (payload.isEmpty() || "[DONE]".equals(payload)) {
					if ("[DONE]".equals(payload)) {
						break;
					}
					continue;
				}
				JsonObject obj;
				try {
					JsonElement el = JsonParser.parseString(payload);
					if (!el.isJsonObject()) {
						continue;
					}
					obj = el.getAsJsonObject();
				} catch (Exception parseEx) {
					continue;
				}
				consumeChunk(obj, content, thinking, listener);
				if (obj.has("choices")) {
					JsonArray choices = obj.getAsJsonArray("choices");
					if (choices != null && !choices.isEmpty()) {
						JsonObject c0 = choices.get(0).getAsJsonObject();
						JsonElement fr = c0.get("finish_reason");
						if (fr != null && fr.isJsonPrimitive() && !fr.isJsonNull()) {
							String s = fr.getAsString();
							if (s != null && !s.isEmpty() && !"null".equals(s)) {
								finishReason = s;
							}
						}
					}
				}
				if (obj.has("done") && obj.get("done").isJsonPrimitive()
						&& obj.get("done").getAsBoolean()) {
					break;
				}
			}
		} catch (Exception readEx) {
			// 看门狗关流时 readLine 会抛 IOException（消息里常带 "cancelled"），
			// 必须优先抛出真正的 killError（空闲/总超时/思考预算/用户取消），
			// 否则上层会把超时误判成「已取消」。
			if (killError.get() != null) {
				throw killError.get();
			}
			if (CANCELLED.get()) {
				throw new CancelledException("已取消本次生成");
			}
			throw readEx;
		} finally {
			done.set(true);
			ACTIVE_STREAM.compareAndSet(raw, null);
			closeQuietly(raw);
		}

		if (killError.get() != null) {
			throw killError.get();
		}

		String contentStr = content.toString();
		String thinkingStr = thinking.toString();
		boolean truncated = "length".equalsIgnoreCase(finishReason);
		if (truncated) {
			MinecraftAIMod.LOGGER.warn("[Minecraft AI] ⚠ AI 回复被输出长度截断（finish_reason=length）");
		}
		if (contentStr.isBlank()) {
			if (truncated) {
				// ★★ 绝不把 reasoning/thinking 当成品规格
				throw new TruncatedReplyException("AI 回复被输出长度截断，正文一个字都没有"
						+ "（输出预算被思考过程吃光了）。请重试；若反复如此，"
						+ "请在描述里要求画简单些，或在配置里关掉思考模式、换一个输出上限更大的模型。");
			}
			if (thinkingStr.isBlank()) {
				throw new RuntimeException("AI 返回为空（模型可能被限流或不支持当前参数，请重试或换个模型）");
			}
			// 非截断但正文空、只有思考：仍按不可用处理（铁律：宁可报错也不盖假楼）
			throw new RuntimeException("AI 只输出了思考过程，没有给出规格 JSON。请重试，或在配置里关掉思考模式。");
		}
		return new AiReply(contentStr, thinkingStr);
	}

	/** 解析单条流式 chunk，累加 content / thinking，并回调 listener。 */
	private static void consumeChunk(JsonObject obj, StringBuilder content, StringBuilder thinking,
			StreamListener listener) {
		// ---- OpenAI 风格 ----
		if (obj.has("choices")) {
			JsonArray choices = obj.getAsJsonArray("choices");
			if (choices == null || choices.isEmpty()) {
				return;
			}
			JsonObject c0 = choices.get(0).getAsJsonObject();
			JsonObject delta = c0.has("delta") && c0.get("delta").isJsonObject()
					? c0.get("delta").getAsJsonObject() : null;
			// 有的网关放在 message
			if (delta == null && c0.has("message") && c0.get("message").isJsonObject()) {
				delta = c0.get("message").getAsJsonObject();
			}
			if (delta == null) {
				return;
			}
			String thinkPiece = firstString(delta, "reasoning_content", "reasoning", "thinking");
			if (thinkPiece != null && !thinkPiece.isEmpty()) {
				thinking.append(thinkPiece);
				if (listener != null) {
					listener.onThinking(thinkPiece);
				}
			}
			String contentPiece = getNonNullString(delta, "content");
			if (contentPiece != null && !contentPiece.isEmpty()) {
				content.append(contentPiece);
				if (listener != null) {
					listener.onContent(contentPiece);
				}
			}
			return;
		}
		// ---- Ollama /api/chat NDJSON ----
		if (obj.has("message") && obj.get("message").isJsonObject()) {
			JsonObject msg = obj.getAsJsonObject("message");
			String thinkPiece = firstString(msg, "thinking", "reasoning_content", "reasoning");
			if (thinkPiece != null && !thinkPiece.isEmpty()) {
				thinking.append(thinkPiece);
				if (listener != null) {
					listener.onThinking(thinkPiece);
				}
			}
			String contentPiece = getNonNullString(msg, "content");
			if (contentPiece != null && !contentPiece.isEmpty()) {
				content.append(contentPiece);
				if (listener != null) {
					listener.onContent(contentPiece);
				}
			}
		}
	}

	private static String firstString(JsonObject obj, String... keys) {
		for (String k : keys) {
			String s = getNonNullString(obj, k);
			if (s != null && !s.isEmpty()) {
				return s;
			}
		}
		return null;
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

	private static void closeQuietly(InputStream in) {
		if (in == null) {
			return;
		}
		try {
			in.close();
		} catch (IOException ignored) {
		}
	}

	private static String readAllQuietly(InputStream in) {
		if (in == null) {
			return "";
		}
		try (InputStream i = in) {
			return new String(i.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
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
					.timeout(Duration.ofSeconds(10))
					.header("User-Agent", "minecraft-ai-mod")
					.GET();
			if (!cfg.apiKey().isEmpty()) {
				rb.header("Authorization", "Bearer " + cfg.apiKey());
			}
			try {
				HttpResponse<String> response = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
				int code = response.statusCode();
				if (code == 200) {
					return true;
				}
				MinecraftAIMod.LOGGER.warn("[Minecraft AI] 预检: {} → HTTP {}（{}）",
						cfg.availabilityEndpoint(), code, describeError(code, response.body()));
				return false;
			} catch (Exception e) {
				MinecraftAIMod.LOGGER.warn("[Minecraft AI] 预检失败: {}", e.getMessage());
				return false;
			}
		}, EXECUTOR);
	}

	/** 把 HTTP 状态码翻译成"人话"，方便在状态栏直接看懂 */
	private static String describeError(int code, String body) {
		String hint = switch (code) {
			case 400 -> "请求被拒绝(HTTP 400)：模型名或参数不被该后端接受";
			case 401 -> "鉴权失败(HTTP 401)：API Key 无效（请在设置里重新粘贴完整 key，注意别被截断）";
			case 402 -> "额度不足(HTTP 402)：请检查账户余额";
			case 403 -> "无权限(HTTP 403)：该 key 不能访问此模型";
			case 404 -> "接口或模型不存在(HTTP 404)：检查 Base URL 与模型名";
			case 408 -> "请求超时(HTTP 408)";
			case 429 -> "请求过于频繁或超出配额(HTTP 429)";
			default -> code >= 500
					? "AI 服务端错误(HTTP " + code + ")：通常是服务方故障，已自动重试"
					: "AI 服务返回错误(HTTP " + code + ")";
		};
		String excerpt = truncate(body == null ? "" : body.replaceAll("\\s+", " "), 160);
		return excerpt.isEmpty() ? hint : hint + " | " + excerpt;
	}

	/**
	 * 写入/清除思考参数。关思考时兼容多家方言：
	 * - OpenAI 风格：不带 thinking / thinking:{type:disabled}
	 * - Qwen / vLLM / 部分网关：enable_thinking=false、chat_template_kwargs.enable_thinking=false
	 * 某些后端会拒未知字段 —— 由调用方捕获 400 后去掉再重试。
	 */
	private static void applyThinkingFlags(JsonObject body, JsonObject thinkingObj, boolean useThinking) {
		body.remove("thinking");
		body.remove("enable_thinking");
		body.remove("chat_template_kwargs");
		if (useThinking) {
			body.add("thinking", thinkingObj);
			body.addProperty("enable_thinking", true);
		} else {
			JsonObject disabled = new JsonObject();
			disabled.addProperty("type", "disabled");
			body.add("thinking", disabled);
			body.addProperty("enable_thinking", false);
			JsonObject kwargs = new JsonObject();
			kwargs.addProperty("enable_thinking", false);
			body.add("chat_template_kwargs", kwargs);
		}
	}

	private static void sleepQuietly(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static String truncate(String s, int n) {
		if (s == null) {
			return "";
		}
		return s.length() <= n ? s : s.substring(0, n);
	}
}
