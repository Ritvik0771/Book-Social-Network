// File: amps-base-image/src/main/java/com/ncratleos/baseimage/jre/JreUpdateChecker.java
package com.ncratleos.baseimage.jre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ncratleos.baseimage.service.GithubService;
import io.micrometer.common.util.StringUtils;
import com.ncratleos.baseimage.service.JreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.ncratleos.baseimage.exception.JreServiceException;

import java.io.*;
import java.lang.InterruptedException;


import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ncratleos.baseimage.constant.FilenameConstants.*;

@Component
@Slf4j
public class JreUpdateChecker {
    
    private final GithubService githubService;
    private final JreService jreService;
    private final ObjectMapper objectMapper;

    public JreUpdateChecker(GithubService githubService, JreService jreService, ObjectMapper objectMapper) {
        this.githubService = githubService;
        this.jreService = jreService;
        this.objectMapper = objectMapper;
    }

    public void checkJreUpdate(String githubAuthToken) throws JreServiceException {
        try{
            String targetDirectory = System.getProperty("java.io.tmpdir") + "/jre/";
//            jreService.downloadAndExtractJDK(targetDirectory);

            // 2. Get Java version
            Path jdkHome = findJdkHome(Paths.get(targetDirectory));
            log.info("Detected JDK at: " + jdkHome);

            String currentVersion = getJavaVersion(jdkHome.toString());
            log.info("Current Java version: {}", currentVersion);

            // 3. Get last JRE image details from GitHub
            String lastVersion = githubService.getLastJreImageDigest(githubAuthToken);
            log.info("Last Java version: {}", lastVersion);


            if (StringUtils.isBlank(currentVersion) || StringUtils.isBlank(lastVersion)) {
                throw new JreServiceException("Failed to get version information");
            }

             if (currentVersion.equals(lastVersion)) {
                 log.info("No new version of JRE available");
                 return;
             }
             // 4. Create and push new JRE image details JSON file
             createNewJreImageJsonFile(currentVersion);
             githubService.pushNewJreImageFileToRepo(githubAuthToken);





        }catch (Exception e) {
             throw new JreServiceException("Failed to check JRE update", e);
         }


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


    private void createNewJreImageJsonFile(String version) {
        Map<String, Object> data = new HashMap<>();
        data.put("version", version);

        try (FileWriter writer = new FileWriter(NEW_JRE_JSON_FILENAME)) {
            objectMapper.writeValue(new File(NEW_JRE_JSON_FILENAME), data);
            log.info("Created new JRE image details file: {}", NEW_JRE_JSON_FILENAME);
        } catch (IOException e) {
            log.error("Failed to create new JRE image details file", e);
        }
    }
}
