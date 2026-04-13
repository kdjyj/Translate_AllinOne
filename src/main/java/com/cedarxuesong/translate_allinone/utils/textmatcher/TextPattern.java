package com.cedarxuesong.translate_allinone.utils.textmatcher;

import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class TextPattern {
    private final List<PatternStep> steps;

    private TextPattern(List<PatternStep> steps) {
        this.steps = steps;
    }

    public static Builder builder() {
        return new Builder();
    }

    public TextMatchResult match(Text text) {
        return match(text, true);
    }

    public TextMatchResult match(Text text, boolean compact) {
        List<FlatNode> nodes = normalize(text, compact);
        Integer endIndex = tryMatch(nodes, 0, 0);
        if (endIndex == null || endIndex != nodes.size()) {
            return TextMatchResult.FAILURE;
        }
        return new TextMatchResult(true, List.copyOf(nodes), 0, nodes.size());
    }

    public TextMatchResult find(Text text) {
        return find(text, true);
    }

    public TextMatchResult find(Text text, boolean compact) {
        List<FlatNode> nodes = normalize(text, compact);
        for (int start = 0; start <= nodes.size(); start++) {
            Integer endIndex = tryMatch(nodes, 0, start);
            if (endIndex != null) {
                return new TextMatchResult(true, List.copyOf(nodes.subList(start, endIndex)), start, endIndex);
            }
        }
        return TextMatchResult.FAILURE;
    }

    private static List<FlatNode> normalize(Text text, boolean compact) {
        List<FlatNode> nodes = FlatNode.flatten(text);
        return compact ? FlatNode.compact(nodes) : nodes;
    }

    private Integer tryMatch(List<FlatNode> nodes, int stepIndex, int nodeIndex) {
        if (stepIndex >= steps.size()) {
            return nodeIndex;
        }

        PatternStep step = steps.get(stepIndex);
        int maxPossible = 0;
        int limit = Math.min(step.maxCount(), nodes.size() - nodeIndex);
        while (maxPossible < limit && step.predicate().test(nodes.get(nodeIndex + maxPossible))) {
            maxPossible++;
        }

        if (maxPossible < step.minCount()) {
            return null;
        }

        if (step.greedy()) {
            for (int count = maxPossible; count >= step.minCount(); count--) {
                Integer result = tryMatch(nodes, stepIndex + 1, nodeIndex + count);
                if (result != null) {
                    return result;
                }
            }
        } else {
            for (int count = step.minCount(); count <= maxPossible; count++) {
                Integer result = tryMatch(nodes, stepIndex + 1, nodeIndex + count);
                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    private record PatternStep(Predicate<FlatNode> predicate, int minCount, int maxCount, boolean greedy) {
    }

    public static final class Builder {
        private final List<PatternStep> steps = new ArrayList<>();

        public Builder one(ContentMatcher contentMatcher, Predicate<FlatNode> nodePredicate) {
            return quantified(contentMatcher, nodePredicate, 1, 1, true);
        }

        public Builder optional(ContentMatcher contentMatcher, Predicate<FlatNode> nodePredicate, boolean greedy) {
            return quantified(contentMatcher, nodePredicate, 0, 1, greedy);
        }

        public Builder oneOrMore(ContentMatcher contentMatcher, Predicate<FlatNode> nodePredicate) {
            return quantified(contentMatcher, nodePredicate, 1, Integer.MAX_VALUE, true);
        }

        public Builder zeroOrMore(ContentMatcher contentMatcher, Predicate<FlatNode> nodePredicate, boolean greedy) {
            return quantified(contentMatcher, nodePredicate, 0, Integer.MAX_VALUE, greedy);
        }

        public Builder exactly(int count, ContentMatcher contentMatcher, Predicate<FlatNode> nodePredicate) {
            return quantified(contentMatcher, nodePredicate, count, count, true);
        }

        public Builder between(int minCount, int maxCount, ContentMatcher contentMatcher, Predicate<FlatNode> nodePredicate, boolean greedy) {
            return quantified(contentMatcher, nodePredicate, minCount, maxCount, greedy);
        }

        public TextPattern build() {
            return new TextPattern(List.copyOf(steps));
        }

        private Builder quantified(
                ContentMatcher contentMatcher,
                Predicate<FlatNode> nodePredicate,
                int minCount,
                int maxCount,
                boolean greedy
        ) {
            Objects.requireNonNull(contentMatcher, "contentMatcher");
            if (minCount < 0) {
                throw new IllegalArgumentException("minCount must be non-negative");
            }
            if (maxCount < minCount) {
                throw new IllegalArgumentException("maxCount must be >= minCount");
            }

            Predicate<FlatNode> predicate = node -> contentMatcher.matches(node.content())
                    && (nodePredicate == null || nodePredicate.test(node));
            steps.add(new PatternStep(predicate, minCount, maxCount, greedy));
            return this;
        }
    }
}
