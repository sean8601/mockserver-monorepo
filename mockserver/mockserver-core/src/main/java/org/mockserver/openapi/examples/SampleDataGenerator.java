package org.mockserver.openapi.examples;

import net.datafaker.Faker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates realistic sample data for OpenAPI example generation using Datafaker.
 * <p>
 * When an OpenAPI schema has no explicit {@code example} or {@code default}, this
 * generator produces format-aware, realistic values based on the schema's
 * {@code type}, {@code format}, and constraints ({@code minimum}/{@code maximum},
 * {@code minLength}/{@code maxLength}, {@code enum}).
 * <p>
 * Output is <strong>deterministic by default</strong>: the same seed produces
 * identical values across runs, so generated mocks are stable across restarts.
 */
public class SampleDataGenerator {

    /**
     * Default seed for deterministic output.
     */
    static final long DEFAULT_SEED = 42L;

    private final Faker faker;
    private final Random random;

    /**
     * Creates a generator with the default seed ({@value #DEFAULT_SEED}).
     */
    public SampleDataGenerator() {
        this(DEFAULT_SEED);
    }

    /**
     * Creates a generator with a specific seed for deterministic output.
     *
     * @param seed the random seed
     */
    public SampleDataGenerator(long seed) {
        this.random = new Random(seed);
        this.faker = new Faker(random);
    }

    // ---- String formats ----

    /**
     * Generates a realistic email address.
     */
    public String email() {
        return faker.internet().emailAddress();
    }

    /**
     * Generates a deterministic UUID.
     */
    public String uuid() {
        return new UUID(random.nextLong(), random.nextLong()).toString();
    }

    /**
     * Generates a date string in {@code yyyy-MM-dd} format.
     */
    public String date() {
        LocalDate base = LocalDate.of(2024, 1, 1);
        int dayOffset = random.nextInt(365);
        return base.plusDays(dayOffset).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Generates a date-time string in ISO-8601 offset format.
     */
    public String dateTime() {
        OffsetDateTime base = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        long secondsOffset = random.nextInt(365 * 24 * 3600);
        return base.plusSeconds(secondsOffset).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * Generates a realistic URI.
     */
    public String uri() {
        return faker.internet().url();
    }

    /**
     * Generates a realistic hostname.
     */
    public String hostname() {
        return faker.internet().domainName();
    }

    /**
     * Generates a realistic IPv4 address.
     */
    public String ipv4() {
        return faker.internet().ipV4Address();
    }

    /**
     * Generates a realistic IPv6 address.
     */
    public String ipv6() {
        return faker.internet().ipV6Address();
    }

    /**
     * Generates a base64-encoded byte sequence.
     */
    public String byteString() {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Generates a password-like string.
     */
    public String password() {
        return faker.internet().password(8, 16, true, true);
    }

    /**
     * Generates a plain string value, optionally respecting length constraints.
     *
     * @param minLength minimum length (nullable)
     * @param maxLength maximum length (nullable)
     */
    public String string(Integer minLength, Integer maxLength) {
        int min = minLength != null ? minLength : 3;
        int max = maxLength != null ? maxLength : 20;
        if (max < min) {
            max = min;
        }
        // Generate a word-based string with realistic content
        StringBuilder sb = new StringBuilder();
        while (sb.length() < min) {
            if (sb.length() > 0) {
                sb.append('_');
            }
            sb.append(faker.lorem().word());
        }
        String result = sb.toString();
        if (result.length() > max) {
            result = result.substring(0, max);
        }
        return result;
    }

    /**
     * Generates a plain string without constraints.
     */
    public String string() {
        return faker.lorem().word();
    }

    // ---- Numeric types ----

    /**
     * Generates an integer, optionally respecting min/max constraints.
     *
     * @param minimum minimum value (nullable)
     * @param maximum maximum value (nullable)
     */
    public int integer(BigDecimal minimum, BigDecimal maximum) {
        int min = minimum != null ? minimum.intValue() : 1;
        int max = maximum != null ? maximum.intValue() : 1000;
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min);
    }

    /**
     * Generates an integer without constraints.
     */
    public int integer() {
        return integer(null, null);
    }

    /**
     * Generates a long, optionally respecting min/max constraints.
     *
     * @param minimum minimum value (nullable)
     * @param maximum maximum value (nullable)
     */
    public long longValue(BigDecimal minimum, BigDecimal maximum) {
        long min = minimum != null ? minimum.longValue() : 1L;
        long max = maximum != null ? maximum.longValue() : 10000L;
        if (max <= min) {
            return min;
        }
        long range = max - min;
        long raw = random.nextLong();
        return min + (Math.abs(raw == Long.MIN_VALUE ? 0L : raw) % range);
    }

    /**
     * Generates a long without constraints.
     */
    public long longValue() {
        return longValue(null, null);
    }

    /**
     * Generates a float, optionally respecting min/max constraints.
     *
     * @param minimum minimum value (nullable)
     * @param maximum maximum value (nullable)
     */
    public float floatValue(BigDecimal minimum, BigDecimal maximum) {
        float min = minimum != null ? minimum.floatValue() : 0.1f;
        float max = maximum != null ? maximum.floatValue() : 100.0f;
        if (max <= min) {
            return min;
        }
        return min + random.nextFloat() * (max - min);
    }

    /**
     * Generates a float without constraints.
     */
    public float floatValue() {
        return floatValue(null, null);
    }

    /**
     * Generates a double, optionally respecting min/max constraints.
     *
     * @param minimum minimum value (nullable)
     * @param maximum maximum value (nullable)
     */
    public double doubleValue(BigDecimal minimum, BigDecimal maximum) {
        double min = minimum != null ? minimum.doubleValue() : 0.1;
        double max = maximum != null ? maximum.doubleValue() : 100.0;
        if (max <= min) {
            return min;
        }
        return min + random.nextDouble() * (max - min);
    }

    /**
     * Generates a double without constraints.
     */
    public double doubleValue() {
        return doubleValue(null, null);
    }

    /**
     * Generates a decimal, optionally respecting min/max constraints.
     *
     * @param minimum minimum value (nullable)
     * @param maximum maximum value (nullable)
     */
    public BigDecimal decimal(BigDecimal minimum, BigDecimal maximum) {
        return BigDecimal.valueOf(doubleValue(minimum, maximum)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Generates a decimal without constraints.
     */
    public BigDecimal decimal() {
        return decimal(null, null);
    }

    // ---- Boolean ----

    /**
     * Generates a boolean value.
     */
    public boolean booleanValue() {
        return random.nextBoolean();
    }

    // ---- Enum selection ----

    /**
     * Selects a value from the given enum list deterministically.
     *
     * @param values the enum values
     * @param <T>    the value type
     * @return a deterministically selected enum value
     */
    public <T> T fromEnum(List<T> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(random.nextInt(values.size()));
    }

    /**
     * Generates a string value based on the given format, falling back to a
     * generic string when the format is not recognised.
     *
     * @param format    the schema format (e.g. "email", "uuid", "date-time")
     * @param minLength minimum length constraint (nullable)
     * @param maxLength maximum length constraint (nullable)
     * @return a realistic string value
     */
    public String stringForFormat(String format, Integer minLength, Integer maxLength) {
        if (format == null) {
            return string(minLength, maxLength);
        }
        return switch (format) {
            case "email" -> email();
            case "uuid" -> uuid();
            case "date" -> date();
            case "date-time" -> dateTime();
            case "uri", "url" -> uri();
            case "hostname" -> hostname();
            case "ipv4" -> ipv4();
            case "ipv6" -> ipv6();
            case "byte" -> byteString();
            case "password" -> password();
            default -> string(minLength, maxLength);
        };
    }
}
