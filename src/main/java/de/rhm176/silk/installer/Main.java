/*
 * Copyright 2025 Silk Loader
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.rhm176.silk.installer;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.jetbrains.annotations.VisibleForTesting;

public class Main {
    // these two only exist to make testing easier
    interface HttpClientProvider {
        HttpClient getHttpClient();
    }

    static class DefaultHttpClientProvider implements HttpClientProvider {
        private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public HttpClient getHttpClient() {
            return HTTP_CLIENT;
        }
    }

    public static List<String> FABRIC_MAVENS =
            List.of("https://maven.fabricmc.net/", "https://maven2.fabricmc.net/", "https://maven3.fabricmc.net/");
    public static final String FABRIC_LOADER_VERSIONS_URL = "https://meta.fabricmc.net/v2/versions/loader";
    public static final String SILK_LOADER_RELEASES_URL =
            "https://api.github.com/repos/SilkLoader/silk-loader/releases";

    public static final String SILK_LOADER_FIXED_JAR_NAME = "silk-loader.jar";

    private static HttpClient httpClient;

    // only so testing is easier
    static {
        setHttpClient(new DefaultHttpClientProvider().getHttpClient());
    }

    public static void setHttpClient(HttpClient client) {
        Main.httpClient = client;
    }

    public static void main(String[] args)
            throws UnsupportedLookAndFeelException, ClassNotFoundException, InstantiationException,
                    IllegalAccessException {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

        if (args.length == 0) {
            System.out.println("Run with --help for cli usage.");
            SwingUtilities.invokeLater(() -> {
                InstallerWindow window = new InstallerWindow();
                window.setVisible(true);
            });
        } else {
            handleCommandLineArgs(args);
        }
    }

    private static void handleCommandLineArgs(String[] args) {
        switch (args[0]) {
            case "--help" -> printHelp();
            case "install" -> {
                if (args.length < 3 || args.length > 4) {
                    System.err.println("Usage: install <silk-release-tag> <fabric-loader-version> [game-path]");
                    return;
                }
                String silkReleaseTag = args[1];
                String fabricVersion = args[2];
                Path gamePath = null;
                if (args.length == 4) {
                    gamePath = Paths.get(args[3]);
                } else {
                    System.out.println("Game path not provided. Attempting to find Equilinox installation...");
                    try {
                        String stringGamePath = EquilinoxGameFinder.tryFindGame();
                        if (stringGamePath == null) {
                            System.err.println(
                                    "Could not automatically find Equilinox installation. Please specify the game path manually for uninstallation.");
                            System.exit(1);
                        }
                        gamePath = Path.of(stringGamePath);
                        System.out.println("Found Equilinox at: " + gamePath);
                    } catch (Exception e) {
                        System.err.println("Error while searching for game path: " + e.getMessage());
                        e.printStackTrace(System.err);
                        System.exit(1);
                    }
                }

                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(FABRIC_LOADER_VERSIONS_URL))
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        // very unoptimized
                        JsonArray versions = Json.parse(response.body()).asArray();
                        if (!versions.isEmpty()) {
                            Path finalGamePath = gamePath;
                            versions.stream()
                                    .filter(JsonValue::isObject)
                                    .map(JsonValue::asObject)
                                    .filter(o -> o.contains("version"))
                                    .filter(o -> fabricVersion.equals(
                                            o.get("version").asString()))
                                    .findFirst()
                                    .ifPresentOrElse(
                                            (obj) -> install(
                                                    obj.get("maven").asString(), silkReleaseTag, finalGamePath, null),
                                            () -> System.out.println(
                                                    "Could not find version '" + fabricVersion + "'."));
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Installation failed: " + e.getMessage());
                    e.printStackTrace(System.err);
                    System.exit(1);
                }
            }
            case "uninstall" -> {
                if (args.length > 2) {
                    System.err.println("Usage: uninstall [game-path]");
                    return;
                }
                Path gamePath = null;
                if (args.length == 2) {
                    gamePath = Paths.get(args[1]);
                } else {
                    System.out.println(
                            "Game path not provided. Attempting to find Equilinox installation for uninstallation...");
                    try {
                        String stringGamePath = EquilinoxGameFinder.tryFindGame();
                        if (stringGamePath == null) {
                            System.err.println(
                                    "Could not automatically find Equilinox installation. Please specify the game path manually for uninstallation.");
                            System.exit(1);
                        }
                        gamePath = Path.of(stringGamePath);
                        System.out.println("Found Equilinox at: " + gamePath);
                    } catch (Exception e) {
                        System.err.println("Error while searching for game path: " + e.getMessage());
                        e.printStackTrace(System.err);
                        System.exit(1);
                    }
                }

                try {
                    uninstall(gamePath, null, false);
                } catch (Exception e) {
                    System.err.println("Uninstallation failed: " + e.getMessage());
                    e.printStackTrace(System.err);
                    System.exit(1);
                }
            }
            default -> {
                System.err.println("Unknown command: " + args[0] + ". Run with --help for usage information.");
                System.exit(1);
            }
        }
    }

    private static void printHelp() {
        System.out.println("Silk Loader Installer Command Line Usage:");
        System.out.println("  --help                          Show this help message.");
        System.out.println("  install <silk-release-tag> <fabric-loader-version> [game-path]");
        System.out.println("                                  Install Silk Loader and Fabric Loader.");
        System.out.println("                                  <silk-release-tag> e.g., v1.0.0");
        System.out.println("                                  <fabric-loader-version> e.g., 0.15.7");
        System.out.println(
                "                                  [game-path] Optional. e.g., /path/to/Equilinox. If omitted, the installer will try to find it automatically.");
        System.out.println("  uninstall [game-path]           Uninstall Silk Loader and Fabric Loader.");
        System.out.println(
                "                                  [game-path] Optional. e.g., /path/to/Equilinox. If omitted, the installer will try to find it automatically.");
        System.out.println("\nVersion Information:");

        String packageVersion = Main.class.getPackage().getImplementationVersion();
        System.out.println("  Installer Version: " + (packageVersion != null ? packageVersion : "Unknown"));

        try {
            String latestSilkLoader = getLatestSilkLoaderVersion();
            System.out.println("  Latest Silk Loader: " + (latestSilkLoader != null ? latestSilkLoader : "N/A"));
        } catch (IOException | InterruptedException e) {
            System.err.println("  Could not fetch latest Silk Loader version: " + e.getMessage());
        }

        try {
            String latestFabricLoader = getLatestFabricLoaderVersion();
            System.out.println("  Latest Fabric Loader: " + (latestFabricLoader != null ? latestFabricLoader : "N/A"));
        } catch (IOException | InterruptedException e) {
            System.err.println("  Could not fetch latest Fabric Loader version: " + e.getMessage());
        }
    }

    private static String getLatestSilkLoaderVersion() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Main.SILK_LOADER_RELEASES_URL))
                .header("Accept", "application/vnd.github.v3+json")
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonArray releases = Json.parse(response.body()).asArray();
            if (!releases.isEmpty()) {
                JsonObject latestRelease = releases.get(0).asObject();
                return latestRelease.get("tag_name").asString();
            }
        }
        return null;
    }

    private static String getLatestFabricLoaderVersion() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(FABRIC_LOADER_VERSIONS_URL))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonArray versions = Json.parse(response.body()).asArray();
            if (!versions.isEmpty()) {
                JsonObject latestLoader = versions.get(0).asObject();
                return latestLoader.get("version").asString();
            }
        }
        return null;
    }

    @VisibleForTesting
    static void updateStatus(JLabel statusLabel, String message) {
        System.out.println(message);
        if (statusLabel != null) {
            SwingUtilities.invokeLater(() -> statusLabel.setText(message));
        }
    }

    @VisibleForTesting
    static void downloadFile(String url, Path outputPath, String fileDescription, JLabel statusLabel)
            throws IOException, InterruptedException {
        updateStatus(statusLabel, "Downloading " + fileDescription + "...");
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();

        Path tempFile = Files.createTempFile(
                outputPath.getParent(), outputPath.getFileName().toString(), ".tmp");
        try {
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            if (response.statusCode() == 200) {
                Files.move(tempFile, outputPath, StandardCopyOption.REPLACE_EXISTING);
                updateStatus(statusLabel, fileDescription + " downloaded successfully.");
            } else {
                throw new IOException("Failed to download " + fileDescription + ". Status: " + response.statusCode()
                        + " from " + url);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @VisibleForTesting
    static void deleteDirectoryRecursively(Path path, JLabel statusLabel) throws IOException {
        if (Files.exists(path) && Files.isDirectory(path)) {
            updateStatus(statusLabel, "Deleting directory: " + path);
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()).forEach((p) -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        updateStatus(statusLabel, "Failed to delete: " + p);
                        e.printStackTrace(System.err);
                    }
                });
            }
            updateStatus(statusLabel, "Directory deleted: " + path);
        } else if (Files.exists(path) && !Files.isDirectory(path)) {
            updateStatus(statusLabel, "Path exists but is not a directory (will attempt to delete as file): " + path);
            Files.deleteIfExists(path);
        }
    }

    public static void uninstall(Path gamePath, JLabel statusLabel, boolean asCleanup) throws IOException {
        if (!asCleanup) {
            updateStatus(statusLabel, "Uninstallation process started...");
        }
        boolean somethingWasUninstalled = false;
        try {
            Path silkJarFixedPath = gamePath.resolve(SILK_LOADER_FIXED_JAR_NAME);
            Path libDirPath = gamePath.resolve("lib");
            Path fabricDirPath = gamePath.resolve(".fabric");

            updateStatus(statusLabel, "Attempting to delete " + SILK_LOADER_FIXED_JAR_NAME + "...");
            if (Files.exists(silkJarFixedPath)) {
                Files.delete(silkJarFixedPath);
                updateStatus(statusLabel, SILK_LOADER_FIXED_JAR_NAME + " deleted successfully.");
                somethingWasUninstalled = true;
            } else {
                updateStatus(statusLabel, SILK_LOADER_FIXED_JAR_NAME + " not found, nothing to delete.");
            }

            updateStatus(statusLabel, "Attempting to delete 'lib' directory...");
            if (Files.exists(libDirPath) && Files.isDirectory(libDirPath)) {
                deleteDirectoryRecursively(libDirPath, statusLabel);
                updateStatus(statusLabel, "'lib' directory deleted successfully.");
                somethingWasUninstalled = true;
            } else {
                updateStatus(statusLabel, "'lib' directory not found, nothing to delete.");
            }

            updateStatus(statusLabel, "Attempting to delete '.fabric' directory...");
            if (Files.exists(fabricDirPath) && Files.isDirectory(fabricDirPath)) {
                deleteDirectoryRecursively(fabricDirPath, statusLabel);
                updateStatus(statusLabel, "'.fabric' directory deleted successfully.");
                somethingWasUninstalled = true;
            } else {
                updateStatus(statusLabel, "'.fabric' directory not found, nothing to delete.");
            }

            if (!asCleanup) {
                if (somethingWasUninstalled) {
                    updateStatus(
                            statusLabel,
                            "Uninstallation completed. Please manually remove Steam launch options if set.");
                } else {
                    updateStatus(statusLabel, "No mod files found to uninstall. Directory seems clean.");
                }
                SwingUtilities.invokeLater(() -> showUninstallInstructionsPopup(statusLabel));
            }

        } catch (IOException e) {
            String errorMessage = "Uninstallation failed: " + e.getMessage();
            updateStatus(statusLabel, errorMessage);
            System.err.println(errorMessage);
            e.printStackTrace(System.err);
            throw e;
        }
    }

    public static void install(String fabricMaven, String silkReleaseTag, Path gamePath, JLabel statusLabel) {
        updateStatus(statusLabel, "Installation process started...");
        ExecutorService executorService =
                Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        List<Future<?>> downloadTasks = new ArrayList<>();

        try {
            uninstall(gamePath, statusLabel, true);
            updateStatus(statusLabel, "Starting Silk Loader installation for " + silkReleaseTag + "...");
            String silkReleaseApiUrl = SILK_LOADER_RELEASES_URL + "/tags/" + silkReleaseTag;
            HttpRequest silkApiRequest = HttpRequest.newBuilder()
                    .uri(URI.create(silkReleaseApiUrl))
                    .header("Accept", "application/vnd.github.v3+json")
                    .build();
            HttpResponse<String> silkApiResponse =
                    httpClient.send(silkApiRequest, HttpResponse.BodyHandlers.ofString());
            if (silkApiResponse.statusCode() != 200) {
                throw new IOException("Failed to fetch Silk Loader release info for " + silkReleaseTag + ". Status: "
                        + silkApiResponse.statusCode() + " Body: " + silkApiResponse.body());
            }
            JsonObject releaseJson = Json.parse(silkApiResponse.body()).asObject();
            JsonArray assets = releaseJson.get("assets").asArray();
            if (assets == null) {
                throw new IOException("No assets found in Silk Loader release " + silkReleaseTag);
            }
            String silkJarDownloadUrl = null;
            String originalSilkJarName = null;
            for (int i = 0; i < assets.size(); i++) {
                JsonObject asset = assets.get(i).asObject();
                String name = asset.get("name").asString();
                if (name.toLowerCase().endsWith(".jar")) {
                    silkJarDownloadUrl = asset.get("browser_download_url").asString();
                    originalSilkJarName = name;
                    break;
                }
            }
            if (silkJarDownloadUrl == null || silkJarDownloadUrl.isEmpty() || originalSilkJarName.isEmpty()) {
                throw new IOException("No JAR file download URL found in Silk Loader release " + silkReleaseTag);
            }

            Path silkJarFixedPath = gamePath.resolve(SILK_LOADER_FIXED_JAR_NAME);
            downloadFile(
                    silkJarDownloadUrl,
                    silkJarFixedPath,
                    "Silk Loader (" + originalSilkJarName + " as " + SILK_LOADER_FIXED_JAR_NAME + ")",
                    statusLabel);
            updateStatus(statusLabel, "Silk Loader (" + SILK_LOADER_FIXED_JAR_NAME + ") installed successfully.");

            updateStatus(statusLabel, "Starting Fabric Loader installation (" + fabricMaven + ")...");
            Path libDir = gamePath.resolve("lib");
            Files.createDirectories(libDir);

            String[] mavenParts = fabricMaven.split(":");
            if (mavenParts.length != 3) {
                throw new IllegalArgumentException("Invalid Fabric Maven coordinates: " + fabricMaven
                        + ". Expected format: group:artifact:version");
            }
            String fabricGroup = mavenParts[0];
            String fabricArtifact = mavenParts[1];
            String fabricVersion = mavenParts[2];
            String fabricGroupPath = fabricGroup.replace('.', '/');

            String fabricJarFileName = fabricArtifact + "-" + fabricVersion + ".jar";
            Path fabricJarOutputPath = libDir.resolve(fabricJarFileName);
            boolean fabricJarDownloaded = false;
            Exception lastFabricJarDownloadException = null;
            for (String mavenRepoUrl : FABRIC_MAVENS) {
                String fullFabricJarUrl = mavenRepoUrl + (mavenRepoUrl.endsWith("/") ? "" : "/") + fabricGroupPath + "/"
                        + fabricArtifact + "/" + fabricVersion + "/" + fabricJarFileName;
                try {
                    downloadFile(
                            fullFabricJarUrl,
                            fabricJarOutputPath,
                            "Fabric Loader JAR (" + fabricJarFileName + ")",
                            statusLabel);
                    fabricJarDownloaded = true;
                    break;
                } catch (IOException e) {
                    lastFabricJarDownloadException = e;
                    updateStatus(
                            statusLabel,
                            "Attempt to download Fabric JAR from " + mavenRepoUrl + " failed. Trying next...");
                }
            }
            if (!fabricJarDownloaded) {
                throw new IOException(
                        "Failed to download Fabric Loader JAR from all configured repositories.",
                        lastFabricJarDownloadException);
            }
            updateStatus(statusLabel, "Fabric Loader JAR (" + fabricJarFileName + ") installed successfully.");

            String fabricJsonMetaFileName = fabricArtifact + "-" + fabricVersion + ".json";
            boolean fabricJsonMetaFetched = false;
            Exception lastFabricJsonMetaFetchException = null;
            String fabricLoaderJsonContent = null;

            updateStatus(statusLabel, "Fetching Fabric Loader JSON metadata...");
            for (String mavenRepoUrl : FABRIC_MAVENS) {
                String fullFabricJsonUrl = mavenRepoUrl + (mavenRepoUrl.endsWith("/") ? "" : "/") + fabricGroupPath
                        + "/" + fabricArtifact + "/" + fabricVersion + "/" + fabricJsonMetaFileName;
                try {
                    updateStatus(
                            statusLabel, "Attempting to fetch Fabric Loader JSON from " + fullFabricJsonUrl + "...");
                    HttpRequest jsonRequest = HttpRequest.newBuilder()
                            .uri(URI.create(fullFabricJsonUrl))
                            .build();
                    HttpResponse<String> jsonResponse =
                            httpClient.send(jsonRequest, HttpResponse.BodyHandlers.ofString());

                    if (jsonResponse.statusCode() >= 200 && jsonResponse.statusCode() < 300) {
                        fabricLoaderJsonContent = jsonResponse.body();
                        updateStatus(
                                statusLabel,
                                "Fabric Loader JSON metadata fetched successfully from " + fullFabricJsonUrl);
                        fabricJsonMetaFetched = true;
                        break;
                    } else {
                        lastFabricJsonMetaFetchException =
                                new IOException("Status: " + jsonResponse.statusCode() + " from " + fullFabricJsonUrl);
                        updateStatus(
                                statusLabel,
                                "Failed to fetch Fabric JSON from " + fullFabricJsonUrl + ". Status: "
                                        + jsonResponse.statusCode() + ". Trying next...");
                    }
                } catch (IOException | InterruptedException e) {
                    lastFabricJsonMetaFetchException = e;
                    updateStatus(
                            statusLabel,
                            "Error fetching Fabric JSON metadata from " + fullFabricJsonUrl + ": " + e.getMessage()
                                    + ". Trying next...");
                }
            }

            if (!fabricJsonMetaFetched || fabricLoaderJsonContent == null) {
                throw new IOException(
                        "Failed to fetch Fabric Loader JSON metadata from all configured repositories.",
                        lastFabricJsonMetaFetchException);
            }

            updateStatus(statusLabel, "Parsing Fabric Loader JSON and downloading common libraries...");
            JsonObject fabricMetaJson = Json.parse(fabricLoaderJsonContent).asObject();
            JsonObject librariesSection = fabricMetaJson.get("libraries").asObject();
            if (librariesSection != null) {
                JsonArray commonLibraries = librariesSection.get("common").asArray();
                if (commonLibraries != null) {
                    for (int i = 0; i < commonLibraries.size(); i++) {
                        JsonObject libInfo = commonLibraries.get(i).asObject();
                        String libNameFull = libInfo.get("name").asString();
                        String libRepoUrl = libInfo.getString("url", FABRIC_MAVENS.get(0));

                        String[] libParts = libNameFull.split(":");
                        if (libParts.length < 3) {
                            updateStatus(statusLabel, "Skipping library with invalid coordinates: " + libNameFull);
                            System.err.println("Skipping library with invalid coordinates: " + libNameFull);
                            continue;
                        }
                        String libGroup = libParts[0];
                        String libArtifact = libParts[1];
                        String libVersion = libParts[2];
                        String libClassifier = null;
                        if (libParts.length > 3) {
                            libClassifier = libParts[3];
                        }

                        String libGroupPath = libGroup.replace('.', '/');
                        String libFileName = libArtifact + "-" + libVersion
                                + (libClassifier != null ? "-" + libClassifier : "") + ".jar";
                        Path libOutputPath = libDir.resolve(libFileName);

                        String repoBase = libRepoUrl.endsWith("/") ? libRepoUrl : libRepoUrl + "/";

                        final String finalFullLibUrl =
                                repoBase + libGroupPath + "/" + libArtifact + "/" + libVersion + "/" + libFileName;
                        final Path finalLibOutputPath = libOutputPath;
                        final String finalLibFileName = libFileName;
                        final JLabel finalStatusLabel = statusLabel;

                        Future<?> task = executorService.submit(() -> {
                            try {
                                downloadFile(
                                        finalFullLibUrl,
                                        finalLibOutputPath,
                                        "Library (" + finalLibFileName + ")",
                                        finalStatusLabel);
                            } catch (IOException | InterruptedException e) {
                                updateStatus(
                                        finalStatusLabel,
                                        "Failed to download " + finalLibFileName + ": " + e.getMessage());
                                System.err.println("Error downloading common library " + finalLibFileName + " from "
                                        + finalFullLibUrl + ": " + e.getMessage());
                            }
                        });
                        downloadTasks.add(task);
                    }
                } else {
                    updateStatus(statusLabel, "No 'common' libraries found in Fabric JSON.");
                }
            } else {
                updateStatus(statusLabel, "No 'libraries' section found in Fabric JSON.");
            }

            updateStatus(statusLabel, "Waiting for common library downloads to complete...");
            for (Future<?> task : downloadTasks) {
                try {
                    task.get();
                } catch (Exception e) {
                    updateStatus(statusLabel, "An error occurred during a library download task: " + e.getMessage());
                    System.err.println("Exception in library download task: "
                            + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                }
            }
            updateStatus(statusLabel, "All common library downloads attempted.");
            updateStatus(statusLabel, "Installation completed successfully!");
            System.out.println();
            System.out.println(
                    "To make the mod loader automatically launch when you start Equilinox on Steam, set your launch options to this:");
            System.out.printf(
                    "java -cp \"%s" + File.pathSeparator + "lib" + File.separator + "*\" %s %%command%%%n",
                    SILK_LOADER_FIXED_JAR_NAME,
                    getMainClassFromJar(silkJarFixedPath));

            SwingUtilities.invokeLater(() -> showInstallInstructionsPopup(statusLabel, silkJarFixedPath));
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            String errorMessage = "Installation failed: " + e.getMessage();
            updateStatus(statusLabel, errorMessage);
            System.err.println(errorMessage);
            e.printStackTrace(System.err);
        } finally {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                    updateStatus(statusLabel, "Library download tasks timed out and were forced to stop.");
                }
            } catch (InterruptedException ie) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @VisibleForTesting
    static String getMainClassFromJar(Path jarPath) {
        try (FileSystem fs = FileSystems.newFileSystem(jarPath, Map.of())) {
            Path manifestPath = fs.getPath("META-INF", "MANIFEST.MF");

            if (Files.exists(manifestPath)) {
                try (InputStream is = Files.newInputStream(manifestPath)) {
                    Manifest manifest = new Manifest(is);
                    Attributes mainAttributes = manifest.getMainAttributes();
                    return Objects.requireNonNull(mainAttributes.getValue(Attributes.Name.MAIN_CLASS));
                }
            } else {
                throw new IOException("JAR did not contain a MANIFEST.MF.");
            }
        } catch (IOException | NullPointerException e) {
            System.err.println("Error reading JAR manifest from " + jarPath + ": " + e.getMessage());
            e.printStackTrace(System.err);
        }
        return "de.rhm176.loader.Main"; // main class prior to including it in manifest
    }

    @VisibleForTesting
    static void showInstallInstructionsPopup(Component parentComponent, Path silkLoaderPath) {
        String title = "Installation Successful!";
        String headerMessage = "To make the mod loader automatically launch when you start Equilinox on Steam:";

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel headerLabel = new JLabel("<html><body style='width: 350px;'>" + headerMessage + "</body></html>");
        panel.add(headerLabel, BorderLayout.NORTH);

        JTextArea instructionsArea = new JTextArea();
        instructionsArea.setEditable(false);
        instructionsArea.setLineWrap(true);
        instructionsArea.setWrapStyleWord(true);
        instructionsArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        instructionsArea.setText(
                """
                        1. Right-click Equilinox in your Steam library.
                        2. Click on 'Properties...'.
                        3. In the 'General' tab, find the 'LAUNCH OPTIONS' text box.
                        4. Paste the following line into the text box:""");
        instructionsArea.setRows(5);
        instructionsArea.setColumns(0);
        instructionsArea.setBackground(panel.getBackground());

        String launchCommand = String.format(
                "java -cp \"%s" + File.pathSeparator + "lib" + File.separator + "*\" %s %%command%%",
                SILK_LOADER_FIXED_JAR_NAME,
                getMainClassFromJar(silkLoaderPath));
        JTextArea commandArea = new JTextArea(launchCommand);
        commandArea.setEditable(false);
        commandArea.setFont(new Font("Monospaced", Font.BOLD, 13));
        commandArea.setLineWrap(true);
        commandArea.setWrapStyleWord(true);
        commandArea.setToolTipText("Click to select, or use the button to copy.");
        commandArea.setRows(3);
        commandArea.setColumns(0);

        JScrollPane commandScrollPane = new JScrollPane(commandArea);
        commandScrollPane.setBorder(BorderFactory.createTitledBorder("Launch Option:"));

        JButton copyButton = new JButton("Copy Launch Option to Clipboard");
        copyButton.addActionListener(e -> {
            StringSelection stringSelection = new StringSelection(launchCommand);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSelection, null);
            JOptionPane.showMessageDialog(
                    panel, "Launch option copied to clipboard!", "Copied!", JOptionPane.INFORMATION_MESSAGE);
        });

        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        centerPanel.add(instructionsArea, BorderLayout.NORTH);
        centerPanel.add(commandScrollPane, BorderLayout.CENTER);

        panel.add(centerPanel, BorderLayout.CENTER);
        panel.add(copyButton, BorderLayout.SOUTH);

        Window windowAncestor = null;
        if (parentComponent != null) {
            windowAncestor = SwingUtilities.getWindowAncestor(parentComponent);
        }

        JOptionPane.showMessageDialog(windowAncestor, panel, title, JOptionPane.INFORMATION_MESSAGE);
    }

    @VisibleForTesting
    static void showUninstallInstructionsPopup(Component parentComponent) {
        String title = "Uninstallation Information";
        String message = "<html><body style='width: 350px;'>" + "The uninstallation process has finished.<br><br>"
                + "<b>Important:</b> If you previously set <b>Steam launch options</b> for Equilinox to use the mod loader, "
                + "you should now remove them to ensure the game launches normally.<br><br>"
                + "To do this:<br>"
                + "1. Right-click Equilinox in your Steam library.<br>"
                + "2. Click on 'Properties...'.<br>"
                + "3. In the 'General' tab, clear the 'LAUNCH OPTIONS' text box.</body></html>";

        Window windowAncestor = null;
        if (parentComponent != null) {
            windowAncestor = SwingUtilities.getWindowAncestor(parentComponent);
        }

        JOptionPane.showMessageDialog(windowAncestor, message, title, JOptionPane.INFORMATION_MESSAGE);
    }
}
