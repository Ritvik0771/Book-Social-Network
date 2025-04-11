package com.ncratleos.baseimage.service;

import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * Disables SSL certificate validation for HTTPS connections.
 * <p>
 * This method configures the SSL context to trust all certificates, effectively disabling
 * SSL certificate validation. It sets a custom {@link javax.net.ssl.TrustManager} that
 * does not perform any checks, and a {@link javax.net.ssl.HostnameVerifier} that always
 * returns {@code true}.
 * </p>
 * <p>
 * <strong>Note:</strong> Disabling SSL certificate validation is not recommended for
 * production environments as it compromises security. Use this method with caution.
 * </p>
 *
 * @throws Exception if an error occurs while disabling SSL certificate validation
 */

@Service
public class SSLConfig {
    public void disableSSLCertificateValidation() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
    }
}