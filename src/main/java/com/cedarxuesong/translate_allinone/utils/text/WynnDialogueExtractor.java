package com.cedarxuesong.translate_allinone.utils.text;

import com.cedarxuesong.translate_allinone.gui.overlay.DialogueOverlayRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WynnCraft NPC 对话包提取工具
 * 参考 doc/reference_mods/Wynn-tl/wynn-analysis-zh.md 实现
 */
public class WynnDialogueExtractor {
    private static final Logger LOGGER = LogManager.getLogger("WynnDialogueExtractor");

    private static String currentNpcName = "";
    private static final StringBuilder currentBodyBuilder = new StringBuilder();
    private static String lastStableText = "";
    
    // 用于缓存已见的选项行，解决滚动时的拼接问题
    private static final List<String> choiceHistory = new ArrayList<>();
    // 记录上一帧的原始选项文本，用于判断选项列表是否发生了切换
    private static String lastRawChoices = "";
    
    // 防抖逻辑相关
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static long lastChangeTime = 0;
    private static final long DEBOUNCE_MS = 300; // 300ms 无变化则认为输入停止
    private static final long STABLE_STORM_MS = 800; // 强制稳定时间
    private static final AtomicBoolean isScheduled = new AtomicBoolean(false);

    public static String extract(Text text) {
        if (text == null) return null;

        StringBuilder frameName = new StringBuilder();
        StringBuilder frameBody = new StringBuilder();
        // 使用 TreeMap 保证选项按 index 排序
        java.util.TreeMap<Integer, StringBuilder> choiceMap = new java.util.TreeMap<>();
        AtomicBoolean hasDialogue = new AtomicBoolean(false);

        // 使用 visit 深度遍历，确保不会丢失嵌套样式中的文本
        text.visit((style, part) -> {
            Identifier font = getFont(style);
            if (font != null) {
                String path = font.getPath();
                if (path.startsWith("hud/dialogue/text/wynncraft/body_")) {
                    hasDialogue.set(true);
                    String cleaned = cleanPua(part);
                    if (!cleaned.isEmpty()) {
                        frameBody.append(cleaned).append(" ");
                    }
                } else if (path.equals("hud/dialogue/text/nameplate")) {
                    hasDialogue.set(true);
                    String cleaned = cleanPua(part);
                    if (!cleaned.isEmpty()) {
                        frameName.append(cleaned);
                    }
                } else if (path.startsWith("hud/dialogue/text/wynncraft/choice_")) {
                    hasDialogue.set(true);
                    try {
                        // 从路径（如 hud/dialogue/text/wynncraft/choice_0）中提取 index
                        String indexStr = path.substring(path.lastIndexOf('_') + 1);
                        int index = Integer.parseInt(indexStr);
                        String cleaned = cleanPua(part);
                        if (!cleaned.isEmpty()) {
                            choiceMap.computeIfAbsent(index, k -> new StringBuilder()).append(cleaned);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Failed to parse choice index from path: {}", path);
                    }
                }
            }
            return Optional.empty();
        }, Style.EMPTY);

        if (hasDialogue.get()) {
            String npcName = frameName.toString().trim();
            String bodyContent = frameBody.toString().trim().replaceAll(" +", " ");
            
            // 拼接所有选项片段
            StringBuilder currentFrameChoices = new StringBuilder();
            choiceMap.forEach((index, sb) -> {
                if (currentFrameChoices.length() > 0) currentFrameChoices.append("\n");
                currentFrameChoices.append(sb.toString().trim());
            });

            // 处理当前帧的所有选项片段
            String rawChoices = currentFrameChoices.toString().trim();
            if (!rawChoices.isEmpty()) {
                processChoices(rawChoices);
            } else {
                // 如果当前帧没有捕获到任何选项字体（但有对话主体），
                // 在 WynnCraft 中通常意味着玩家已经选完了选项，正在进入下一段对话或结束对话。
                // 我们应该清空选项缓存，避免过期的选项在后续对话中停留。
                if (!choiceHistory.isEmpty()) {
                    choiceHistory.clear();
                    LOGGER.info("[WynnDialogue] Choices cleared (selected or timeout).");
                }
            }

            // 构建选项内容的显示文本
            StringBuilder choiceContentBuilder = new StringBuilder();
            for (String choice : choiceHistory) {
                if (choiceContentBuilder.length() > 0) choiceContentBuilder.append("\n");
                choiceContentBuilder.append("> ").append(choice);
            }
            String choiceContent = choiceContentBuilder.toString();
            
            // 拼接当前的完整快照
            StringBuilder snapshotBuilder = new StringBuilder();
            if (!npcName.isEmpty()) snapshotBuilder.append("[").append(npcName).append("]\n");
            snapshotBuilder.append(bodyContent);
            if (!choiceContent.isEmpty()) {
                snapshotBuilder.append("\n\n").append(choiceContent);
            }
            
            String currentSnapshot = snapshotBuilder.toString().trim();

            if (!currentSnapshot.equals(lastStableText)) {
                lastChangeTime = System.currentTimeMillis();
                lastStableText = currentSnapshot;
                
                // 启动或更新防抖任务
                if (isScheduled.compareAndSet(false, true)) {
                    scheduleDebounce();
                }
            }
        } else {
            // 如果连续收到不包含对话的数据包，且当前还在显示对话，则说明对话已结束
            if (!lastStableText.isEmpty()) {
                lastStableText = "";
                choiceHistory.clear(); // 清理选项历史
                lastRawChoices = "";
                DialogueOverlayRenderer.updateDialogue("");
                LOGGER.info("[WynnDialogue] Dialogue cleared.");
            }
        }

        return null;
    }

    /**
     * 处理多行选项的合并逻辑。
     * 策略：
     * 1. 如果当前帧渲染的行数与历史不一致，说明发生了“上下翻页滚动”或进入新对话，重置历史。
     * 2. 如果行数一致，但某一行内容与历史完全无法匹配（既不包含也无重叠），也认为发生了“上下翻页”，重置历史。
     * 3. 只有当行数一致且内容能对上时，才进行内部的“左右横向滚动”拼接。
     */
    private static void processChoices(String frameChoicesStr) {
        String[] currentLines = Arrays.stream(frameChoicesStr.split("\n"))
                                     .map(String::trim)
                                     .filter(s -> !s.isEmpty())
                                     .toArray(String[]::new);
        
        if (currentLines.length == 0) return;

        // 如果历史为空，或行数发生了变化（上下滚动翻页），则认为是一组新的展示内容
        if (choiceHistory.isEmpty() || currentLines.length != choiceHistory.size()) {
            choiceHistory.clear();
            for (String line : currentLines) {
                choiceHistory.add(line);
            }
            return;
        }

        // 行数一致时，尝试 1:1 匹配拼接
        for (int i = 0; i < currentLines.length; i++) {
            String part = currentLines[i];
            String existing = choiceHistory.get(i);
            
            if (part.contains(existing)) {
                choiceHistory.set(i, part);
            } else if (existing.contains(part)) {
                // 已有更全内容
            } else {
                // 尝试横向滚动拼接
                boolean merged = false;
                for (int len = Math.min(existing.length(), part.length()); len >= 4; len--) {
                    if (existing.endsWith(part.substring(0, len))) {
                        choiceHistory.set(i, existing + part.substring(len));
                        merged = true;
                        break;
                    }
                }
                
                // 如果该行内容完全变了（且行数没变），说明玩家很可能进行了一次上下滚动（Scroll Down/Up）
                // 导致虽然行数相同，但每一行的文字都变了。此时必须清空重新采集。
                if (!merged) {
                    choiceHistory.clear();
                    for (String line : currentLines) {
                        choiceHistory.add(line);
                    }
                    return;
                }
            }
        }
    }

    private static void updateChoiceHistory(String part) {
        // Obsolete
    }

    private static void scheduleDebounce() {
        scheduler.schedule(() -> {
            try {
                long now = System.currentTimeMillis();
                if (now - lastChangeTime >= STABLE_STORM_MS) {
                    // 文本已稳定，输出最终结果
                    if (!lastStableText.isEmpty()) {
                        DialogueOverlayRenderer.updateDialogue(lastStableText);
                        LOGGER.info("[WynnDialogue] Stability Reached. Outputting:\n{}", lastStableText);
                    }
                    isScheduled.set(false);
                } else {
                    // 还在变化中，继续等待
                    scheduleDebounce();
                }
            } catch (Exception e) {
                isScheduled.set(false);
                LOGGER.error("Error in debounce task", e);
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private static Identifier getFont(Style style) {
        Object fontObj = style.getFont();
        if (fontObj instanceof Identifier) {
            return (Identifier) fontObj;
        } else if (fontObj != null) {
            String s = fontObj.toString();
            if (s.contains("id=")) {
                try {
                    String extracted = s.substring(s.indexOf("id=") + 3);
                    if (extracted.contains("]")) extracted = extracted.substring(0, extracted.indexOf("]"));
                    if (extracted.contains(",")) extracted = extracted.split(",")[0].trim();
                    return Identifier.of(extracted);
                } catch (Exception e) {}
            }
        }
        return null;
    }

    private static String cleanPua(String text) {
        if (text == null) return "";
        
        StringBuilder debugSb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            debugSb.append(String.format("\\u%04X ", (int)c));
        }
        if (debugSb.length() > 0) {
            System.out.println("[WynnDialogue] Segment Hex: " + debugSb.toString() + " | Text: " + text);
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 彻底放开，仅仅过滤 PUA 区
            if (c >= '\uE000' && c <= '\uF8FF') continue;
            if (c >= '\uD800' && c <= '\uDFFF') continue;
            
            result.append(c);
        }
        
        return result.toString().trim();
    }

    private static boolean isDialogueFont(Identifier font) {
        String path = font.getPath();
        // 匹配 body_0 到 body_3
        return path.startsWith("hud/dialogue/text/wynncraft/body_") || 
               path.equals("hud/dialogue/text/nameplate");
    }
}
