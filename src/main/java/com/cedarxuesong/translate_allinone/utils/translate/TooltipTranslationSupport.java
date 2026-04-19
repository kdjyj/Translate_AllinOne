package com.cedarxuesong.translate_allinone.utils.translate;

import com.cedarxuesong.translate_allinone.Translate_AllinOne;
import com.cedarxuesong.translate_allinone.utils.translate.TooltipRoutePlanner.TooltipParagraphBlock;
import com.cedarxuesong.translate_allinone.utils.translate.TooltipRoutePlanner.TooltipPlan;
import com.cedarxuesong.translate_allinone.utils.translate.TooltipRoutePlanner.TooltipRouteKind;
import com.cedarxuesong.translate_allinone.utils.translate.TooltipRoutePlanner.TooltipRouteSegment;
import com.cedarxuesong.translate_allinone.utils.translate.TooltipTemplateRuntime.PreparedParagraphTemplate;
import com.cedarxuesong.translate_allinone.utils.translate.TooltipTemplateRuntime.PreparedTooltipTemplate;
import com.cedarxuesong.translate_allinone.utils.AnimationManager;
import com.cedarxuesong.translate_allinone.utils.cache.ItemTemplateCache;
import com.cedarxuesong.translate_allinone.utils.config.pojos.ItemTranslateConfig;
import com.cedarxuesong.translate_allinone.utils.input.KeybindingManager;
import com.cedarxuesong.translate_allinone.utils.text.StylePreserver;
import com.cedarxuesong.translate_allinone.utils.text.TemplateProcessor;
import com.cedarxuesong.translate_allinone.utils.textmatcher.FlatNode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TooltipTranslationSupport {
    private static final String MISSING_KEY_HINT = "missing key";
    private static final String KEY_MISMATCH_HINT = "key mismatch";
    private static final String TOOLTIP_REFRESH_NOTICE_KEY = "text.translate_allinone.item.tooltip_refresh_forced";
    private static final long REFRESH_NOTICE_DURATION_MILLIS = 1500L;
    private static final Pattern ENGLISH_CONNECTOR_PATTERN =
            Pattern.compile("(?i)\\b(by|to|and|of|for|in|on|from|with|the|a|an)\\b");
    private static final int MIN_PARAGRAPH_BODY_STYLE_DOMINANT_SCORE = 5;
    private static final int MIN_PARAGRAPH_BODY_RUN_SCORE = 1;
    private static final int MIN_PARAGRAPH_BODY_STYLE_DOMINANCE_PERCENT = 55;
    private static final int MAX_NON_WYNN_MISSING_STYLE_SCORE = 8;
    private static final int MAX_NON_WYNN_TOTAL_MISSING_STYLE_SCORE = 24;
    private static final Logger LOGGER = LoggerFactory.getLogger("Translate_AllinOne/TooltipTranslationSupport");
    private static final Set<Integer> refreshedTooltipSignaturesThisHold = new HashSet<>();
    private static volatile int refreshNoticeTooltipSignature = 0;
    private static volatile long refreshNoticeExpiresAtMillis = 0L;

    private TooltipTranslationSupport() {
    }

    public record TooltipLineResult(Text translatedLine, boolean pending, boolean missingKeyIssue) {
    }

    public record TooltipProcessingResult(
            List<Text> translatedLines,
            int translatableLines,
            boolean pending,
            boolean missingKeyIssue
    ) {
    }

    private record TooltipBlockTranslationAttempt(
            List<TooltipLineResult> lineResults,
            boolean pending,
            boolean missingKeyIssue
    ) {
    }

    private static final class ParagraphTaggedRun {
        private Integer styleId;
        private final String content;

        private ParagraphTaggedRun(Integer styleId, String content) {
            this.styleId = styleId;
            this.content = content;
        }
    }

    public static boolean shouldShowOriginal(ItemTranslateConfig.KeybindingMode mode, boolean isKeyPressed) {
        return switch (mode) {
            case HOLD_TO_TRANSLATE -> !isKeyPressed;
            case HOLD_TO_SEE_ORIGINAL -> isKeyPressed;
            case DISABLED -> false;
        };
    }

    public static TooltipLineResult translateLine(Text line) {
        return translateLine(line, false);
    }

    public static TooltipLineResult translateLine(Text line, boolean useTagStylePreservation) {
        return TooltipTemplateRuntime.translateLine(line, useTagStylePreservation);
    }

    public static void maybeForceRefreshCurrentTooltip(List<Text> tooltip, ItemTranslateConfig config) {
        Set<String> keysToRefresh = TooltipRoutePlanner.planTooltip(
                tooltip,
                config,
                config != null && config.wynn_item_compatibility
        ).translationTemplateKeys();
        maybeForceRefreshCurrentTooltip(keysToRefresh, config);
    }

    private static void maybeForceRefreshCurrentTooltip(Set<String> keysToRefresh, ItemTranslateConfig config) {
        boolean isRefreshPressed = config != null
                && config.keybinding != null
                && KeybindingManager.isPressed(config.keybinding.refreshBinding);
        if (!isRefreshPressed) {
            synchronized (refreshedTooltipSignaturesThisHold) {
                refreshedTooltipSignaturesThisHold.clear();
            }
            return;
        }

        if (keysToRefresh.isEmpty()) {
            return;
        }

        int tooltipSignature = computeTooltipSignature(keysToRefresh);
        synchronized (refreshedTooltipSignaturesThisHold) {
            if (!refreshedTooltipSignaturesThisHold.add(tooltipSignature)) {
                return;
            }
        }

        int refreshedCount = ItemTemplateCache.getInstance().forceRefresh(keysToRefresh);
        if (refreshedCount > 0) {
            TooltipTemplateRuntime.registerForceRefreshCompatBypass(keysToRefresh);
            refreshNoticeTooltipSignature = tooltipSignature;
            refreshNoticeExpiresAtMillis = System.currentTimeMillis() + REFRESH_NOTICE_DURATION_MILLIS;
            LOGGER.info("Forced refresh of {} current item tooltip translation key(s).", refreshedCount);
        }
    }

    public static List<Text> buildTranslatedTooltip(List<Text> originalTooltip, String animationKey) {
        if (originalTooltip == null || originalTooltip.isEmpty()) {
            return originalTooltip;
        }

        List<Text> tooltip = stripInternalGeneratedLines(originalTooltip);
        if (tooltip.isEmpty()) {
            return tooltip;
        }

        ItemTranslateConfig config = Translate_AllinOne.getConfig().itemTranslate;
        if (!config.enabled) {
            return tooltip;
        }

        TooltipPlan tooltipPlan = TooltipRoutePlanner.planTooltip(tooltip, config, config.wynn_item_compatibility);
        maybeForceRefreshCurrentTooltip(tooltipPlan.translationTemplateKeys(), config);
        boolean showRefreshNotice = shouldShowRefreshNotice(tooltipPlan.translationTemplateKeys());

        boolean isKeyPressed = KeybindingManager.isPressed(config.keybinding.binding);
        if (shouldShowOriginal(config.keybinding.mode, isKeyPressed)) {
            return appendRefreshNoticeLine(tooltip, showRefreshNotice);
        }

        boolean emitDevLog = TooltipTextMatcherSupport.beginTooltipDevPass(config, "screen-mirror", tooltip);
        long tooltipStartedAtNanos = emitDevLog ? System.nanoTime() : 0L;
        TooltipRoutePlanner.logLineDecisionsIfDev(tooltipPlan, config, emitDevLog, "screen-mirror");

        try {
            TooltipProcessingResult processedTooltip = processTooltipPlan(
                    tooltipPlan,
                    config,
                    config.wynn_item_compatibility,
                    emitDevLog,
                    "screen-mirror"
            );
            List<Text> mirroredTooltip = new ArrayList<>(processedTooltip.translatedLines());

            if (processedTooltip.translatableLines() > 0) {
                ItemTemplateCache.CacheStats stats = ItemTemplateCache.getInstance().getCacheStats();
                boolean isAnythingPending = stats.total() > stats.translated();
                boolean shouldShowStatus = processedTooltip.pending() || processedTooltip.missingKeyIssue() || isAnythingPending;

                if (shouldShowStatus) {
                    mirroredTooltip.add(createStatusLine(
                            stats,
                            processedTooltip.missingKeyIssue(),
                            animationKey
                    ));
                }
            }

            TooltipTextMatcherSupport.logTooltipPassIfDev(
                    config,
                    emitDevLog,
                    "screen-mirror",
                    tooltip.size(),
                    processedTooltip.translatableLines(),
                    tooltipStartedAtNanos
            );
            return appendRefreshNoticeLine(mirroredTooltip, showRefreshNotice);
        } catch (Exception e) {
            LOGGER.error("Failed to build translated tooltip", e);
            return appendRefreshNoticeLine(tooltip, showRefreshNotice);
        }
    }

    public static TooltipProcessingResult processTooltipLines(
            List<Text> tooltip,
            ItemTranslateConfig config,
            boolean useTagStylePreservation,
            boolean emitDevLog,
            String devSource
    ) {
        TooltipPlan tooltipPlan = TooltipRoutePlanner.planTooltip(tooltip, config, useTagStylePreservation);
        TooltipRoutePlanner.logLineDecisionsIfDev(tooltipPlan, config, emitDevLog, devSource);
        return processTooltipPlan(tooltipPlan, config, useTagStylePreservation, emitDevLog, devSource);
    }

    private static TooltipProcessingResult processTooltipPlan(
            TooltipPlan tooltipPlan,
            ItemTranslateConfig config,
            boolean useTagStylePreservation,
            boolean emitDevLog,
            String devSource
    ) {
        if (tooltipPlan == null || tooltipPlan.segments() == null || tooltipPlan.segments().isEmpty()) {
            return new TooltipProcessingResult(List.of(), 0, false, false);
        }

        List<Text> translatedLines = new ArrayList<>(tooltipPlan.segments().size());
        int translatableLines = 0;
        boolean hasPending = false;
        boolean hasMissingKeyIssue = false;

        for (TooltipRouteSegment segment : tooltipPlan.segments()) {
            if (segment.kind() == TooltipRouteKind.PASSTHROUGH) {
                translatedLines.add(segment.candidate() == null ? null : segment.candidate().line());
                continue;
            }

            translatableLines += segment.translatableLineCount();
            if (segment.kind() == TooltipRouteKind.PARAGRAPH_BLOCK) {
                TooltipParagraphBlock paragraphBlock = segment.paragraphBlock();
                long blockStartedAtNanos = emitDevLog ? System.nanoTime() : 0L;
                TooltipBlockTranslationAttempt blockAttempt = translateParagraphBlock(
                        paragraphBlock,
                        config,
                        emitDevLog,
                        devSource
                );
                for (TooltipLineResult lineResult : blockAttempt.lineResults()) {
                    if (lineResult.pending()) {
                        hasPending = true;
                    }
                    if (lineResult.missingKeyIssue()) {
                        hasMissingKeyIssue = true;
                    }
                    translatedLines.add(lineResult.translatedLine());
                }

                int loggableLineCount = Math.min(blockAttempt.lineResults().size(), paragraphBlock.preparedLines().size());
                for (int offset = 0; offset < loggableLineCount; offset++) {
                    TooltipLineResult lineResult = blockAttempt.lineResults().get(offset);
                    TooltipTextMatcherSupport.logLineTranslationIfDev(
                            config,
                            emitDevLog,
                            devSource,
                            paragraphBlock.startLineIndex() + offset,
                            lineResult,
                            "paragraph-block",
                            "paragraphKey=" + (paragraphBlock.paragraphTemplate().translationTemplateKey() == null
                                    ? ""
                                    : paragraphBlock.paragraphTemplate().translationTemplateKey()),
                            blockStartedAtNanos
                    );
                }
                if (blockAttempt.pending()) {
                    hasPending = true;
                }
                if (blockAttempt.missingKeyIssue()) {
                    hasMissingKeyIssue = true;
                }
                continue;
            }

            TooltipRouteSegment lineSegment = segment;
            if (lineSegment.candidate() == null) {
                continue;
            }

            long lineStartedAtNanos = emitDevLog ? System.nanoTime() : 0L;
            TooltipStructuredCaptureSupport.StructuredTooltipLineResult structuredLineResult = null;
            TooltipLineResult lineResult;
            String route;
            String detail;

            if (lineSegment.kind() == TooltipRouteKind.STRUCTURED_LINE) {
                structuredLineResult = TooltipStructuredCaptureSupport.tryTranslateStructuredLine(
                        lineSegment.candidate().line(),
                        useTagStylePreservation
                );
            }

            if (structuredLineResult != null) {
                lineResult = structuredLineResult.lineResult();
                route = "capture";
                detail = structuredLineResult.debugSummary();
            } else {
                PreparedTooltipTemplate preparedTemplate = lineSegment.preparedTemplate();
                if (preparedTemplate == null) {
                    preparedTemplate = TooltipTemplateRuntime.prepareTemplate(
                            lineSegment.candidate().line(),
                            useTagStylePreservation
                    );
                }
                lineResult = TooltipTemplateRuntime.translatePreparedTemplate(preparedTemplate);
                route = "line-template";
                detail = "templateKey=" + (preparedTemplate.translationTemplateKey() == null
                        ? ""
                        : preparedTemplate.translationTemplateKey());
            }

            if (lineResult.pending()) {
                hasPending = true;
            }
            if (lineResult.missingKeyIssue()) {
                hasMissingKeyIssue = true;
            }
            translatedLines.add(lineResult.translatedLine());
            TooltipTextMatcherSupport.logLineTranslationIfDev(
                    config,
                    emitDevLog,
                    devSource,
                    lineSegment.candidate().lineIndex(),
                    lineResult,
                    route,
                    detail,
                    lineStartedAtNanos
            );
        }

        return new TooltipProcessingResult(translatedLines, translatableLines, hasPending, hasMissingKeyIssue);
    }

    public static Text createStatusLine(
            ItemTemplateCache.CacheStats stats,
            boolean hasMissingKeyIssue,
            String animationKey
    ) {
        float percentage = (stats.total() > 0) ? ((float) stats.translated() / stats.total()) * 100 : 100;
        String progressText = String.format(" (%d/%d) - %.0f%%", stats.translated(), stats.total(), percentage);

        Text statusMessage = hasMissingKeyIssue
                ? Text.literal("Item translation key mismatch, retrying...").formatted(Formatting.RED)
                : Text.literal("Translating...").formatted(Formatting.GRAY);

        MutableText statusText = AnimationManager.getAnimatedStyledText(statusMessage, animationKey, hasMissingKeyIssue);
        return statusText.append(Text.literal(progressText).formatted(Formatting.YELLOW));
    }

    public static boolean isMissingKeyIssue(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return false;
        }
        String lower = errorMessage.toLowerCase(Locale.ROOT);
        return lower.contains(MISSING_KEY_HINT) || lower.contains(KEY_MISMATCH_HINT);
    }

    public static boolean isInternalStatusLine(Text line) {
        if (line == null) {
            return false;
        }

        String content = line.getString();
        return content.startsWith("Translating...")
                || content.startsWith("Item translation key mismatch, retrying...");
    }

    public static boolean isRefreshNoticeLine(Text line) {
        if (line == null) {
            return false;
        }
        return createRefreshNoticeLine().getString().equals(line.getString());
    }

    public static boolean isInternalGeneratedLine(Text line) {
        return isInternalStatusLine(line) || isRefreshNoticeLine(line);
    }

    public static List<Text> stripInternalGeneratedLines(List<Text> tooltip) {
        if (tooltip == null || tooltip.isEmpty()) {
            return tooltip;
        }

        List<Text> sanitized = null;
        for (int i = 0; i < tooltip.size(); i++) {
            Text line = tooltip.get(i);
            if (!isInternalGeneratedLine(line)) {
                if (sanitized != null) {
                    sanitized.add(line);
                }
                continue;
            }

            if (sanitized == null) {
                sanitized = new ArrayList<>(tooltip.size());
                sanitized.addAll(tooltip.subList(0, i));
            }
        }
        return sanitized == null ? tooltip : sanitized;
    }

    public static boolean shouldShowRefreshNotice(List<Text> tooltip, ItemTranslateConfig config) {
        Set<String> keys = TooltipRoutePlanner.planTooltip(
                tooltip,
                config,
                config != null && config.wynn_item_compatibility
        ).translationTemplateKeys();
        return shouldShowRefreshNotice(keys);
    }

    private static boolean shouldShowRefreshNotice(Set<String> keys) {
        long expiresAt = refreshNoticeExpiresAtMillis;
        if (expiresAt <= 0L || System.currentTimeMillis() > expiresAt) {
            return false;
        }

        if (keys.isEmpty()) {
            return false;
        }
        return computeTooltipSignature(keys) == refreshNoticeTooltipSignature;
    }

    public static Text createRefreshNoticeLine() {
        return Text.translatable(TOOLTIP_REFRESH_NOTICE_KEY).formatted(Formatting.GREEN);
    }

    public static List<Text> appendRefreshNoticeLine(List<Text> tooltip, boolean showRefreshNotice) {
        if (!showRefreshNotice || tooltip == null) {
            return tooltip;
        }

        for (Text line : tooltip) {
            if (isRefreshNoticeLine(line)) {
                return tooltip;
            }
        }

        List<Text> tooltipWithNotice = new ArrayList<>(tooltip.size() + 1);
        tooltipWithNotice.addAll(tooltip);
        tooltipWithNotice.add(createRefreshNoticeLine());
        return tooltipWithNotice;
    }

    private static boolean containsDigit(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }

        for (int offset = 0; offset < raw.length(); ) {
            int codePoint = raw.codePointAt(offset);
            if (Character.isDigit(codePoint)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static int countWordTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }

        int count = 0;
        for (String token : raw.trim().split("\\s+")) {
            if (!trimEdgePunctuation(token).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsSymbolicMarker(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }

        for (int offset = 0; offset < raw.length(); ) {
            int codePoint = raw.codePointAt(offset);
            if (!Character.isLetterOrDigit(codePoint)
                    && !Character.isWhitespace(codePoint)
                    && codePoint != '.'
                    && codePoint != ',') {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean containsSentencePunctuation(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }

        for (int offset = 0; offset < raw.length(); ) {
            int codePoint = raw.codePointAt(offset);
            if (codePoint == '.'
                    || codePoint == ','
                    || codePoint == '!'
                    || codePoint == '?'
                    || codePoint == ';'
                    || codePoint == ':'
                    || codePoint == '。'
                    || codePoint == '，'
                    || codePoint == '！'
                    || codePoint == '？'
                    || codePoint == '：'
                    || codePoint == '；') {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static String trimEdgePunctuation(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }

        int start = 0;
        int end = token.length();
        while (start < end) {
            int codePoint = token.codePointAt(start);
            if (Character.isLetterOrDigit(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = token.codePointBefore(end);
            if (Character.isLetterOrDigit(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return token.substring(start, end);
    }

    private static boolean isMenuWordToken(String token) {
        boolean sawLetter = false;
        boolean firstLetterHandled = false;

        for (int offset = 0; offset < token.length(); ) {
            int codePoint = token.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (!Character.isLetter(codePoint)) {
                if (codePoint == '\'' || codePoint == '-') {
                    continue;
                }
                return false;
            }

            sawLetter = true;
            if (!firstLetterHandled) {
                if (!Character.isUpperCase(codePoint)) {
                    return false;
                }
                firstLetterHandled = true;
                continue;
            }

            if (!Character.isLowerCase(codePoint) && !Character.isUpperCase(codePoint)) {
                return false;
            }
        }

        return sawLetter;
    }

    private static boolean endsWithStrongTerminalPunctuation(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }

        String normalized = raw.trim();
        char last = normalized.charAt(normalized.length() - 1);
        return last == '.'
                || last == '!'
                || last == '?'
                || last == '。'
                || last == '！'
                || last == '？';
    }

    private static TooltipBlockTranslationAttempt translateParagraphBlock(
            TooltipParagraphBlock block,
            ItemTranslateConfig config,
            boolean emitDevLog,
            String devSource
    ) {
        ItemTemplateCache cache = ItemTemplateCache.getInstance();
        ItemTemplateCache.LookupResult blockLookup = cache.lookupOrQueue(block.paragraphTemplate().translationTemplateKey());
        if (blockLookup.status() == ItemTemplateCache.TranslationStatus.TRANSLATED) {
            List<TooltipLineResult> blockResults = renderTranslatedParagraphBlock(
                    block,
                    blockLookup.translation(),
                    config,
                    emitDevLog,
                    devSource
            );
            if (blockResults != null) {
                return new TooltipBlockTranslationAttempt(blockResults, false, false);
            }
            return translateParagraphBlockLineByLine(block);
        }

        boolean pending = blockLookup.status() == ItemTemplateCache.TranslationStatus.PENDING
                || blockLookup.status() == ItemTemplateCache.TranslationStatus.IN_PROGRESS;
        boolean missingKeyIssue = blockLookup.status() == ItemTemplateCache.TranslationStatus.ERROR
                && isMissingKeyIssue(blockLookup.errorMessage());
        if (blockLookup.status() == ItemTemplateCache.TranslationStatus.TRANSLATED) {
            pending = true;
        }

        List<TooltipLineResult> fallbackResults = new ArrayList<>(block.preparedLines().size());
        for (int i = 0; i < block.preparedLines().size(); i++) {
            PreparedTooltipTemplate preparedLine = block.preparedLines().get(i);
            Text originalLine = TooltipTemplateRuntime.renderOriginalPreparedLine(preparedLine);
            if (pending) {
                originalLine = AnimationManager.getAnimatedStyledText(
                        originalLine,
                        block.paragraphTemplate().translationTemplateKey() + "#" + i,
                        false
                );
            }
            fallbackResults.add(new TooltipLineResult(originalLine, pending, missingKeyIssue));
        }
        return new TooltipBlockTranslationAttempt(fallbackResults, pending, missingKeyIssue);
    }

    private static TooltipBlockTranslationAttempt translateParagraphBlockLineByLine(TooltipParagraphBlock block) {
        if (block == null || block.preparedLines() == null || block.preparedLines().isEmpty()) {
            return new TooltipBlockTranslationAttempt(List.of(), false, false);
        }

        List<TooltipLineResult> lineResults = new ArrayList<>(block.preparedLines().size());
        boolean pending = false;
        boolean missingKeyIssue = false;

        for (PreparedTooltipTemplate preparedLine : block.preparedLines()) {
            TooltipLineResult lineResult = TooltipTemplateRuntime.translatePreparedTemplate(preparedLine);
            if (lineResult.pending()) {
                pending = true;
            }
            if (lineResult.missingKeyIssue()) {
                missingKeyIssue = true;
            }
            lineResults.add(lineResult);
        }

        return new TooltipBlockTranslationAttempt(lineResults, pending, missingKeyIssue);
    }

    private static List<TooltipLineResult> renderTranslatedParagraphBlock(
            TooltipParagraphBlock block,
            String translatedBlockTemplate,
            ItemTranslateConfig config,
            boolean emitDevLog,
            String devSource
    ) {
        if (translatedBlockTemplate == null || translatedBlockTemplate.isBlank()) {
            logParagraphRenderIfDev(
                    config,
                    emitDevLog,
                    devSource,
                    block,
                    "reject-empty-provider-template",
                    true,
                    "Cached paragraph template was blank before normalization.",
                    translatedBlockTemplate,
                    "",
                    List.of()
            );
            return null;
        }

        String normalizedTemplate = normalizeParagraphTranslatedTemplate(translatedBlockTemplate);
        if (normalizedTemplate.isBlank()) {
            logParagraphRenderIfDev(
                    config,
                    emitDevLog,
                    devSource,
                    block,
                    "reject-empty-normalized-template",
                    true,
                    "Cached paragraph template became blank after whitespace normalization.",
                    normalizedTemplate,
                    "",
                    List.of()
            );
            return null;
        }

        Text renderedParagraphText = renderTranslatedParagraphText(
                block,
                normalizedTemplate,
                config,
                emitDevLog,
                devSource
        );
        if (renderedParagraphText == null) {
            return null;
        }

        List<Text> wrappedLines = wrapParagraphText(renderedParagraphText, block.paragraphTemplate().wrapWidth());
        if (wrappedLines.isEmpty()) {
            logParagraphRenderIfDev(
                    config,
                    emitDevLog,
                    devSource,
                    block,
                    "reject-empty-wrap",
                    true,
                    "Local tooltip rewrap returned no visible lines.",
                    normalizedTemplate,
                    renderedParagraphText.getString(),
                    List.of()
            );
            return null;
        }

        List<TooltipLineResult> results = new ArrayList<>(wrappedLines.size());
        for (Text wrappedLine : wrappedLines) {
            if (wrappedLine == null) {
                continue;
            }
            results.add(new TooltipLineResult(wrappedLine, false, false));
        }
        if (results.isEmpty()) {
            logParagraphRenderIfDev(
                    config,
                    emitDevLog,
                    devSource,
                    block,
                    "reject-empty-wrap-result",
                    true,
                    "Wrapped paragraph lines were filtered out after reconstruction.",
                    normalizedTemplate,
                    renderedParagraphText.getString(),
                    wrappedLines
            );
            return null;
        }
        boolean allowCompressedLineCount = shouldAllowCompressedParagraphLineCount(config);
        if (paragraphRenderDropsTooManyLines(block.preparedLines().size(), results.size(), allowCompressedLineCount)) {
            int minimumAcceptedLines = minimumAcceptedRenderedLineCount(
                    block.preparedLines().size(),
                    allowCompressedLineCount
            );
            logParagraphRenderIfDev(
                    config,
                    emitDevLog,
                    devSource,
                    block,
                    "reject-line-loss",
                    true,
                    "Paragraph rewrap would compress too aggressively. sourceLines="
                            + block.preparedLines().size()
                            + ", renderedLines="
                            + results.size()
                            + ", minimumAcceptedLines="
                            + minimumAcceptedLines,
                    normalizedTemplate,
                    renderedParagraphText.getString(),
                    wrappedLines
            );
            return null;
        }

        logParagraphRenderIfDev(
                config,
                emitDevLog,
                devSource,
                block,
                "accept",
                false,
                "Rendered paragraph block successfully.",
                normalizedTemplate,
                renderedParagraphText.getString(),
                wrappedLines
        );
        return results;
    }

    static boolean paragraphRenderDropsTooManyLines(
            int sourceLineCount,
            int renderedLineCount,
            boolean allowCompressedLineCount
    ) {
        if (sourceLineCount <= 0 || renderedLineCount < 0) {
            return false;
        }
        return renderedLineCount < minimumAcceptedRenderedLineCount(sourceLineCount, allowCompressedLineCount);
    }

    private static int minimumAcceptedRenderedLineCount(int sourceLineCount, boolean allowCompressedLineCount) {
        if (sourceLineCount <= 0) {
            return 0;
        }
        if (!allowCompressedLineCount) {
            return sourceLineCount;
        }

        // CJK output often preserves meaning while wrapping into about half as many lines.
        return Math.max(1, (sourceLineCount + 1) / 2);
    }

    private static boolean shouldAllowCompressedParagraphLineCount(ItemTranslateConfig config) {
        if (config == null || config.target_language == null) {
            return false;
        }

        String language = config.target_language.toLowerCase(Locale.ROOT);
        return language.contains("chinese") || language.contains("中文") || language.startsWith("zh");
    }

    private static Text renderTranslatedParagraphText(
            TooltipParagraphBlock block,
            String normalizedTemplate,
            ItemTranslateConfig config,
            boolean emitDevLog,
            String devSource
    ) {
        PreparedParagraphTemplate paragraphTemplate = block.paragraphTemplate();
        String reassembledTranslated = TemplateProcessor.reassembleDecorativeGlyphs(
                TemplateProcessor.reassemble(normalizedTemplate, paragraphTemplate.templateValues()),
                paragraphTemplate.glyphValues(),
                true
        );
        reassembledTranslated = postProcessTranslatedParagraphText(
                reassembledTranslated,
                paragraphTemplate.styleMap(),
                paragraphTemplate.bodyStyleId()
        );
        logParagraphStyleMapIfDev(
                config,
                emitDevLog,
                devSource,
                block,
                reassembledTranslated
        );
        if (TooltipTemplateRuntime.containsNumericPlaceholder(reassembledTranslated)) {
            logParagraphRenderIfDev(
                    config,
                    emitDevLog,
                    devSource,
                    block,
                    "reject-unresolved-placeholder",
                    true,
                    "Rendered paragraph still contains unresolved numeric placeholders.",
                    normalizedTemplate,
                    reassembledTranslated,
                    List.of()
            );
            return null;
        }

        String paragraphQualityIssue = describeParagraphQualityIssue(block, reassembledTranslated);
        if (paragraphQualityIssue != null) {
            logParagraphRenderIfDev(
                    config,
                    emitDevLog,
                    devSource,
                    block,
                    "reject-low-quality",
                    true,
                    paragraphQualityIssue,
                    normalizedTemplate,
                    reassembledTranslated,
                    List.of()
            );
            return null;
        }

        Text renderedText = StylePreserver.reapplyStylesFromTags(
                reassembledTranslated,
                paragraphTemplate.styleMap(),
                true
        );
        if (renderedText == null || renderedText.getString().isBlank()) {
            logParagraphRenderIfDev(
                    config,
                    emitDevLog,
                    devSource,
                    block,
                    "reject-empty-styled-text",
                    true,
                    "Style reapplication produced a blank paragraph text.",
                    normalizedTemplate,
                    reassembledTranslated,
                    List.of()
            );
            return null;
        }
        return renderedText;
    }

    private static String postProcessTranslatedParagraphText(
            String translatedText,
            Map<Integer, Style> styleMap,
            Integer bodyStyleId
    ) {
        if (translatedText == null || translatedText.isBlank()) {
            return translatedText;
        }

        boolean applyChineseHeuristics = shouldApplyChineseParagraphQualityHeuristics();
        String normalized = translatedText;
        if (applyChineseHeuristics) {
            normalized = normalizeChineseTaggedWhitespace(normalized);
        }
        normalized = normalizeParagraphTaggedStyles(normalized, styleMap, bodyStyleId);
        return applyChineseHeuristics ? normalizeChineseTaggedWhitespace(normalized) : normalized;
    }

    private static String normalizeParagraphTranslatedTemplate(String translatedBlockTemplate) {
        if (translatedBlockTemplate == null || translatedBlockTemplate.isBlank()) {
            return "";
        }

        String normalized = translatedBlockTemplate
                .replace("\r\n", " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
        return normalized.replaceAll("\\s{2,}", " ");
    }

    private static String normalizeChineseTaggedWhitespace(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder normalized = new StringBuilder(text.length());
        int lastVisibleCodePoint = -1;
        for (int index = 0; index < text.length(); ) {
            int styleTagEnd = findStyleTagEnd(text, index);
            if (styleTagEnd >= index) {
                normalized.append(text, index, styleTagEnd + 1);
                index = styleTagEnd + 1;
                continue;
            }

            int codePoint = text.codePointAt(index);
            int charCount = Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                int nextIndex = index + charCount;
                while (nextIndex < text.length()) {
                    int nextStyleTagEnd = findStyleTagEnd(text, nextIndex);
                    if (nextStyleTagEnd >= nextIndex) {
                        nextIndex = nextStyleTagEnd + 1;
                        continue;
                    }

                    int nextCodePoint = text.codePointAt(nextIndex);
                    if (!Character.isWhitespace(nextCodePoint)) {
                        break;
                    }
                    nextIndex += Character.charCount(nextCodePoint);
                }

                int nextVisibleCodePoint = findNextVisibleCodePoint(text, nextIndex);
                if (!shouldStripChineseWhitespace(lastVisibleCodePoint, nextVisibleCodePoint)
                        && normalized.length() > 0
                        && normalized.charAt(normalized.length() - 1) != ' ') {
                    normalized.append(' ');
                }
                index = nextIndex;
                continue;
            }

            normalized.appendCodePoint(codePoint);
            lastVisibleCodePoint = codePoint;
            index += charCount;
        }

        return normalized.toString().trim();
    }

    private static String normalizeParagraphTaggedStyles(String text, Map<Integer, Style> styleMap, Integer bodyStyleId) {
        if (text == null
                || text.isBlank()
                || styleMap == null
                || styleMap.isEmpty()
                || text.indexOf('<') < 0) {
            return text;
        }

        List<ParagraphTaggedRun> runs = parseParagraphTaggedRuns(text);
        if (runs.size() <= 1) {
            return text;
        }

        if (TooltipTranslationContext.shouldRequireStrictParagraphStyleCoverage()) {
            canonicalizeEquivalentParagraphStyles(runs, styleMap);
        }
        absorbParagraphBodyStyle(runs, styleMap, bodyStyleId);
        return serializeParagraphTaggedRuns(runs);
    }

    private static List<ParagraphTaggedRun> parseParagraphTaggedRuns(String text) {
        List<ParagraphTaggedRun> runs = new ArrayList<>();
        Matcher matcher = TooltipTemplateRuntime.STYLE_TAG_ID_PATTERN.matcher(text);
        int lastEnd = 0;
        Integer activeStyleId = null;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                addParagraphTaggedRun(runs, activeStyleId, text.substring(lastEnd, matcher.start()));
            }

            int styleId = Integer.parseInt(matcher.group(1));
            boolean closingTag = text.charAt(matcher.start() + 1) == '/';
            activeStyleId = closingTag ? null : styleId;
            lastEnd = matcher.end();
        }

        if (lastEnd < text.length()) {
            addParagraphTaggedRun(runs, activeStyleId, text.substring(lastEnd));
        }
        return runs;
    }

    private static void addParagraphTaggedRun(List<ParagraphTaggedRun> runs, Integer styleId, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }

        if (!runs.isEmpty()) {
            int lastIndex = runs.size() - 1;
            ParagraphTaggedRun previous = runs.get(lastIndex);
            if (Objects.equals(previous.styleId, styleId)) {
                runs.set(lastIndex, new ParagraphTaggedRun(styleId, previous.content + content));
                return;
            }
        }

        runs.add(new ParagraphTaggedRun(styleId, content));
    }

    private static void canonicalizeEquivalentParagraphStyles(
            List<ParagraphTaggedRun> runs,
            Map<Integer, Style> styleMap
    ) {
        Map<Style, Integer> canonicalStyleIdsByVisualStyle = new HashMap<>();
        for (ParagraphTaggedRun run : runs) {
            if (run == null || run.styleId == null) {
                continue;
            }

            Style visualStyle = StylePreserver.sanitizeStyleForComparison(
                    styleMap.getOrDefault(run.styleId, Style.EMPTY),
                    true
            );
            Integer canonicalStyleId = canonicalStyleIdsByVisualStyle.putIfAbsent(visualStyle, run.styleId);
            if (canonicalStyleId != null) {
                run.styleId = canonicalStyleId;
            }
        }
    }

    private static void absorbParagraphBodyStyle(
            List<ParagraphTaggedRun> runs,
            Map<Integer, Style> styleMap,
            Integer preferredBodyStyleId
    ) {
        Integer dominantStyleId = preferredBodyStyleId == null
                ? findDominantParagraphBodyStyleId(runs, styleMap)
                : findCanonicalParagraphStyleId(preferredBodyStyleId, runs, styleMap);
        if (dominantStyleId == null) {
            return;
        }

        for (int index = 0; index < runs.size(); index++) {
            ParagraphTaggedRun run = runs.get(index);
            if (run == null || run.styleId != null) {
                continue;
            }
            if (!shouldAbsorbPlainRunIntoParagraphBody(runs, index, dominantStyleId)) {
                continue;
            }
            run.styleId = dominantStyleId;
        }
    }

    static Integer findDominantParagraphBodyStyleId(String taggedText, Map<Integer, Style> styleMap) {
        if (taggedText == null || taggedText.isBlank() || styleMap == null || styleMap.isEmpty()) {
            return null;
        }

        List<ParagraphTaggedRun> runs = parseParagraphTaggedRuns(taggedText);
        if (runs.isEmpty()) {
            return null;
        }
        canonicalizeEquivalentParagraphStyles(runs, styleMap);
        return findDominantParagraphBodyStyleId(runs, styleMap);
    }

    private static Integer findDominantParagraphBodyStyleId(List<ParagraphTaggedRun> runs, Map<Integer, Style> styleMap) {
        Map<Integer, Integer> bodyScoreByStyleId = new HashMap<>();
        int totalBodyScore = 0;
        for (ParagraphTaggedRun run : runs) {
            if (run == null || run.styleId == null) {
                continue;
            }

            int bodyScore = scoreParagraphBodyText(run.content);
            if (bodyScore <= 0) {
                continue;
            }
            bodyScoreByStyleId.merge(run.styleId, bodyScore, Integer::sum);
            totalBodyScore += bodyScore;
        }
        if (totalBodyScore <= 0 || bodyScoreByStyleId.isEmpty()) {
            return null;
        }

        int dominantStyleId = -1;
        int dominantStyleScore = 0;
        for (Map.Entry<Integer, Integer> entry : bodyScoreByStyleId.entrySet()) {
            if (entry.getValue() > dominantStyleScore) {
                dominantStyleId = entry.getKey();
                dominantStyleScore = entry.getValue();
            }
        }
        if (dominantStyleId < 0
                || dominantStyleScore < MIN_PARAGRAPH_BODY_STYLE_DOMINANT_SCORE
                || dominantStyleScore * 100 < totalBodyScore * MIN_PARAGRAPH_BODY_STYLE_DOMINANCE_PERCENT) {
            return null;
        }
        return dominantStyleId;
    }

    private static Integer findCanonicalParagraphStyleId(
            Integer preferredStyleId,
            List<ParagraphTaggedRun> runs,
            Map<Integer, Style> styleMap
    ) {
        if (preferredStyleId == null || runs == null || runs.isEmpty() || styleMap == null || styleMap.isEmpty()) {
            return preferredStyleId;
        }

        Style preferredVisualStyle = StylePreserver.sanitizeStyleForComparison(
                styleMap.getOrDefault(preferredStyleId, Style.EMPTY),
                true
        );
        for (ParagraphTaggedRun run : runs) {
            if (run == null || run.styleId == null) {
                continue;
            }
            Style runVisualStyle = StylePreserver.sanitizeStyleForComparison(
                    styleMap.getOrDefault(run.styleId, Style.EMPTY),
                    true
            );
            if (preferredVisualStyle.equals(runVisualStyle)) {
                return run.styleId;
            }
        }
        return preferredStyleId;
    }

    private static boolean shouldAbsorbPlainRunIntoParagraphBody(
            List<ParagraphTaggedRun> runs,
            int index,
            int dominantStyleId
    ) {
        ParagraphTaggedRun run = runs.get(index);
        if (run == null || run.styleId != null) {
            return false;
        }
        return scoreParagraphBodyText(run.content) >= MIN_PARAGRAPH_BODY_RUN_SCORE;
    }

    private static Integer findAdjacentStyledRunStyleId(List<ParagraphTaggedRun> runs, int startIndex, int direction) {
        for (int index = startIndex + direction; index >= 0 && index < runs.size(); index += direction) {
            ParagraphTaggedRun run = runs.get(index);
            if (run == null || run.content == null || run.content.isBlank()) {
                continue;
            }
            if (run.styleId != null) {
                return run.styleId;
            }
        }
        return null;
    }

    private static int scoreParagraphBodyText(String text) {
        if (text == null || text.isBlank() || containsDigit(text) || TooltipTemplateRuntime.containsDecorativeGlyph(text)) {
            return 0;
        }

        int letterCount = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            if (Character.isLetter(codePoint)) {
                letterCount++;
                continue;
            }
            if (!isParagraphBodyPunctuation(codePoint)) {
                return 0;
            }
        }
        return letterCount;
    }

    private static boolean isParagraphBodyPunctuation(int codePoint) {
        return codePoint == '.'
                || codePoint == ','
                || codePoint == '!'
                || codePoint == '?'
                || codePoint == ';'
                || codePoint == ':'
                || codePoint == '。'
                || codePoint == '，'
                || codePoint == '！'
                || codePoint == '？'
                || codePoint == '；'
                || codePoint == '：'
                || codePoint == '\''
                || codePoint == '"'
                || codePoint == '‘'
                || codePoint == '’'
                || codePoint == '“'
                || codePoint == '”'
                || codePoint == '-'
                || codePoint == '–'
                || codePoint == '—'
                || codePoint == '('
                || codePoint == ')'
                || codePoint == '（'
                || codePoint == '）'
                || codePoint == '['
                || codePoint == ']'
                || codePoint == '【'
                || codePoint == '】'
                || codePoint == '<'
                || codePoint == '>'
                || codePoint == '《'
                || codePoint == '》'
                || codePoint == '、';
    }

    private static String serializeParagraphTaggedRuns(List<ParagraphTaggedRun> runs) {
        if (runs == null || runs.isEmpty()) {
            return "";
        }

        StringBuilder serialized = new StringBuilder();
        Integer activeStyleId = null;
        for (ParagraphTaggedRun run : runs) {
            if (run == null || run.content == null || run.content.isEmpty()) {
                continue;
            }

            if (!Objects.equals(activeStyleId, run.styleId)) {
                if (activeStyleId != null) {
                    serialized.append("</s").append(activeStyleId).append(">");
                }
                if (run.styleId != null) {
                    serialized.append("<s").append(run.styleId).append(">");
                }
                activeStyleId = run.styleId;
            }
            serialized.append(run.content);
        }

        if (activeStyleId != null) {
            serialized.append("</s").append(activeStyleId).append(">");
        }
        return serialized.toString();
    }

    private static int findStyleTagEnd(String text, int startIndex) {
        if (text == null
                || startIndex < 0
                || startIndex >= text.length()
                || text.charAt(startIndex) != '<') {
            return -1;
        }

        int endIndex = text.indexOf('>', startIndex);
        if (endIndex < 0) {
            return -1;
        }

        String candidate = text.substring(startIndex, endIndex + 1);
        return TooltipTemplateRuntime.STYLE_TAG_ID_PATTERN.matcher(candidate).matches() ? endIndex : -1;
    }

    private static String stripParagraphStyleTags(String text) {
        if (text == null || text.isEmpty() || text.indexOf('<') < 0) {
            return text;
        }

        StringBuilder stripped = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); ) {
            int styleTagEnd = findStyleTagEnd(text, index);
            if (styleTagEnd >= index) {
                index = styleTagEnd + 1;
                continue;
            }

            int codePoint = text.codePointAt(index);
            stripped.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }
        return stripped.toString();
    }

    private static int findNextVisibleCodePoint(String text, int startIndex) {
        if (text == null || startIndex < 0) {
            return -1;
        }

        for (int index = startIndex; index < text.length(); ) {
            int styleTagEnd = findStyleTagEnd(text, index);
            if (styleTagEnd >= index) {
                index = styleTagEnd + 1;
                continue;
            }

            int codePoint = text.codePointAt(index);
            if (!Character.isWhitespace(codePoint)) {
                return codePoint;
            }
            index += Character.charCount(codePoint);
        }
        return -1;
    }

    private static boolean shouldStripChineseWhitespace(int previousCodePoint, int nextCodePoint) {
        if (previousCodePoint < 0 || nextCodePoint < 0) {
            return false;
        }

        return isChineseTightPunctuation(previousCodePoint)
                || isChineseTightPunctuation(nextCodePoint)
                || (isCjkCodePoint(previousCodePoint) && isCjkCodePoint(nextCodePoint))
                || (isCjkCodePoint(previousCodePoint) && isChineseNumericLikeCodePoint(nextCodePoint))
                || (isChineseNumericLikeCodePoint(previousCodePoint) && isCjkCodePoint(nextCodePoint));
    }

    private static boolean isChineseNumericLikeCodePoint(int codePoint) {
        return Character.isDigit(codePoint)
                || codePoint == '+'
                || codePoint == '-'
                || codePoint == '.'
                || codePoint == ','
                || codePoint == '%';
    }

    private static boolean isChineseTightPunctuation(int codePoint) {
        return codePoint == '。'
                || codePoint == '，'
                || codePoint == '、'
                || codePoint == '：'
                || codePoint == '；'
                || codePoint == '！'
                || codePoint == '？'
                || codePoint == ')'
                || codePoint == '）'
                || codePoint == ']'
                || codePoint == '】'
                || codePoint == '}'
                || codePoint == '》'
                || codePoint == '>'
                || codePoint == '＜'
                || codePoint == '《'
                || codePoint == '('
                || codePoint == '（'
                || codePoint == '['
                || codePoint == '【';
    }

    private static boolean isCjkCodePoint(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static List<Text> wrapParagraphText(Text paragraphText, int wrapWidth) {
        if (paragraphText == null || paragraphText.getString().isBlank()) {
            return List.of();
        }

        TextRenderer textRenderer = getTooltipTextRenderer();
        if (textRenderer == null || wrapWidth <= 0) {
            return List.of(paragraphText);
        }

        List<OrderedText> wrappedOrderedLines = textRenderer.wrapLines(paragraphText, wrapWidth);
        if (wrappedOrderedLines == null || wrappedOrderedLines.isEmpty()) {
            return List.of(paragraphText);
        }

        List<Text> wrappedLines = new ArrayList<>(wrappedOrderedLines.size());
        for (OrderedText orderedLine : wrappedOrderedLines) {
            Text wrappedLine = orderedTextToText(orderedLine);
            if (wrappedLine != null) {
                wrappedLines.add(wrappedLine);
            }
        }
        return wrappedLines.isEmpty() ? List.of(paragraphText) : wrappedLines;
    }

    private static TextRenderer getTooltipTextRenderer() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client == null ? null : client.textRenderer;
    }

    private static Text orderedTextToText(OrderedText orderedText) {
        if (orderedText == null) {
            return Text.empty();
        }

        MutableText result = Text.empty();
        StringBuilder currentSegment = new StringBuilder();
        Style[] currentStyle = {Style.EMPTY};

        orderedText.accept((index, style, codePoint) -> {
            if (!style.equals(currentStyle[0]) && currentSegment.length() > 0) {
                result.append(Text.literal(currentSegment.toString()).setStyle(currentStyle[0]));
                currentSegment.setLength(0);
            }
            currentStyle[0] = style;
            currentSegment.appendCodePoint(codePoint);
            return true;
        });

        if (currentSegment.length() > 0) {
            result.append(Text.literal(currentSegment.toString()).setStyle(currentStyle[0]));
        }
        return result;
    }

    private static String describeParagraphQualityIssue(
            TooltipParagraphBlock block,
            String translatedTemplate
    ) {
        if (!shouldApplyChineseParagraphQualityHeuristics() || translatedTemplate == null || translatedTemplate.isBlank()) {
            return null;
        }

        String visibleText = TooltipRoutePlanner.normalizeTooltipText(stripParagraphStyleTags(translatedTemplate));
        if (visibleText.isEmpty() || !containsCjk(visibleText)) {
            return null;
        }

        String connector = findStandaloneEnglishConnector(visibleText);
        if (connector != null) {
            return "Rendered paragraph still contains standalone English connector: " + connector;
        }

        String styleCoverageIssue = describeParagraphStyleCoverageIssue(block, translatedTemplate);
        if (styleCoverageIssue != null) {
            return styleCoverageIssue;
        }

        int sourceLetterCount = 0;
        for (PreparedTooltipTemplate preparedLine : block.preparedLines()) {
            if (preparedLine == null || preparedLine.sourceLine() == null) {
                continue;
            }
            sourceLetterCount += countAsciiLetters(preparedLine.sourceLine().getString());
        }

        int translatedCjkCount = countCjk(visibleText);
        int translatedSignalCount = countTranslatedSignalUnits(visibleText);
        if (paragraphLooksTooShortForChineseOutput(sourceLetterCount, translatedSignalCount)) {
            return "Rendered paragraph looks too short for Chinese output (sourceLetters="
                    + sourceLetterCount
                    + ", translatedSignal="
                    + translatedSignalCount
                    + ", translatedCjk="
                    + translatedCjkCount
                    + ").";
        }
        return null;
    }

    static boolean paragraphLooksTooShortForChineseOutput(int sourceLetterCount, int translatedSignalCount) {
        return sourceLetterCount >= 18
                && translatedSignalCount > 0
                && translatedSignalCount * 3 < sourceLetterCount;
    }

    private static String describeParagraphStyleCoverageIssue(
            TooltipParagraphBlock block,
            String translatedTemplate
    ) {
        if (block == null
                || block.preparedLines() == null
                || block.preparedLines().isEmpty()
                || block.paragraphTemplate() == null) {
            return null;
        }

        LinkedHashMap<Style, Integer> sourceScores = collectSourceParagraphVisualStyleScores(block);
        if (sourceScores.isEmpty()) {
            return null;
        }

        LinkedHashMap<Style, Integer> translatedScores = collectTaggedParagraphVisualStyleScores(
                translatedTemplate,
                block.paragraphTemplate().styleMap()
        );
        List<Style> missingStyles = new ArrayList<>();
        for (Style sourceStyle : sourceScores.keySet()) {
            if (!translatedScores.containsKey(sourceStyle)) {
                missingStyles.add(sourceStyle);
            }
        }
        if (missingStyles.isEmpty()) {
            return null;
        }
        if (shouldAllowRelaxedParagraphStyleCoverage(block, sourceScores, translatedScores)) {
            return null;
        }

        return "Rendered paragraph dropped significant visual style(s). missing="
                + summarizeParagraphVisualStyles(missingStyles)
                + " source="
                + summarizeParagraphVisualStyles(sourceScores.keySet())
                + " translated="
                + summarizeParagraphVisualStyles(translatedScores.keySet());
    }

    private static boolean shouldAllowRelaxedParagraphStyleCoverage(
            TooltipParagraphBlock block,
            LinkedHashMap<Style, Integer> sourceScores,
            LinkedHashMap<Style, Integer> translatedScores
    ) {
        if (TooltipTranslationContext.shouldRequireStrictParagraphStyleCoverage()
                || sourceScores == null
                || sourceScores.isEmpty()
                || translatedScores == null
                || translatedScores.isEmpty()) {
            return false;
        }

        Style bodyStyle = resolveParagraphBodyVisualStyle(block);
        boolean translatedHasBodyStyle = bodyStyle == null || translatedScores.containsKey(bodyStyle);
        int sourceAccentStyleCount = countNonBodyParagraphStyles(sourceScores.keySet(), bodyStyle);
        int translatedAccentStyleCount = countNonBodyParagraphStyles(translatedScores.keySet(), bodyStyle);
        if (allowsRelaxedParagraphStyleCoverage(
                false,
                translatedHasBodyStyle,
                sourceAccentStyleCount,
                translatedAccentStyleCount
        )) {
            return true;
        }

        int maxMissingAccentScore = 0;
        int totalMissingAccentScore = 0;
        for (Map.Entry<Style, Integer> entry : sourceScores.entrySet()) {
            Style sourceStyle = entry.getKey();
            if (sourceStyle == null
                    || (bodyStyle != null && bodyStyle.equals(sourceStyle))
                    || translatedScores.containsKey(sourceStyle)) {
                continue;
            }

            int score = entry.getValue() == null ? 0 : entry.getValue();
            if (score <= 0) {
                continue;
            }
            totalMissingAccentScore += score;
            if (score > maxMissingAccentScore) {
                maxMissingAccentScore = score;
            }
        }
        return allowsRelaxedCompleteAccentLoss(
                false,
                translatedHasBodyStyle,
                translatedAccentStyleCount,
                maxMissingAccentScore,
                totalMissingAccentScore
        );
    }

    static boolean allowsRelaxedParagraphStyleCoverage(
            boolean strictParagraphStyleCoverage,
            boolean translatedHasBodyStyle,
            int sourceAccentStyleCount,
            int translatedAccentStyleCount
    ) {
        if (strictParagraphStyleCoverage || !translatedHasBodyStyle) {
            return false;
        }
        if (sourceAccentStyleCount <= 0) {
            return true;
        }
        return translatedAccentStyleCount > 0;
    }

    static boolean allowsRelaxedCompleteAccentLoss(
            boolean strictParagraphStyleCoverage,
            boolean translatedHasBodyStyle,
            int translatedAccentStyleCount,
            int maxMissingAccentScore,
            int totalMissingAccentScore
    ) {
        if (strictParagraphStyleCoverage || !translatedHasBodyStyle || translatedAccentStyleCount > 0) {
            return false;
        }
        return maxMissingAccentScore > 0
                && maxMissingAccentScore <= MAX_NON_WYNN_MISSING_STYLE_SCORE
                && totalMissingAccentScore <= MAX_NON_WYNN_TOTAL_MISSING_STYLE_SCORE;
    }

    private static boolean shouldApplyChineseParagraphQualityHeuristics() {
        ItemTranslateConfig config = Translate_AllinOne.getConfig().itemTranslate;
        if (config == null || config.target_language == null) {
            return false;
        }

        String language = config.target_language.toLowerCase(Locale.ROOT);
        return language.contains("chinese") || language.contains("中文") || language.startsWith("zh");
    }

    private static boolean containsStandaloneEnglishConnector(String text) {
        return text != null && ENGLISH_CONNECTOR_PATTERN.matcher(text).find();
    }

    private static String findStandaloneEnglishConnector(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Matcher matcher = ENGLISH_CONNECTOR_PATTERN.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private static int countAsciiLetters(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if ((current >= 'A' && current <= 'Z') || (current >= 'a' && current <= 'z')) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsCjk(String text) {
        return countCjk(text) > 0;
    }

    private static int countCjk(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                count++;
            }
            offset += Character.charCount(codePoint);
        }
        return count;
    }

    static int countTranslatedSignalUnits(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (isMeaningfulTranslatedSignalCodePoint(codePoint)) {
                count++;
            }
            offset += Character.charCount(codePoint);
        }
        return count;
    }

    private static boolean isMeaningfulTranslatedSignalCodePoint(int codePoint) {
        if (isCjkCodePoint(codePoint)
                || Character.isDigit(codePoint)
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= 'a' && codePoint <= 'z')) {
            return true;
        }

        int type = Character.getType(codePoint);
        return type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL
                || codePoint == '%';
    }

    private static Style resolveParagraphBodyVisualStyle(TooltipParagraphBlock block) {
        if (block == null || block.paragraphTemplate() == null || block.paragraphTemplate().bodyStyleId() == null) {
            return null;
        }

        Map<Integer, Style> styleMap = block.paragraphTemplate().styleMap();
        if (styleMap == null || styleMap.isEmpty()) {
            return null;
        }

        return StylePreserver.sanitizeStyleForComparison(
                styleMap.getOrDefault(block.paragraphTemplate().bodyStyleId(), Style.EMPTY),
                true
        );
    }

    private static int countNonBodyParagraphStyles(Iterable<Style> styles, Style bodyStyle) {
        if (styles == null) {
            return 0;
        }

        int count = 0;
        for (Style style : styles) {
            if (style == null || (bodyStyle != null && bodyStyle.equals(style))) {
                continue;
            }
            count++;
        }
        return count;
    }

    private static LinkedHashMap<Style, Integer> collectSourceParagraphVisualStyleScores(TooltipParagraphBlock block) {
        LinkedHashMap<Style, Integer> scores = new LinkedHashMap<>();
        if (block == null || block.preparedLines() == null) {
            return scores;
        }

        for (PreparedTooltipTemplate preparedLine : block.preparedLines()) {
            if (preparedLine == null || preparedLine.styleResult() == null) {
                continue;
            }
            mergeTaggedParagraphVisualStyleScores(
                    scores,
                    preparedLine.styleResult().markedText,
                    preparedLine.styleResult().styleMap
            );
        }
        return scores;
    }

    private static LinkedHashMap<Style, Integer> collectTaggedParagraphVisualStyleScores(
            String taggedText,
            Map<Integer, Style> styleMap
    ) {
        LinkedHashMap<Style, Integer> scores = new LinkedHashMap<>();
        mergeTaggedParagraphVisualStyleScores(scores, taggedText, styleMap);
        return scores;
    }

    private static void mergeTaggedParagraphVisualStyleScores(
            Map<Style, Integer> scores,
            String taggedText,
            Map<Integer, Style> styleMap
    ) {
        if (scores == null
                || taggedText == null
                || taggedText.isBlank()
                || styleMap == null
                || styleMap.isEmpty()) {
            return;
        }

        for (ParagraphTaggedRun run : parseParagraphTaggedRuns(taggedText)) {
            if (run == null || run.styleId == null || run.content == null || run.content.isBlank()) {
                continue;
            }

            int score = scoreSignificantStyledParagraphContent(run.content);
            if (score <= 0) {
                continue;
            }

            Style visualStyle = StylePreserver.sanitizeStyleForComparison(
                    styleMap.getOrDefault(run.styleId, Style.EMPTY),
                    true
            );
            if (visualStyle.isEmpty()) {
                continue;
            }
            scores.merge(visualStyle, score, Integer::sum);
        }
    }

    private static int scoreSignificantStyledParagraphContent(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        int score = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (Character.isWhitespace(codePoint) || TooltipTemplateRuntime.containsDecorativeGlyph(Character.toString(codePoint))) {
                continue;
            }
            if (Character.isLetterOrDigit(codePoint) || isCjkCodePoint(codePoint)) {
                score++;
            }
        }
        return score;
    }

    private static String summarizeParagraphVisualStyles(Iterable<Style> styles) {
        if (styles == null) {
            return "[]";
        }

        StringBuilder summary = new StringBuilder("[");
        boolean first = true;
        for (Style style : styles) {
            if (style == null) {
                continue;
            }
            if (!first) {
                summary.append("; ");
            }
            summary.append(describeStyleForLog(style, false));
            first = false;
        }
        summary.append(']');
        return summary.toString();
    }

    static String extractTemplateKeyForLine(Text line, boolean useTagStylePreservation) {
        return TooltipTemplateRuntime.extractTemplateKeyForLine(line, useTagStylePreservation);
    }

    private static void logParagraphRenderIfDev(
            ItemTranslateConfig config,
            boolean emitDevLog,
            String source,
            TooltipParagraphBlock block,
            String phase,
            boolean forceRefresh,
            String detail,
            String normalizedTemplate,
            String renderedText,
            List<Text> wrappedLines
    ) {
        if (!emitDevLog || !TooltipTextMatcherSupport.shouldLogTooltipParagraphResult(config) || block == null) {
            return;
        }

        LOGGER.info(
                "[TooltipDev:{}:paragraph] lines={}-{} phase={} forceRefresh={} key=\"{}\" source=\"{}\" provider=\"{}\" rendered=\"{}\" wrappedLines={} wrapped=\"{}\" detail=\"{}\"",
                source,
                block.startLineIndex() + 1,
                block.endLineIndex() + 1,
                phase,
                forceRefresh,
                truncateForLog(block.paragraphTemplate() == null ? "" : block.paragraphTemplate().translationTemplateKey(), 220),
                truncateForLog(summarizeParagraphSource(block), 220),
                truncateForLog(normalizedTemplate, 220),
                truncateForLog(renderedText, 220),
                wrappedLines == null ? 0 : wrappedLines.size(),
                truncateForLog(summarizeWrappedLines(wrappedLines), 220),
                truncateForLog(detail, 220)
        );
    }

    private static void logParagraphStyleMapIfDev(
            ItemTranslateConfig config,
            boolean emitDevLog,
            String source,
            TooltipParagraphBlock block,
            String taggedText
    ) {
        if (!emitDevLog
                || !TooltipTextMatcherSupport.shouldLogTooltipStyleMap(config)
                || block == null
                || block.paragraphTemplate() == null) {
            return;
        }

        Map<Integer, Style> styleMap = block.paragraphTemplate().styleMap();
        LinkedHashSet<Integer> usedStyleIds = collectUsedStyleIdsFromTaggedText(taggedText);
        LOGGER.info(
                "[TooltipDev:{}:style-map] lines={}-{} styleCount={} usedStyleIds={} styles=\"{}\" tagged=\"{}\"",
                source,
                block.startLineIndex() + 1,
                block.endLineIndex() + 1,
                styleMap == null ? 0 : styleMap.size(),
                summarizeStyleIdSet(usedStyleIds),
                truncateForLog(summarizeStyleMapForLog(styleMap, usedStyleIds), 700),
                truncateForLog(taggedText, 320)
        );
    }

    private static LinkedHashSet<Integer> collectUsedStyleIdsFromTaggedText(String taggedText) {
        LinkedHashSet<Integer> usedStyleIds = new LinkedHashSet<>();
        if (taggedText == null || taggedText.isBlank()) {
            return usedStyleIds;
        }

        Matcher matcher = TooltipTemplateRuntime.STYLE_TAG_ID_PATTERN.matcher(taggedText);
        while (matcher.find()) {
            usedStyleIds.add(Integer.parseInt(matcher.group(1)));
        }
        return usedStyleIds;
    }

    private static String summarizeStyleIdSet(Set<Integer> styleIds) {
        if (styleIds == null || styleIds.isEmpty()) {
            return "[]";
        }

        StringBuilder summary = new StringBuilder("[");
        boolean first = true;
        for (Integer styleId : styleIds) {
            if (!first) {
                summary.append(',');
            }
            summary.append(styleId == null ? "null" : styleId);
            first = false;
        }
        summary.append(']');
        return summary.toString();
    }

    private static String summarizeStyleMapForLog(Map<Integer, Style> styleMap, Set<Integer> usedStyleIds) {
        if (styleMap == null || styleMap.isEmpty()) {
            return "";
        }

        List<Integer> orderedStyleIds = new ArrayList<>();
        if (usedStyleIds != null && !usedStyleIds.isEmpty()) {
            orderedStyleIds.addAll(usedStyleIds);
        } else {
            orderedStyleIds.addAll(styleMap.keySet());
            orderedStyleIds.sort(Integer::compareTo);
        }

        StringBuilder summary = new StringBuilder();
        for (Integer styleId : orderedStyleIds) {
            if (styleId == null) {
                continue;
            }

            if (!summary.isEmpty()) {
                summary.append("; ");
            }
            Style rawStyle = styleMap.getOrDefault(styleId, Style.EMPTY);
            Style visualStyle = StylePreserver.sanitizeStyleForComparison(rawStyle, true);
            summary.append(styleId)
                    .append("={raw:")
                    .append(describeStyleForLog(rawStyle, true))
                    .append(",visual:")
                    .append(describeStyleForLog(visualStyle, false))
                    .append('}');
        }
        return summary.toString();
    }

    private static String describeStyleForLog(Style style, boolean includeFont) {
        if (style == null) {
            style = Style.EMPTY;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("color=").append(formatStyleColorForLog(style))
                .append(",b=").append(style.isBold() ? '1' : '0')
                .append(",i=").append(style.isItalic() ? '1' : '0')
                .append(",u=").append(style.isUnderlined() ? '1' : '0')
                .append(",s=").append(style.isStrikethrough() ? '1' : '0')
                .append(",o=").append(style.isObfuscated() ? '1' : '0');
        if (includeFont) {
            summary.append(",font=")
                    .append(style.getFont() == null ? "-" : style.getFont());
        }
        return summary.toString();
    }

    private static String formatStyleColorForLog(Style style) {
        if (style == null || style.getColor() == null) {
            return "-";
        }

        int rgb = style.getColor().getRgb() & 0xFFFFFF;
        return String.format(Locale.ROOT, "#%06X", rgb);
    }

    private static String truncateForLog(String value, int maxLength) {
        if (value == null) {
            return "";
        }

        String normalized = value
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String summarizeParagraphSource(TooltipParagraphBlock block) {
        if (block == null || block.preparedLines() == null || block.preparedLines().isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (PreparedTooltipTemplate preparedLine : block.preparedLines()) {
            if (preparedLine == null || preparedLine.sourceLine() == null) {
                continue;
            }

            String raw = TooltipRoutePlanner.normalizeTooltipText(preparedLine.sourceLine().getString());
            if (raw.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" / ");
            }
            builder.append(raw);
        }
        return builder.toString();
    }

    private static String summarizeWrappedLines(List<Text> wrappedLines) {
        if (wrappedLines == null || wrappedLines.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (Text wrappedLine : wrappedLines) {
            if (wrappedLine == null) {
                continue;
            }

            String raw = TooltipRoutePlanner.normalizeTooltipText(wrappedLine.getString());
            if (raw.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(raw);
        }
        return builder.toString();
    }

    private static int computeTooltipSignature(Set<String> keys) {
        int hash = 1;
        for (String key : keys) {
            hash = 31 * hash + key.hashCode();
        }
        return 31 * hash + keys.size();
    }
}
