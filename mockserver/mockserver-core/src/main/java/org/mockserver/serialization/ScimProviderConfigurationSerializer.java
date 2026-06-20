package org.mockserver.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scim.ScimProviderConfiguration;
import org.slf4j.event.Level;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mockserver.character.Character.NEW_LINE;

@SuppressWarnings("FieldMayBeFinal")
public class ScimProviderConfigurationSerializer implements Serializer<ScimProviderConfiguration> {
    private final MockServerLogger mockServerLogger;
    private ObjectWriter objectWriter = ObjectMapperFactory.createObjectMapper(true, false);
    private ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    public ScimProviderConfigurationSerializer(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    public String serialize(ScimProviderConfiguration scimConfiguration) {
        if (scimConfiguration != null) {
            try {
                return objectWriter.writeValueAsString(scimConfiguration);
            } catch (Exception e) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception while serializing ScimProviderConfiguration to JSON with value " + scimConfiguration)
                        .setThrowable(e)
                );
                throw new RuntimeException("Exception while serializing ScimProviderConfiguration to JSON with value " + scimConfiguration, e);
            }
        } else {
            return "";
        }
    }

    public ScimProviderConfiguration deserialize(String jsonScimConfiguration) {
        if (isBlank(jsonScimConfiguration)) {
            throw new IllegalArgumentException(
                "1 error:" + NEW_LINE
                    + " - a SCIM provider configuration is required but value was \"" + jsonScimConfiguration + "\""
            );
        } else {
            try {
                return objectMapper.readValue(jsonScimConfiguration, ScimProviderConfiguration.class);
            } catch (Throwable throwable) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception while parsing{}for ScimProviderConfiguration " + throwable.getMessage())
                        .setArguments(jsonScimConfiguration)
                        .setThrowable(throwable)
                );
                throw new IllegalArgumentException("exception while parsing [" + jsonScimConfiguration + "] for ScimProviderConfiguration", throwable);
            }
        }
    }

    @Override
    public Class<ScimProviderConfiguration> supportsType() {
        return ScimProviderConfiguration.class;
    }
}
