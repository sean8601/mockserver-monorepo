package org.mockserver.xds;

import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;

import java.util.*;

/**
 * Builds xDS-compatible RouteConfiguration from active MockServer expectations.
 * Supports both:
 * <ul>
 *   <li>Simplified JSON map (for the REST endpoint at GET /mockserver/xds/routes)</li>
 *   <li>Protobuf-encoded {@code envoy.config.route.v3.RouteConfiguration} bytes
 *       (for the gRPC RDS server)</li>
 * </ul>
 */
public class XdsRouteBuilder {

    /**
     * Converts expectations to a simplified xDS RouteConfiguration structure.
     *
     * @param expectations the active expectations to convert
     * @return a map representing the xDS RouteConfiguration JSON
     */
    public Map<String, Object> buildRouteConfiguration(List<Expectation> expectations) {
        List<Map<String, Object>> routes = new ArrayList<>();
        for (Expectation expectation : expectations) {
            RequestDefinition requestDefinition = expectation.getHttpRequest();
            if (requestDefinition instanceof HttpRequest req) {
                Map<String, Object> route = new LinkedHashMap<>();
                Map<String, Object> match = new LinkedHashMap<>();

                if (req.getPath() != null && req.getPath().getValue() != null) {
                    match.put("path", req.getPath().getValue());
                }
                if (req.getMethod() != null && req.getMethod().getValue() != null
                    && !req.getMethod().getValue().isEmpty()) {
                    match.put("method", req.getMethod().getValue());
                }

                route.put("match", match);
                route.put("expectationId", expectation.getId());
                routes.add(route);
            }
        }

        Map<String, Object> virtualHost = new LinkedHashMap<>();
        virtualHost.put("name", "mockserver");
        virtualHost.put("domains", Collections.singletonList("*"));
        virtualHost.put("routes", routes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "mockserver_routes");
        result.put("virtual_hosts", Collections.singletonList(virtualHost));
        return result;
    }

    /**
     * Converts expectations to a protobuf-encoded {@code envoy.config.route.v3.RouteConfiguration}.
     * <p>
     * Each expectation with a path produces an Envoy Route with:
     * <ul>
     *   <li>RouteMatch.path = the expectation's path (exact match)</li>
     *   <li>RouteAction.cluster = "mockserver" (fixed cluster name)</li>
     * </ul>
     * Expectations without a concrete path are included with a prefix "/" (match-all).
     *
     * @param expectations the active expectations to convert
     * @return serialized protobuf bytes for a RouteConfiguration message
     */
    public byte[] buildRouteConfigurationProto(List<Expectation> expectations) {
        List<XdsProtoMessages.Route> protoRoutes = new ArrayList<>();
        for (Expectation expectation : expectations) {
            RequestDefinition requestDefinition = expectation.getHttpRequest();
            if (requestDefinition instanceof HttpRequest req) {
                XdsProtoMessages.RouteMatch match = new XdsProtoMessages.RouteMatch();
                if (req.getPath() != null && req.getPath().getValue() != null
                    && !req.getPath().getValue().isEmpty()) {
                    match.setPath(req.getPath().getValue());
                } else {
                    match.setPrefix("/");
                }

                XdsProtoMessages.RouteAction action = new XdsProtoMessages.RouteAction();
                action.setCluster("mockserver");

                XdsProtoMessages.Route route = new XdsProtoMessages.Route();
                route.setMatch(match);
                route.setRouteAction(action);
                protoRoutes.add(route);
            }
        }

        XdsProtoMessages.VirtualHost virtualHost = new XdsProtoMessages.VirtualHost();
        virtualHost.setName("mockserver");
        virtualHost.setDomains(Collections.singletonList("*"));
        virtualHost.setRoutes(protoRoutes);

        XdsProtoMessages.RouteConfiguration routeConfig = new XdsProtoMessages.RouteConfiguration();
        routeConfig.setName("mockserver_routes");
        routeConfig.setVirtualHosts(Collections.singletonList(virtualHost));

        return routeConfig.encode();
    }
}
