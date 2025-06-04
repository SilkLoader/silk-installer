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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class EquilinoxGameFinder {
    private static final List<String> COMMON_STEAM_DIRECTORIES;

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

    private EquilinoxGameFinder() {}

    public static boolean isValidGamePath(Path gamePath) {
        if (gamePath == null) return false;
        if (!Files.exists(gamePath) || !Files.isDirectory(gamePath)) return false;

        Path unlockList = gamePath.resolve("unlockList.dat");
        Path userConfigs;

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

    public static String tryFindGame() {
        Set<String> potentialLibraryRoots = new LinkedHashSet<>();
        String userHome = System.getProperty("user.home");
        String osName = System.getProperty("os.name").toLowerCase();

        Path scoopEquilinox =
                Paths.get(userHome, "scoop", "apps", "steam", "current", "steamapps", "common", "Equilinox");
        if (isValidGamePath(scoopEquilinox)) return scoopEquilinox.toString();

        String mainSteamInstallStr;
        if (osName.contains("win")) {
            mainSteamInstallStr = getSteamInstallPathFromRegistry();
            if (mainSteamInstallStr != null) {
                Path steamDir = Paths.get(mainSteamInstallStr);
                if (Files.isDirectory(steamDir)) {
                    potentialLibraryRoots.add(steamDir.toString());
                    potentialLibraryRoots.addAll(
                            parseLibraryFoldersVDF(steamDir.resolve("steamapps").resolve("libraryfolders.vdf")));
                }
            }
        } else if (osName.contains("mac")) {
            Path macSteam = Paths.get(userHome, "Library", "Application Support", "Steam");
            if (Files.isDirectory(macSteam)) {
                potentialLibraryRoots.add(macSteam.toString());
                potentialLibraryRoots.addAll(
                        parseLibraryFoldersVDF(macSteam.resolve("steamapps").resolve("libraryfolders.vdf")));
            }
        } else if (osName.contains("nix") || osName.contains("nux")) {
            Path steam1 = Paths.get(userHome, ".steam", "steam");
            if (Files.isDirectory(steam1)) {
                potentialLibraryRoots.add(steam1.toString());
                potentialLibraryRoots.addAll(
                        parseLibraryFoldersVDF(steam1.resolve("steamapps").resolve("libraryfolders.vdf")));
            }
            Path steam2 = Paths.get(userHome, ".local", "share", "Steam");
            if (Files.isDirectory(steam2)) {
                potentialLibraryRoots.add(steam2.toString());
                potentialLibraryRoots.addAll(
                        parseLibraryFoldersVDF(steam2.resolve("steamapps").resolve("libraryfolders.vdf")));
            }
        }
        COMMON_STEAM_DIRECTORIES.forEach(commonPathStr -> {
            Path commonDir = Paths.get(commonPathStr);
            if (Files.isDirectory(commonDir)) {
                potentialLibraryRoots.add(commonPathStr);
                potentialLibraryRoots.addAll(
                        parseLibraryFoldersVDF(commonDir.resolve("steamapps").resolve("libraryfolders.vdf")));
            }
        });
        for (String libRootStr : new ArrayList<>(potentialLibraryRoots)) {
            Path gamePath = Paths.get(libRootStr, "steamapps", "common", "Equilinox");
            if (isValidGamePath(gamePath)) return gamePath.toString();
        }
        return null;
    }

    private static List<String> parseLibraryFoldersVDF(Path vdfPath) {
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

    private static String getSteamInstallPathFromRegistry() {
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
}
