package com.cedarxuesong.translate_allinone.utils.textmatcher;

import net.minecraft.text.MutableText;
import net.minecraft.text.PlainTextContent;

import java.util.List;

public final class TextMatchResult {
    public static final TextMatchResult FAILURE = new TextMatchResult(false, List.of(), -1, -1);

    private final boolean success;
    private final List<FlatNode> matchedNodes;
    private final int startIndex;
    private final int endIndex;

    public TextMatchResult(boolean success, List<FlatNode> matchedNodes, int startIndex, int endIndex) {
        this.success = success;
        this.matchedNodes = matchedNodes;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
    }

    public boolean success() {
        return success;
    }

    public List<FlatNode> matchedNodes() {
        return matchedNodes;
    }

    public int startIndex() {
        return startIndex;
    }

    public int endIndex() {
        return endIndex;
    }

    public String fullText() {
        StringBuilder builder = new StringBuilder();
        for (FlatNode node : matchedNodes) {
            builder.append(node.extractString());
        }
        return builder.toString();
    }

    public MutableText toText() {
        MutableText base = MutableText.of(PlainTextContent.EMPTY);
        for (FlatNode node : matchedNodes) {
            base.append(node.toText());
        }
        return base;
    }
}
