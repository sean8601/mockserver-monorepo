package org.mockserver.xds;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-coded protobuf message types for the minimal xDS RDS subset needed to
 * serve Envoy-compatible RouteConfiguration responses without the heavyweight
 * {@code io.envoyproxy.controlplane:api} or {@code grpc-java} dependencies.
 * <p>
 * Only the fields actually used by MockServer's RDS implementation are included.
 * Unknown fields are silently skipped on decode.
 * <p>
 * Protobuf field numbers follow the Envoy v3 API .proto definitions:
 * <ul>
 *   <li>envoy.service.discovery.v3.DiscoveryRequest</li>
 *   <li>envoy.service.discovery.v3.DiscoveryResponse</li>
 *   <li>google.protobuf.Any</li>
 *   <li>envoy.config.route.v3.RouteConfiguration</li>
 *   <li>envoy.config.route.v3.VirtualHost</li>
 *   <li>envoy.config.route.v3.Route</li>
 *   <li>envoy.config.route.v3.RouteMatch</li>
 *   <li>envoy.config.route.v3.RouteAction</li>
 * </ul>
 */
public final class XdsProtoMessages {

    public static final String ROUTE_CONFIGURATION_TYPE_URL = "type.googleapis.com/envoy.config.route.v3.RouteConfiguration";

    private XdsProtoMessages() {
    }

    // -----------------------------------------------------------------------
    // envoy.service.discovery.v3.DiscoveryRequest
    //   1: version_info (string)
    //   2: node (message, ignored)
    //   3: resource_names (repeated string)
    //   4: type_url (string)
    //   5: response_nonce (string)
    //   6: error_detail (message, ignored)
    // -----------------------------------------------------------------------

    public static class DiscoveryRequest {
        private String versionInfo;
        private List<String> resourceNames = new ArrayList<>();
        private String typeUrl;
        private String responseNonce;

        public String getVersionInfo() {
            return versionInfo;
        }

        public void setVersionInfo(String versionInfo) {
            this.versionInfo = versionInfo;
        }

        public List<String> getResourceNames() {
            return resourceNames;
        }

        public void setResourceNames(List<String> resourceNames) {
            this.resourceNames = resourceNames;
        }

        public String getTypeUrl() {
            return typeUrl;
        }

        public void setTypeUrl(String typeUrl) {
            this.typeUrl = typeUrl;
        }

        public String getResponseNonce() {
            return responseNonce;
        }

        public void setResponseNonce(String responseNonce) {
            this.responseNonce = responseNonce;
        }

        /**
         * Decode a DiscoveryRequest from protobuf wire format.
         */
        public static DiscoveryRequest decode(byte[] data) {
            DiscoveryRequest req = new DiscoveryRequest();
            ProtoReader reader = new ProtoReader(data);
            while (reader.hasRemaining()) {
                int[] tag = reader.readTag();
                int fieldNumber = tag[0];
                int wireType = tag[1];
                switch (fieldNumber) {
                    case 1: // version_info
                        req.versionInfo = reader.readString();
                        break;
                    case 3: // resource_names (repeated string)
                        req.resourceNames.add(reader.readString());
                        break;
                    case 4: // type_url
                        req.typeUrl = reader.readString();
                        break;
                    case 5: // response_nonce
                        req.responseNonce = reader.readString();
                        break;
                    default:
                        reader.skipField(wireType);
                        break;
                }
            }
            return req;
        }

        /**
         * Encode to protobuf wire format.
         */
        public byte[] encode() {
            ProtoWriter writer = new ProtoWriter();
            writer.writeString(1, versionInfo);
            if (resourceNames != null) {
                for (String name : resourceNames) {
                    writer.writeString(3, name);
                }
            }
            writer.writeString(4, typeUrl);
            writer.writeString(5, responseNonce);
            return writer.toByteArray();
        }
    }

    // -----------------------------------------------------------------------
    // envoy.service.discovery.v3.DiscoveryResponse
    //   1: version_info (string)
    //   2: resources (repeated google.protobuf.Any)
    //   3: canary (bool, deprecated — skipped on decode)
    //   4: type_url (string)
    //   5: nonce (string)
    //   6: control_plane (message, ignored)
    // -----------------------------------------------------------------------

    public static class DiscoveryResponse {
        private String versionInfo;
        private List<Any> resources = new ArrayList<>();
        private String typeUrl;
        private String nonce;

        public String getVersionInfo() {
            return versionInfo;
        }

        public void setVersionInfo(String versionInfo) {
            this.versionInfo = versionInfo;
        }

        public List<Any> getResources() {
            return resources;
        }

        public void setResources(List<Any> resources) {
            this.resources = resources;
        }

        public String getTypeUrl() {
            return typeUrl;
        }

        public void setTypeUrl(String typeUrl) {
            this.typeUrl = typeUrl;
        }

        public String getNonce() {
            return nonce;
        }

        public void setNonce(String nonce) {
            this.nonce = nonce;
        }

        public byte[] encode() {
            ProtoWriter writer = new ProtoWriter();
            writer.writeString(1, versionInfo);
            if (resources != null) {
                for (Any any : resources) {
                    writer.writeMessage(2, any.encode());
                }
            }
            // field 3 is canary (deprecated bool) — not encoded
            writer.writeString(4, typeUrl);
            writer.writeString(5, nonce);
            return writer.toByteArray();
        }

        public static DiscoveryResponse decode(byte[] data) {
            DiscoveryResponse resp = new DiscoveryResponse();
            ProtoReader reader = new ProtoReader(data);
            while (reader.hasRemaining()) {
                int[] tag = reader.readTag();
                int fieldNumber = tag[0];
                int wireType = tag[1];
                switch (fieldNumber) {
                    case 1: // version_info
                        resp.versionInfo = reader.readString();
                        break;
                    case 2: // resources (repeated Any)
                        resp.resources.add(Any.decode(reader.readLengthDelimited()));
                        break;
                    case 4: // type_url
                        resp.typeUrl = reader.readString();
                        break;
                    case 5: // nonce
                        resp.nonce = reader.readString();
                        break;
                    default: // field 3 (canary), field 6 (control_plane), etc.
                        reader.skipField(wireType);
                        break;
                }
            }
            return resp;
        }
    }

    // -----------------------------------------------------------------------
    // google.protobuf.Any
    //   1: type_url (string)
    //   2: value (bytes)
    // -----------------------------------------------------------------------

    public static class Any {
        private String typeUrl;
        private byte[] value;

        public Any() {
        }

        public Any(String typeUrl, byte[] value) {
            this.typeUrl = typeUrl;
            this.value = value;
        }

        public String getTypeUrl() {
            return typeUrl;
        }

        public void setTypeUrl(String typeUrl) {
            this.typeUrl = typeUrl;
        }

        public byte[] getValue() {
            return value;
        }

        public void setValue(byte[] value) {
            this.value = value;
        }

        public byte[] encode() {
            ProtoWriter writer = new ProtoWriter();
            writer.writeString(1, typeUrl);
            writer.writeBytes(2, value);
            return writer.toByteArray();
        }

        public static Any decode(byte[] data) {
            Any any = new Any();
            ProtoReader reader = new ProtoReader(data);
            while (reader.hasRemaining()) {
                int[] tag = reader.readTag();
                int fieldNumber = tag[0];
                int wireType = tag[1];
                switch (fieldNumber) {
                    case 1:
                        any.typeUrl = reader.readString();
                        break;
                    case 2:
                        any.value = reader.readLengthDelimited();
                        break;
                    default:
                        reader.skipField(wireType);
                        break;
                }
            }
            return any;
        }
    }

    // -----------------------------------------------------------------------
    // envoy.config.route.v3.RouteConfiguration
    //   1: name (string)
    //   2: virtual_hosts (repeated VirtualHost)
    // -----------------------------------------------------------------------

    public static class RouteConfiguration {
        private String name;
        private List<VirtualHost> virtualHosts = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<VirtualHost> getVirtualHosts() {
            return virtualHosts;
        }

        public void setVirtualHosts(List<VirtualHost> virtualHosts) {
            this.virtualHosts = virtualHosts;
        }

        public byte[] encode() {
            ProtoWriter writer = new ProtoWriter();
            writer.writeString(1, name);
            if (virtualHosts != null) {
                for (VirtualHost vh : virtualHosts) {
                    writer.writeMessage(2, vh.encode());
                }
            }
            return writer.toByteArray();
        }

        public static RouteConfiguration decode(byte[] data) {
            RouteConfiguration rc = new RouteConfiguration();
            ProtoReader reader = new ProtoReader(data);
            while (reader.hasRemaining()) {
                int[] tag = reader.readTag();
                int fieldNumber = tag[0];
                int wireType = tag[1];
                switch (fieldNumber) {
                    case 1:
                        rc.name = reader.readString();
                        break;
                    case 2:
                        rc.virtualHosts.add(VirtualHost.decode(reader.readLengthDelimited()));
                        break;
                    default:
                        reader.skipField(wireType);
                        break;
                }
            }
            return rc;
        }
    }

    // -----------------------------------------------------------------------
    // envoy.config.route.v3.VirtualHost
    //   1: name (string)
    //   2: domains (repeated string)
    //   3: routes (repeated Route)
    // -----------------------------------------------------------------------

    public static class VirtualHost {
        private String name;
        private List<String> domains = new ArrayList<>();
        private List<Route> routes = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getDomains() {
            return domains;
        }

        public void setDomains(List<String> domains) {
            this.domains = domains;
        }

        public List<Route> getRoutes() {
            return routes;
        }

        public void setRoutes(List<Route> routes) {
            this.routes = routes;
        }

        public byte[] encode() {
            ProtoWriter writer = new ProtoWriter();
            writer.writeString(1, name);
            if (domains != null) {
                for (String domain : domains) {
                    writer.writeString(2, domain);
                }
            }
            if (routes != null) {
                for (Route route : routes) {
                    writer.writeMessage(3, route.encode());
                }
            }
            return writer.toByteArray();
        }

        public static VirtualHost decode(byte[] data) {
            VirtualHost vh = new VirtualHost();
            ProtoReader reader = new ProtoReader(data);
            while (reader.hasRemaining()) {
                int[] tag = reader.readTag();
                int fieldNumber = tag[0];
                int wireType = tag[1];
                switch (fieldNumber) {
                    case 1:
                        vh.name = reader.readString();
                        break;
                    case 2:
                        vh.domains.add(reader.readString());
                        break;
                    case 3:
                        vh.routes.add(Route.decode(reader.readLengthDelimited()));
                        break;
                    default:
                        reader.skipField(wireType);
                        break;
                }
            }
            return vh;
        }
    }

    // -----------------------------------------------------------------------
    // envoy.config.route.v3.Route
    //   1: match (RouteMatch)
    //   2: route (RouteAction)
    // -----------------------------------------------------------------------

    public static class Route {
        private RouteMatch match;
        private RouteAction routeAction;

        public RouteMatch getMatch() {
            return match;
        }

        public void setMatch(RouteMatch match) {
            this.match = match;
        }

        public RouteAction getRouteAction() {
            return routeAction;
        }

        public void setRouteAction(RouteAction routeAction) {
            this.routeAction = routeAction;
        }

        public byte[] encode() {
            ProtoWriter writer = new ProtoWriter();
            if (match != null) {
                writer.writeMessage(1, match.encode());
            }
            if (routeAction != null) {
                writer.writeMessage(2, routeAction.encode());
            }
            return writer.toByteArray();
        }

        public static Route decode(byte[] data) {
            Route route = new Route();
            ProtoReader reader = new ProtoReader(data);
            while (reader.hasRemaining()) {
                int[] tag = reader.readTag();
                int fieldNumber = tag[0];
                int wireType = tag[1];
                switch (fieldNumber) {
                    case 1:
                        route.match = RouteMatch.decode(reader.readLengthDelimited());
                        break;
                    case 2:
                        route.routeAction = RouteAction.decode(reader.readLengthDelimited());
                        break;
                    default:
                        reader.skipField(wireType);
                        break;
                }
            }
            return route;
        }
    }

    // -----------------------------------------------------------------------
    // envoy.config.route.v3.RouteMatch
    //   1: prefix (string) -- oneof path_specifier
    //   2: path (string) -- oneof path_specifier
    // -----------------------------------------------------------------------

    public static class RouteMatch {
        private String prefix;
        private String path;

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public byte[] encode() {
            ProtoWriter writer = new ProtoWriter();
            // only one of prefix or path should be set (oneof path_specifier)
            if (path != null && !path.isEmpty()) {
                writer.writeString(2, path);
            } else if (prefix != null) {
                writer.writeString(1, prefix);
            }
            return writer.toByteArray();
        }

        public static RouteMatch decode(byte[] data) {
            RouteMatch match = new RouteMatch();
            ProtoReader reader = new ProtoReader(data);
            while (reader.hasRemaining()) {
                int[] tag = reader.readTag();
                int fieldNumber = tag[0];
                int wireType = tag[1];
                switch (fieldNumber) {
                    case 1:
                        match.prefix = reader.readString();
                        break;
                    case 2:
                        match.path = reader.readString();
                        break;
                    default:
                        reader.skipField(wireType);
                        break;
                }
            }
            return match;
        }
    }

    // -----------------------------------------------------------------------
    // envoy.config.route.v3.RouteAction
    //   1: cluster (string) -- oneof cluster_specifier
    // -----------------------------------------------------------------------

    public static class RouteAction {
        private String cluster;

        public String getCluster() {
            return cluster;
        }

        public void setCluster(String cluster) {
            this.cluster = cluster;
        }

        public byte[] encode() {
            ProtoWriter writer = new ProtoWriter();
            writer.writeString(1, cluster);
            return writer.toByteArray();
        }

        public static RouteAction decode(byte[] data) {
            RouteAction action = new RouteAction();
            ProtoReader reader = new ProtoReader(data);
            while (reader.hasRemaining()) {
                int[] tag = reader.readTag();
                int fieldNumber = tag[0];
                int wireType = tag[1];
                if (fieldNumber == 1) {
                    action.cluster = reader.readString();
                } else {
                    reader.skipField(wireType);
                }
            }
            return action;
        }
    }
}
