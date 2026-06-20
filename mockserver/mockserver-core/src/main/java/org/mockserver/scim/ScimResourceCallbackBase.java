package org.mockserver.scim;

import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.mock.crud.CrudDataStore;
import org.mockserver.model.HttpRequest;
import org.mockserver.scim.ScimResourceStore.Provider;

/**
 * Common base for the SCIM resource callbacks. Resolves the provider, applies the bearer gate, and
 * determines whether the request targets the {@code Users} or {@code Groups} collection (so a
 * single callback class can serve both resource types).
 */
abstract class ScimResourceCallbackBase implements ExpectationResponseCallback {

    /**
     * Resolves the resource type from the request path relative to the provider base path.
     */
    static ScimShaper.ResourceType resourceType(HttpRequest request, Provider provider) {
        String path = request.getPath().getValue();
        String groupsPrefix = provider.getBasePath() + "/" + ScimShaper.ResourceType.GROUP.getPathSegment();
        if (path.equals(groupsPrefix) || path.startsWith(groupsPrefix + "/")) {
            return ScimShaper.ResourceType.GROUP;
        }
        return ScimShaper.ResourceType.USER;
    }

    static CrudDataStore storeFor(Provider provider, ScimShaper.ResourceType type) {
        return type == ScimShaper.ResourceType.GROUP ? provider.getGroups() : provider.getUsers();
    }
}
