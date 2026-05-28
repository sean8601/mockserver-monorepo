package org.mockserver.mappers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.X509Certificate;
import org.mockserver.socket.tls.PEMToFile;

import java.security.cert.Certificate;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

/**
 * @author jamesdbloom
 */
public class JDKCertificateToMockServerX509CertificateTest {

    private static final MockServerLogger mockServerLogger = new MockServerLogger();
    private final JDKCertificateToMockServerX509Certificate converter =
        new JDKCertificateToMockServerX509Certificate(mockServerLogger);

    // --- happy path with real cert ---

    @Test
    public void shouldConvertLeafCertificate() {
        // given
        java.security.cert.X509Certificate[] certs =
            PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/leaf-cert.pem")
                .toArray(new java.security.cert.X509Certificate[0]);
        HttpRequest httpRequest = request();

        // when
        HttpRequest result = converter.setClientCertificates(httpRequest, certs);

        // then
        List<X509Certificate> chain = result.getClientCertificateChain();
        assertThat(chain, is(notNullValue()));
        assertThat(chain, hasSize(1));

        X509Certificate cert = chain.get(0);
        assertThat(cert.getSerialNumber(), is(notNullValue()));
        assertThat(cert.getIssuerDistinguishedName(), is(notNullValue()));
        assertThat(cert.getSubjectDistinguishedName(), is(notNullValue()));
        assertThat(cert.getSignatureAlgorithmName(), is(notNullValue()));
        assertThat(cert.getCertificate(), is(notNullValue()));
    }

    @Test
    public void shouldConvertCertificateChain() {
        // given
        List<java.security.cert.X509Certificate> leafCerts =
            PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/leaf-cert.pem");
        List<java.security.cert.X509Certificate> caCerts =
            PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/ca.pem");

        java.security.cert.X509Certificate[] allCerts =
            new java.security.cert.X509Certificate[leafCerts.size() + caCerts.size()];
        int i = 0;
        for (java.security.cert.X509Certificate c : leafCerts) {
            allCerts[i++] = c;
        }
        for (java.security.cert.X509Certificate c : caCerts) {
            allCerts[i++] = c;
        }
        HttpRequest httpRequest = request();

        // when
        HttpRequest result = converter.setClientCertificates(httpRequest, allCerts);

        // then
        List<X509Certificate> chain = result.getClientCertificateChain();
        assertThat(chain, hasSize(allCerts.length));
    }

    @Test
    public void shouldMapIssuerDistinguishedName() {
        // given
        java.security.cert.X509Certificate[] certs =
            PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/leaf-cert.pem")
                .toArray(new java.security.cert.X509Certificate[0]);
        HttpRequest httpRequest = request();

        // when
        converter.setClientCertificates(httpRequest, certs);

        // then
        X509Certificate cert = httpRequest.getClientCertificateChain().get(0);
        // The leaf cert's issuer should be the CA's distinguished name
        assertThat(cert.getIssuerDistinguishedName(), not(emptyOrNullString()));
    }

    @Test
    public void shouldMapSubjectDistinguishedName() {
        // given
        java.security.cert.X509Certificate[] certs =
            PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/leaf-cert.pem")
                .toArray(new java.security.cert.X509Certificate[0]);
        HttpRequest httpRequest = request();

        // when
        converter.setClientCertificates(httpRequest, certs);

        // then
        X509Certificate cert = httpRequest.getClientCertificateChain().get(0);
        assertThat(cert.getSubjectDistinguishedName(), not(emptyOrNullString()));
    }

    @Test
    public void shouldMapSerialNumber() {
        // given
        java.security.cert.X509Certificate[] certs =
            PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/leaf-cert.pem")
                .toArray(new java.security.cert.X509Certificate[0]);
        HttpRequest httpRequest = request();

        // when
        converter.setClientCertificates(httpRequest, certs);

        // then
        X509Certificate cert = httpRequest.getClientCertificateChain().get(0);
        assertThat(cert.getSerialNumber(), not(emptyOrNullString()));
    }

    @Test
    public void shouldMapSignatureAlgorithmName() {
        // given
        java.security.cert.X509Certificate[] certs =
            PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/leaf-cert.pem")
                .toArray(new java.security.cert.X509Certificate[0]);
        HttpRequest httpRequest = request();

        // when
        converter.setClientCertificates(httpRequest, certs);

        // then
        X509Certificate cert = httpRequest.getClientCertificateChain().get(0);
        assertThat(cert.getSignatureAlgorithmName(), not(emptyOrNullString()));
    }

    @Test
    public void shouldPreserveOriginalCertificateReference() {
        // given
        java.security.cert.X509Certificate[] certs =
            PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/leaf-cert.pem")
                .toArray(new java.security.cert.X509Certificate[0]);
        HttpRequest httpRequest = request();

        // when
        converter.setClientCertificates(httpRequest, certs);

        // then
        Certificate storedCert = httpRequest.getClientCertificateChain().get(0).getCertificate();
        assertThat(storedCert, is(notNullValue()));
    }

    // --- null and empty input ---

    @Test
    public void shouldHandleNullCertificates() {
        // given
        HttpRequest httpRequest = request();

        // when
        HttpRequest result = converter.setClientCertificates(httpRequest, null);

        // then
        assertThat(result.getClientCertificateChain(), is(nullValue()));
    }

    @Test
    public void shouldHandleEmptyCertificates() {
        // given
        HttpRequest httpRequest = request();

        // when
        HttpRequest result = converter.setClientCertificates(httpRequest, new Certificate[0]);

        // then
        // empty array produces no X509Certificates, so chain should not be set
        assertThat(result.getClientCertificateChain(), is(nullValue()));
    }

    // --- converter with null logger ---

    @Test
    public void shouldConvertCertificateWhenLoggerIsNull() {
        // given
        JDKCertificateToMockServerX509Certificate converterWithNullLogger =
            new JDKCertificateToMockServerX509Certificate(null);
        java.security.cert.X509Certificate[] certs =
            PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/leaf-cert.pem")
                .toArray(new java.security.cert.X509Certificate[0]);
        HttpRequest httpRequest = request();

        // when
        HttpRequest result = converterWithNullLogger.setClientCertificates(httpRequest, certs);

        // then
        assertThat(result.getClientCertificateChain(), hasSize(1));
    }
}
