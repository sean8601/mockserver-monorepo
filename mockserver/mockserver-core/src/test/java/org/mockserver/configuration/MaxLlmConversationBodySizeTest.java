package org.mockserver.configuration;

import org.junit.After;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class MaxLlmConversationBodySizeTest {

    @After
    public void tearDown() {
        // reset to default by clearing the property
        System.clearProperty("mockserver.maxLlmConversationBodySize");
    }

    @Test
    public void shouldReturnDefaultValue() {
        // given - no property set
        System.clearProperty("mockserver.maxLlmConversationBodySize");

        // then
        assertThat(ConfigurationProperties.maxLlmConversationBodySize(), is(1048576));
    }

    @Test
    public void shouldReturnOverriddenValue() {
        // given
        ConfigurationProperties.maxLlmConversationBodySize(2097152);

        // then
        assertThat(ConfigurationProperties.maxLlmConversationBodySize(), is(2097152));
    }

    @Test
    public void shouldClampBelowMinimum() {
        // given - set value below minimum (16384)
        ConfigurationProperties.maxLlmConversationBodySize(1000);

        // then - should clamp to 16384
        assertThat(ConfigurationProperties.maxLlmConversationBodySize(), is(16384));
    }

    @Test
    public void shouldClampAboveMaximum() {
        // given - set value above maximum (67108864)
        ConfigurationProperties.maxLlmConversationBodySize(100000000);

        // then - should clamp to 67108864
        assertThat(ConfigurationProperties.maxLlmConversationBodySize(), is(67108864));
    }

    @Test
    public void shouldAcceptMinimumBoundary() {
        // given
        ConfigurationProperties.maxLlmConversationBodySize(16384);

        // then
        assertThat(ConfigurationProperties.maxLlmConversationBodySize(), is(16384));
    }

    @Test
    public void shouldAcceptMaximumBoundary() {
        // given
        ConfigurationProperties.maxLlmConversationBodySize(67108864);

        // then
        assertThat(ConfigurationProperties.maxLlmConversationBodySize(), is(67108864));
    }

    @Test
    public void shouldWorkWithConfigurationInstance() {
        // given
        Configuration configuration = Configuration.configuration();

        // when - no override set
        // then - should return default from ConfigurationProperties
        assertThat(configuration.maxLlmConversationBodySize(), is(ConfigurationProperties.maxLlmConversationBodySize()));

        // when - set per-instance value
        configuration.maxLlmConversationBodySize(524288);

        // then - should return per-instance value
        assertThat(configuration.maxLlmConversationBodySize(), is(524288));
    }
}
