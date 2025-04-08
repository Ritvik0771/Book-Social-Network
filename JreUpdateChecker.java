package com.ncratleos.baseimage.jre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ncratleos.baseimage.dto.AlpineImageInfoDto;
import com.ncratleos.baseimage.service.GithubService;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.ncratleos.baseimage.exception.JreServiceException;

import java.io.*;
import java.lang.InterruptedException;


import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.ncratleos.baseimage.constant.FilenameConstants.*;

@Component
@Slf4j
public class JreUpdateChecker {

    private static final String DOWNLOAD_URL = "https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip";
    private final GithubService githubService;
    private final ObjectMapper objectMapper;

    public JreUpdateChecker(GithubService githubService, ObjectMapper objectMapper) {
        this.githubService = githubService;
        this.objectMapper = objectMapper;
    }

    public void checkJreUpdate(String githubAuthToken) throws JreServiceException {
        try{
            String targetDirectory = System.getProperty("java.io.tmpdir") + "/jre/";
            downloadAndExtractJDK(targetDirectory);

            // 2. Get Java version
            Path jdkHome = findJdkHome(Paths.get(targetDirectory));
            log.info("Detected JDK at: " + jdkHome);

            String currentVersion = getJavaVersion(jdkHome.toString());
            log.info("Current Java version: {}", currentVersion);

            // 3. Get last JRE image details from GitHub
            String lastVersion = githubService.getLastJreImageVersion(githubAuthToken);
            log.info("Last Java version: {}", lastVersion);


            if (StringUtils.isBlank(currentVersion) || StringUtils.isBlank(lastVersion)) {
                throw new JreServiceException("Failed to get version information");
            }

             if (currentVersion.equals(lastVersion)) {
                 log.info("No new version of JRE available");
                 return;
             }
             // 4. Create and push new JRE image details JSON file
             createNewJreImageJsonFile(currentVersion, githubAuthToken);
             githubService.pushNewJreImageFileToRepo(githubAuthToken);

        }catch (Exception e) {
             throw new JreServiceException("Failed to check JRE update", e);
         }
    }

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
        try (InputStream in = downloadJdkFile(DOWNLOAD_URL)) {
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

    protected InputStream downloadJdkFile(String url) throws IOException {
        return new URL(url).openStream();
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

    public Path findJdkHome(Path parentDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parentDir)) {
            for (Path path : stream) {
                if (Files.isDirectory(path) && Files.exists(path.resolve("bin/java.exe"))) {
                    return path;
                }
            }
        }
        throw new IOException("No JDK installation found in: " + parentDir);
    }

    public String getJavaVersion(String javaHome) throws IOException, InterruptedException {
        // Point to the java binary inside the extracted JDK
        Path javaBin = Paths.get(javaHome, "bin", "java");

        ProcessBuilder pb = new ProcessBuilder(javaBin.toString(), "--version");
        pb.redirectErrorStream(true); // merge stderr into stdout
        Process process = pb.start();

        // Capture output
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
                break;
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("java --version exited with code " + exitCode);
        }

        return output.toString().trim();
    }

    public void createNewJreImageJsonFile(String version, String githubAuthToken) {
        Map<String, Object> data = new HashMap<>();
        data.put("jre-version", version);
        data.put("alpine-image-repo", "fsg-ess-docker-releases");
        data.put("alpine-image-path", "ncratleos.jfrog.io/fsg-ess-docker-releases/com/ncratleos/baseimages/alpine");

        List<String> latestAlpineTags = githubService.getLastImageTags(githubAuthToken);
        String alpineTags = String.join(",", latestAlpineTags);
        data.put("alpine-tags",alpineTags);
        data.put("jre-repo-path", "fsg-ess-docker-releases/com/ncratleos/baseimages/corretto/jre");

        String jreTags = customJreTags(githubAuthToken, version, latestAlpineTags);

        data.put("jre-image-tags", jreTags);

        try (FileWriter writer = new FileWriter(System.getenv("GITHUB_ENV"), true)) {
            objectMapper.writeValue(new File(NEW_JRE_JSON_FILENAME), data);
            log.info("Created new JRE image details file: {}", NEW_JRE_JSON_FILENAME);
            for (Map.Entry<String, Object> entry : data.entrySet()){
                writer.write(entry.getKey() + "=" + entry.getValue().toString() + "\n");
            }
        } catch (IOException e) {
            log.error("Failed to create new JRE image details file", e);
        }
    }

    public String customJreTags(String githubAuthToken, String version, List<String> latestAlpineTags){

        List<String> jreTags = new ArrayList<>();
        // fix values
        String latestAlpine = "latest";
        String fixJreVersion = "17";
        String platformArchitecture = getPlatformArchitecture(githubAuthToken);
        String os = "alpine";
        String variableJreVersion = extractVersion(version);
        String alpinetag = extractAlpineTag(latestAlpineTags);

        jreTags.add(latestAlpine);
        jreTags.add(fixJreVersion);
        jreTags.add(fixJreVersion + "-" + os + "-" + alpinetag);
        jreTags.add(fixJreVersion + "-" + os + "-" + alpinetag + "-" + platformArchitecture);
        jreTags.add(variableJreVersion);
        jreTags.add(variableJreVersion + "-" + os + "-" + alpinetag);
        jreTags.add(variableJreVersion + "-" + os + "-" + alpinetag + "-" + platformArchitecture);

        return String.join(",", jreTags);
    }

    public String getPlatformArchitecture(String githubAuthToken){
        AlpineImageInfoDto alpineImageInfoDto = this.githubService.getAlpineManifest(githubAuthToken);
        return alpineImageInfoDto.getPlatform().getArchitecture();
    }

    public String extractVersion(String version){
        Pattern pattern = Pattern.compile("\\b(\\d+\\.\\d+\\.\\d+)\\b");
        Matcher matcher = pattern.matcher(version);
        if(matcher.find())
            return matcher.group(1);
        return null;
    }

    public String extractAlpineTag(List<String> latestAlpineTags){
        String alpinetag = null ;
        for(String tag : latestAlpineTags){
            if(!tag.equals("latest")){
                Pattern pattern = Pattern.compile("^(\\d+\\.\\d+)");
                Matcher matcher = pattern.matcher(tag);
                if(matcher.find())
                    alpinetag = matcher.group(1);
            }
        }
        return alpinetag;
    }
}
