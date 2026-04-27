package com.cedarxuesong.translate_allinone.utils.translate;

import com.cedarxuesong.translate_allinone.Translate_AllinOne;
import com.cedarxuesong.translate_allinone.gui.overlay.DialogueOverlayRenderer;
import com.cedarxuesong.translate_allinone.utils.config.ProviderRouteResolver;
import com.cedarxuesong.translate_allinone.utils.config.pojos.ApiProviderProfile;
import com.cedarxuesong.translate_allinone.utils.llmapi.LLM;
import com.cedarxuesong.translate_allinone.utils.llmapi.ProviderSettings;
import com.cedarxuesong.translate_allinone.utils.llmapi.openai.OpenAIRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WynnCraft NPC 对话翻译管理器
 * 
 * 职责：
 * 1. 接收来自 WynnDialogueExtractor 的对话快照
 * 2. 调用 LLM 进行翻译
 * 3. 更新 DialogueOverlayRenderer 中的显示内容
 */
public class WynnDialogueTranslateManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(WynnDialogueTranslateManager.class);
    
    private static ExecutorService translationExecutor;
    private static int currentConcurrentRequests = -1;
    private static final int DEFAULT_CONCURRENT_REQUESTS = 3;
    
    // 用于追踪当前正在翻译的对话快照，防止重复请求
    private static String lastProcessedDialogue = "";
    private static final AtomicBoolean isTranslating = new AtomicBoolean(false);

    /**
     * 处理稳定的对话快照进行翻译
     */
    public static void translateDialogue(String dialogueSnapshot) {
        if (dialogueSnapshot == null || dialogueSnapshot.trim().isEmpty()) {
            return;
        }

        // 防止重复翻译同一内容
        if (dialogueSnapshot.equals(lastProcessedDialogue)) {
            return;
        }
        lastProcessedDialogue = dialogueSnapshot;

        updateExecutorServiceIfNeeded();
        
        if (translationExecutor == null) {
            LOGGER.warn("[WynnDialogueTranslateManager] Translator executor not initialized");
            return;
        }

        // 异步提交翻译任务
        translationExecutor.submit(() -> {
            if (!isTranslating.compareAndSet(false, true)) {
                LOGGER.debug("[WynnDialogueTranslateManager] Already translating, skipping duplicate request");
                return;
            }

            try {
                var config = Translate_AllinOne.getConfig();
                if (config == null || !config.wynnCraft.npc_dialogue.enabled) {
                    LOGGER.debug("[WynnDialogueTranslateManager] NPC dialogue translation is disabled");
                    isTranslating.set(false);
                    return;
                }

                // 解析对话内容
                DialogueComponents components = parseDialogueSnapshot(dialogueSnapshot);
                
                // 分别翻译对话主体和选项
                String translatedDialogue = components.npcName;
                if (components.bodyContent != null && !components.bodyContent.isEmpty()) {
                    if (config.wynnCraft.npc_dialogue.translate_dialogue) {
                        String translated = translateText(
                            components.bodyContent,
                            config.wynnCraft.npc_dialogue.target_language
                        );
                        translatedDialogue = (components.npcName.isEmpty() ? "" : (components.npcName + "\n")) + translated;
                    } else {
                        translatedDialogue = (components.npcName.isEmpty() ? "" : (components.npcName + "\n")) + components.bodyContent;
                    }
                }

                // 处理选项翻译
                StringBuilder finalResult = new StringBuilder(translatedDialogue);
                if (components.choices != null && !components.choices.isEmpty()) {
                    if (config.wynnCraft.npc_dialogue.translate_choices) {
                        finalResult.append("\n\n");
                        for (int i = 0; i < components.choices.size(); i++) {
                            if (i > 0) finalResult.append("\n");
                            String choice = components.choices.get(i);
                            String translatedChoice = translateText(choice, config.wynnCraft.npc_dialogue.target_language);
                            finalResult.append("> ").append(translatedChoice);
                        }
                    } else {
                        finalResult.append("\n\n");
                        for (int i = 0; i < components.choices.size(); i++) {
                            if (i > 0) finalResult.append("\n");
                            finalResult.append("> ").append(components.choices.get(i));
                        }
                    }
                }

                // 更新Overlay显示
                DialogueOverlayRenderer.updateDialogue(finalResult.toString());
                LOGGER.info("[WynnDialogueTranslateManager] Dialogue translated successfully");

            } catch (Exception e) {
                LOGGER.error("[WynnDialogueTranslateManager] Error during translation", e);
            } finally {
                isTranslating.set(false);
            }
        });
    }

    /**
     * 清空当前翻译状态
     */
    public static void clearDialogue() {
        lastProcessedDialogue = "";
        isTranslating.set(false);
    }

    /**
     * 内部方法：翻译文本
     */
    private static String translateText(String text, String targetLanguage) {
        try {
            var config = Translate_AllinOne.getConfig();
            ApiProviderProfile provider = ProviderRouteResolver.resolve(config, ProviderRouteResolver.Route.NPCDIALOG);
            
            if (provider == null) {
                LOGGER.warn("[WynnDialogueTranslateManager] No provider configured for NPCDIALOG route");
                return text;
            }

            ProviderSettings settings = ProviderSettings.fromProviderProfile(provider);
            LLM llm = new LLM(settings);

            String systemPrompt = buildSystemPrompt(targetLanguage);
            List<OpenAIRequest.Message> messages = new ArrayList<>();
            messages.add(new OpenAIRequest.Message("system", systemPrompt));
            messages.add(new OpenAIRequest.Message("user", text));

            String result = llm.getCompletion(messages).join();
            return result != null ? result.trim() : text;
        } catch (Exception e) {
            LOGGER.error("[WynnDialogueTranslateManager] Translation request failed", e);
            return text;
        }
    }

    /**
     * 构建翻译系统提示
     */
    private static String buildSystemPrompt(String targetLanguage) {
        return String.format(
            "You are a professional game translator specializing in WynnCraft dialogue and NPC interactions. " +
            "Translate the following WynnCraft NPC dialogue or quest text to %s. " +
            "Keep the translation concise and maintain the tone and context of the original text. " +
            "Do not add explanations or translation notes. Return only the translated text.",
            targetLanguage
        );
    }

    /**
     * 解析对话快照为组件
     */
    private static DialogueComponents parseDialogueSnapshot(String snapshot) {
        DialogueComponents components = new DialogueComponents();
        String[] lines = snapshot.split("\n");
        
        int bodyStartIndex = 0;
        
        // 提取NPC名字（第一行如果是 [name] 格式）
        if (lines.length > 0 && lines[0].startsWith("[") && lines[0].endsWith("]")) {
            components.npcName = lines[0];
            bodyStartIndex = 1;
        }

        // 提取对话主体（直到遇到空行或 > 选项标记）
        StringBuilder bodyBuilder = new StringBuilder();
        int choicesStartIndex = -1;
        
        for (int i = bodyStartIndex; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                if (bodyBuilder.length() > 0) {
                    choicesStartIndex = i + 1;
                    break;
                }
                continue;
            }
            if (line.startsWith(">")) {
                choicesStartIndex = i;
                break;
            }
            if (bodyBuilder.length() > 0) bodyBuilder.append(" ");
            bodyBuilder.append(line);
        }

        components.bodyContent = bodyBuilder.toString();

        // 提取选项
        if (choicesStartIndex >= 0) {
            for (int i = choicesStartIndex; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith(">")) {
                    String choice = line.substring(1).trim();
                    if (!choice.isEmpty()) {
                        components.choices.add(choice);
                    }
                }
            }
        }

        return components;
    }

    /**
     * 确保翻译执行器已初始化
     */
    private static synchronized void updateExecutorServiceIfNeeded() {
        var config = Translate_AllinOne.getConfig();
        if (config == null) {
            return;
        }

        int desiredConcurrency = DEFAULT_CONCURRENT_REQUESTS;
        
        if (currentConcurrentRequests != desiredConcurrency) {
            if (translationExecutor != null) {
                translationExecutor.shutdown();
                try {
                    if (!translationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        translationExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    translationExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            translationExecutor = Executors.newFixedThreadPool(desiredConcurrency);
            currentConcurrentRequests = desiredConcurrency;
            LOGGER.info("[WynnDialogueTranslateManager] Executor service initialized with {} threads", desiredConcurrency);
        }
    }

    /**
     * 对话组件容器类
     */
    public static class DialogueComponents {
        public String npcName = "";
        public String bodyContent = "";
        public List<String> choices = new ArrayList<>();
    }
}
