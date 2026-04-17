package com.cedarxuesong.translate_allinone.utils.translate;

import com.cedarxuesong.translate_allinone.utils.textmatcher.ContentMatcher;
import com.cedarxuesong.translate_allinone.utils.textmatcher.FlatNode;
import com.cedarxuesong.translate_allinone.utils.textmatcher.TextMatchResult;
import com.cedarxuesong.translate_allinone.utils.textmatcher.TextPattern;
import net.minecraft.text.MutableText;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

final class TooltipStructuredCaptureSupport {
    private static final TextPattern STRUCTURED_LABEL_VALUE_PATTERN = TextPattern.builder()
            .oneOrMore(
                    ContentMatcher.any(),
                    "label",
                    nodePredicate(TooltipStructuredCaptureSupport::isStructuredLabelSegment)
            )
            .one(
                    ContentMatcher.text(":"),
                    "colon",
                    nodePredicate(node -> true)
            )
            .zeroOrMore(
                    ContentMatcher.any(),
                    "spacing",
                    nodePredicate(TooltipStructuredCaptureSupport::isWhitespaceOnlySegment),
                    true
            )
            .oneOrMore(
                    ContentMatcher.any(),
                    "value",
                    nodePredicate(TooltipStructuredCaptureSupport::isStructuredGenericValueSegment)
            )
            .build();

    private TooltipStructuredCaptureSupport() {
    }

    static StructuredTooltipLineResult tryTranslateStructuredLine(Text line, boolean useTagStylePreservation) {
        StructuredLineMatch structuredLine = matchStructuredLine(line, useTagStylePreservation);
        if (structuredLine == null) {
            return null;
        }

        TooltipTranslationSupport.TooltipLineResult labelTranslation =
                TooltipTranslationSupport.translateLine(structuredLine.labelTranslationText(), useTagStylePreservation);
        if (isErrorTranslation(labelTranslation.translatedLine())) {
            return new StructuredTooltipLineResult(
                    labelTranslation,
                    structuredLine.translationTemplateKeys(),
                    buildDebugSummary(structuredLine)
            );
        }

        TooltipTranslationSupport.TooltipLineResult valueTranslation = null;
        if (structuredLine.kind().translateValue()) {
            valueTranslation = TooltipTranslationSupport.translateLine(structuredLine.valueTranslationText(), useTagStylePreservation);
            if (isErrorTranslation(valueTranslation.translatedLine())) {
                return new StructuredTooltipLineResult(
                        valueTranslation,
                        structuredLine.translationTemplateKeys(),
                        buildDebugSummary(structuredLine)
                );
            }
        }

        MutableText combined = Text.empty();
        combined.append(labelTranslation.translatedLine());
        if (structuredLine.colonText() != null) {
            combined.append(structuredLine.colonText());
        }
        if (structuredLine.spacingText() != null) {
            combined.append(structuredLine.spacingText());
        }
        combined.append(valueTranslation == null ? structuredLine.valueText() : valueTranslation.translatedLine());
        return new StructuredTooltipLineResult(
                new TooltipTranslationSupport.TooltipLineResult(
                        combined,
                        labelTranslation.pending() || (valueTranslation != null && valueTranslation.pending()),
                        labelTranslation.missingKeyIssue() || (valueTranslation != null && valueTranslation.missingKeyIssue())
                ),
                structuredLine.translationTemplateKeys(),
                buildDebugSummary(structuredLine)
        );
    }

    static Set<String> collectStructuredTemplateKeys(Text line, boolean useTagStylePreservation) {
        StructuredLineMatch structuredLine = matchStructuredLine(line, useTagStylePreservation);
        if (structuredLine == null) {
            return Set.of();
        }
        return structuredLine.translationTemplateKeys();
    }

    private static StructuredLineMatch matchStructuredLine(Text line, boolean useTagStylePreservation) {
        if (line == null) {
            return null;
        }

        List<FlatNode> splitNodes = splitInlineNodes(line);
        if (splitNodes.isEmpty()) {
            return null;
        }

        TextMatchResult match = STRUCTURED_LABEL_VALUE_PATTERN.match(splitNodes);
        if (!match.success()) {
            return null;
        }

        List<FlatNode> labelNodes = trimWhitespaceNodes(match.groupNodes("label"));
        List<FlatNode> valueNodes = trimWhitespaceNodes(match.groupNodes("value"));
        Text labelText = toText(labelNodes);
        Text colonText = toText(match.groupNodes("colon"));
        Text spacingText = toText(match.groupNodes("spacing"));
        Text valueText = toText(valueNodes);
        if (labelText == null || valueText == null) {
            return null;
        }

        StructuredLineKind kind = classifyStructuredLineKind(labelText, valueText);
        if (kind == null) {
            return null;
        }

        Text labelTranslationText = toCompactedText(labelNodes);
        String labelKey = TooltipTranslationSupport.extractTemplateKeyForLine(labelTranslationText, useTagStylePreservation);
        if (labelKey == null || labelKey.isBlank()) {
            return null;
        }

        String valueKey = null;
        Text valueTranslationText = valueText;
        if (kind.translateValue()) {
            valueTranslationText = toCompactedText(valueNodes);
            valueKey = TooltipTranslationSupport.extractTemplateKeyForLine(valueTranslationText, useTagStylePreservation);
            if (valueKey == null || valueKey.isBlank()) {
                return null;
            }
        }

        return new StructuredLineMatch(
                kind,
                labelText,
                labelTranslationText,
                colonText,
                spacingText,
                valueText,
                valueTranslationText,
                labelKey,
                valueKey
        );
    }

    private static StructuredLineKind classifyStructuredLineKind(Text labelText, Text valueText) {
        String normalizedLabel = normalizeLabel(textString(labelText));
        String normalizedValue = normalizeValue(textString(valueText));
        if (normalizedLabel.isEmpty() || normalizedValue.isEmpty()) {
            return null;
        }

        if (isStructuredNumericLikeValue(normalizedValue)) {
            return StructuredLineKind.NUMERIC;
        }
        if (isCooldownLikeLabel(normalizedLabel) && isTimedUnitLikeValue(normalizedValue)) {
            return StructuredLineKind.TIMED_VALUE;
        }
        if (isPriceLikeLabel(normalizedLabel) && isCoinsLikeValue(normalizedValue)) {
            return StructuredLineKind.PRICE_VALUE;
        }
        if (isSelectedLikeLabel(normalizedLabel) && isWordPhraseValue(normalizedValue)) {
            return StructuredLineKind.SELECTED_VALUE;
        }
        if (isMuseumLikeLabel(normalizedLabel) && isDonatedStatusValue(normalizedValue)) {
            return StructuredLineKind.DONATED_STATUS;
        }
        if (isListLikeLabel(normalizedLabel) && isWordListValue(normalizedValue)) {
            return StructuredLineKind.WORD_LIST;
        }

        return null;
    }

    private static boolean isErrorTranslation(Text text) {
        return text != null && text.getString().startsWith("Error: ");
    }

    private static String buildDebugSummary(StructuredLineMatch structuredLine) {
        if (structuredLine == null) {
            return "";
        }
        return "pattern=" + structuredLine.kind().debugName()
                + ", translateValue=" + structuredLine.kind().translateValue()
                + ", label=\"" + truncateForLog(textString(structuredLine.labelText()), 96) + "\""
                + ", value=\"" + truncateForLog(textString(structuredLine.valueText()), 96) + "\""
                + ", labelKey=\"" + truncateForLog(structuredLine.labelKey(), 140) + "\""
                + (structuredLine.valueKey() == null ? "" : ", valueKey=\"" + truncateForLog(structuredLine.valueKey(), 140) + "\"");
    }

    private static Predicate<FlatNode> nodePredicate(Predicate<FlatNode> predicate) {
        return predicate;
    }

    private static Text toText(List<FlatNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }

        MutableText text = Text.empty();
        for (FlatNode node : nodes) {
            text.append(node.toText());
        }
        return text;
    }

    private static Text toCompactedText(List<FlatNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        return toText(FlatNode.compact(nodes));
    }

    private static List<FlatNode> trimWhitespaceNodes(List<FlatNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        int start = 0;
        int end = nodes.size();
        while (start < end && isWhitespaceOnlySegment(nodes.get(start))) {
            start++;
        }
        while (end > start && isWhitespaceOnlySegment(nodes.get(end - 1))) {
            end--;
        }
        if (start >= end) {
            return List.of();
        }
        return List.copyOf(nodes.subList(start, end));
    }

    private static String textString(Text text) {
        return text == null ? "" : text.getString();
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

    private static String normalizeLabel(String label) {
        return normalizeSpaces(label).trim();
    }

    private static String normalizeValue(String value) {
        return normalizeSpaces(value).trim();
    }

    private static String normalizeSpaces(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isCooldownLikeLabel(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        return lower.equals("cooldown")
                || lower.endsWith(" cooldown")
                || lower.endsWith(" cost")
                || lower.endsWith(" time");
    }

    private static boolean isPriceLikeLabel(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        return lower.contains("price")
                || lower.endsWith(" value")
                || lower.contains("sell value")
                || lower.contains("item value");
    }

    private static boolean isSelectedLikeLabel(String label) {
        return label.toLowerCase(Locale.ROOT).startsWith("selected ");
    }

    private static boolean isMuseumLikeLabel(String label) {
        return label.toLowerCase(Locale.ROOT).startsWith("museum");
    }

    private static boolean isListLikeLabel(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        return lower.equals("attracts")
                || lower.endsWith(" attracts");
    }

    private static boolean isStructuredNumericLikeValue(String value) {
        if (value == null || value.isBlank() || containsLetter(value)) {
            return false;
        }

        boolean sawNonWhitespace = false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)) {
                sawNonWhitespace = true;
            }
            if (!(Character.isWhitespace(codePoint)
                    || Character.isDigit(codePoint)
                    || isStructuredValueSymbol(codePoint)
                    || isPrivateUseCodePoint(codePoint))) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return sawNonWhitespace;
    }

    private static boolean isTimedUnitLikeValue(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.matches("(?i)[+-]?\\d+(?:\\.\\d+)?\\s*[a-z]{1,6}")
                || normalized.matches("(?i)[+-]?\\d+(?:\\.\\d+)?%")
                || normalized.matches("(?i)[+-]?\\d+(?:\\.\\d+)?\\s*x");
    }

    private static boolean isCoinsLikeValue(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || !containsDigit(normalized)) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.endsWith("coins")
                || lower.endsWith("coin")
                || lower.contains(" coins");
    }

    private static boolean isWordPhraseValue(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || !containsLetter(normalized) || containsDigit(normalized)) {
            return false;
        }
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            if (!(Character.isLetter(codePoint)
                    || Character.isWhitespace(codePoint)
                    || codePoint == '\''
                    || codePoint == '-'
                    || codePoint == '&'
                    || codePoint == '/'
                    || codePoint == '('
                    || codePoint == ')')) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isWordListValue(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || !containsLetter(normalized)) {
            return false;
        }
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            if (!(Character.isLetter(codePoint)
                    || Character.isWhitespace(codePoint)
                    || codePoint == ','
                    || codePoint == '\''
                    || codePoint == '-'
                    || codePoint == '&'
                    || isPrivateUseCodePoint(codePoint))) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return normalized.indexOf(',') >= 0 || normalized.indexOf(' ') >= 0;
    }

    private static boolean isDonatedStatusValue(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.contains("donated")
                || lower.contains("not donated")
                || lower.contains("donate");
    }

    private static boolean containsLetter(String value) {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetter(codePoint)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean containsDigit(String value) {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isDigit(codePoint)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static List<FlatNode> splitInlineNodes(Text line) {
        List<FlatNode> compactNodes = FlatNode.compact(FlatNode.flatten(line));
        List<FlatNode> splitNodes = new ArrayList<>();
        for (FlatNode node : compactNodes) {
            TextContent content = node.content();
            if (!(content instanceof PlainTextContent plainTextContent)) {
                splitNodes.add(node);
                continue;
            }

            String text = plainTextContent.string();
            if (text == null || text.isEmpty()) {
                continue;
            }

            StringBuilder segment = new StringBuilder();
            InlineSegmentType currentType = null;
            for (int offset = 0; offset < text.length(); ) {
                int codePoint = text.codePointAt(offset);
                InlineSegmentType nextType = classifyInlineSegmentType(codePoint);
                if (currentType != null && currentType != nextType && segment.length() > 0) {
                    splitNodes.add(new FlatNode(PlainTextContent.of(segment.toString()), node.style()));
                    segment.setLength(0);
                }
                currentType = nextType;
                segment.appendCodePoint(codePoint);
                offset += Character.charCount(codePoint);
            }

            if (segment.length() > 0) {
                splitNodes.add(new FlatNode(PlainTextContent.of(segment.toString()), node.style()));
            }
        }
        return splitNodes;
    }

    private static InlineSegmentType classifyInlineSegmentType(int codePoint) {
        if (codePoint == ':') {
            return InlineSegmentType.COLON;
        }
        if (Character.isWhitespace(codePoint)) {
            return InlineSegmentType.WHITESPACE;
        }
        if (Character.isLetter(codePoint)) {
            return InlineSegmentType.LETTER;
        }
        if (Character.isDigit(codePoint)) {
            return InlineSegmentType.DIGIT;
        }
        return InlineSegmentType.OTHER;
    }

    private static boolean isStructuredLabelSegment(FlatNode node) {
        String value = node == null ? "" : node.extractString();
        if (value.isEmpty()) {
            return false;
        }
        if (isWhitespaceOnlySegment(node)) {
            return true;
        }
        if (containsLetter(value)) {
            return true;
        }
        return isAllowedLabelPunctuation(value);
    }

    private static boolean isWhitespaceOnlySegment(FlatNode node) {
        String value = node == null ? "" : node.extractString();
        if (value.isEmpty()) {
            return false;
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isStructuredGenericValueSegment(FlatNode node) {
        String value = node == null ? "" : node.extractString();
        if (value.isEmpty()) {
            return false;
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == ':') {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isAllowedLabelPunctuation(String value) {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (!(codePoint == '('
                    || codePoint == ')'
                    || codePoint == '.'
                    || codePoint == '\''
                    || codePoint == '-'
                    || codePoint == '/'
                    || codePoint == '&')) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isStructuredValueSymbol(int codePoint) {
        if (codePoint == ':') {
            return false;
        }
        if (codePoint == '+' || codePoint == '-' || codePoint == '%' || codePoint == '.' || codePoint == ','
                || codePoint == '(' || codePoint == ')' || codePoint == '[' || codePoint == ']'
                || codePoint == '/' || codePoint == '\\' || codePoint == '*' || codePoint == 'x'
                || codePoint == 'X') {
            return true;
        }

        int type = Character.getType(codePoint);
        return type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL
                || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static boolean isPrivateUseCodePoint(int codePoint) {
        return Character.getType(codePoint) == Character.PRIVATE_USE;
    }

    private enum InlineSegmentType {
        COLON,
        WHITESPACE,
        LETTER,
        DIGIT,
        OTHER
    }

    private enum StructuredLineKind {
        NUMERIC("structured-numeric", false),
        TIMED_VALUE("structured-timed", true),
        PRICE_VALUE("structured-price", true),
        SELECTED_VALUE("structured-selected", true),
        DONATED_STATUS("structured-donated", true),
        WORD_LIST("structured-list", true);

        private final String debugName;
        private final boolean translateValue;

        StructuredLineKind(String debugName, boolean translateValue) {
            this.debugName = debugName;
            this.translateValue = translateValue;
        }

        public String debugName() {
            return debugName;
        }

        public boolean translateValue() {
            return translateValue;
        }
    }

    record StructuredTooltipLineResult(
            TooltipTranslationSupport.TooltipLineResult lineResult,
            Set<String> translationTemplateKeys,
            String debugSummary
    ) {
        StructuredTooltipLineResult {
            translationTemplateKeys = translationTemplateKeys == null
                    ? Set.of()
                    : new LinkedHashSet<>(translationTemplateKeys);
            debugSummary = debugSummary == null ? "" : debugSummary;
        }
    }

    private record StructuredLineMatch(
            StructuredLineKind kind,
            Text labelText,
            Text labelTranslationText,
            Text colonText,
            Text spacingText,
            Text valueText,
            Text valueTranslationText,
            String labelKey,
            String valueKey
    ) {
        private Set<String> translationTemplateKeys() {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            if (labelKey != null && !labelKey.isBlank()) {
                keys.add(labelKey);
            }
            if (valueKey != null && !valueKey.isBlank()) {
                keys.add(valueKey);
            }
            return keys;
        }
    }
}
