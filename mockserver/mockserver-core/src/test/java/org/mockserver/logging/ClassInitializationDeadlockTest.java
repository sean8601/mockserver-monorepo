package org.mockserver.logging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Regression test for the {@code ConfigurationProperties} &lt;-&gt; {@code MockServerLogger}
 * class-initialization deadlock that forced the parallel-Surefire configuration to be reverted
 * (see git history: enabled in 7b2fa05aa, reverted in 4fbf52ae0).
 *
 * <p>The two classes referenced each other from their static initializers:
 * {@code ConfigurationProperties.<clinit>} eagerly constructed a {@code MockServerLogger}, while
 * {@code MockServerLogger.<clinit>} called {@code configureLogger()} which read
 * {@code ConfigurationProperties}. When two threads first-touched the two classes concurrently,
 * each acquired one class-initialization lock and waited for the other - a permanent deadlock that
 * surfaced as a 30-minute Surefire fork timeout.</p>
 *
 * <p>Class initialization happens at most once per class per {@link ClassLoader}, so by the time an
 * ordinary test runs both classes are already initialized and the race cannot be reproduced. This
 * test therefore loads {@code org.mockserver.*} classes afresh in an isolated child-first
 * class loader and triggers initialization of the two classes from two threads simultaneously. With
 * the cycle present this deadlocks; with the cycle broken both initializations complete promptly.</p>
 */
public class ClassInitializationDeadlockTest {

    private static final String CONFIGURATION_PROPERTIES = "org.mockserver.configuration.ConfigurationProperties";
    private static final String MOCK_SERVER_LOGGER = "org.mockserver.logging.MockServerLogger";

    @Rule
    public Timeout globalTimeout = Timeout.seconds(60);

    @Test
    public void concurrentFirstTouchOfConfigurationPropertiesAndMockServerLoggerDoesNotDeadlock() throws Exception {
        FreshMockServerClassLoader classLoader = new FreshMockServerClassLoader();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread touchConfiguration = new Thread(
            initializer(classLoader, CONFIGURATION_PROPERTIES, ready, start, finished, failure),
            "first-touch-ConfigurationProperties");
        Thread touchLogger = new Thread(
            initializer(classLoader, MOCK_SERVER_LOGGER, ready, start, finished, failure),
            "first-touch-MockServerLogger");

        touchConfiguration.start();
        touchLogger.start();

        // make sure both threads are alive and about to initialize before releasing them together,
        // maximising the chance of interleaving the two <clinit> locks
        if (!ready.await(10, TimeUnit.SECONDS)) {
            fail("initializer threads were not ready within 10s - a thread likely crashed before "
                + "reaching the start gate, so the deadlock race was never exercised");
        }
        start.countDown();

        boolean bothCompleted = finished.await(30, TimeUnit.SECONDS);
        if (!bothCompleted) {
            fail("class-initialization deadlock: ConfigurationProperties / MockServerLogger did not "
                + "initialize within 30s when first-touched concurrently\n"
                + threadDump(touchConfiguration) + threadDump(touchLogger));
        }

        touchConfiguration.join(TimeUnit.SECONDS.toMillis(5));
        touchLogger.join(TimeUnit.SECONDS.toMillis(5));
        assertNull("initialization threw: " + failure.get(), failure.get());
    }

    private static Runnable initializer(ClassLoader classLoader,
                                        String className,
                                        CountDownLatch ready,
                                        CountDownLatch start,
                                        CountDownLatch finished,
                                        AtomicReference<Throwable> failure) {
        return () -> {
            try {
                ready.countDown();
                start.await();
                // initialize = true forces <clinit> to run on this thread
                Class.forName(className, true, classLoader);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                finished.countDown();
            }
        };
    }

    private static String threadDump(Thread thread) {
        StringBuilder builder = new StringBuilder("\n\"").append(thread.getName()).append("\" state=")
            .append(thread.getState()).append('\n');
        for (StackTraceElement element : thread.getStackTrace()) {
            builder.append("\tat ").append(element).append('\n');
        }
        return builder.toString();
    }

    /**
     * Child-first class loader that defines {@code org.mockserver.*} classes itself (so they get
     * fresh {@link Class} objects with fresh initialization locks) while delegating everything else
     * - JDK, SLF4J, Guava, Netty, etc. - to the parent so shared dependencies keep working.
     */
    private static final class FreshMockServerClassLoader extends ClassLoader {

        private FreshMockServerClassLoader() {
            super(FreshMockServerClassLoader.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("org.mockserver.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineFromParentResource(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private Class<?> defineFromParentResource(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream in = openClassBytes(resource)) {
                if (in == null) {
                    throw new ClassNotFoundException(name + " (no .class resource on parent, context or system class loader)");
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        // Surefire may run with a manifest-only booter jar, so the immediate parent's
        // getResourceAsStream can miss the real classpath entries. Try the parent, then the
        // thread context class loader, then the system class loader before giving up.
        private InputStream openClassBytes(String resource) {
            InputStream in = getParent().getResourceAsStream(resource);
            if (in == null) {
                ClassLoader context = Thread.currentThread().getContextClassLoader();
                if (context != null) {
                    in = context.getResourceAsStream(resource);
                }
            }
            if (in == null) {
                in = ClassLoader.getSystemResourceAsStream(resource);
            }
            return in;
        }
    }
}
