package org.mockserver.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.CrudExpectationsDefinition.IdStrategy;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the SCIM 2.0 mock provider. All fields are optional with sensible defaults so
 * that {@code PUT /mockserver/scim} with an empty body produces a fully functional SCIM provider
 * serving {@code /scim/v2/Users}, {@code /scim/v2/Groups}, and the discovery endpoints
 * ({@code /ServiceProviderConfig}, {@code /ResourceTypes}, {@code /Schemas}).
 *
 * <p>Mirrors {@link org.mockserver.oidc.OidcProviderConfiguration} in shape (fluent setters,
 * {@code @JsonIgnoreProperties(ignoreUnknown=true)}, {@code @JsonInclude(NON_NULL)},
 * {@link Serializable}) so it serializes/deserializes cleanly over the control plane.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScimProviderConfiguration implements Serializable {

    private String basePath = "/scim/v2";
    private IdStrategy idStrategy = IdStrategy.UUID;
    private List<ObjectNode> initialUsers = new ArrayList<>();
    private List<ObjectNode> initialGroups = new ArrayList<>();
    private boolean enforceFilter = true;
    private boolean enforcePatch = true;
    private boolean requireBearerToken = false;
    private String expectedBearerToken = null;

    public ScimProviderConfiguration() {
    }

    @JsonProperty("basePath")
    public String getBasePath() {
        return basePath;
    }

    public ScimProviderConfiguration setBasePath(String basePath) {
        this.basePath = basePath;
        return this;
    }

    @JsonProperty("idStrategy")
    public IdStrategy getIdStrategy() {
        return idStrategy;
    }

    public ScimProviderConfiguration setIdStrategy(IdStrategy idStrategy) {
        this.idStrategy = idStrategy;
        return this;
    }

    @JsonProperty("initialUsers")
    public List<ObjectNode> getInitialUsers() {
        return initialUsers;
    }

    public ScimProviderConfiguration setInitialUsers(List<ObjectNode> initialUsers) {
        this.initialUsers = initialUsers;
        return this;
    }

    @JsonProperty("initialGroups")
    public List<ObjectNode> getInitialGroups() {
        return initialGroups;
    }

    public ScimProviderConfiguration setInitialGroups(List<ObjectNode> initialGroups) {
        this.initialGroups = initialGroups;
        return this;
    }

    @JsonProperty("enforceFilter")
    public boolean isEnforceFilter() {
        return enforceFilter;
    }

    public ScimProviderConfiguration setEnforceFilter(boolean enforceFilter) {
        this.enforceFilter = enforceFilter;
        return this;
    }

    @JsonProperty("enforcePatch")
    public boolean isEnforcePatch() {
        return enforcePatch;
    }

    public ScimProviderConfiguration setEnforcePatch(boolean enforcePatch) {
        this.enforcePatch = enforcePatch;
        return this;
    }

    @JsonProperty("requireBearerToken")
    public boolean isRequireBearerToken() {
        return requireBearerToken;
    }

    public ScimProviderConfiguration setRequireBearerToken(boolean requireBearerToken) {
        this.requireBearerToken = requireBearerToken;
        return this;
    }

    @JsonProperty("expectedBearerToken")
    public String getExpectedBearerToken() {
        return expectedBearerToken;
    }

    public ScimProviderConfiguration setExpectedBearerToken(String expectedBearerToken) {
        this.expectedBearerToken = expectedBearerToken;
        return this;
    }
}
