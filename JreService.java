package com.ncratleos.baseimage.service;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;




// LINUXXXXXXXXXXXXXXXXXXX
//@Service
//public class JreService {
//
//    private static final String DOWNLOAD_URL = "https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.tar.gz";
//
//    public void downloadAndExtractJDK(String outputDirPath) throws IOException {
//        Path outputDir = Paths.get(outputDirPath);
//        Files.createDirectories(outputDir);
//
//        Path tempFile = Files.createTempFile("jdk", ".tar.gz");
//
//        try{
//            disableSSLCertificateValidation();
//        }catch(Exception e){
//            throw new IOException("Failed to disable SSL Validation" , e);
//        }
//
//        // Step 1: Download the file
//        try (InputStream in = new URL(DOWNLOAD_URL).openStream()) {
//            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
//            System.out.println("JDK downloaded to: " + tempFile);
//        }
//
//        // Step 2: Extract .tar.gz
//        // for LINUX don't remove
////        try (
////                GZIPInputStream gzipIn = new GZIPInputStream(new FileInputStream(tempFile.toFile()));
////                TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)
////        ) {
////            TarArchiveEntry entry;
////            while ((entry = tarIn.getNextTarEntry()) != null) {
////                Path entryPath = outputDir.resolve(entry.getName());
////                if (entry.isDirectory()) {
////                    Files.createDirectories(entryPath);
////                } else {
////                    Files.createDirectories(entryPath.getParent());
////                    try (OutputStream out = Files.newOutputStream(entryPath)) {
////                        tarIn.transferTo(out);
////                    }
////                }
////            }
////            System.out.println("JDK extracted to: " + outputDir.toAbsolutePath());
////        }
//        // dont remove above code
//
//
//        // Step 3: Clean up
//        Files.deleteIfExists(tempFile);
//    }

import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class JreService {

    private static final String DOWNLOAD_URL = "https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip";

    public void downloadAndExtractJDK(String outputDirPath) throws IOException {
        Path outputDir = Paths.get(outputDirPath);
        Files.createDirectories(outputDir);

        Path tempZip = Files.createTempFile("jdk", ".zip");

        try{
            disableSSLCertificateValidation();
        }catch(Exception e){
            throw new IOException("Failed to disable SSL Validation" , e);
        }

        // Step 1: Download the JDK ZIP
        try (InputStream in = new URL(DOWNLOAD_URL).openStream()) {
            Files.copy(in, tempZip, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("JDK downloaded to: " + tempZip);
        }

        // Step 2: Extract the ZIP
        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(tempZip.toFile()))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                Path filePath = outputDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(filePath);
                } else {
                    Files.createDirectories(filePath.getParent());
                    try (OutputStream out = Files.newOutputStream(filePath)) {
                        zipIn.transferTo(out);
                    }
                }
                zipIn.closeEntry();
            }
            System.out.println("JDK extracted to: " + outputDir.toAbsolutePath());
        }

        // Step 3: Clean up
        Files.deleteIfExists(tempZip);
    }

    private void disableSSLCertificateValidation() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
    }
}