package com.mcai.client.ai;

import java.awt.FileDialog;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.mcai.MinecraftAIMod;

/**
 * 附图：选图 → 缩到 ≤1024 → JPEG base64 data URL。
 * 选图优先用 AWT FileDialog（Windows 原生），失败再 JFileChooser；
 * 两者都不可用时由界面退回「输入路径」。
 */
public final class ImageAttach {

	private ImageAttach() {
	}

	/**
	 * 选一张图：直接弹系统「打开文件」对话框。取消返回 null，失败抛异常。
	 * Windows 优先用 PowerShell + WinForms OpenFileDialog（最稳）；
	 * 失败再退回 AWT FileDialog / JFileChooser。
	 */
	public static File pickImageFile() throws Exception {
		// ---- 1. Windows 原生「打开」对话框（资源管理器）----
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) {
			File fromPs = pickViaWindowsDialog();
			if (fromPs != null) {
				return fromPs;
			}
			// 用户取消 → null；真正失败再往下走
			if (windowsDialogWasCancel) {
				return null;
			}
		}

		// ---- 2. AWT / Swing ----
		if (GraphicsEnvironment.isHeadless()) {
			throw new IllegalStateException("无法打开系统选图框（headless）");
		}
		final File[] picked = new File[1];
		final Exception[] err = new Exception[1];
		final boolean[] cancelled = new boolean[1];
		SwingUtilities.invokeAndWait(() -> {
			try {
				FileDialog fd = new FileDialog((java.awt.Frame) null, "选择建筑参考图", FileDialog.LOAD);
				fd.setMode(FileDialog.LOAD);
				fd.setVisible(true);
				String dir = fd.getDirectory();
				String name = fd.getFile();
				fd.dispose();
				if (dir == null || name == null) {
					cancelled[0] = true;
					return;
				}
				picked[0] = new File(dir, name);
			} catch (Throwable t1) {
				MinecraftAIMod.LOGGER.warn("[Minecraft AI] FileDialog 失败，改试 JFileChooser: {}", t1.toString());
				try {
					JFileChooser chooser = new JFileChooser();
					chooser.setDialogTitle("选择建筑参考图");
					chooser.setFileFilter(new FileNameExtensionFilter("图片 (jpg/png/webp/bmp)",
							"jpg", "jpeg", "png", "webp", "bmp"));
					int result = chooser.showOpenDialog(null);
					if (result != JFileChooser.APPROVE_OPTION) {
						cancelled[0] = true;
						return;
					}
					picked[0] = chooser.getSelectedFile();
				} catch (Throwable t2) {
					err[0] = t2 instanceof Exception ? (Exception) t2 : new Exception(t2);
				}
			}
		});
		if (err[0] != null) {
			throw err[0];
		}
		if (cancelled[0]) {
			return null;
		}
		return picked[0];
	}

	private static volatile boolean windowsDialogWasCancel = false;

	/**
	 * PowerShell 调 Windows 系统「打开文件」对话框（资源管理器）。
	 * 路径经 Base64 传出，避免中文被控制台编码弄坏。
	 * 对话框 TopMost，尽量不把游戏挤到后台。
	 */
	private static File pickViaWindowsDialog() throws Exception {
		windowsDialogWasCancel = false;
		String ps = """
				[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
				Add-Type -AssemblyName System.Windows.Forms
				Add-Type -AssemblyName System.Drawing
				$d = New-Object System.Windows.Forms.OpenFileDialog
				$d.Title = '选择建筑参考图'
				$d.Filter = '图片|*.jpg;*.jpeg;*.png;*.webp;*.bmp|所有文件|*.*'
				$d.ShowHelp = $false
				# 尽量盖在游戏窗口上方
				$d.AutoUpgradeEnabled = $true
				$frm = New-Object System.Windows.Forms.Form
				$frm.TopMost = $true
				$frm.StartPosition = 'CenterScreen'
				$frm.ShowInTaskbar = $false
				$r = $d.ShowDialog($frm)
				$frm.Dispose()
				if ($r -eq [System.Windows.Forms.DialogResult]::OK) {
				  $b64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($d.FileName))
				  Write-Output $b64
				}
				""";
		ProcessBuilder pb = new ProcessBuilder(
				"powershell.exe", "-NoProfile", "-STA", "-Command", ps);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		byte[] raw = p.getInputStream().readAllBytes();
		int code = p.waitFor();
		String out = new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();
		MinecraftAIMod.LOGGER.info("[Minecraft AI] 系统选图框 exit={} outLen={}", code, out.length());
		if (code != 0) {
			throw new IOException("打开系统选图框失败 exit=" + code + " " + truncate(out, 200));
		}
		if (out.isEmpty()) {
			windowsDialogWasCancel = true;
			return null;
		}
		// 取最后一行 Base64
		String[] lines = out.split("\\R");
		String b64 = lines[lines.length - 1].trim();
		String path;
		try {
			path = new String(Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ex) {
			// 非 Base64 时按纯文本当路径
			path = b64;
		}
		File f = new File(path.trim());
		if (!f.isFile()) {
			throw new IOException("系统返回的路径无效: " + path);
		}
		return f;
	}

	private static String truncate(String s, int n) {
		if (s == null) {
			return "";
		}
		return s.length() <= n ? s : s.substring(0, n);
	}

	/** 按路径加载（仅作最后兜底）。 */
	public static File fileFromPath(String path) {
		if (path == null || path.trim().isEmpty()) {
			return null;
		}
		File f = new File(path.trim().replace('"', ' ').trim());
		return f.isFile() ? f : null;
	}

	/**
	 * 从 Windows 系统剪贴板读图片（截图/微信/QQ/PNG/DIB 等）。
	 * 优先 PowerShell + WinForms（MC 里 AWT 常 Headless/锁失败），再 AWT。
	 * 永不抛异常；无图返回 null。
	 */
	public static BufferedImage imageFromClipboard() {
		// ---- 1. PowerShell：兼容 GetImage / PNG 字节 / DIB ----
		String ps = """
				[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
				Add-Type -AssemblyName System.Windows.Forms
				Add-Type -AssemblyName System.Drawing
				$p = Join-Path $env:TEMP ('mcai_clip_' + [guid]::NewGuid().ToString() + '.png')
				$ok = $false
				try {
				  $img = [System.Windows.Forms.Clipboard]::GetImage()
				  if ($null -ne $img) {
				    $img.Save($p, [System.Drawing.Imaging.ImageFormat]::Png)
				    $ok = $true
				  }
				} catch {}
				if (-not $ok) {
				  try {
				    $d = [System.Windows.Forms.Clipboard]::GetDataObject()
				    if ($null -ne $d) {
				      foreach ($fmt in @('PNG', 'image/png', 'image/x-png')) {
				        if ($d.GetDataPresent($fmt)) {
				          $o = $d.GetData($fmt)
				          $bytes = $null
				          if ($o -is [byte[]]) { $bytes = $o }
				          elseif ($o -is [System.IO.Stream]) {
				            $ms = New-Object System.IO.MemoryStream
				            $o.CopyTo($ms)
				            $bytes = $ms.ToArray()
				          }
				          if ($null -ne $bytes -and $bytes.Length -gt 16) {
				            [System.IO.File]::WriteAllBytes($p, $bytes)
				            $ok = $true
				            break
				          }
				        }
				      }
				    }
				  } catch {}
				}
				if ($ok -and (Test-Path $p)) {
				  $b64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($p))
				  Write-Output $b64
				  exit 0
				}
				exit 2
				""";
		try {
			ProcessBuilder pb = new ProcessBuilder(
					"powershell.exe", "-NoProfile", "-STA", "-Command", ps);
			pb.redirectErrorStream(true);
			Process proc = pb.start();
			byte[] raw = proc.getInputStream().readAllBytes();
			int code = proc.waitFor();
			String out = new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();
			MinecraftAIMod.LOGGER.info("[Minecraft AI] 剪贴板PS exit={} outLen={}", code, out.length());
			if (code == 0 && !out.isEmpty()) {
				String[] lines = out.split("\\R");
				String b64 = lines[lines.length - 1].trim();
				String path;
				try {
					path = new String(Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
				} catch (IllegalArgumentException ex) {
					path = b64;
				}
				File tmp = new File(path.trim());
				if (tmp.isFile()) {
					BufferedImage bi = ImageIO.read(tmp);
					tmp.delete();
					if (bi != null) {
						return bi;
					}
				}
			}
		} catch (Throwable e) {
			MinecraftAIMod.LOGGER.warn("[Minecraft AI] PowerShell 读剪贴板失败", e);
		}

		// ---- 2. AWT 兜底（失败也不抛）----
		try {
			java.awt.datatransfer.Clipboard clip =
					java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
			java.awt.datatransfer.Transferable t = clip.getContents(null);
			if (t == null) {
				return null;
			}
			if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
				Object img = t.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor);
				if (img instanceof java.awt.Image awtImg) {
					int w = awtImg.getWidth(null);
					int h = awtImg.getHeight(null);
					if (w > 0 && h > 0) {
						BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
						Graphics2D g = out.createGraphics();
						g.drawImage(awtImg, 0, 0, java.awt.Color.WHITE, null);
						g.dispose();
						return out;
					}
				}
			}
			for (java.awt.datatransfer.DataFlavor f : t.getTransferDataFlavors()) {
				String mime = f.getMimeType() == null ? "" : f.getMimeType();
				if (!mime.startsWith("image/")) {
					continue;
				}
				Object data = t.getTransferData(f);
				byte[] bytes = null;
				if (data instanceof byte[] b) {
					bytes = b;
				} else if (data instanceof java.io.InputStream in) {
					bytes = in.readAllBytes();
				}
				if (bytes != null && bytes.length > 8) {
					BufferedImage bi = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
					if (bi != null) {
						return bi;
					}
				}
			}
		} catch (Throwable e) {
			MinecraftAIMod.LOGGER.warn("[Minecraft AI] AWT 读剪贴板失败: {}", e.toString());
		}
		return null;
	}

	/**
	 * 把 AWT/Image 或 BufferedImage 压成 data:image/jpeg;base64,... 。
	 */
	public static String toDataUrl(java.awt.Image awtImg) throws IOException {
		if (awtImg == null) {
			throw new IOException("图片为空");
		}
		int w = awtImg.getWidth(null);
		int h = awtImg.getHeight(null);
		if (w <= 0 || h <= 0) {
			throw new IOException("无法读取图片尺寸");
		}
		BufferedImage src = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = src.createGraphics();
		g.drawImage(awtImg, 0, 0, java.awt.Color.WHITE, null);
		g.dispose();
		BufferedImage scaled = scaleToFit(src, 1024);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(scaled, "jpeg", bos);
		byte[] bytes = bos.toByteArray();
		if (bytes.length > 4 * 1024 * 1024) {
			throw new IOException("压缩后图片仍超过 4MB");
		}
		return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
	}

	/**
	 * 读文件并压成 data:image/jpeg;base64,... 。
	 * 超大图按最长边 1024 缩放；失败抛 IOException。
	 */
	public static String toDataUrl(File file) throws IOException {
		BufferedImage src = ImageIO.read(file);
		if (src == null) {
			throw new IOException("无法解码图片（格式不支持）: " + file.getName());
		}
		BufferedImage scaled = scaleToFit(src, 1024);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(toRgb(scaled), "jpeg", bos);
		byte[] bytes = bos.toByteArray();
		if (bytes.length > 4 * 1024 * 1024) {
			throw new IOException("压缩后图片仍超过 4MB，请换更小的图");
		}
		return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
	}

	private static BufferedImage scaleToFit(BufferedImage src, int maxSide) {
		int w = src.getWidth();
		int h = src.getHeight();
		if (w <= maxSide && h <= maxSide) {
			return src;
		}
		double scale = Math.min((double) maxSide / w, (double) maxSide / h);
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		Image tmp = src.getScaledInstance(nw, nh, Image.SCALE_SMOOTH);
		BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(tmp, 0, 0, null);
		g.dispose();
		return out;
	}

	private static BufferedImage toRgb(BufferedImage img) {
		if (img.getType() == BufferedImage.TYPE_INT_RGB) {
			return img;
		}
		BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(img, 0, 0, java.awt.Color.WHITE, null);
		g.dispose();
		return out;
	}

	public static byte[] readAll(File file) throws IOException {
		return Files.readAllBytes(file.toPath());
	}
}
