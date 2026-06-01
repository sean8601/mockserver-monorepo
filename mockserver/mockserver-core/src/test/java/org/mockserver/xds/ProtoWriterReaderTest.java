package org.mockserver.xds;

import org.junit.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Round-trip unit tests for {@link ProtoWriter} and {@link ProtoReader}: varint,
 * string, length-delimited, repeated, nested message, and Any wrapper.
 */
public class ProtoWriterReaderTest {

    @Test
    public void shouldRoundTripVarint() {
        // write a varint and read it back
        ProtoWriter writer = new ProtoWriter();
        writer.writeRawVarint(0);
        writer.writeRawVarint(1);
        writer.writeRawVarint(127);
        writer.writeRawVarint(128);
        writer.writeRawVarint(300);
        writer.writeRawVarint(16384);
        writer.writeRawVarint(Long.MAX_VALUE);

        byte[] data = writer.toByteArray();
        ProtoReader reader = new ProtoReader(data);

        assertThat(reader.readVarint(), is(0L));
        assertThat(reader.readVarint(), is(1L));
        assertThat(reader.readVarint(), is(127L));
        assertThat(reader.readVarint(), is(128L));
        assertThat(reader.readVarint(), is(300L));
        assertThat(reader.readVarint(), is(16384L));
        assertThat(reader.readVarint(), is(Long.MAX_VALUE));
        assertThat(reader.hasRemaining(), is(false));
    }

    @Test
    public void shouldRoundTripStringField() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeString(1, "hello");
        writer.writeString(2, "world");

        byte[] data = writer.toByteArray();
        assertThat(ProtoReader.readFirstString(data, 1), is("hello"));
        assertThat(ProtoReader.readFirstString(data, 2), is("world"));
        assertThat(ProtoReader.readFirstString(data, 3), is(nullValue()));
    }

    @Test
    public void shouldSkipEmptyStrings() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeString(1, null);
        writer.writeString(2, "");
        writer.writeString(3, "present");

        byte[] data = writer.toByteArray();
        assertThat(ProtoReader.readFirstString(data, 1), is(nullValue()));
        assertThat(ProtoReader.readFirstString(data, 2), is(nullValue()));
        assertThat(ProtoReader.readFirstString(data, 3), is("present"));
    }

    @Test
    public void shouldRoundTripRepeatedStrings() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeString(3, "alpha");
        writer.writeString(3, "beta");
        writer.writeString(3, "gamma");

        byte[] data = writer.toByteArray();
        List<String> values = ProtoReader.readRepeatedString(data, 3);
        assertThat(values, hasSize(3));
        assertThat(values, contains("alpha", "beta", "gamma"));
    }

    @Test
    public void shouldRoundTripBytesField() {
        byte[] payload = {1, 2, 3, 4, 5};
        ProtoWriter writer = new ProtoWriter();
        writer.writeBytes(2, payload);

        byte[] data = writer.toByteArray();
        List<byte[]> values = ProtoReader.readRepeatedBytes(data, 2);
        assertThat(values, hasSize(1));
        assertThat(values.get(0), is(payload));
    }

    @Test
    public void shouldSkipEmptyBytes() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeBytes(1, null);
        writer.writeBytes(2, new byte[0]);
        writer.writeString(3, "after");

        byte[] data = writer.toByteArray();
        assertThat(ProtoReader.readRepeatedBytes(data, 1), is(empty()));
        assertThat(ProtoReader.readRepeatedBytes(data, 2), is(empty()));
        assertThat(ProtoReader.readFirstString(data, 3), is("after"));
    }

    @Test
    public void shouldRoundTripNestedMessage() {
        // Simulate a nested message: field 1 = string "inner"
        ProtoWriter innerWriter = new ProtoWriter();
        innerWriter.writeString(1, "inner");
        byte[] innerBytes = innerWriter.toByteArray();

        ProtoWriter outerWriter = new ProtoWriter();
        outerWriter.writeMessage(2, innerBytes);
        outerWriter.writeString(1, "outer");

        byte[] outerData = outerWriter.toByteArray();

        // Read the outer message
        assertThat(ProtoReader.readFirstString(outerData, 1), is("outer"));

        // Read the nested message
        List<byte[]> nested = ProtoReader.readRepeatedBytes(outerData, 2);
        assertThat(nested, hasSize(1));
        assertThat(ProtoReader.readFirstString(nested.get(0), 1), is("inner"));
    }

    @Test
    public void shouldRoundTripAnyWrapper() {
        String typeUrl = "type.googleapis.com/test.Message";
        byte[] value = {10, 20, 30};

        XdsProtoMessages.Any original = new XdsProtoMessages.Any(typeUrl, value);
        byte[] encoded = original.encode();

        XdsProtoMessages.Any decoded = XdsProtoMessages.Any.decode(encoded);
        assertThat(decoded.getTypeUrl(), is(typeUrl));
        assertThat(decoded.getValue(), is(value));
    }

    @Test
    public void shouldRoundTripDiscoveryRequest() {
        XdsProtoMessages.DiscoveryRequest req = new XdsProtoMessages.DiscoveryRequest();
        req.setVersionInfo("v1");
        req.setResourceNames(List.of("route1", "route2"));
        req.setTypeUrl("type.googleapis.com/envoy.config.route.v3.RouteConfiguration");
        req.setResponseNonce("nonce-42");

        byte[] encoded = req.encode();
        XdsProtoMessages.DiscoveryRequest decoded = XdsProtoMessages.DiscoveryRequest.decode(encoded);

        assertThat(decoded.getVersionInfo(), is("v1"));
        assertThat(decoded.getResourceNames(), contains("route1", "route2"));
        assertThat(decoded.getTypeUrl(), is("type.googleapis.com/envoy.config.route.v3.RouteConfiguration"));
        assertThat(decoded.getResponseNonce(), is("nonce-42"));
    }

    @Test
    public void shouldRoundTripDiscoveryResponse() {
        byte[] routeConfigBytes = {1, 2, 3};
        XdsProtoMessages.Any resource = new XdsProtoMessages.Any(
            XdsProtoMessages.ROUTE_CONFIGURATION_TYPE_URL,
            routeConfigBytes
        );

        XdsProtoMessages.DiscoveryResponse resp = new XdsProtoMessages.DiscoveryResponse();
        resp.setVersionInfo("v2");
        resp.setResources(List.of(resource));
        resp.setTypeUrl(XdsProtoMessages.ROUTE_CONFIGURATION_TYPE_URL);
        resp.setNonce("nonce-99");

        byte[] encoded = resp.encode();
        XdsProtoMessages.DiscoveryResponse decoded = XdsProtoMessages.DiscoveryResponse.decode(encoded);

        assertThat(decoded.getVersionInfo(), is("v2"));
        assertThat(decoded.getTypeUrl(), is(XdsProtoMessages.ROUTE_CONFIGURATION_TYPE_URL));
        assertThat(decoded.getNonce(), is("nonce-99"));
        assertThat(decoded.getResources(), hasSize(1));
        assertThat(decoded.getResources().get(0).getTypeUrl(), is(XdsProtoMessages.ROUTE_CONFIGURATION_TYPE_URL));
        assertThat(decoded.getResources().get(0).getValue(), is(routeConfigBytes));
    }

    @Test
    public void shouldRoundTripRouteConfiguration() {
        XdsProtoMessages.RouteMatch match = new XdsProtoMessages.RouteMatch();
        match.setPath("/api/users");

        XdsProtoMessages.RouteAction action = new XdsProtoMessages.RouteAction();
        action.setCluster("mockserver");

        XdsProtoMessages.Route route = new XdsProtoMessages.Route();
        route.setMatch(match);
        route.setRouteAction(action);

        XdsProtoMessages.VirtualHost vh = new XdsProtoMessages.VirtualHost();
        vh.setName("mockserver");
        vh.setDomains(List.of("*"));
        vh.setRoutes(List.of(route));

        XdsProtoMessages.RouteConfiguration rc = new XdsProtoMessages.RouteConfiguration();
        rc.setName("mockserver_routes");
        rc.setVirtualHosts(List.of(vh));

        byte[] encoded = rc.encode();
        XdsProtoMessages.RouteConfiguration decoded = XdsProtoMessages.RouteConfiguration.decode(encoded);

        assertThat(decoded.getName(), is("mockserver_routes"));
        assertThat(decoded.getVirtualHosts(), hasSize(1));

        XdsProtoMessages.VirtualHost decodedVh = decoded.getVirtualHosts().get(0);
        assertThat(decodedVh.getName(), is("mockserver"));
        assertThat(decodedVh.getDomains(), contains("*"));
        assertThat(decodedVh.getRoutes(), hasSize(1));

        XdsProtoMessages.Route decodedRoute = decodedVh.getRoutes().get(0);
        assertThat(decodedRoute.getMatch().getPath(), is("/api/users"));
        assertThat(decodedRoute.getMatch().getPrefix(), is(nullValue()));
        assertThat(decodedRoute.getRouteAction().getCluster(), is("mockserver"));
    }

    @Test
    public void shouldRoundTripRouteMatchWithPrefix() {
        XdsProtoMessages.RouteMatch match = new XdsProtoMessages.RouteMatch();
        match.setPrefix("/api/");

        byte[] encoded = match.encode();
        XdsProtoMessages.RouteMatch decoded = XdsProtoMessages.RouteMatch.decode(encoded);

        assertThat(decoded.getPrefix(), is("/api/"));
        assertThat(decoded.getPath(), is(nullValue()));
    }

    @Test
    public void shouldSkipUnknownFieldsGracefully() {
        // Write a message with extra unknown fields (varint at field 99)
        ProtoWriter writer = new ProtoWriter();
        writer.writeString(1, "known");
        writer.writeUInt32(99, 42);
        writer.writeString(2, "also_known");

        byte[] data = writer.toByteArray();

        // The reader should skip field 99 and still find fields 1 and 2
        assertThat(ProtoReader.readFirstString(data, 1), is("known"));
        assertThat(ProtoReader.readFirstString(data, 2), is("also_known"));
    }

    @Test
    public void shouldHandleUInt32Field() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeUInt32(1, 42);
        writer.writeString(2, "after");

        byte[] data = writer.toByteArray();
        // Read the varint field manually
        ProtoReader reader = new ProtoReader(data);
        int[] tag = reader.readTag();
        assertThat(tag[0], is(1));
        assertThat(tag[1], is(ProtoReader.WIRE_TYPE_VARINT));
        assertThat((int) reader.readVarint(), is(42));

        // String should still be readable after varint
        tag = reader.readTag();
        assertThat(tag[0], is(2));
        assertThat(reader.readString(), is("after"));
    }

    @Test
    public void shouldSkipZeroValueUInt32() {
        // writeUInt32 with 0 should not write anything (proto3 default)
        ProtoWriter writer = new ProtoWriter();
        writer.writeUInt32(1, 0);
        assertThat(writer.size(), is(0));
    }

    @Test
    public void shouldHandleWriterReset() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeString(1, "first");
        assertThat(writer.size(), is(greaterThan(0)));

        writer.reset();
        assertThat(writer.size(), is(0));

        writer.writeString(1, "second");
        byte[] data = writer.toByteArray();
        assertThat(ProtoReader.readFirstString(data, 1), is("second"));
    }

    // -----------------------------------------------------------------------
    // GOLDEN-BYTES test: DiscoveryResponse with corrected Envoy v3 field numbers
    //
    // Verifies the encoder produces exactly the wire bytes mandated by the
    // Envoy v3 proto definitions:
    //   DiscoveryResponse: version_info=1, resources=2, canary=3(skip), type_url=4, nonce=5
    //   Any: type_url=1, value=2
    //   RouteConfiguration: name=1, virtual_hosts=2
    //   VirtualHost: name=1, domains=2, routes=3
    //   Route: match=1, route=2
    //   RouteMatch: prefix=1
    //   RouteAction: cluster=1
    // -----------------------------------------------------------------------
    @Test
    public void shouldEncodeDiscoveryResponseWithCorrectFieldNumbers() {
        // Build: RouteAction(cluster="ms")
        ProtoWriter actionW = new ProtoWriter();
        actionW.writeString(1, "ms"); // cluster
        byte[] actionBytes = actionW.toByteArray();

        // Build: RouteMatch(prefix="/")
        ProtoWriter matchW = new ProtoWriter();
        matchW.writeString(1, "/"); // prefix
        byte[] matchBytes = matchW.toByteArray();

        // Build: Route(match=1, route=2)
        ProtoWriter routeW = new ProtoWriter();
        routeW.writeMessage(1, matchBytes);
        routeW.writeMessage(2, actionBytes);
        byte[] routeBytes = routeW.toByteArray();

        // Build: VirtualHost(name="vh", domains=["*"], routes=[route])
        ProtoWriter vhW = new ProtoWriter();
        vhW.writeString(1, "vh"); // name
        vhW.writeString(2, "*");  // domains (repeated)
        vhW.writeMessage(3, routeBytes); // routes (repeated)
        byte[] vhBytes = vhW.toByteArray();

        // Build: RouteConfiguration(name="rc", virtual_hosts=[vh])
        ProtoWriter rcW = new ProtoWriter();
        rcW.writeString(1, "rc"); // name
        rcW.writeMessage(2, vhBytes); // virtual_hosts (repeated)
        byte[] rcBytes = rcW.toByteArray();

        // Build: Any(type_url=1, value=2)
        String typeUrl = XdsProtoMessages.ROUTE_CONFIGURATION_TYPE_URL;
        ProtoWriter anyW = new ProtoWriter();
        anyW.writeString(1, typeUrl);
        anyW.writeBytes(2, rcBytes);
        byte[] anyBytes = anyW.toByteArray();

        // Build expected DiscoveryResponse by hand with correct field numbers:
        //   field 1 (string) = "v1"   (version_info)
        //   field 2 (message)= anyBytes (resources)
        //   field 4 (string) = typeUrl (type_url)
        //   field 5 (string) = "n1"   (nonce)
        ProtoWriter expectedW = new ProtoWriter();
        expectedW.writeString(1, "v1");
        expectedW.writeMessage(2, anyBytes);
        expectedW.writeString(4, typeUrl);
        expectedW.writeString(5, "n1");
        byte[] expectedBytes = expectedW.toByteArray();

        // Now build the same via the domain objects
        XdsProtoMessages.RouteMatch match = new XdsProtoMessages.RouteMatch();
        match.setPrefix("/");
        XdsProtoMessages.RouteAction action = new XdsProtoMessages.RouteAction();
        action.setCluster("ms");
        XdsProtoMessages.Route route = new XdsProtoMessages.Route();
        route.setMatch(match);
        route.setRouteAction(action);
        XdsProtoMessages.VirtualHost vh = new XdsProtoMessages.VirtualHost();
        vh.setName("vh");
        vh.setDomains(List.of("*"));
        vh.setRoutes(List.of(route));
        XdsProtoMessages.RouteConfiguration rc = new XdsProtoMessages.RouteConfiguration();
        rc.setName("rc");
        rc.setVirtualHosts(List.of(vh));

        XdsProtoMessages.Any any = new XdsProtoMessages.Any(typeUrl, rc.encode());
        XdsProtoMessages.DiscoveryResponse resp = new XdsProtoMessages.DiscoveryResponse();
        resp.setVersionInfo("v1");
        resp.setResources(List.of(any));
        resp.setTypeUrl(typeUrl);
        resp.setNonce("n1");

        byte[] actualBytes = resp.encode();

        // Assert exact wire-byte equality
        assertThat("DiscoveryResponse wire bytes must match hand-built golden bytes "
                + "(field numbers: version_info=1, resources=2, type_url=4, nonce=5)",
            actualBytes, is(expectedBytes));

        // Verify the golden bytes decode correctly too
        XdsProtoMessages.DiscoveryResponse decoded = XdsProtoMessages.DiscoveryResponse.decode(expectedBytes);
        assertThat(decoded.getVersionInfo(), is("v1"));
        assertThat(decoded.getTypeUrl(), is(typeUrl));
        assertThat(decoded.getNonce(), is("n1"));
        assertThat(decoded.getResources(), hasSize(1));
        XdsProtoMessages.RouteConfiguration decodedRc = XdsProtoMessages.RouteConfiguration.decode(
            decoded.getResources().get(0).getValue()
        );
        assertThat(decodedRc.getName(), is("rc"));
        assertThat(decodedRc.getVirtualHosts().get(0).getName(), is("vh"));
        assertThat(decodedRc.getVirtualHosts().get(0).getDomains(), contains("*"));
        assertThat(decodedRc.getVirtualHosts().get(0).getRoutes().get(0).getMatch().getPrefix(), is("/"));
        assertThat(decodedRc.getVirtualHosts().get(0).getRoutes().get(0).getRouteAction().getCluster(), is("ms"));
    }

    // -----------------------------------------------------------------------
    // Malformed / truncated input tests (MAJOR 3 hardening)
    // -----------------------------------------------------------------------

    @Test(expected = IllegalStateException.class)
    public void shouldRejectTruncated64BitFieldInSkip() {
        // Write a tag for a 64-bit field (wire type 1) followed by only 3 bytes (need 8)
        ProtoWriter writer = new ProtoWriter();
        writer.writeTag(10, ProtoReader.WIRE_TYPE_64BIT);
        writer.writeRawBytes(new byte[]{1, 2, 3}); // only 3 bytes, need 8

        byte[] data = writer.toByteArray();
        ProtoReader reader = new ProtoReader(data);
        reader.readTag(); // read the tag
        reader.skipField(ProtoReader.WIRE_TYPE_64BIT); // should throw
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectTruncated32BitFieldInSkip() {
        // Write a tag for a 32-bit field (wire type 5) followed by only 2 bytes (need 4)
        ProtoWriter writer = new ProtoWriter();
        writer.writeTag(10, ProtoReader.WIRE_TYPE_32BIT);
        writer.writeRawBytes(new byte[]{1, 2}); // only 2 bytes, need 4

        byte[] data = writer.toByteArray();
        ProtoReader reader = new ProtoReader(data);
        reader.readTag();
        reader.skipField(ProtoReader.WIRE_TYPE_32BIT); // should throw
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectNegativeLengthInSkip() {
        // Craft a length-delimited field with a huge varint that decodes to a
        // negative int32, triggering the length<0 check.
        // Varint encoding of 0xFFFFFFFF (which is -1 as int32):
        // 0xFF 0xFF 0xFF 0xFF 0x0F
        byte[] data = new byte[]{
            (byte) 0x12, // tag: field 2, wire type 2 (length-delimited)
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x0F // varint = 4294967295 → -1 as int
        };
        ProtoReader reader = new ProtoReader(data);
        reader.readTag();
        reader.skipField(ProtoReader.WIRE_TYPE_LENGTH_DELIMITED); // should throw
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectOverflowingLengthInSkip() {
        // Length-delimited field claiming 100 bytes but only 5 remain
        ProtoWriter writer = new ProtoWriter();
        writer.writeTag(1, ProtoReader.WIRE_TYPE_LENGTH_DELIMITED);
        writer.writeRawVarint(100); // claim 100 bytes of payload
        writer.writeRawBytes(new byte[]{1, 2, 3, 4, 5}); // only 5 bytes

        byte[] data = writer.toByteArray();
        ProtoReader reader = new ProtoReader(data);
        reader.readTag();
        reader.skipField(ProtoReader.WIRE_TYPE_LENGTH_DELIMITED); // should throw
    }

    @Test
    public void shouldDecodeDiscoveryResponseWithUnknownCanaryField() {
        // Build a DiscoveryResponse with a canary (field 3, varint bool) inserted
        // to verify the decoder skips it gracefully
        ProtoWriter writer = new ProtoWriter();
        writer.writeString(1, "v1");            // version_info
        writer.writeUInt32(3, 1);               // canary = true (deprecated field)
        writer.writeString(4, "some_type_url"); // type_url
        writer.writeString(5, "n1");            // nonce

        byte[] data = writer.toByteArray();
        XdsProtoMessages.DiscoveryResponse decoded = XdsProtoMessages.DiscoveryResponse.decode(data);

        assertThat(decoded.getVersionInfo(), is("v1"));
        assertThat(decoded.getTypeUrl(), is("some_type_url"));
        assertThat(decoded.getNonce(), is("n1"));
        assertThat(decoded.getResources(), is(empty()));
    }
}
