package com.mcai.client.gui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.mcai.MinecraftAIMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 跨版本屏幕切换兼容工具。
 * 兼容 Minecraft 26.1.x（Minecraft.setScreen）与 26.2+（Minecraft.gui.setScreen）。
 * 精确匹配 setScreen 接口，严格排除 disconnect/clearLevel 等断开连接方法。
 */
public final class ScreenCompat {
	private static MethodHandle setScreenDirectHandle;
	private static MethodHandle getGuiHandle;
	private static MethodHandle setScreenGuiHandle;
	private static boolean initialized = false;

	private ScreenCompat() {}

	private static synchronized void init(Minecraft client) {
		if (initialized) {
			return;
		}
		initialized = true;
		MethodHandles.Lookup lookup = MethodHandles.lookup();

		// 1. 尝试直接在 Minecraft.class 寻找 setScreen (26.1.x)
		Method directMethod = findSetScreenMethod(Minecraft.class);
		if (directMethod != null) {
			try {
				setScreenDirectHandle = lookup.unreflect(directMethod);
				MinecraftAIMod.LOGGER.info("[ScreenCompat] 成功绑定 26.1.x 界面接口: Minecraft.{}", directMethod.getName());
				return;
			} catch (Exception e) {
				MinecraftAIMod.LOGGER.warn("[ScreenCompat] 绑定 Minecraft.setScreen 失败: {}", e.getMessage());
			}
		}

		// 2. 尝试在 Minecraft.gui 寻找 setScreen (26.2+)
		try {
			Object guiObj = null;
			Field guiField = null;
			for (Field f : Minecraft.class.getFields()) {
				if (f.getName().equals("gui") || f.getType().getName().endsWith(".Gui") || f.getType().getSimpleName().equals("Gui")) {
					guiField = f;
					break;
				}
			}
			if (guiField == null) {
				for (Field f : Minecraft.class.getDeclaredFields()) {
					if (f.getName().equals("gui") || f.getType().getName().endsWith(".Gui") || f.getType().getSimpleName().equals("Gui")) {
						f.setAccessible(true);
						guiField = f;
						break;
					}
				}
			}

			if (guiField != null) {
				getGuiHandle = lookup.unreflectGetter(guiField);
				guiObj = guiField.get(client);
			}

			if (guiObj != null) {
				Method guiMethod = findSetScreenMethod(guiObj.getClass());
				if (guiMethod != null) {
					setScreenGuiHandle = lookup.unreflect(guiMethod);
					MinecraftAIMod.LOGGER.info("[ScreenCompat] 成功绑定 26.2+ 界面接口: gui.{}", guiMethod.getName());
					return;
				}
			}
		} catch (Throwable t) {
			MinecraftAIMod.LOGGER.error("[ScreenCompat] 初始化 26.2 gui 反射失败", t);
		}
	}

	private static Method findSetScreenMethod(Class<?> clazz) {
		// 1. 优先精准匹配方法名
		for (Method m : clazz.getMethods()) {
			if (m.getParameterCount() == 1 && Screen.class.isAssignableFrom(m.getParameterTypes()[0]) && m.getReturnType() == void.class) {
				String name = m.getName();
				if (name.equalsIgnoreCase("disconnect") || name.equalsIgnoreCase("clearLevel") || name.equalsIgnoreCase("stop")) {
					continue;
				}
				if (name.equals("setScreen") || name.equals("method_1507") || name.equals("openScreen")) {
					return m;
				}
			}
		}
		// 2. 备用安全模糊匹配（严格排除危险退出方法）
		for (Method m : clazz.getMethods()) {
			if (m.getParameterCount() == 1 && Screen.class.isAssignableFrom(m.getParameterTypes()[0]) && m.getReturnType() == void.class) {
				String name = m.getName().toLowerCase();
				if (name.contains("disconnect") || name.contains("clear") || name.contains("stop")
						|| name.contains("save") || name.contains("crash") || name.contains("exit")
						|| name.contains("close") || name.contains("leave")) {
					continue;
				}
				if (name.contains("screen") || name.startsWith("set") || name.startsWith("open")) {
					return m;
				}
			}
		}
		return null;
	}

	public static void setScreen(Minecraft client, Screen screen) {
		if (client == null) {
			return;
		}
		if (!initialized) {
			init(client);
		}

		if (setScreenDirectHandle != null) {
			try {
				setScreenDirectHandle.invoke(client, screen);
				return;
			} catch (Throwable t) {
				MinecraftAIMod.LOGGER.error("[ScreenCompat] 调用 Minecraft.setScreen 失败", t);
			}
		}

		if (getGuiHandle != null && setScreenGuiHandle != null) {
			try {
				Object guiObj = getGuiHandle.invoke(client);
				if (guiObj != null) {
					setScreenGuiHandle.invoke(guiObj, screen);
					return;
				}
			} catch (Throwable t) {
				MinecraftAIMod.LOGGER.error("[ScreenCompat] 调用 gui.setScreen 失败", t);
			}
		}

		// 兜底直接调用（26.1.2 编译时可用）
		try {
			client.setScreen(screen);
		} catch (Throwable t) {
			MinecraftAIMod.LOGGER.error("[ScreenCompat] 兜底打开界面失败", t);
		}
	}
}
