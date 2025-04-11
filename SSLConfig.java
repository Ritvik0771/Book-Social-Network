package com.ncratleos.baseimage.service;

import com.ncratleos.baseimage.ssl.TrustAllX509TrustManager;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/**
 * Configures SSL to trust all certificates.
 * <p>
 * This is useful for local testing or environments where certificate validation is not needed.
 * Use with caution.
 */
@Service
public class SSLConfig {

    public void disableSSLCertificateValidation() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{new TrustAllX509TrustManager()};
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
    }
}
