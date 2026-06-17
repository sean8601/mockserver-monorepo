package org.mockserver.saml;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;

public class SamlProviderGeneratorTest {

    private final SamlProviderGenerator generator = new SamlProviderGenerator();

    @Before
    public void resetStore() {
        SamlAssertionStore.getInstance().reset();
    }

    // --- generation shape ---

    @Test
    public void defaultsProduceTwoExpectations() {
        java.util.List<Expectation> expectations = generator.generate(new SamlProviderConfiguration());

        assertThat(expectations.size(), is(2));
        assertThat(expectations.get(0).getId(), is("saml.metadata"));
        assertThat(expectations.get(1).getId(), is("saml.sso"));
    }

    @Test
    public void metadataExpectationIsGetStaticResponse() {
        Expectation metadata = generator.generate(new SamlProviderConfiguration()).get(0);
        HttpRequest request = (HttpRequest) metadata.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("GET"));
        assertThat(request.getPath().getValue(), is("/saml/metadata"));
        assertThat(metadata.getHttpResponse().getStatusCode(), is(200));
        assertThat(metadata.getHttpResponse().getFirstHeader("content-type"), containsString("samlmetadata+xml"));
    }

    @Test
    public void ssoExpectationIsClassCallback() {
        Expectation sso = generator.generate(new SamlProviderConfiguration()).get(1);
        HttpRequest request = (HttpRequest) sso.getHttpRequest();
        assertThat(request.getPath().getValue(), is("/saml/sso"));
        assertThat(sso.getHttpResponseClassCallback(), is(notNullValue()));
        assertThat(sso.getHttpResponseClassCallback().getCallbackClass(),
            is(SamlSsoCallback.class.getName()));
    }

    @Test
    public void nullConfigThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(null));
    }

    // --- (a) metadata is well-formed SAML metadata ---

    @Test
    public void metadataIsWellFormedSamlMetadata() throws Exception {
        SamlProviderConfiguration config = new SamlProviderConfiguration()
            .setIdpEntityId("https://idp.example.com/saml/idp")
            .setSsoServiceUrl("/saml/sso");

        Expectation metadata = generator.generate(config).get(0);
        String xml = metadata.getHttpResponse().getBodyAsString();

        Document doc = parse(xml);
        Element root = doc.getDocumentElement();
        assertThat(root.getLocalName(), is("EntityDescriptor"));
        assertThat(root.getAttribute("entityID"), is("https://idp.example.com/saml/idp"));

        // IDPSSODescriptor present
        assertThat(elementsNS(doc, "urn:oasis:names:tc:SAML:2.0:metadata", "IDPSSODescriptor").getLength(), is(1));

        // X509 certificate present and is real base64 DER
        NodeList certs = elementsNS(doc, "http://www.w3.org/2000/09/xmldsig#", "X509Certificate");
        assertThat(certs.getLength(), is(1));
        String certB64 = certs.item(0).getTextContent().trim();
        assertTrue("X509Certificate should be non-empty base64", certB64.length() > 0);
        java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate)
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certB64)));
        assertThat(cert, is(notNullValue()));

        // SingleSignOnService with POST binding
        NodeList ssoServices = elementsNS(doc, "urn:oasis:names:tc:SAML:2.0:metadata", "SingleSignOnService");
        assertThat(ssoServices.getLength(), is(1));
        Element ssoService = (Element) ssoServices.item(0);
        assertThat(ssoService.getAttribute("Binding"), is("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"));
        assertThat(ssoService.getAttribute("Location"), is("https://idp.example.com/saml/sso"));
    }

    @Test
    public void metadataUsesCustomPaths() throws Exception {
        SamlProviderConfiguration config = new SamlProviderConfiguration()
            .setMetadataUrl("/custom/metadata")
            .setSsoServiceUrl("/custom/sso");

        java.util.List<Expectation> expectations = generator.generate(config);
        assertThat(((HttpRequest) expectations.get(0).getHttpRequest()).getPath().getValue(), is("/custom/metadata"));
        assertThat(((HttpRequest) expectations.get(1).getHttpRequest()).getPath().getValue(), is("/custom/sso"));
    }

    // --- (b) SSO endpoint returns an auto-POST form with a decodable, correct Response ---

    @Test
    public void ssoReturnsAutoPostFormToAcsWithRelayStateEchoed() throws Exception {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("email", "alice@example.com");
        attributes.put("role", "admin");

        SamlProviderConfiguration config = new SamlProviderConfiguration()
            .setIdpEntityId("https://idp.test/idp")
            .setSpEntityId("https://sp.test/metadata")
            .setAssertionConsumerServiceUrl("https://sp.test/acs")
            .setSubjectNameId("alice@example.com")
            .setAttributes(attributes);

        generator.generate(config);

        HttpResponse response = new SamlSsoCallback().handle(
            request()
                .withMethod("GET")
                .withPath("/saml/sso")
                .withQueryStringParameter("RelayState", "/dashboard?tab=1")
        );

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader("content-type"), containsString("text/html"));

        String html = response.getBodyAsString();
        // auto-submitting POST form to the ACS URL
        assertThat(html, containsString("onload=\"document.forms[0].submit()\""));
        assertThat(html, containsString("method=\"post\""));
        assertThat(html, containsString("action=\"https://sp.test/acs\""));
        // RelayState echoed (the & is XML-escaped in the attribute value)
        assertThat(html, containsString("name=\"RelayState\""));
        assertThat(html, containsString("/dashboard?tab=1".replace("&", "&amp;")));

        // SAMLResponse decodes to a Response containing the configured assertion
        String samlResponseB64 = extractHiddenInput(html, "SAMLResponse");
        assertThat(samlResponseB64, is(notNullValue()));
        String responseXml = new String(Base64.getDecoder().decode(samlResponseB64), StandardCharsets.UTF_8);

        Document doc = parse(responseXml);
        assertThat(doc.getDocumentElement().getLocalName(), is("Response"));

        // NameID
        NodeList nameIds = elementsNS(doc, "urn:oasis:names:tc:SAML:2.0:assertion", "NameID");
        assertThat(nameIds.getLength(), is(1));
        assertThat(nameIds.item(0).getTextContent(), is("alice@example.com"));

        // Audience == spEntityId
        NodeList audiences = elementsNS(doc, "urn:oasis:names:tc:SAML:2.0:assertion", "Audience");
        assertThat(audiences.getLength(), is(1));
        assertThat(audiences.item(0).getTextContent(), is("https://sp.test/metadata"));

        // AuthnStatement present
        assertThat(elementsNS(doc, "urn:oasis:names:tc:SAML:2.0:assertion", "AuthnStatement").getLength(), is(1));

        // Attributes
        NodeList attributeNodes = elementsNS(doc, "urn:oasis:names:tc:SAML:2.0:assertion", "Attribute");
        assertThat(attributeNodes.getLength(), is(2));
        Map<String, String> seen = new LinkedHashMap<>();
        for (int i = 0; i < attributeNodes.getLength(); i++) {
            Element attribute = (Element) attributeNodes.item(i);
            String name = attribute.getAttribute("Name");
            NodeList values = attribute.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "AttributeValue");
            seen.put(name, values.item(0).getTextContent());
        }
        assertThat(seen.get("email"), is("alice@example.com"));
        assertThat(seen.get("role"), is("admin"));
    }

    @Test
    public void ssoWithoutRelayStateOmitsRelayStateInput() {
        generator.generate(new SamlProviderConfiguration());
        HttpResponse response = new SamlSsoCallback().handle(
            request().withMethod("GET").withPath("/saml/sso")
        );
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString().contains("name=\"RelayState\""), is(false));
    }

    @Test
    public void ssoForUnregisteredPathReturns404() {
        // store reset by @Before; nothing registered for this path
        HttpResponse response = new SamlSsoCallback().handle(
            request().withMethod("GET").withPath("/saml/sso")
        );
        assertThat(response.getStatusCode(), is(404));
    }

    @Test
    public void ssoEchoesInResponseToFromSamlRequest() throws Exception {
        generator.generate(new SamlProviderConfiguration());

        String authnRequest = "<samlp:AuthnRequest xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\""
            + " ID=\"_abc123request\" Version=\"2.0\"/>";
        String samlRequestB64 = Base64.getEncoder().encodeToString(authnRequest.getBytes(StandardCharsets.UTF_8));

        HttpResponse response = new SamlSsoCallback().handle(
            request().withMethod("GET").withPath("/saml/sso")
                .withQueryStringParameter("SAMLRequest", samlRequestB64)
        );

        String samlResponseB64 = extractHiddenInput(response.getBodyAsString(), "SAMLResponse");
        String responseXml = new String(Base64.getDecoder().decode(samlResponseB64), StandardCharsets.UTF_8);
        Document doc = parse(responseXml);
        assertThat(doc.getDocumentElement().getAttribute("InResponseTo"), is("_abc123request"));
    }

    // --- SEC-05 / SEC-12: InResponseTo must come from the ROOT element ID, not a decoy scan ---

    @Test
    public void ssoIgnoresDecoyIdInCommentBeforeRealAuthnRequestId() throws Exception {
        generator.generate(new SamlProviderConfiguration());

        // a forged ID="forged" sits in a comment BEFORE the real root AuthnRequest ID. A naive
        // first-"ID=" string scan would echo "forged"; a root-element parser must echo the real id.
        String authnRequest = "<!--ID=\"forged\"-->"
            + "<samlp:AuthnRequest xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\""
            + " ID=\"_realid\" Version=\"2.0\"/>";
        String samlRequestB64 = Base64.getEncoder().encodeToString(authnRequest.getBytes(StandardCharsets.UTF_8));

        HttpResponse response = new SamlSsoCallback().handle(
            request().withMethod("GET").withPath("/saml/sso")
                .withQueryStringParameter("SAMLRequest", samlRequestB64)
        );

        String samlResponseB64 = extractHiddenInput(response.getBodyAsString(), "SAMLResponse");
        String responseXml = new String(Base64.getDecoder().decode(samlResponseB64), StandardCharsets.UTF_8);
        Document doc = parse(responseXml);

        String inResponseTo = doc.getDocumentElement().getAttribute("InResponseTo");
        assertThat("forged comment ID must never be echoed", inResponseTo, is(not("forged")));
        assertThat(inResponseTo, is("_realid"));
    }

    @Test
    public void ssoParsesAuthnRequestWithDoctypeSafelyWithoutXxe() {
        generator.generate(new SamlProviderConfiguration());

        // a DOCTYPE with an external entity — an XXE-hardened parser must reject the DOCTYPE and
        // (since parsing fails) simply omit InResponseTo, never read the local file or crash.
        String maliciousRequest =
            "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE samlp:AuthnRequest [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>"
                + "<samlp:AuthnRequest xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\""
                + " ID=\"_xxe&xxe;\" Version=\"2.0\"/>";
        String samlRequestB64 = Base64.getEncoder().encodeToString(maliciousRequest.getBytes(StandardCharsets.UTF_8));

        HttpResponse response = new SamlSsoCallback().handle(
            request().withMethod("GET").withPath("/saml/sso")
                .withQueryStringParameter("SAMLRequest", samlRequestB64)
        );

        // no crash; a valid IdP response is still produced
        assertThat(response.getStatusCode(), is(200));
        String samlResponseB64 = extractHiddenInput(response.getBodyAsString(), "SAMLResponse");
        assertThat(samlResponseB64, is(notNullValue()));
        String responseXml = new String(Base64.getDecoder().decode(samlResponseB64), StandardCharsets.UTF_8);
        // the file contents must NOT have leaked into the response
        assertThat(responseXml, not(containsString("root:")));
    }

    @Test
    public void ssoHandlesMalformedSamlRequestGracefullyWithoutInResponseTo() throws Exception {
        generator.generate(new SamlProviderConfiguration());

        // not valid base64-of-XML — must be handled without throwing and without InResponseTo
        String samlRequestB64 = Base64.getEncoder().encodeToString(
            "this is not xml at all <<<".getBytes(StandardCharsets.UTF_8));

        HttpResponse response = new SamlSsoCallback().handle(
            request().withMethod("GET").withPath("/saml/sso")
                .withQueryStringParameter("SAMLRequest", samlRequestB64)
        );

        assertThat(response.getStatusCode(), is(200));
        String responseXml = new String(
            Base64.getDecoder().decode(extractHiddenInput(response.getBodyAsString(), "SAMLResponse")),
            StandardCharsets.UTF_8);
        Document doc = parse(responseXml);
        // no InResponseTo attribute echoed for an unparseable request
        assertThat(doc.getDocumentElement().getAttribute("InResponseTo"), is(""));
    }

    @Test
    public void ssoRejectsOversizedSamlRequestGracefully() throws Exception {
        generator.generate(new SamlProviderConfiguration());

        // a >64KB decoded payload must be refused (no InResponseTo, no exception)
        StringBuilder big = new StringBuilder("<samlp:AuthnRequest xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" ID=\"_huge\" Version=\"2.0\">");
        while (big.length() < 70 * 1024) {
            big.append("<pad>xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</pad>");
        }
        big.append("</samlp:AuthnRequest>");
        String samlRequestB64 = Base64.getEncoder().encodeToString(big.toString().getBytes(StandardCharsets.UTF_8));

        HttpResponse response = new SamlSsoCallback().handle(
            request().withMethod("GET").withPath("/saml/sso")
                .withQueryStringParameter("SAMLRequest", samlRequestB64)
        );

        assertThat(response.getStatusCode(), is(200));
        String responseXml = new String(
            Base64.getDecoder().decode(extractHiddenInput(response.getBodyAsString(), "SAMLResponse")),
            StandardCharsets.UTF_8);
        Document doc = parse(responseXml);
        assertThat(doc.getDocumentElement().getAttribute("InResponseTo"), is(""));
    }

    // --- SEC-06: the signing private key must never be serialized to JSON ---

    @Test
    public void signingPrivateKeyIsNeverSerialized() throws Exception {
        SamlProviderConfiguration config = new SamlProviderConfiguration()
            .setSigningCertificatePem("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----")
            .setSigningPrivateKeyPem("-----BEGIN PRIVATE KEY-----\nSUPER-SECRET\n-----END PRIVATE KEY-----");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = mapper.writeValueAsString(config);

        assertThat("private key PEM must not appear in serialized JSON", json, not(containsString("SUPER-SECRET")));
        assertThat(json, not(containsString("signingPrivateKeyPem")));
        // the public certificate is still serialized
        assertThat(json, containsString("signingCertificatePem"));
    }

    @Test
    public void signingPrivateKeyStillDeserializesFromInboundPut() throws Exception {
        String inbound = "{\"signingPrivateKeyPem\":\"-----BEGIN PRIVATE KEY-----\\nKEYDATA\\n-----END PRIVATE KEY-----\"}";
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        SamlProviderConfiguration config = mapper.readValue(inbound, SamlProviderConfiguration.class);

        assertThat(config.getSigningPrivateKeyPem(), containsString("KEYDATA"));
    }

    // --- (c) the XML signature on the assertion validates against the IdP cert ---

    @Test
    public void assertionSignatureValidatesAgainstIdpCertificate() throws Exception {
        SamlProviderConfiguration config = new SamlProviderConfiguration()
            .setSubjectNameId("bob@example.com");
        java.util.List<Expectation> expectations = generator.generate(config);

        // pull the IdP signing cert public key from the metadata
        Document metadataDoc = parse(expectations.get(0).getHttpResponse().getBodyAsString());
        String certB64 = elementsNS(metadataDoc, "http://www.w3.org/2000/09/xmldsig#", "X509Certificate")
            .item(0).getTextContent().trim();
        java.security.cert.X509Certificate idpCert = (java.security.cert.X509Certificate)
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certB64)));
        PublicKey idpPublicKey = idpCert.getPublicKey();

        // get a signed Response
        HttpResponse response = new SamlSsoCallback().handle(
            request().withMethod("GET").withPath("/saml/sso")
        );
        String responseXml = new String(
            Base64.getDecoder().decode(extractHiddenInput(response.getBodyAsString(), "SAMLResponse")),
            StandardCharsets.UTF_8
        );

        Document doc = parse(responseXml);

        // mark the Assertion's ID attribute as an ID so the signature Reference resolves
        Element assertion = (Element) elementsNS(doc, "urn:oasis:names:tc:SAML:2.0:assertion", "Assertion").item(0);
        assertion.setIdAttribute("ID", true);

        NodeList signatures = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        assertThat("expected a ds:Signature on the assertion", signatures.getLength(), is(1));

        DOMValidateContext valContext = new DOMValidateContext(idpPublicKey, signatures.item(0));
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        XMLSignature signature = fac.unmarshalXMLSignature(valContext);

        assertTrue("SAML assertion signature should validate against the IdP certificate",
            signature.validate(valContext));
    }

    @Test
    public void tamperedAssertionFailsSignatureValidation() throws Exception {
        java.util.List<Expectation> expectations =
            generator.generate(new SamlProviderConfiguration().setSubjectNameId("carol@example.com"));

        // IdP signing cert public key from the metadata produced by THIS generate()
        Document metadataDoc = parse(expectations.get(0).getHttpResponse().getBodyAsString());
        String certB64 = elementsNS(metadataDoc, "http://www.w3.org/2000/09/xmldsig#", "X509Certificate")
            .item(0).getTextContent().trim();
        PublicKey idpPublicKey = ((java.security.cert.X509Certificate)
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certB64)))).getPublicKey();

        HttpResponse response = new SamlSsoCallback().handle(
            request().withMethod("GET").withPath("/saml/sso")
        );
        String responseXml = new String(
            Base64.getDecoder().decode(extractHiddenInput(response.getBodyAsString(), "SAMLResponse")),
            StandardCharsets.UTF_8
        );
        // tamper with the NameID after signing
        responseXml = responseXml.replace("carol@example.com", "attacker@example.com");

        Document doc = parse(responseXml);
        Element assertion = (Element) elementsNS(doc, "urn:oasis:names:tc:SAML:2.0:assertion", "Assertion").item(0);
        assertion.setIdAttribute("ID", true);

        NodeList signatures = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        DOMValidateContext valContext = new DOMValidateContext(idpPublicKey, signatures.item(0));
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        XMLSignature signature = fac.unmarshalXMLSignature(valContext);

        assertThat("tampered assertion must fail signature validation",
            signature.validate(valContext), is(false));
    }

    // --- supplied signing credential is reused ---

    @Test
    public void suppliedSigningCredentialIsPublishedInMetadata() throws Exception {
        // generate a credential, export to PEM, feed it back in
        SamlSigningCredential generated = SamlSigningCredential.from(new SamlProviderConfiguration());
        String certPem = org.mockserver.socket.tls.PEMToFile.certToPEM(generated.getCertificate());
        // a JDK RSA PrivateKey encodes as PKCS#8 DER -> wrap as a PKCS#8 "PRIVATE KEY" PEM, which
        // SamlSigningCredential parses via BouncyCastle's PEMParser
        String keyPem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(generated.getPrivateKey().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";

        SamlProviderConfiguration config = new SamlProviderConfiguration()
            .setSigningCertificatePem(certPem)
            .setSigningPrivateKeyPem(keyPem);

        Expectation metadata = generator.generate(config).get(0);
        Document doc = parse(metadata.getHttpResponse().getBodyAsString());
        String publishedCertB64 = elementsNS(doc, "http://www.w3.org/2000/09/xmldsig#", "X509Certificate")
            .item(0).getTextContent().trim();

        assertThat(publishedCertB64, is(generated.getCertificateBase64()));
    }

    // --- helpers ---

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static NodeList elementsNS(Document doc, String ns, String localName) {
        return doc.getElementsByTagNameNS(ns, localName);
    }

    private static final Pattern HIDDEN_INPUT = Pattern.compile(
        "<input type=\"hidden\" name=\"([^\"]+)\" value=\"([^\"]*)\"");

    private static String extractHiddenInput(String html, String name) {
        Matcher matcher = HIDDEN_INPUT.matcher(html);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                // value is XML-attribute-escaped; for base64 SAMLResponse there are no special chars
                return matcher.group(2)
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">");
            }
        }
        return null;
    }
}
