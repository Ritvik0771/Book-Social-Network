package com.ncratleos.baseimage.jre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ncratleos.baseimage.dto.AlpineImageInfoDto;
import com.ncratleos.baseimage.service.GithubService;
import com.ncratleos.baseimage.service.SSLConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.ncratleos.baseimage.exception.JreServiceException;

import java.io.*;
import java.lang.InterruptedException;

import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;

import java.util.regex.Pattern;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import static com.ncratleos.baseimage.constant.FilenameConstants.NEW_JRE_JSON_FILENAME;
import static com.ncratleos.baseimage.constant.UrlConstants.TMP_JRE_DIR;
import static com.ncratleos.baseimage.constant.UrlConstants.JRE_DIR;
import static com.ncratleos.baseimage.constant.UrlConstants.PREFIX_TMP_FILE;
import static com.ncratleos.baseimage.constant.UrlConstants.SUFFIX_TMP_FILE;
import static com.ncratleos.baseimage.constant.UrlConstants.PATH_JAVA_APPLICATION;
import static com.ncratleos.baseimage.constant.UrlConstants.BIN;
import static com.ncratleos.baseimage.constant.UrlConstants.JAVA;
import static com.ncratleos.baseimage.constant.UrlConstants.CHMOD;
import static com.ncratleos.baseimage.constant.UrlConstants.PERMISSION;
import static com.ncratleos.baseimage.constant.UrlConstants.VERSION_CHECKER;
import static com.ncratleos.baseimage.constant.UrlConstants.KEY_JRE_VERSION;
import static com.ncratleos.baseimage.constant.UrlConstants.KEY_ALPINE_IMAGE_REPO;
import static com.ncratleos.baseimage.constant.UrlConstants.VALUE_ALPINE_IMAGE_REPO;
import static com.ncratleos.baseimage.constant.UrlConstants.KEY_ALPINE_IMAGE_PATH;
import static com.ncratleos.baseimage.constant.UrlConstants.VALUE_ALPINE_IMAGE_PATH;
import static com.ncratleos.baseimage.constant.UrlConstants.KEY_ALPINE_TAGS;
import static com.ncratleos.baseimage.constant.UrlConstants.KEY_JRE_REPO_PATH;
import static com.ncratleos.baseimage.constant.UrlConstants.VALUE_JRE_REPO_PATH;
import static com.ncratleos.baseimage.constant.UrlConstants.KEY_JRE_IMAGE_TAGS;
import static com.ncratleos.baseimage.constant.UrlConstants.GITHUB_ENV;
import static com.ncratleos.baseimage.constant.UrlConstants.LATEST_ALPINE;
import static com.ncratleos.baseimage.constant.UrlConstants.FIXED_JRE_VERSION;
import static com.ncratleos.baseimage.constant.UrlConstants.OS;
import static com.ncratleos.baseimage.constant.UrlConstants.REGEX_EXTRACT_JRE_VERSION;
import static com.ncratleos.baseimage.constant.UrlConstants.REGEX_EXTRACT_ALPINE_TAG_NUMBER;
import static com.ncratleos.baseimage.constant.UrlConstants.JRE_LATEST_DOWNLOAD_URL;

/**
 * Class responsible for checking and updating the JRE version using GitHub services.
 * <p>
 * This class utilizes {@link GithubService} to interact with GitHub repositories and {@link ObjectMapper}
 * to handle JSON operations. It provides methods to check for JRE updates and push new JRE image details
 * to the GitHub repository if a new version is available.
 * </p>
 */
@Component
@Slf4j
public class JreUpdateChecker {

    /**
     * Service used to interact with GitHub repositories.
     * <p>
     * This service provides methods to retrieve and push data to GitHub, such as getting the latest JRE image version
     * and pushing new JRE image details.
     * </p>
     */
    private final GithubService githubService;

    /**
     * Object mapper used to handle JSON operations.
     * <p>
     * This object mapper is used to serialize and deserialize JSON data, which is essential for creating and
     * processing JRE image details in JSON format.
     * </p>
     */
    private final ObjectMapper objectMapper;

    /**
     * This field holds the SSL configuration instance used to disable SSL certificate validation.
     * It is initialized and used to set up the SSL context and hostname verifier.
     */
    private final SSLConfig sslConfig;
    /**
     * Constructs a new JreUpdateChecker with the specified GitHub service and object mapper.
     *
     * @param githubService the GitHub service used to interact with GitHub repositories
     * @param objectMapper  the object mapper used to handle JSON operations
     * @param sslConfig
     */
    public JreUpdateChecker(GithubService githubService, ObjectMapper objectMapper, SSLConfig sslConfig) {
        this.githubService = githubService;
        this.objectMapper = objectMapper;
        this.sslConfig = sslConfig;
    }

    /**
     * Checks for updates to the JRE version and updates the GitHub repository if a new version is available.
     * <p>
     * This method performs the following steps:
     * <ol>
     *   <li>Downloads and extracts the latest JDK to a temporary directory.</li>
     *   <li>Detects the current Java version from the extracted JDK.</li>
     *   <li>Retrieves the last JRE image version details from GitHub.</li>
     *   <li>Compares the current Java version with the last version from GitHub.</li>
     *   <li>If a new version is available, creates and pushes a new JRE image details JSON file to the GitHub repository.</li>
     * </ol>
     * </p>
     *
     * @param githubAuthToken the GitHub authentication token used to access the repository
     * @throws JreServiceException if there is an error during the update check process
     */
    public void checkJreUpdate(String githubAuthToken) throws JreServiceException {
        try {
            String targetDirectory = System.getProperty(TMP_JRE_DIR) + JRE_DIR;
            downloadAndExtractJDK(targetDirectory);

            // 2. Get Java version
            Path jdkHome = findJdkHome(Paths.get(targetDirectory));
            log.info("Detected JDK at: {}", jdkHome);

            String currentVersion = getJavaVersion(jdkHome.toString());
            log.info("Current Java version: {}", currentVersion);

            // 3. Get last JRE image details from GitHub
            String lastVersion = githubService.getLastJreImageVersion(githubAuthToken);
            log.info("Last Java version: {}", lastVersion);

            if (currentVersion.equals(lastVersion)) {
                log.info("No new version of JRE available");
                return;
            }
            // 4. Create and push new JRE image details JSON file
            createNewJreImageJsonFile(currentVersion, githubAuthToken);
            githubService.pushNewJreImageFileToRepo(githubAuthToken);

        } catch (Exception e) {
            throw new JreServiceException("Failed to check JRE update", e);
        }
    }

    /**
     * Downloads and extracts the JDK to the specified output directory.
     * <p>
     * This method performs the following steps:
     * <ol>
     *   <li>Creates the output directory if it does not exist.</li>
     *   <li>Downloads the JDK ZIP file to a temporary location.</li>
     *   <li>Extracts the contents of the ZIP file to the output directory.</li>
     * </ol>
     * </p>
     *
     * @param outputDirPath the path to the directory where the JDK should be extracted
     * @throws IOException if an I/O error occurs during the download or extraction process
     */
    public void downloadAndExtractJDK(String outputDirPath) throws IOException {
        Path outputDir = Paths.get(outputDirPath);
        Files.createDirectories(outputDir);

        Path tempFile = Files.createTempFile(PREFIX_TMP_FILE, SUFFIX_TMP_FILE);

        try {
            sslConfig.disableSSLCertificateValidation();
        } catch (Exception e) {
            throw new IOException("Failed to disable SSL Validation", e);
        }

        // Step 1: Download the file
        try (InputStream in = new URL(JRE_LATEST_DOWNLOAD_URL).openStream()) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("JDK downloaded to: {}" + tempFile);
        } catch (IOException e) {
            throw new IOException("Failed to download jdk", e);
        }

        try (
                GZIPInputStream gzipIn = new GZIPInputStream(new FileInputStream(tempFile.toFile()));
                TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)
        ) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                Path entryPath = outputDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream out = Files.newOutputStream(entryPath)) {
                        tarIn.transferTo(out);
                    } catch (IOException e) {
                         log.error("Error writing file: " + entryPath + " - " + e.getMessage());
                    }
                }
            }
            log.info("JDK extracted to: " + outputDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Error extracting JDK: " + e.getMessage());
        }

        // Step 3: Clean up
        Files.deleteIfExists(tempFile);
    }


    /**
     * Finds the JDK home directory within the specified parent directory.
     * <p>
     * This method searches the specified parent directory for a subdirectory containing the JDK installation.
     * It checks for the presence of the {@code bin/java.exe} file to identify the JDK home.
     * </p>
     *
     * @param parentDir the parent directory to search for the JDK installation
     * @return the path to the JDK home directory
     * @throws IOException if no JDK installation is found in the specified parent directory
     */
    public Path findJdkHome(Path parentDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parentDir)) {
            for (Path path : stream) {
                if (Files.isDirectory(path) && Files.exists(path.resolve(PATH_JAVA_APPLICATION))) {
                    return path;
                }
            }
        } catch (IOException e) {
            log.error("Error reading directory: " + e.getMessage());
        }
        throw new IOException("No JDK installation found in: {}" + parentDir);
    }

    /**
     * Retrieves the Java version from the specified Java home directory.
     * <p>
     * This method executes the {@code java --version} command to obtain the Java version from the specified
     * Java home directory. It captures the output and returns the version as a string.
     * </p>
     *
     * @param javaHome the path to the Java home directory
     * @return the Java version as a string
     * @throws IOException          if an I/O error occurs during the process execution
     * @throws InterruptedException if the process is interrupted
     */
    public String getJavaVersion(String javaHome) throws IOException, InterruptedException {
        // Point to the java binary inside the extracted JDK
        Path javaBin = Paths.get(javaHome, BIN, JAVA);
        new ProcessBuilder(CHMOD, PERMISSION, javaBin.toString()).start().waitFor();
        ProcessBuilder pb = new ProcessBuilder(javaBin.toString(), VERSION_CHECKER);
        pb.redirectErrorStream(true); // merge stderr into stdout
        Process process = pb.start();

        // Capture output
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            if (reader.readLine() != null)
                output.append(reader.readLine());
        } catch (IOException e) {
            log.error("Error reading process output: " + e.getMessage());
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("java --version exited with code " + exitCode);
        }

        return output.toString().trim();
    }

    /**
     * Creates a new JSON file with JRE image details and pushes it to the GitHub repository.
     * <p>
     * This method creates a JSON file containing JRE image details such as version, repository paths, and tags.
     * It then pushes the file to the GitHub repository using the specified authentication token.
     * </p>
     *
     * @param version         the JRE version
     * @param githubAuthToken the GitHub authentication token used to access the repository
     */
    public void createNewJreImageJsonFile(String version, String githubAuthToken) {
        Map<String, Object> data = new HashMap<>();
        data.put(KEY_ALPINE_IMAGE_REPO, VALUE_ALPINE_IMAGE_REPO);
        data.put(KEY_ALPINE_IMAGE_PATH, VALUE_ALPINE_IMAGE_PATH);

        List<String> latestAlpineTags = githubService.getLastImageTags(githubAuthToken);
        data.put(KEY_ALPINE_TAGS, String.join(",", latestAlpineTags));
        data.put(KEY_JRE_REPO_PATH, VALUE_JRE_REPO_PATH);
        data.put(KEY_JRE_VERSION, version);

        String jreTags = customJreTags(githubAuthToken, version, latestAlpineTags);
        data.put(KEY_JRE_IMAGE_TAGS, jreTags);

        data.put("update", "true");
        try (FileWriter writer = new FileWriter(System.getenv(GITHUB_ENV), true)) {
            objectMapper.writeValue(new File(NEW_JRE_JSON_FILENAME), data);
            log.info("Created new JRE image details file: {}", NEW_JRE_JSON_FILENAME);
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue().toString() + "\n");
            }
        } catch (IOException e) {
            log.error("Failed to create new JRE image details file", e);
        }
    }

    /**
     * Generates custom JRE tags based on the provided version and latest Alpine tags.
     * <p>
     * This method creates a list of custom JRE tags using fixed values, the provided version, and the latest Alpine tags.
     * It returns the tags as a comma-separated string.
     * </p>
     *
     * @param githubAuthToken  the GitHub authentication token used to access the repository
     * @param version          the JRE version
     * @param latestAlpineTags the list of latest Alpine tags
     * @return a comma-separated string of custom JRE tags
     */
    public String customJreTags(String githubAuthToken, String version, List<String> latestAlpineTags) {
        List<String> jreTags = new ArrayList<>();
        // fix values
        String platformArchitecture = getPlatformArchitecture(githubAuthToken);
        String variableJreVersion = extractVersion(version);
        String alpinetag = extractAlpineTag(latestAlpineTags);

        jreTags.add(LATEST_ALPINE);
        jreTags.add(FIXED_JRE_VERSION);
        jreTags.add(FIXED_JRE_VERSION + "-" + OS + "-" + alpinetag);
        jreTags.add(FIXED_JRE_VERSION + "-" + OS + "-" + alpinetag + "-" + platformArchitecture);
        jreTags.add(variableJreVersion);
        jreTags.add(variableJreVersion + "-" + OS + "-" + alpinetag);
        jreTags.add(variableJreVersion + "-" + OS + "-" + alpinetag + "-" + platformArchitecture);

        return String.join(",", jreTags);
    }

    /**
     * Retrieves the platform architecture from the Alpine image manifest.
     * <p>
     * This method uses the GitHub service to get the Alpine image manifest and extracts the platform architecture.
     * </p>
     *
     * @param githubAuthToken the GitHub authentication token used to access the repository
     * @return the platform architecture as a string
     */
    public String getPlatformArchitecture(String githubAuthToken) {
        AlpineImageInfoDto alpineImageInfoDto = this.githubService.getAlpineManifest(githubAuthToken);
        return alpineImageInfoDto.getPlatform().getArchitecture();
    }

    /**
     * Extracts the version number from the provided version string.
     * <p>
     * This method uses a regular expression to find and extract the version number from the provided string.
     * </p>
     *
     * @param version the version string to extract the version number from
     * @return the extracted version number as a string, or {@code null} if no version number is found
     */
    public String extractVersion(String version) {
        Pattern pattern = Pattern.compile(REGEX_EXTRACT_JRE_VERSION);
        Matcher matcher = pattern.matcher(version);
        if (matcher.find())
            return matcher.group(1);
        return null;
    }

    /**
     * Extracts the Alpine tag from the list of latest Alpine tags.
     * <p>
     * This method iterates through the list of latest Alpine tags and uses a regular expression to find and extract
     * the Alpine tag, excluding the "latest" tag.
     * </p>
     *
     * @param latestAlpineTags the list of latest Alpine tags
     * @return the extracted Alpine tag as a string, or {@code null} if no tag is found
     */
    public String extractAlpineTag(List<String> latestAlpineTags) {
        return latestAlpineTags.stream()
                .filter(tag -> !tag.equals("latest"))
                .map(tag -> {
                    Pattern pattern = Pattern.compile(REGEX_EXTRACT_ALPINE_TAG_NUMBER);
                    Matcher matcher = pattern.matcher(tag);
                    return matcher.find() ? matcher.group(1) : null;
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}