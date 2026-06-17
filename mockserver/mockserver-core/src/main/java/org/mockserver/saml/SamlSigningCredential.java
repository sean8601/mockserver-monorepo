package org.mockserver.saml;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.mockserver.keys.AsymmetricKeyGenerator;
import org.mockserver.keys.AsymmetricKeyPairAlgorithm;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import static org.mockserver.socket.tls.PEMToFile.privateKeyFromPEM;
import static org.mockserver.socket.tls.PEMToFile.x509FromPEM;

/**
 * The RSA signing credential (private key + X.509 certificate) used to enveloped-sign SAML
 * assertions and to publish the IdP signing certificate in the metadata.
 *
 * <p>If the configuration supplies a PEM-encoded certificate and key they are parsed and reused
 * (mirroring the way the TLS layer accepts user-provided certificates); otherwise a fresh
 * self-signed RSA-2048 credential is generated with the existing {@link AsymmetricKeyGenerator}
 * and BouncyCastle certificate builder (the same building blocks the TLS {@code
 * BCKeyAndCertificateFactory} uses), so no real IdP infrastructure is required.
 */
public class SamlSigningCredential {

    private static final String PROVIDER_NAME = "BC";

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    private SamlSigningCredential(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    /**
     * Builds the signing credential from configuration: parses a supplied PEM pair if present,
     * otherwise generates a self-signed RSA credential keyed to the IdP entity id.
     */
    public static SamlSigningCredential from(SamlProviderConfiguration config) {
        String certPem = config.getSigningCertificatePem();
        String keyPem = config.getSigningPrivateKeyPem();
        if (certPem != null && !certPem.trim().isEmpty() && keyPem != null && !keyPem.trim().isEmpty()) {
            return new SamlSigningCredential(privateKeyFromPEM(keyPem), x509FromPEM(certPem));
        }
        return generateSelfSigned(config.getIdpEntityId());
    }

    private static SamlSigningCredential generateSelfSigned(String idpEntityId) {
        try {
            KeyPair keyPair = AsymmetricKeyGenerator.createKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
            PublicKey publicKey = keyPair.getPublic();
            PrivateKey privateKey = keyPair.getPrivate();

            String commonName = "MockServer SAML IdP";
            X500Name subject = new X500Name("CN=" + commonName + ", O=MockServer, OU=" + sanitise(idpEntityId));
            BigInteger serial = new BigInteger(64, new SecureRandom());
            Date notBefore = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
            Date notAfter = Date.from(Instant.now().plus(3650, ChronoUnit.DAYS));

            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, publicKey
            );
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(PROVIDER_NAME).build(privateKey);
            X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider(PROVIDER_NAME).getCertificate(builder.build(signer));
            certificate.verify(publicKey);

            return new SamlSigningCredential(privateKey, certificate);
        } catch (Exception e) {
            throw new RuntimeException("Exception generating self-signed SAML signing credential", e);
        }
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public X509Certificate getCertificate() {
        return certificate;
    }

    /**
     * The base64 DER of the certificate, exactly as embedded in a SAML {@code <ds:X509Certificate>}
     * or metadata {@code <ds:X509Certificate>} element (no PEM header/footer, no line wrapping).
     */
    public String getCertificateBase64() {
        try {
            return Base64.getEncoder().encodeToString(certificate.getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("Exception encoding SAML signing certificate", e);
        }
    }

    private static String sanitise(String value) {
        if (value == null) {
            return "mockserver";
        }
        // X500 names cannot contain unescaped commas/equals/plus; keep it simple and safe
        return value.replaceAll("[,=+<>;\"\\\\]", "_");
    }
}
