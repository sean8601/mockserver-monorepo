package org.mockserver.state;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class StateBackendFactoryTest {

    @After
    public void tearDown() {
        StateBackendFactory.resetToDefault();
    }

    @Test
    public void shouldCreateDefaultInMemoryBackend() {
        Configuration config = Configuration.configuration().maxExpectations(50);
        StateBackend backend = StateBackendFactory.create(config);

        assertNotNull(backend);
        assertThat(backend, instanceOf(InMemoryStateBackend.class));
        backend.close();
    }

    @Test
    public void shouldReportNoCustomFactoryByDefault() {
        assertFalse(StateBackendFactory.isCustomFactoryRegistered());
    }

    @Test
    public void shouldRegisterCustomFactory() {
        StateBackendFactory.register(config -> new InMemoryStateBackend(10));
        assertTrue(StateBackendFactory.isCustomFactoryRegistered());
    }

    @Test
    public void shouldResetToDefault() {
        StateBackendFactory.register(config -> new InMemoryStateBackend(10));
        StateBackendFactory.resetToDefault();
        assertFalse(StateBackendFactory.isCustomFactoryRegistered());
    }

    @Test
    public void shouldResetToDefaultWhenRegisteringNull() {
        StateBackendFactory.register(config -> new InMemoryStateBackend(10));
        StateBackendFactory.register(null);
        assertFalse(StateBackendFactory.isCustomFactoryRegistered());
    }
}
