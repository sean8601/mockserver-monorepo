package org.mockserver.matchers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Joiner;
import net.javacrumbs.jsonunit.core.Configuration;
import net.javacrumbs.jsonunit.core.internal.Diff;
import net.javacrumbs.jsonunit.core.Option;
import net.javacrumbs.jsonunit.core.listener.DifferenceContext;
import net.javacrumbs.jsonunit.core.listener.DifferenceListener;
import org.apache.commons.lang3.StringUtils;
import org.hamcrest.Matcher;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.ObjectMapperFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static net.javacrumbs.jsonunit.core.Option.*;
import static org.mockserver.character.Character.NEW_LINE;

/**
 * @author jamesdbloom
 */
public class JsonStringMatcher extends BodyMatcher<String> {
    private static final String[] EXCLUDED_FIELDS = {"mockServerLogger"};
    private static final ObjectWriter PRETTY_PRINTER = ObjectMapperFactory.createObjectMapper(true, false);
    private static final ThreadLocal<Object[]> BODY_PARSE_CACHE = ThreadLocal.withInitial(() -> new Object[2]);
    private final MockServerLogger mockServerLogger;
    private final String matcher;
    private JsonNode matcherJsonNode;
    private final MatchType matchType;
    private final boolean matchNumbersAsStrings;

    JsonStringMatcher(MockServerLogger mockServerLogger, String matcher, MatchType matchType) {
        this(mockServerLogger, matcher, matchType, false);
    }

    JsonStringMatcher(MockServerLogger mockServerLogger, String matcher, MatchType matchType, boolean matchNumbersAsStrings) {
        this.mockServerLogger = mockServerLogger;
        this.matcher = matcher;
        this.matchType = matchType;
        this.matchNumbersAsStrings = matchNumbersAsStrings;
    }

    public boolean matches(final MatchDifference context, String matched) {
        boolean result = false;

        try {
            if (StringUtils.isBlank(matcher)) {
                result = true;
            } else {
                EnumSet<Option> options = EnumSet.noneOf(Option.class);
                switch (matchType) {
                    case STRICT:
                        break;
                    case ONLY_MATCHING_FIELDS:
                        options.add(IGNORING_ARRAY_ORDER);
                        options.add(IGNORING_EXTRA_ARRAY_ITEMS);
                        options.add(IGNORING_EXTRA_FIELDS);
                        break;
                }
                final Difference diffListener = new Difference();
                Configuration diffConfig = Configuration.empty().withDifferenceListener(diffListener).withOptions(options);
                if (matchNumbersAsStrings) {
                    diffConfig = diffConfig.withTolerance(BigDecimal.ZERO);
                }
                for (Map.Entry<String, Matcher<?>> entry : CustomJsonUnitMatcherLoader.load().entrySet()) {
                    diffConfig = diffConfig.withMatcher(entry.getKey(), entry.getValue());
                }

                try {
                    if (matcherJsonNode == null) {
                        matcherJsonNode = ObjectMapperFactory.createObjectMapper().readTree(matcher);
                    }
                    Object[] cache = BODY_PARSE_CACHE.get();
                    JsonNode matchedNode;
                    if (matched.equals(cache[0])) {
                        matchedNode = (JsonNode) cache[1];
                    } else {
                        matchedNode = ObjectMapperFactory.createObjectMapper().readTree(matched);
                        cache[0] = matched;
                        cache[1] = matchedNode;
                    }
                    result = Diff
                        .create(
                            matcherJsonNode,
                            matchedNode,
                            "",
                            "",
                            diffConfig
                        )
                        .similar();
                } catch (Throwable throwable) {
                    if (context != null) {
                        context.addDifference(mockServerLogger, throwable, "exception while perform json match failed expected:{}found:{}", this.matcher, matched);
                    }
                }

                if (!result) {
                    if (context != null) {
                        if (diffListener.differences.isEmpty()) {
                            context.addDifference(mockServerLogger, "json match failed expected:{}found:{}", this.matcher, matched);
                        } else {
                            context.addDifference(mockServerLogger, "json match failed expected:{}found:{}failed because:{}", this.matcher, matched, Joiner.on("," + NEW_LINE).join(diffListener.differences));
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            if (context != null) {
                context.addDifference(mockServerLogger, throwable, "json match failed expected:{}found:{}failed because:{}", this.matcher, matched, throwable.getMessage());
            }
        }

        return not != result;
    }

    private static class Difference implements DifferenceListener {

        public List<String> differences = new ArrayList<>();

        @Override
        public void diff(net.javacrumbs.jsonunit.core.listener.Difference difference, DifferenceContext context) {
            switch (difference.getType()) {
                case EXTRA:
                    differences.add("additional element at \"" + difference.getActualPath() + "\" with value: " + prettyPrint(difference.getActual()));
                    break;
                case MISSING:
                    differences.add("missing element at \"" + difference.getActualPath() + "\"");
                    break;
                case DIFFERENT:
                    differences.add("wrong value at \"" + difference.getActualPath() + "\", expected: " + prettyPrint(difference.getExpected()) + " but was: " + prettyPrint(difference.getActual()));
                    break;
            }
        }

        private String prettyPrint(Object value) {
            try {
                return PRETTY_PRINTER.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                return String.valueOf(value);
            }
        }
    }

    public boolean isBlank() {
        return StringUtils.isBlank(matcher);
    }

    @Override
    @JsonIgnore
    protected String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }
}
