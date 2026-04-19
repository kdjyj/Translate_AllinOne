package com.cedarxuesong.translate_allinone.utils.translate;

final class ItemTranslationPromptSupport {
    private ItemTranslationPromptSupport() {
    }

    static String buildSystemPrompt(String targetLanguage, String suffix) {
        String basePrompt = "Translate JSON string values into " + targetLanguage + ".\n"
                + "Input: one JSON object of string keys to string values. Output: one valid JSON object only.\n"
                + "\n"
                + "Rules:\n"
                + "1) Keep every key and the key count unchanged; translate values only.\n"
                + "2) Preserve style tags exactly: <s0>...</s0>, <s1>...</s1>, ... same ids, order, and count.\n"
                + "3) Preserve tokens exactly: § codes, %s %d %f, {d1}, URLs, numbers, <...>, {...}, \\n, \\t.\n"
                + "4) For multiline values, keep full meaning and paragraph order. Keep intentional breaks when needed; soft wraps may be reflowed. Never drop words or fragments.\n"
                + "5) If unsure, keep that value unchanged.\n"
                + "6) No text outside JSON.";
        return PromptMessageBuilder.appendSystemPromptSuffix(basePrompt, suffix);
    }
}
