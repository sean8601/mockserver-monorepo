package org.mockserver.openapi.examples;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link SampleDataGenerator} verifying format-aware data generation
 * and determinism (same seed produces identical output).
 */
public class SampleDataGeneratorTest {

    // ---- Determinism ----

    @Test
    public void shouldProduceDeterministicOutputWithSameSeed() {
        SampleDataGenerator gen1 = new SampleDataGenerator(123L);
        SampleDataGenerator gen2 = new SampleDataGenerator(123L);

        assertThat(gen1.string(), is(gen2.string()));
        assertThat(gen1.email(), is(gen2.email()));
        assertThat(gen1.uuid(), is(gen2.uuid()));
        assertThat(gen1.date(), is(gen2.date()));
        assertThat(gen1.dateTime(), is(gen2.dateTime()));
        assertThat(gen1.integer(), is(gen2.integer()));
        assertThat(gen1.longValue(), is(gen2.longValue()));
        assertThat(gen1.floatValue(), is(gen2.floatValue()));
        assertThat(gen1.doubleValue(), is(gen2.doubleValue()));
        assertThat(gen1.decimal(), is(gen2.decimal()));
        assertThat(gen1.booleanValue(), is(gen2.booleanValue()));
    }

    @Test
    public void shouldProduceDeterministicOutputWithDefaultSeed() {
        SampleDataGenerator gen1 = new SampleDataGenerator();
        SampleDataGenerator gen2 = new SampleDataGenerator();

        // Multiple calls in sequence should be identical
        for (int i = 0; i < 5; i++) {
            assertThat("string at index " + i, gen1.string(), is(gen2.string()));
        }
    }

    @Test
    public void shouldProduceDifferentOutputWithDifferentSeeds() {
        SampleDataGenerator gen1 = new SampleDataGenerator(1L);
        SampleDataGenerator gen2 = new SampleDataGenerator(2L);

        // At least one value should differ (extremely unlikely all match)
        boolean anyDiffers = !gen1.string().equals(gen2.string())
            || !gen1.email().equals(gen2.email())
            || gen1.integer() != gen2.integer();
        assertThat("different seeds should produce different output", anyDiffers, is(true));
    }

    // ---- Email ----

    @Test
    public void shouldGenerateValidEmailFormat() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String email = gen.email();

        assertThat(email, containsString("@"));
        assertThat(email, matchesRegex(".+@.+\\..+"));
    }

    // ---- UUID ----

    @Test
    public void shouldGenerateValidUuidFormat() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String uuid = gen.uuid();

        assertThat(uuid, matchesRegex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    // ---- Date ----

    @Test
    public void shouldGenerateValidDateFormat() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String date = gen.date();

        assertThat(date, matchesRegex("\\d{4}-\\d{2}-\\d{2}"));
    }

    // ---- DateTime ----

    @Test
    public void shouldGenerateValidDateTimeFormat() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String dateTime = gen.dateTime();

        // ISO-8601 offset date-time: yyyy-MM-ddTHH:mm:ssZ or yyyy-MM-ddTHH:mm:ss+HH:MM
        assertThat(dateTime, matchesRegex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"));
    }

    // ---- URI ----

    @Test
    public void shouldGenerateUriLikeString() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String uri = gen.uri();

        assertThat(uri, anyOf(startsWith("http://"), startsWith("https://"), startsWith("www.")));
    }

    // ---- Hostname ----

    @Test
    public void shouldGenerateHostname() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String hostname = gen.hostname();

        assertThat(hostname, is(not(emptyOrNullString())));
        assertThat(hostname, containsString("."));
    }

    // ---- IPv4 ----

    @Test
    public void shouldGenerateValidIpv4() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String ipv4 = gen.ipv4();

        assertThat(ipv4, matchesRegex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"));
    }

    // ---- IPv6 ----

    @Test
    public void shouldGenerateValidIpv6() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String ipv6 = gen.ipv6();

        assertThat(ipv6, is(not(emptyOrNullString())));
        assertThat(ipv6, containsString(":"));
    }

    // ---- Byte string ----

    @Test
    public void shouldGenerateBase64EncodedByteString() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String byteString = gen.byteString();

        assertThat(byteString, is(not(emptyOrNullString())));
        // Base64 characters only
        assertThat(byteString, matchesRegex("[A-Za-z0-9+/]+=*"));
    }

    // ---- Password ----

    @Test
    public void shouldGeneratePasswordOfSufficientLength() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String password = gen.password();

        assertThat(password.length(), is(greaterThanOrEqualTo(8)));
    }

    // ---- String with constraints ----

    @Test
    public void shouldRespectMinLengthConstraint() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String s = gen.string(10, 20);

        assertThat(s.length(), is(greaterThanOrEqualTo(10)));
        assertThat(s.length(), is(lessThanOrEqualTo(20)));
    }

    @Test
    public void shouldRespectMaxLengthConstraint() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String s = gen.string(null, 5);

        assertThat(s.length(), is(lessThanOrEqualTo(5)));
    }

    // ---- Integer with constraints ----

    @Test
    public void shouldRespectIntegerMinMax() {
        SampleDataGenerator gen = new SampleDataGenerator();
        int value = gen.integer(BigDecimal.valueOf(10), BigDecimal.valueOf(20));

        assertThat(value, is(greaterThanOrEqualTo(10)));
        assertThat(value, is(lessThan(20)));
    }

    @Test
    public void shouldGeneratePositiveIntegerByDefault() {
        SampleDataGenerator gen = new SampleDataGenerator();
        int value = gen.integer();

        assertThat(value, is(greaterThanOrEqualTo(1)));
        assertThat(value, is(lessThan(1000)));
    }

    // ---- Long with constraints ----

    @Test
    public void shouldRespectLongMinMax() {
        SampleDataGenerator gen = new SampleDataGenerator();
        long value = gen.longValue(BigDecimal.valueOf(100), BigDecimal.valueOf(200));

        assertThat(value, is(greaterThanOrEqualTo(100L)));
        assertThat(value, is(lessThan(200L)));
    }

    // ---- Float ----

    @Test
    public void shouldGenerateFloatInRange() {
        SampleDataGenerator gen = new SampleDataGenerator();
        float value = gen.floatValue(BigDecimal.valueOf(1.0), BigDecimal.valueOf(10.0));

        assertThat((double) value, is(greaterThanOrEqualTo(1.0)));
        assertThat((double) value, is(lessThanOrEqualTo(10.0)));
    }

    // ---- Double ----

    @Test
    public void shouldGenerateDoubleInRange() {
        SampleDataGenerator gen = new SampleDataGenerator();
        double value = gen.doubleValue(BigDecimal.valueOf(5.0), BigDecimal.valueOf(50.0));

        assertThat(value, is(greaterThanOrEqualTo(5.0)));
        assertThat(value, is(lessThanOrEqualTo(50.0)));
    }

    // ---- Decimal ----

    @Test
    public void shouldGenerateDecimalWithTwoDecimalPlaces() {
        SampleDataGenerator gen = new SampleDataGenerator();
        BigDecimal value = gen.decimal();

        assertThat(value.scale(), is(2));
    }

    // ---- Boolean ----

    @Test
    public void shouldGenerateBoolean() {
        // Just verify it doesn't throw — value is deterministic from seed
        SampleDataGenerator gen = new SampleDataGenerator();
        gen.booleanValue();
    }

    // ---- Enum selection ----

    @Test
    public void shouldSelectFromEnum() {
        SampleDataGenerator gen = new SampleDataGenerator();
        List<String> enums = Arrays.asList("red", "green", "blue");
        String selected = gen.fromEnum(enums);

        assertThat(selected, isIn(enums));
    }

    @Test
    public void shouldReturnNullForEmptyEnum() {
        SampleDataGenerator gen = new SampleDataGenerator();
        assertThat(gen.fromEnum(List.of()), is(nullValue()));
        assertThat(gen.fromEnum(null), is(nullValue()));
    }

    // ---- Format dispatch ----

    @Test
    public void shouldDispatchEmailFormat() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String value = gen.stringForFormat("email", null, null);

        assertThat(value, containsString("@"));
    }

    @Test
    public void shouldDispatchUuidFormat() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String value = gen.stringForFormat("uuid", null, null);

        assertThat(value, matchesRegex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    @Test
    public void shouldDispatchDateFormat() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String value = gen.stringForFormat("date", null, null);

        assertThat(value, matchesRegex("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    public void shouldDispatchDateTimeFormat() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String value = gen.stringForFormat("date-time", null, null);

        assertThat(value, matchesRegex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"));
    }

    @Test
    public void shouldFallBackToPlainStringForUnknownFormat() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String value = gen.stringForFormat("unknown-format", null, null);

        assertThat(value, is(not(emptyOrNullString())));
    }

    @Test
    public void shouldFallBackToPlainStringForNullFormat() {
        SampleDataGenerator gen = new SampleDataGenerator();
        String value = gen.stringForFormat(null, null, null);

        assertThat(value, is(not(emptyOrNullString())));
    }
}
