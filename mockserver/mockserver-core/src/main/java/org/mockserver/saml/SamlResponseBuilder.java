package org.mockserver.saml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Builds and enveloped-signs a SAML 2.0 {@code <Response>} document using only the JDK XML APIs
 * ({@code javax.xml} DOM) and the JDK XML Digital Signature API ({@code javax.xml.crypto.dsig.*}) —
 * no OpenSAML/Shibboleth dependency.
 *
 * <p>The {@code <Assertion>} is enveloped-signed (the signature element is inserted into the
 * Assertion immediately after its {@code <Issuer>}, as required by the SAML schema), with an
 * exclusive-canonicalised {@code Reference} to the Assertion's {@code ID} and the signing
 * certificate embedded in {@code <ds:KeyInfo>/<ds:X509Data>}. Signing the Assertion (rather than the
 * Response envelope) is the most widely interoperable choice for the Web-Browser-SSO POST profile.
 */
public class SamlResponseBuilder {

    static final String NS_PROTOCOL = "urn:oasis:names:tc:SAML:2.0:protocol";
    static final String NS_ASSERTION = "urn:oasis:names:tc:SAML:2.0:assertion";

    /**
     * Builds a signed SAML Response and returns its serialized XML string.
     *
     * @param provider   the mock IdP provider state
     * @param relayState the SP RelayState (echoed by the caller, not embedded in the assertion)
     * @return the serialized, signed {@code <Response>} XML
     */
    public String buildSignedResponse(SamlAssertionStore.Provider provider, String inResponseTo) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.newDocument();

            String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().with(java.time.temporal.ChronoField.NANO_OF_SECOND, 0));
            String notOnOrAfter = DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().plusSeconds(provider.sessionDurationSeconds).with(java.time.temporal.ChronoField.NANO_OF_SECOND, 0)
            );
            String responseId = "_" + UUID.randomUUID();
            String assertionId = "_" + UUID.randomUUID();

            // <samlp:Response>
            Element response = doc.createElementNS(NS_PROTOCOL, "samlp:Response");
            // namespace declarations are emitted automatically from createElementNS — declaring them
            // manually via setAttribute creates a plain attribute (null namespace) whose canonical
            // form differs from a proper namespace node after a serialize/re-parse round-trip, which
            // would break the enveloped XML signature's digest. So do not add xmlns:* by hand.
            response.setAttribute("ID", responseId);
            response.setAttribute("Version", "2.0");
            response.setAttribute("IssueInstant", now);
            response.setAttribute("Destination", provider.assertionConsumerServiceUrl);
            if (inResponseTo != null && !inResponseTo.isEmpty()) {
                response.setAttribute("InResponseTo", inResponseTo);
            }
            doc.appendChild(response);

            // <saml:Issuer>
            response.appendChild(issuer(doc, provider.idpEntityId));

            // <samlp:Status><samlp:StatusCode Value="...:Success"/></samlp:Status>
            Element status = doc.createElementNS(NS_PROTOCOL, "samlp:Status");
            Element statusCode = doc.createElementNS(NS_PROTOCOL, "samlp:StatusCode");
            statusCode.setAttribute("Value", "urn:oasis:names:tc:SAML:2.0:status:Success");
            status.appendChild(statusCode);
            response.appendChild(status);

            // <saml:Assertion>
            Element assertion = doc.createElementNS(NS_ASSERTION, "saml:Assertion");
            assertion.setAttribute("ID", assertionId);
            assertion.setAttribute("Version", "2.0");
            assertion.setAttribute("IssueInstant", now);
            response.appendChild(assertion);

            // <saml:Issuer> (inside assertion)
            assertion.appendChild(issuer(doc, provider.idpEntityId));

            // <saml:Subject>
            Element subject = doc.createElementNS(NS_ASSERTION, "saml:Subject");
            Element nameId = doc.createElementNS(NS_ASSERTION, "saml:NameID");
            if (provider.nameIdFormat != null && !provider.nameIdFormat.isEmpty()) {
                nameId.setAttribute("Format", provider.nameIdFormat);
            }
            nameId.setTextContent(provider.subjectNameId);
            subject.appendChild(nameId);
            Element subjectConfirmation = doc.createElementNS(NS_ASSERTION, "saml:SubjectConfirmation");
            subjectConfirmation.setAttribute("Method", "urn:oasis:names:tc:SAML:2.0:cm:bearer");
            Element subjectConfirmationData = doc.createElementNS(NS_ASSERTION, "saml:SubjectConfirmationData");
            subjectConfirmationData.setAttribute("NotOnOrAfter", notOnOrAfter);
            subjectConfirmationData.setAttribute("Recipient", provider.assertionConsumerServiceUrl);
            if (inResponseTo != null && !inResponseTo.isEmpty()) {
                subjectConfirmationData.setAttribute("InResponseTo", inResponseTo);
            }
            subjectConfirmation.appendChild(subjectConfirmationData);
            subject.appendChild(subjectConfirmation);
            assertion.appendChild(subject);

            // <saml:Conditions> with <saml:AudienceRestriction><saml:Audience>spEntityId
            Element conditions = doc.createElementNS(NS_ASSERTION, "saml:Conditions");
            conditions.setAttribute("NotBefore", now);
            conditions.setAttribute("NotOnOrAfter", notOnOrAfter);
            Element audienceRestriction = doc.createElementNS(NS_ASSERTION, "saml:AudienceRestriction");
            Element audience = doc.createElementNS(NS_ASSERTION, "saml:Audience");
            audience.setTextContent(provider.spEntityId);
            audienceRestriction.appendChild(audience);
            conditions.appendChild(audienceRestriction);
            assertion.appendChild(conditions);

            // <saml:AuthnStatement>
            Element authnStatement = doc.createElementNS(NS_ASSERTION, "saml:AuthnStatement");
            authnStatement.setAttribute("AuthnInstant", now);
            authnStatement.setAttribute("SessionNotOnOrAfter", notOnOrAfter);
            authnStatement.setAttribute("SessionIndex", "_" + UUID.randomUUID());
            Element authnContext = doc.createElementNS(NS_ASSERTION, "saml:AuthnContext");
            Element authnContextClassRef = doc.createElementNS(NS_ASSERTION, "saml:AuthnContextClassRef");
            authnContextClassRef.setTextContent("urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport");
            authnContext.appendChild(authnContextClassRef);
            authnStatement.appendChild(authnContext);
            assertion.appendChild(authnStatement);

            // <saml:AttributeStatement>
            if (provider.attributes != null && !provider.attributes.isEmpty()) {
                Element attributeStatement = doc.createElementNS(NS_ASSERTION, "saml:AttributeStatement");
                for (Map.Entry<String, String> entry : provider.attributes.entrySet()) {
                    Element attribute = doc.createElementNS(NS_ASSERTION, "saml:Attribute");
                    attribute.setAttribute("Name", entry.getKey());
                    attribute.setAttribute("NameFormat", "urn:oasis:names:tc:SAML:2.0:attrname-format:basic");
                    Element attributeValue = doc.createElementNS(NS_ASSERTION, "saml:AttributeValue");
                    attributeValue.setTextContent(entry.getValue());
                    attribute.appendChild(attributeValue);
                    attributeStatement.appendChild(attribute);
                }
                assertion.appendChild(attributeStatement);
            }

            // Round-trip the unsigned document through serialize + re-parse before signing so the
            // sign-time DOM is structurally identical to the DOM a relying party reconstructs when it
            // re-parses the response. Without this the enveloped-signature Reference digest computed
            // at sign time can differ from the digest recomputed after a parse, failing validation.
            Document reparsed = reparse(serialize(doc));
            Element reparsedAssertion = (Element) reparsed
                .getElementsByTagNameNS(NS_ASSERTION, "Assertion").item(0);
            reparsedAssertion.setIdAttribute("ID", true);

            // enveloped-sign the Assertion
            signAssertion(reparsedAssertion, provider.signingPrivateKey, provider.signingCertificate);

            return serialize(reparsed);
        } catch (Exception e) {
            throw new RuntimeException("Exception building signed SAML response", e);
        }
    }

    private Element issuer(Document doc, String idpEntityId) {
        Element issuer = doc.createElementNS(NS_ASSERTION, "saml:Issuer");
        issuer.setTextContent(idpEntityId);
        return issuer;
    }

    /**
     * Enveloped-signs the assertion in place. The {@code <ds:Signature>} is inserted as the second
     * child of the Assertion (immediately after {@code <saml:Issuer>}), which is where the SAML
     * schema requires it.
     */
    private void signAssertion(Element assertion, PrivateKey privateKey, X509Certificate certificate) throws Exception {
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        String assertionId = assertion.getAttribute("ID");

        Reference reference = fac.newReference(
            "#" + assertionId,
            fac.newDigestMethod(DigestMethod.SHA256, null),
            java.util.Arrays.asList(
                fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                fac.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)
            ),
            null, null
        );

        SignedInfo signedInfo = fac.newSignedInfo(
            fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
            fac.newSignatureMethod("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256", null),
            Collections.singletonList(reference)
        );

        KeyInfoFactory kif = fac.getKeyInfoFactory();
        X509Data x509Data = kif.newX509Data(Collections.singletonList(certificate));
        KeyInfo keyInfo = kif.newKeyInfo(Collections.singletonList(x509Data));

        // Insert the signature after <saml:Issuer> (the first child of the assertion).
        DOMSignContext signContext = new DOMSignContext(privateKey, assertion);
        if (assertion.getFirstChild() != null && assertion.getFirstChild().getNextSibling() != null) {
            signContext.setNextSibling(assertion.getFirstChild().getNextSibling());
        }
        signContext.setDefaultNamespacePrefix("ds");

        XMLSignature signature = fac.newXMLSignature(signedInfo, keyInfo);
        signature.sign(signContext);
    }

    private Document reparse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(
            new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private String serialize(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
