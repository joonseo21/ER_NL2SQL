package io.eranalytics.pipeline.collection.metadata;

import java.util.HashMap;
import java.util.Map;

public final class KoreanLocalization {
    private KoreanLocalization() {}

    /** 한국어 파일을 사전으로 ex) MasteryType/TwoHandSword┃양손검 */
    public static Map<String, String> parse(String text) {
        Map<String, String> names = new HashMap<>();
        text.lines()
                .forEach(
                        line -> {
                            String clean = line.startsWith("\uFEFF") ? line.substring(1) : line;
                            int separator = clean.indexOf('┃');
                            if (separator > 0) {
                                String key = clean.substring(0, separator);
                                String value = clean.substring(separator + 1);
                                String previous = names.putIfAbsent(key, value);
                                if (previous != null && !previous.equals(value)) {
                                    throw new IllegalArgumentException(
                                            "Conflicting localization keys");
                                }
                            }
                        });
        if (names.isEmpty()) throw new IllegalArgumentException("Localization is empty");
        return Map.copyOf(names);
    }
}
