package org.mockserver.xds;

import org.junit.Test;
import org.mockserver.mock.Expectation;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

public class XdsRouteBuilderTest {

    private final XdsRouteBuilder builder = new XdsRouteBuilder();

    @Test
    public void shouldBuildRouteConfigurationFromExpectations() {
        // given
        Expectation expectation = new Expectation(request().withMethod("GET").withPath("/api/users"));

        // when
        Map<String, Object> result = builder.buildRouteConfiguration(List.of(expectation));

        // then
        assertThat(result.get("name"), is("mockserver_routes"));
        assertThat(result.containsKey("virtual_hosts"), is(true));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hosts = (List<Map<String, Object>>) result.get("virtual_hosts");
        assertThat(hosts, hasSize(1));
        assertThat(hosts.get(0).get("name"), is("mockserver"));

        @SuppressWarnings("unchecked")
        List<String> domains = (List<String>) hosts.get(0).get("domains");
        assertThat(domains, contains("*"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routes = (List<Map<String, Object>>) hosts.get(0).get("routes");
        assertThat(routes, hasSize(1));

        @SuppressWarnings("unchecked")
        Map<String, Object> match = (Map<String, Object>) routes.get(0).get("match");
        assertThat(match.get("path"), is("/api/users"));
        assertThat(match.get("method"), is("GET"));
        assertThat(routes.get(0).get("expectationId"), is(notNullValue()));
    }

    @Test
    public void shouldReturnEmptyRoutesForEmptyExpectations() {
        // when
        Map<String, Object> result = builder.buildRouteConfiguration(Collections.emptyList());

        // then
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hosts = (List<Map<String, Object>>) result.get("virtual_hosts");
        @SuppressWarnings("unchecked")
        List<Object> routes = (List<Object>) hosts.get(0).get("routes");
        assertThat(routes, is(empty()));
    }

    @Test
    public void shouldHandleMultipleExpectations() {
        // given
        Expectation getUsers = new Expectation(request().withMethod("GET").withPath("/api/users"));
        Expectation postUsers = new Expectation(request().withMethod("POST").withPath("/api/users"));
        Expectation getOrders = new Expectation(request().withMethod("GET").withPath("/api/orders"));

        // when
        Map<String, Object> result = builder.buildRouteConfiguration(List.of(getUsers, postUsers, getOrders));

        // then
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hosts = (List<Map<String, Object>>) result.get("virtual_hosts");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routes = (List<Map<String, Object>>) hosts.get(0).get("routes");
        assertThat(routes, hasSize(3));
    }

    @Test
    public void shouldHandleExpectationWithPathOnly() {
        // given
        Expectation expectation = new Expectation(request().withPath("/health"));

        // when
        Map<String, Object> result = builder.buildRouteConfiguration(List.of(expectation));

        // then
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hosts = (List<Map<String, Object>>) result.get("virtual_hosts");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routes = (List<Map<String, Object>>) hosts.get(0).get("routes");
        @SuppressWarnings("unchecked")
        Map<String, Object> match = (Map<String, Object>) routes.get(0).get("match");
        assertThat(match.get("path"), is("/health"));
    }

    // --- Protobuf encoding tests ---

    @Test
    public void shouldBuildProtobufRouteConfigurationFromExpectations() {
        // given
        Expectation expectation = new Expectation(request().withMethod("GET").withPath("/api/users"));

        // when
        byte[] protoBytes = builder.buildRouteConfigurationProto(List.of(expectation));

        // then - decode and verify
        XdsProtoMessages.RouteConfiguration rc = XdsProtoMessages.RouteConfiguration.decode(protoBytes);
        assertThat(rc.getName(), is("mockserver_routes"));
        assertThat(rc.getVirtualHosts(), hasSize(1));

        XdsProtoMessages.VirtualHost vh = rc.getVirtualHosts().get(0);
        assertThat(vh.getName(), is("mockserver"));
        assertThat(vh.getDomains(), contains("*"));
        assertThat(vh.getRoutes(), hasSize(1));

        XdsProtoMessages.Route route = vh.getRoutes().get(0);
        assertThat(route.getMatch().getPath(), is("/api/users"));
        assertThat(route.getRouteAction().getCluster(), is("mockserver"));
    }

    @Test
    public void shouldBuildProtobufWithMultipleRoutes() {
        // given
        Expectation e1 = new Expectation(request().withMethod("GET").withPath("/api/users"));
        Expectation e2 = new Expectation(request().withMethod("POST").withPath("/api/orders"));

        // when
        byte[] protoBytes = builder.buildRouteConfigurationProto(List.of(e1, e2));

        // then
        XdsProtoMessages.RouteConfiguration rc = XdsProtoMessages.RouteConfiguration.decode(protoBytes);
        assertThat(rc.getVirtualHosts().get(0).getRoutes(), hasSize(2));

        XdsProtoMessages.Route route1 = rc.getVirtualHosts().get(0).getRoutes().get(0);
        assertThat(route1.getMatch().getPath(), is("/api/users"));

        XdsProtoMessages.Route route2 = rc.getVirtualHosts().get(0).getRoutes().get(1);
        assertThat(route2.getMatch().getPath(), is("/api/orders"));
    }

    @Test
    public void shouldBuildProtobufEmptyRoutes() {
        // when
        byte[] protoBytes = builder.buildRouteConfigurationProto(Collections.emptyList());

        // then
        XdsProtoMessages.RouteConfiguration rc = XdsProtoMessages.RouteConfiguration.decode(protoBytes);
        assertThat(rc.getName(), is("mockserver_routes"));
        assertThat(rc.getVirtualHosts(), hasSize(1));
        assertThat(rc.getVirtualHosts().get(0).getRoutes(), is(empty()));
    }

    @Test
    public void shouldUsePathExactMatchWhenPathIsSet() {
        // given
        Expectation expectation = new Expectation(request().withPath("/exact/path"));

        // when
        byte[] protoBytes = builder.buildRouteConfigurationProto(List.of(expectation));

        // then
        XdsProtoMessages.RouteConfiguration rc = XdsProtoMessages.RouteConfiguration.decode(protoBytes);
        XdsProtoMessages.RouteMatch match = rc.getVirtualHosts().get(0).getRoutes().get(0).getMatch();
        assertThat(match.getPath(), is("/exact/path"));
        assertThat(match.getPrefix(), is(nullValue()));
    }

    @Test
    public void shouldUsePrefixMatchWhenPathIsNull() {
        // given - expectation without a path defaults to prefix "/"
        Expectation expectation = new Expectation(request().withMethod("GET"));

        // when
        byte[] protoBytes = builder.buildRouteConfigurationProto(List.of(expectation));

        // then
        XdsProtoMessages.RouteConfiguration rc = XdsProtoMessages.RouteConfiguration.decode(protoBytes);
        XdsProtoMessages.RouteMatch match = rc.getVirtualHosts().get(0).getRoutes().get(0).getMatch();
        assertThat(match.getPrefix(), is("/"));
        assertThat(match.getPath(), is(nullValue()));
    }
}
