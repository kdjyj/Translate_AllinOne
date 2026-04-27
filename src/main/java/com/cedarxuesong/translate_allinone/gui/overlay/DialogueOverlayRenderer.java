package com.cedarxuesong.translate_allinone.gui.overlay;

import com.cedarxuesong.translate_allinone.registration.ConfigManager;
import com.cedarxuesong.translate_allinone.utils.config.pojos.OverlayConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class DialogueOverlayRenderer {
    private static final List<String> lines = new ArrayList<>();

    public static void updateDialogue(String text) {
        synchronized (lines) {
            lines.clear();
            
            OverlayConfig config = ConfigManager.getConfig().overlay;
            int maxLineLength = config.maxLineLength;
            if (maxLineLength <= 0) maxLineLength = 40; 
            
            String[] rawLines = text.split("\n");
            for (String rawLine : rawLines) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;
                
                if (line.startsWith("[")) {
                    lines.add(line);
                    continue;
                }
                
                while (line.length() > maxLineLength) {
                    int splitIndex = maxLineLength;
                    int lastSpace = line.lastIndexOf(' ', splitIndex);
                    if (lastSpace > maxLineLength / 2) {
                        splitIndex = lastSpace;
                    }
                    
                    lines.add(line.substring(0, splitIndex).trim());
                    line = line.substring(splitIndex).trim();
                }
                
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
    }

    public static void render(DrawContext context, float tickDelta) {
        OverlayConfig config = ConfigManager.getConfig().overlay;
        if (!config.enabled) return;

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer renderer = client.textRenderer;

        int curY = config.y;
        synchronized (lines) {
            if (lines.isEmpty()) return;

            for (String line : lines) {
                Text text = Text.literal(line.trim());
                int width = (int)(renderer.getWidth(text) * config.scale);
                int height = (int)(renderer.fontHeight * config.scale);
                
                context.fill(config.x - 2, curY - 1, config.x + width + 2, curY + height, config.backgroundColor);
                context.drawText(renderer, text, config.x, curY, config.textColor, false);
                curY += renderer.fontHeight + 2;
            }
        }
    }
}
