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
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class InstallerWindow extends JFrame {
    private static final List<String> COMMON_STEAM_DIRECTORIES;
    private static final FabricVersionItem FABRIC_LOADING_ITEM = new FabricVersionItem("Loading...", "");
    private static final FabricVersionItem FABRIC_ERROR_ITEM = new FabricVersionItem("Error", "");
    private static final FabricVersionItem FABRIC_NO_VERSIONS_ITEM = new FabricVersionItem("No versions found.", "");

    static {
        List<String> paths = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");
        boolean isWindows = os.contains("win");
        boolean isMac = os.contains("mac");
        boolean isUnix = os.contains("nix") || os.contains("nux") || os.contains("aix");

        if (isWindows) {
            paths.add("C:\\Program Files (x86)\\Steam");
            paths.add("C:\\Program Files\\Steam");
            for (char drive = 'D'; drive <= 'Z'; drive++) {
                paths.add(drive + ":\\SteamLibrary");
                paths.add(drive + ":\\SteamGames");
                paths.add(drive + ":\\Steam");
            }
            paths.add("C:\\ProgramData\\chocolatey\\lib\\steam-client");
            paths.add("C:\\ProgramData\\scoop\\apps\\steam");
        } else if (isMac) {
            paths.add(Paths.get(userHome, "Library", "Application Support", "Steam")
                    .toString());
        } else if (isUnix) {
            paths.add(Paths.get(userHome, ".steam", "steam").toString());
            paths.add(Paths.get(userHome, ".local", "share", "Steam").toString());
        }

        if (isMac || isUnix) {
            paths.add(Paths.get(userHome, "SteamLibrary").toString());
        }

        COMMON_STEAM_DIRECTORIES = List.copyOf(paths);
    }

    private final JComboBox<FabricVersionItem> fabricVersionDropdown;
    private final JComboBox<String> silkVersionDropdown;
    private final JTextField pathTextField;
    private final JButton browseButton;
    private final JLabel statusLabel;
    private final JButton installButton;
    private final JButton uninstallButton;
    private volatile boolean fabricTaskComplete = false;
    private volatile boolean silkTaskComplete = false;
    private volatile boolean fabricSucceeded = false;
    private volatile boolean silkSucceeded = false;
    private volatile boolean pathSearchComplete = false;

    public InstallerWindow() {
        // I'm aware that this doesn't work in a devenv
        String version = InstallerWindow.class.getPackage().getImplementationVersion();
        if (version == null) {
            version = "";
        } else {
            version = " " + version;
        }

        setTitle("Silk Loader Installer" + version);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setResizable(false);
        String initialStatusMessage = "Initializing installer...";

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JPanel fabricRowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        JLabel fabricLabel = new JLabel("Fabric Loader Version:");
        fabricVersionDropdown = new JComboBox<>();
        fabricVersionDropdown.addItem(FABRIC_LOADING_ITEM);
        fabricVersionDropdown.setEnabled(false);
        fabricVersionDropdown.setPreferredSize(new Dimension(200, fabricVersionDropdown.getPreferredSize().height));
        fabricRowPanel.add(fabricLabel);
        fabricRowPanel.add(fabricVersionDropdown);
        mainPanel.add(fabricRowPanel);

        JPanel silkRowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        JLabel silkLabel = new JLabel("Silk Loader Version:    ");
        silkVersionDropdown = new JComboBox<>();
        silkVersionDropdown.addItem("Loading...");
        silkVersionDropdown.setEnabled(false);
        silkVersionDropdown.setPreferredSize(new Dimension(200, silkVersionDropdown.getPreferredSize().height));
        silkRowPanel.add(silkLabel);
        silkRowPanel.add(silkVersionDropdown);
        mainPanel.add(silkRowPanel);

        JPanel pathRowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        JLabel pathLabel = new JLabel("Equilinox Location:   ");
        pathTextField = new JTextField();
        pathTextField.setPreferredSize(new Dimension(280, pathTextField.getPreferredSize().height));
        pathTextField.setEnabled(false);
        browseButton = new JButton("...");
        browseButton.setToolTipText("Browse for Equilinox installation directory");
        browseButton.setPreferredSize(new Dimension(45, pathTextField.getPreferredSize().height));
        browseButton.setEnabled(false);

        pathRowPanel.add(pathLabel);
        pathRowPanel.add(pathTextField);
        pathRowPanel.add(browseButton);
        mainPanel.add(pathRowPanel);

        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        JPanel statusRowPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
        statusLabel = new JLabel(initialStatusMessage);
        statusRowPanel.add(statusLabel);
        mainPanel.add(statusRowPanel);

        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        installButton = new JButton("Install");
        installButton.setEnabled(false);
        installButton.setPreferredSize(new Dimension(100, 30));
        buttonPanel.add(installButton);

        uninstallButton = new JButton("Uninstall");
        uninstallButton.setEnabled(false);
        uninstallButton.setPreferredSize(new Dimension(100, 30));
        buttonPanel.add(uninstallButton);

        mainPanel.add(buttonPanel);

        add(mainPanel);
        pack();
        setMinimumSize(getSize());

        browseButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Select Equilinox Installation Directory");
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fileChooser.setAcceptAllFileFilterUsed(false);

            String currentPathText = pathTextField.getText();
            if (currentPathText != null && !currentPathText.isEmpty()) {
                Path currentDir = Paths.get(currentPathText);
                if (Files.exists(currentDir) && Files.isDirectory(currentDir)) {
                    fileChooser.setCurrentDirectory(currentDir.toFile());
                }
            }

            int result = fileChooser.showOpenDialog(InstallerWindow.this);

            if (result == JFileChooser.APPROVE_OPTION) {
                File selectedDirFile = fileChooser.getSelectedFile();
                if (selectedDirFile != null && selectedDirFile.isDirectory()) {
                    pathTextField.setText(selectedDirFile.getAbsolutePath());
                    updateOverallStatus();
                }
            }
        });

        pathTextField.getDocument().addDocumentListener(new DocumentListener() {
            public void changedUpdate(DocumentEvent e) {
                updateOverallStatus();
            }

            public void removeUpdate(DocumentEvent e) {
                updateOverallStatus();
            }

            public void insertUpdate(DocumentEvent e) {
                updateOverallStatus();
            }
        });

        installButton.addActionListener(e -> {
            FabricVersionItem selectedFabricItem = (FabricVersionItem) fabricVersionDropdown.getSelectedItem();
            String selectedSilkVersion = (String) silkVersionDropdown.getSelectedItem();
            String gamePathString = pathTextField.getText();

            String fabricMavenCoordinates = null;
            if (selectedFabricItem != null && selectedFabricItem.isSelectable()) {
                fabricMavenCoordinates = selectedFabricItem.mavenCoordinates();
            }

            boolean silkVersionIsValid = selectedSilkVersion != null
                    && !selectedSilkVersion.isEmpty()
                    && !selectedSilkVersion.equals("Loading...")
                    && !selectedSilkVersion.equals("Error")
                    && !selectedSilkVersion.equals("No releases found.");

            Path gamePath = Paths.get(gamePathString.trim());
            if (fabricMavenCoordinates != null
                    && silkVersionIsValid
                    && !gamePathString.isEmpty()
                    && isValidGamePath(gamePath)) {
                final String finalFabricMavenCoords = fabricMavenCoordinates;

                installButton.setEnabled(false);
                statusLabel.setText("Installing...");

                SwingWorker<Void, String> installerWorker = new SwingWorker<>() {
                    @Override
                    protected Void doInBackground() {
                        publish("Starting installation...");
                        Main.install(finalFabricMavenCoords, selectedSilkVersion, gamePath, statusLabel);
                        return null;
                    }

                    @Override
                    protected void process(List<String> chunks) {
                        chunks.forEach(statusLabel::setText);
                    }

                    @Override
                    protected void done() {
                        try {
                            get();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            statusLabel.setText("Installation interrupted.");
                            updateOverallStatus();
                        } catch (ExecutionException ex) {
                            Throwable cause = ex.getCause();
                            statusLabel.setText("Installation failed: " + cause.getMessage());
                            System.err.println("Installation failed:");
                            cause.printStackTrace(System.err);
                            updateOverallStatus();
                        }
                    }
                };
                installerWorker.execute();
            } else {
                updateOverallStatus();
            }
        });

        uninstallButton.addActionListener(e -> {
            String gamePathString = pathTextField.getText();
            if (gamePathString == null || gamePathString.trim().isEmpty()) {
                statusLabel.setText("Game location cannot be empty for uninstall.");
                return;
            }
            Path gamePath = Paths.get(gamePathString.trim());

            if (isValidGamePath(gamePath)) {
                installButton.setEnabled(false);
                uninstallButton.setEnabled(false);
                statusLabel.setText("Uninstalling...");

                SwingWorker<Void, String> uninstallerWorker = new SwingWorker<>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        publish("Starting uninstallation...");
                        Main.uninstall(gamePath, statusLabel, false);
                        return null;
                    }

                    @Override
                    protected void process(List<String> chunks) {
                        chunks.forEach(statusLabel::setText);
                    }

                    @Override
                    protected void done() {
                        try {
                            get();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            statusLabel.setText("Uninstallation interrupted.");
                            System.err.println("Uninstallation interrupted.");
                        } catch (ExecutionException ex) {
                            Throwable cause = ex.getCause();
                            statusLabel.setText("Uninstallation failed: " + cause.getMessage());
                            System.err.println("Uninstallation failed:");
                            cause.printStackTrace(System.err);
                        } finally {
                            updateOverallStatus();
                        }
                    }
                };
                uninstallerWorker.execute();
            } else {
                statusLabel.setText("Invalid or non-existent game location for uninstall.");
                updateOverallStatus();
            }
        });

        loadFabricVersions();
        loadSilkLoaderVersions();
        searchForEquilinoxLocation();
    }

    private void loadFabricVersions() {
        SwingWorker<List<FabricVersionItem>, Void> fabricWorker = new SwingWorker<>() {
            @Override
            protected List<FabricVersionItem> doInBackground() throws Exception {
                List<FabricVersionItem> versions = new ArrayList<>();
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(Main.FABRIC_LOADER_VERSIONS_URL))
                        .header("Accept", "application/json")
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonArray jsonArray = Json.parse(response.body()).asArray();
                    for (int i = 0; i < jsonArray.size(); i++) {
                        JsonObject versionObject = jsonArray.get(i).asObject();
                        if (versionObject.contains("version") && versionObject.contains("maven")) {
                            versions.add(new FabricVersionItem(
                                    versionObject.get("version").asString(),
                                    versionObject.get("maven").asString()));
                        }
                    }
                } else {
                    throw new Exception("Fabric: Failed to fetch. Status: " + response.statusCode());
                }
                return versions;
            }

            @Override
            protected void done() {
                fabricVersionDropdown.removeAllItems();
                try {
                    List<FabricVersionItem> versions = get();
                    if (versions.isEmpty()) {
                        fabricVersionDropdown.addItem(FABRIC_NO_VERSIONS_ITEM);
                        fabricSucceeded = false;
                    } else {
                        versions.forEach(fabricVersionDropdown::addItem);
                        if (fabricVersionDropdown.getItemCount() > 0) {
                            fabricVersionDropdown.setSelectedIndex(0);
                        }
                        fabricSucceeded = true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fabricSucceeded = false;
                    handleFabricLoadingError("Fabric: Loading interrupted.");
                } catch (ExecutionException e) {
                    fabricSucceeded = false;
                    handleFabricLoadingError("Fabric: " + e.getCause().getMessage());
                } finally {
                    fabricVersionDropdown.setEnabled(fabricSucceeded
                            && fabricVersionDropdown.getItemCount() > 0
                            && ((FabricVersionItem) fabricVersionDropdown.getSelectedItem()).isSelectable());
                    fabricTaskComplete = true;
                    updateOverallStatus();
                }
            }
        };
        fabricWorker.execute();
    }

    private void loadSilkLoaderVersions() {
        SwingWorker<List<String>, Void> silkWorker = new SwingWorker<>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                List<String> releaseTags = new ArrayList<>();
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(Main.SILK_LOADER_RELEASES_URL))
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonArray jsonArray = Json.parse(response.body()).asArray();
                    for (int i = 0; i < jsonArray.size(); i++) {
                        JsonObject releaseObject = jsonArray.get(i).asObject();
                        if (releaseObject.contains("tag_name")) {
                            releaseTags.add(releaseObject.get("tag_name").asString());
                        }
                    }
                } else {
                    throw new Exception("Silk: Failed to fetch. Status: " + response.statusCode());
                }
                return releaseTags;
            }

            @Override
            protected void done() {
                silkVersionDropdown.removeAllItems();
                try {
                    List<String> versions = get();
                    if (versions.isEmpty()) {
                        silkVersionDropdown.addItem("No releases found.");
                        silkSucceeded = false;
                    } else {
                        versions.forEach(silkVersionDropdown::addItem);
                        if (silkVersionDropdown.getItemCount() > 0) {
                            silkVersionDropdown.setSelectedIndex(0);
                        }
                        silkSucceeded = true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    silkSucceeded = false;
                    handleSilkLoadingError("Silk: Loading interrupted.");
                } catch (ExecutionException e) {
                    silkSucceeded = false;
                    handleSilkLoadingError("Silk: " + e.getCause().getMessage());
                } finally {
                    silkVersionDropdown.setEnabled(silkSucceeded
                            && silkVersionDropdown.getItemCount() > 0
                            && !((String) Objects.requireNonNull(silkVersionDropdown.getSelectedItem()))
                                    .startsWith("No releases")
                            && !Objects.requireNonNull(silkVersionDropdown.getSelectedItem())
                                    .equals("Error"));
                    silkTaskComplete = true;
                    updateOverallStatus();
                }
            }
        };
        silkWorker.execute();
    }

    private void handleFabricLoadingError(String errorMessage) {
        fabricVersionDropdown.removeAllItems();
        fabricVersionDropdown.addItem(FABRIC_ERROR_ITEM);
        fabricVersionDropdown.setEnabled(false);
        fabricSucceeded = false;
        System.err.println(errorMessage);
    }

    private void handleSilkLoadingError(String errorMessage) {
        silkVersionDropdown.removeAllItems();
        silkVersionDropdown.addItem("Error");
        silkVersionDropdown.setEnabled(false);
        silkSucceeded = false;
        System.err.println(errorMessage);
    }

    private boolean isValidGamePath(Path gamePath) {
        if (gamePath == null) return false;
        if (!Files.exists(gamePath) || !Files.isDirectory(gamePath)) return false;

        Path unlockList = gamePath.resolve("unlockList.dat");
        Path userConfigs = null;

        try (Stream<Path> stream = Files.list(gamePath)) {
            userConfigs = stream.filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().startsWith("Equilinox")
                            && p.getFileName().toString().endsWith("UserConfigs.dat"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            System.err.println("Error listing files in " + gamePath + ": " + e.getMessage());
            return false;
        }
        return Files.exists(unlockList)
                && Files.isRegularFile(unlockList)
                && userConfigs != null
                && Files.exists(userConfigs)
                && Files.isRegularFile(userConfigs);
    }

    private String getSteamInstallPathFromRegistry() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return null;
        try {
            Process process = Runtime.getRuntime().exec("reg query \"HKCU\\Software\\Valve\\Steam\" /v SteamPath");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                Pattern pattern = Pattern.compile("^\\s+SteamPath\\s+REG_SZ\\s+(.*)$");
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.matches()) {
                        String path = matcher.group(1);
                        if (path != null && !path.trim().isEmpty()) return path.trim();
                    }
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.err.println("Could not read Steam path from registry: " + e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return null;
    }

    private List<String> parseLibraryFoldersVDF(Path vdfPath) {
        List<String> paths = new ArrayList<>();
        if (vdfPath == null || !Files.exists(vdfPath) || !Files.isRegularFile(vdfPath)) return paths;
        Pattern pathPattern =
                Pattern.compile("^\\s*\"(?:path|[0-9]+)\"\\s+\"([^\"]+)\"\\s*$", Pattern.CASE_INSENSITIVE);
        try (BufferedReader reader = Files.newBufferedReader(vdfPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = pathPattern.matcher(line.trim());
                if (matcher.find()) {
                    String pathString = matcher.group(1).replace("\\\\", "\\");
                    Path potentialPath = Paths.get(pathString);
                    if (Files.isDirectory(potentialPath)) paths.add(potentialPath.toString());
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading VDF file '" + vdfPath + "': " + e.getMessage());
        }
        return paths;
    }

    private void searchForEquilinoxLocation() {
        statusLabel.setText("Searching for Equilinox...");
        pathTextField.setEnabled(false);
        browseButton.setEnabled(false);
        SwingWorker<String, Void> pathFinderWorker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                Set<String> potentialLibraryRoots = new LinkedHashSet<>();
                String userHome = System.getProperty("user.home");
                String osName = System.getProperty("os.name").toLowerCase();

                Path scoopEquilinox =
                        Paths.get(userHome, "scoop", "apps", "steam", "current", "steamapps", "common", "Equilinox");
                if (isValidGamePath(scoopEquilinox)) return scoopEquilinox.toString();

                String mainSteamInstallStr = null;
                if (osName.contains("win")) {
                    mainSteamInstallStr = getSteamInstallPathFromRegistry();
                    if (mainSteamInstallStr != null) {
                        Path steamDir = Paths.get(mainSteamInstallStr);
                        if (Files.isDirectory(steamDir)) {
                            potentialLibraryRoots.add(steamDir.toString());
                            potentialLibraryRoots.addAll(parseLibraryFoldersVDF(
                                    steamDir.resolve("steamapps").resolve("libraryfolders.vdf")));
                        }
                    }
                } else if (osName.contains("mac")) {
                    Path macSteam = Paths.get(userHome, "Library", "Application Support", "Steam");
                    if (Files.isDirectory(macSteam)) {
                        potentialLibraryRoots.add(macSteam.toString());
                        potentialLibraryRoots.addAll(parseLibraryFoldersVDF(
                                macSteam.resolve("steamapps").resolve("libraryfolders.vdf")));
                    }
                } else if (osName.contains("nix") || osName.contains("nux")) {
                    Path steam1 = Paths.get(userHome, ".steam", "steam");
                    if (Files.isDirectory(steam1)) {
                        potentialLibraryRoots.add(steam1.toString());
                        potentialLibraryRoots.addAll(parseLibraryFoldersVDF(
                                steam1.resolve("steamapps").resolve("libraryfolders.vdf")));
                    }
                    Path steam2 = Paths.get(userHome, ".local", "share", "Steam");
                    if (Files.isDirectory(steam2)) {
                        potentialLibraryRoots.add(steam2.toString());
                        potentialLibraryRoots.addAll(parseLibraryFoldersVDF(
                                steam2.resolve("steamapps").resolve("libraryfolders.vdf")));
                    }
                }
                COMMON_STEAM_DIRECTORIES.forEach(commonPathStr -> {
                    Path commonDir = Paths.get(commonPathStr);
                    if (Files.isDirectory(commonDir)) {
                        potentialLibraryRoots.add(commonPathStr);
                        potentialLibraryRoots.addAll(parseLibraryFoldersVDF(
                                commonDir.resolve("steamapps").resolve("libraryfolders.vdf")));
                    }
                });
                for (String libRootStr : new ArrayList<>(potentialLibraryRoots)) {
                    Path gamePath = Paths.get(libRootStr, "steamapps", "common", "Equilinox");
                    if (isValidGamePath(gamePath)) return gamePath.toString();
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    String foundPath = get();
                    if (foundPath != null && !foundPath.isEmpty()) pathTextField.setText(foundPath);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    System.err.println(
                            "Error during path search: " + e.getCause().getMessage());
                    e.getCause().printStackTrace(System.out);
                } finally {
                    pathTextField.setEnabled(true);
                    browseButton.setEnabled(true);
                    pathSearchComplete = true;
                    updateOverallStatus();
                }
            }
        };
        pathFinderWorker.execute();
    }

    private synchronized void updateOverallStatus() {
        String currentPathText = pathTextField.getText();
        boolean pathIsValid = false;
        if (currentPathText != null && !currentPathText.trim().isEmpty()) {
            try {
                pathIsValid = isValidGamePath(Paths.get(currentPathText.trim()));
            } catch (java.nio.file.InvalidPathException ipe) {
                /* pathIsValid remains false */
            }
        }

        FabricVersionItem currentFabricItem = (FabricVersionItem) fabricVersionDropdown.getSelectedItem();
        boolean fabricHasSelectableVersion =
                fabricSucceeded && currentFabricItem != null && currentFabricItem.isSelectable();

        String currentSilkItem = (String) silkVersionDropdown.getSelectedItem();
        boolean silkHasSelectableVersion = silkSucceeded
                && currentSilkItem != null
                && !currentSilkItem.equals("Loading...")
                && !currentSilkItem.equals("Error")
                && !currentSilkItem.equals("No releases found.");

        installButton.setEnabled(fabricTaskComplete
                && silkTaskComplete
                && pathSearchComplete
                && fabricHasSelectableVersion
                && silkHasSelectableVersion
                && pathIsValid);

        uninstallButton.setEnabled(pathSearchComplete && pathIsValid);

        if (!pathSearchComplete && !(fabricTaskComplete && silkTaskComplete)) {
            statusLabel.setText("Initializing: Loading versions & searching game...");
        } else if (!pathSearchComplete) {
            statusLabel.setText("Versions loaded. Searching for Equilinox location...");
        } else if (!fabricTaskComplete || !silkTaskComplete) {
            String loadingStatus = "";
            if (!fabricTaskComplete && !silkTaskComplete) loadingStatus = "Loading Fabric & Silk...";
            else if (!fabricTaskComplete) loadingStatus = "Loading Fabric...";
            else loadingStatus = "Loading Silk...";

            if (pathTextField.getText().trim().isEmpty()) statusLabel.setText(loadingStatus + " Select game location.");
            else if (!pathIsValid) statusLabel.setText(loadingStatus + " Invalid game location.");
            else statusLabel.setText(loadingStatus + " Game location set.");
        } else {
            if (installButton.isEnabled()) {
                statusLabel.setText("Ready to install.");
            } else if (uninstallButton.isEnabled() && !pathIsValid) {
                statusLabel.setText("Game files may be incomplete. Verify location or reinstall game.");
            } else if (uninstallButton.isEnabled()) {
                statusLabel.setText("Game location valid. Versions might be loading or erroneous for install.");
            } else if (fabricHasSelectableVersion && silkHasSelectableVersion) {
                if (pathIsValid) statusLabel.setText("Ready to install.");
                else if (!currentPathText.trim().isEmpty())
                    statusLabel.setText("Invalid game location. Please verify.");
                else statusLabel.setText("Please select or verify the game location.");
            } else {
                StringBuilder sb = new StringBuilder();
                if (!fabricSucceeded) sb.append("Fabric: Error. ");
                else if (!fabricHasSelectableVersion && fabricTaskComplete) sb.append("Fabric: No/Invalid versions. ");
                else if (fabricTaskComplete) sb.append("Fabric: OK. ");
                else sb.append("Fabric: Loading... ");

                if (!silkSucceeded) sb.append("Silk: Error. ");
                else if (!silkHasSelectableVersion && silkTaskComplete) sb.append("Silk: No/Invalid versions. ");
                else if (silkTaskComplete) sb.append("Silk: OK. ");
                else sb.append("Silk: Loading... ");

                if (pathIsValid) sb.append("Game location OK.");
                else if (!currentPathText.trim().isEmpty() && pathSearchComplete) sb.append("Invalid game location.");
                else if (pathSearchComplete) sb.append("Set game location.");
                else sb.append("Searching game...");
                statusLabel.setText(sb.toString().trim());
            }
        }
    }

    private record FabricVersionItem(String displayVersion, String mavenCoordinates) {
        private FabricVersionItem(String displayVersion, String mavenCoordinates) {
            this.displayVersion = Objects.requireNonNull(displayVersion);
            this.mavenCoordinates = Objects.requireNonNull(mavenCoordinates);
        }

        @Override
        public String toString() {
            return displayVersion;
        }

        public boolean isSelectable() {
            return !mavenCoordinates.isEmpty()
                    && !displayVersion.equals("Loading...")
                    && !displayVersion.equals("Error")
                    && !displayVersion.equals("No versions found.");
        }
    }
}
