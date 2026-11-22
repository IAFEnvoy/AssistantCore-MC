package com.iafenvoy.assistantcore.routing;

import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Evaluates a local ranking. The caller supplies business eligibility and validates the parsed action.
 */
public final class IntentRouter {
    private static final Pattern COMPLEX = Pattern.compile(
            "不|别|没|取消|如果|假如|是否|能否|怎么|如何|为什么|顺便|然后|并且|同时|那个|这个|上一个|第二个|全部|所有奖励|多少钱|"
                    + "[0-9]+\\s*(?:个|份|组)|[;；\\r\\n]|\\b(?:not|no|never|don't|do not|cannot|can't|isn't|without|except|instead|but|if|unless|while|then|also|and|or|that|this|it|them|those|these|previous|second|all rewards|why)\\b",
            Pattern.CASE_INSENSITIVE);

    private IntentRouter() {
    }

    public enum Reason {
        TEXT_LIMIT, LANGUAGE, COMPLEX_REQUEST, NO_CANDIDATE, WRITE_REQUEST, NO_TOPIC, NO_DIRECT_PERMISSION,
        UNMATCHED_TOPIC, LOW_COVERAGE, LOW_SCORE, SMALL_MARGIN, MISSING_ARGUMENTS, DIRECT
    }

    public record Threshold(double score, double margin, double coverage) {
    }

    public record Decision<A>(A action, Reason reason) {
    }

    /** Returns the reason to stop, or null if this text can be matched locally. */
    public static Reason guard(String text, String language, Set<String> languages, int maxCharacters) {
        if (text.isBlank() || text.codePointCount(0, text.length()) > maxCharacters) return Reason.TEXT_LIMIT;
        if (!languages.contains(language) || !supportedText(text, language)) return Reason.LANGUAGE;
        return COMPLEX.matcher(text).find() ? Reason.COMPLEX_REQUEST : null;
    }

    public static <T, A> Decision<A> decide(LexicalIndex.Ranking<T> ranking, String text, Threshold threshold,
                                           boolean allowWriteTerms, Predicate<? super T> canRoute,
                                           BiFunction<? super T, String, ? extends A> parse) {
        LexicalIndex.Match<T> best = ranking.first();
        if (best == null) return new Decision<>(null, Reason.NO_CANDIDATE);
        Reason reason = null;
        // Similarity is a ranking score. Direct routing relies on coverage and an independently parsed action.
        if (ranking.writeRequest() && !allowWriteTerms) reason = Reason.WRITE_REQUEST;
        else if (!ranking.hasTopic()) reason = Reason.NO_TOPIC;
        else if (!canRoute.test(best.value())) reason = Reason.NO_DIRECT_PERMISSION;
        else if (!best.topicsCovered()) reason = Reason.UNMATCHED_TOPIC;
        else if (best.coverage() + 1e-9 < threshold.coverage()) reason = Reason.LOW_COVERAGE;
        else if (best.score() + 1e-9 < threshold.score()) reason = Reason.LOW_SCORE;
        else if (ranking.margin() + 1e-9 < threshold.margin()) reason = Reason.SMALL_MARGIN;
        if (reason != null) return new Decision<>(null, reason);

        A action = parse.apply(best.value(), text);
        return new Decision<>(action, action == null ? Reason.MISSING_ARGUMENTS : Reason.DIRECT);
    }

    private static boolean supportedText(String text, String language) {
        boolean han = false;
        for (int point : text.codePoints().toArray()) {
            Character.UnicodeScript script = Character.UnicodeScript.of(point);
            if (script == Character.UnicodeScript.HAN) han = true;
            else if (Character.isLetter(point) && script != Character.UnicodeScript.LATIN) return false;
        }
        return !language.equals("en") || !han;
    }
}
