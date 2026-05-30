package org.mockserver.uuid;

import org.junit.rules.ExternalResource;

/**
 * JUnit rule that pins {@link UUIDService#getUUID()} to {@link UUIDService#FIXED_UUID_FOR_TESTS} for
 * the duration of a test (as a {@code @Rule}) or a test class (as a {@code @ClassRule}), on the
 * running thread only, and ALWAYS unpins afterwards - even if the test throws.
 *
 * <p>This replaces the previous {@code fixedUUID = true} static-boolean idiom, which fixed the UUID
 * JVM-wide and so caused colliding ids (and overwritten expectations) when other test classes ran
 * concurrently under Surefire {@code parallel=classes}. Because the rule guarantees the reset, the
 * fixed UUID cannot leak onto a reused Surefire worker thread.</p>
 *
 * <pre>{@code  @ClassRule public static final FixedUUID fixedUUID = new FixedUUID(); }</pre>
 */
public class FixedUUID extends ExternalResource {

    @Override
    protected void before() {
        UUIDService.fixedUUID(true);
    }

    @Override
    protected void after() {
        UUIDService.fixedUUID(false);
    }
}
