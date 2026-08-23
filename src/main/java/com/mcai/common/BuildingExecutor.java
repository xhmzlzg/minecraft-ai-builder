package com.mcai.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mcai.MinecraftAIMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * 在服务端执行方块放置，并记录旧状态用于撤销。
 * 仅在单人游戏的内置服务器中工作。
 */
public class BuildingExecutor {
	private static final List<UndoData> undoStack = new ArrayList<>();

	private record UndoData(ResourceKey<Level> worldKey, BlockPos origin, List<BlockPos> positions, List<BlockState> oldStates) {}

	/**
	 * 在服务端线程中执行建造，返回完成时可获得放置方块数量的 Future。
	 */
	public static CompletableFuture<Integer> execute(MinecraftServer server, ServerLevel world, BlockPos origin, BuildingPlan plan) {
		return execute(server, world, origin, plan, null);
	}

	/**
	 * 在服务端线程中执行建造。bound 非 null 时建筑的外接长方体不允许超出
	 * origin..bound 围成的立方体（超出部分直接跳过，不出格）。
	 */
	public static CompletableFuture<Integer> execute(MinecraftServer server, ServerLevel world, BlockPos origin, BuildingPlan plan, BlockPos bound) {
		if (server == null || world == null) {
			throw new IllegalStateException("仅支持在单人游戏中使用");
		}
		return server.submit(() -> doExecute(world, origin, plan, bound));
	}

	/**
	 * 在服务端线程中执行撤销，返回是否撤销成功。
	 * 从上至下逆序恢复方块，并彻底清除任何产生的掉落物实体。
	 */
	public static CompletableFuture<Boolean> undo(MinecraftServer server) {
		if (server == null) {
			throw new IllegalStateException("仅支持在单人游戏中使用");
		}
		return server.submit(() -> {
			if (undoStack.isEmpty()) {
				return false;
			}
			UndoData data = undoStack.remove(undoStack.size() - 1);
			ServerLevel world = server.getLevel(data.worldKey);
			if (world == null) {
				return false;
			}
			int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
			int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
			int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

			// 从上往下逆序撤销（防止支撑方块先被移除导致上方门/床/灯笼掉落）
			for (int i = data.positions.size() - 1; i >= 0; i--) {
				BlockPos pos = data.positions.get(i);
				minX = Math.min(minX, pos.getX());
				maxX = Math.max(maxX, pos.getX());
				minY = Math.min(minY, pos.getY());
				maxY = Math.max(maxY, pos.getY());
				minZ = Math.min(minZ, pos.getZ());
				maxZ = Math.max(maxZ, pos.getZ());

				// 静默恢复原状态（无掉落）
				world.setBlock(pos, data.oldStates.get(i), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}

			// 清除该区域内可能产生的所有掉落物
			if (minX <= maxX) {
				net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
						minX - 2, minY - 2, minZ - 2, maxX + 3, maxY + 3, maxZ + 3);
				world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)
						.forEach(net.minecraft.world.entity.Entity::discard);
			}

			MinecraftAIMod.LOGGER.info("[Minecraft AI] 已撤销上次建造 ({} 个方块，无掉落物)", data.positions.size());
			return true;
		});
	}

	public static boolean canUndo() {
		return !undoStack.isEmpty();
	}

	private static int doExecute(ServerLevel world, BlockPos origin, BuildingPlan plan, BlockPos bound) {
		List<BlockPos> positions = new ArrayList<>();
		List<BlockState> oldStates = new ArrayList<>();
		int placed = 0;
		for (BuildingPlan.Entry e : plan.entries) {
			BlockPos pos = origin.offset(e.x(), e.y(), e.z());
			if (!world.isInWorldBounds(pos)) {
				continue;
			}
			// 空间约束：建筑外接长方体不得超出 origin..bound 立方体
			if (bound != null) {
				if (pos.getX() < origin.getX() || pos.getY() < origin.getY() || pos.getZ() < origin.getZ()
						|| pos.getX() > bound.getX() || pos.getY() > bound.getY() || pos.getZ() > bound.getZ()) {
					continue;
				}
			}
			boolean upperDoor = e.blockId().endsWith("_upper");
			boolean bedFoot = e.blockId().endsWith("_foot");
			boolean bedHead = e.blockId().endsWith("_head");
			String baseId = e.blockId();
			if (upperDoor) {
				baseId = baseId.substring(0, baseId.length() - "_upper".length());
			} else if (bedFoot) {
				baseId = baseId.substring(0, baseId.length() - "_foot".length());
			} else if (bedHead) {
				baseId = baseId.substring(0, baseId.length() - "_head".length());
			}
			Block block = resolveBlock(baseId);
			if (block == Blocks.AIR) {
				continue;
			}
			BlockState state = block.defaultBlockState();
			// 门是两格方块：上半格必须显式标记为 UPPER，
			// 否则默认 LOWER 状态因下方不是实心而掉落。
			if (block instanceof DoorBlock) {
				state = state.setValue(DoorBlock.HALF, upperDoor ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
			}
			// 床也是两格方块（尾+头）：只放一格会变成半张床。
			// foot 在 R 格、head 朝 +x（East）方向一格。
			if (block instanceof BedBlock) {
				state = state.setValue(BedBlock.PART, bedHead ? BedPart.HEAD : BedPart.FOOT);
				state = state.setValue(BedBlock.FACING, Direction.EAST);
			}
			oldStates.add(world.getBlockState(pos));
			// 床是两格方块：先放的一半（foot）会被 setBlock 的形状检查立刻判定"搭档格
			// （+x 的 head）不是床"而返回 AIR 掉落，另一半随之一起掉。
			// 用 recursion=0 跳过对自身的形状检查（保留邻居通知），等另一半放好即可。
			if (block instanceof BedBlock) {
				world.setBlock(pos, state, 3, 0);
			} else {
				world.setBlock(pos, state, 3);
			}
			positions.add(pos);
			placed++;
		}

		// 第二阶段：更新所有相连方块（如玻璃板、栏杆、栅栏、台阶、墙等）的形貌連接
		for (BlockPos pos : positions) {
			BlockState current = world.getBlockState(pos);
			BlockState updated = Block.updateFromNeighbourShapes(current, world, pos);
			if (updated != current) {
				world.setBlock(pos, updated, 3);
			}
		}

		if (!positions.isEmpty()) {
			undoStack.add(new UndoData(world.dimension(), origin, positions, oldStates));
		}
		MinecraftAIMod.LOGGER.info("[Minecraft AI] 建造完成: {} ({} 个方块)", plan.name, placed);
		return placed;
	}

	public static Block resolveBlock(String id) {
		try {
			Identifier identifier = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
			if (identifier == null) {
				return Blocks.AIR;
			}
			return BuiltInRegistries.BLOCK.getOptional(identifier).orElse(Blocks.AIR);
		} catch (Exception e) {
			return Blocks.AIR;
		}
	}
}