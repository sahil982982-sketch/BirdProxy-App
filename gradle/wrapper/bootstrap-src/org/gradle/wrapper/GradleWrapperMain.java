package org.gradle.wrapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Small transparent Gradle bootstrap used because the uploaded project did not
 * contain the standard gradle-wrapper.jar. It reads gradle-wrapper.properties,
 * downloads/extracts the configured distribution and launches Gradle.
 */
public final class GradleWrapperMain {
    private static final int BUFFER_SIZE = 64 * 1024;

    private GradleWrapperMain() {}

    public static void main(String[] args) throws Exception {
        File wrapperJar = new File(GradleWrapperMain.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        File wrapperDir = wrapperJar.getParentFile();
        File projectDir = wrapperDir.getParentFile().getParentFile();
        File propertiesFile = new File(wrapperDir, "gradle-wrapper.properties");

        Properties properties = new Properties();
        try (InputStream input = new FileInputStream(propertiesFile)) {
            properties.load(input);
        }

        String distributionUrl = required(properties, "distributionUrl");
        URI distributionUri = propertiesFile.toURI().resolve(distributionUrl.replace("\\:", ":"));
        String distributionName = new File(distributionUri.getPath()).getName();
        if (!distributionName.endsWith(".zip")) {
            throw new IOException("Gradle distribution must be a ZIP: " + distributionUri);
        }

        File gradleUserHome = resolveGradleUserHome();
        String distributionPath = properties.getProperty("distributionPath", "wrapper/dists");
        String distBaseName = distributionName.substring(0, distributionName.length() - 4);
        String urlHash = hex(sha256(distributionUri.toString().getBytes("UTF-8"))).substring(0, 24);
        File installRoot = new File(new File(new File(gradleUserHome, distributionPath), distBaseName), urlHash);
        File marker = new File(installRoot, ".installed");
        File lockFile = new File(installRoot, distributionName + ".lck");

        if (!installRoot.exists() && !installRoot.mkdirs()) {
            throw new IOException("Could not create " + installRoot);
        }

        File gradleHome;
        try (FileChannel channel = new FileOutputStream(lockFile, true).getChannel();
             FileLock ignored = channel.lock()) {
            gradleHome = findGradleHome(installRoot);
            if (gradleHome == null || !marker.isFile()) {
                installDistribution(distributionUri.toURL(), properties, installRoot, distributionName);
                gradleHome = findGradleHome(installRoot);
                if (gradleHome == null) {
                    throw new IOException("Downloaded archive does not contain a Gradle installation");
                }
                if (!marker.exists() && !marker.createNewFile()) {
                    throw new IOException("Could not create installation marker");
                }
            }
        }

        int exitCode = launchGradle(gradleHome, projectDir, args);
        System.exit(exitCode);
    }

    private static void installDistribution(URL url, Properties properties,
                                            File installRoot, String distributionName) throws Exception {
        File zip = new File(installRoot, distributionName);
        File part = new File(installRoot, distributionName + ".part");
        deleteExtractedDirectories(installRoot, distributionName);

        System.out.println("Downloading " + url);
        download(url, part);

        String expected = properties.getProperty("distributionSha256Sum", "").trim();
        if (!expected.isEmpty()) {
            String actual = hex(sha256(part));
            if (!expected.equalsIgnoreCase(actual)) {
                part.delete();
                throw new IOException("Gradle distribution checksum mismatch. Expected "
                        + expected + " but got " + actual);
            }
        }

        Files.move(part.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING);
        unzip(zip, installRoot);
        if (!zip.delete()) zip.deleteOnExit();
    }

    private static void download(URL initialUrl, File destination) throws IOException {
        URL current = initialUrl;
        for (int redirects = 0; redirects < 10; redirects++) {
            URLConnection rawConnection = current.openConnection();
            rawConnection.setConnectTimeout(15000);
            rawConnection.setReadTimeout(30000);
            rawConnection.setRequestProperty("User-Agent", "BirdProxy-Gradle-Bootstrap/1.0");

            if (!(rawConnection instanceof HttpURLConnection)) {
                try (InputStream input = new BufferedInputStream(rawConnection.getInputStream());
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                    copy(input, output);
                }
                return;
            }

            HttpURLConnection connection = (HttpURLConnection) rawConnection;
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Redirect without Location header");
                current = new URL(current, location);
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IOException("Download failed with HTTP " + status + " from " + current);
            }

            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                copy(input, output);
            } finally {
                connection.disconnect();
            }
            return;
        }
        throw new IOException("Too many redirects while downloading Gradle");
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
    }

    private static void unzip(File zipFile, File destination) throws IOException {
        Path root = destination.toPath().toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = zip.getNextEntry()) != null) {
                Path output = root.resolve(entry.getName()).normalize();
                if (!output.startsWith(root)) {
                    throw new IOException("Unsafe ZIP entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(output))) {
                        int read;
                        while ((read = zip.read(buffer)) >= 0) out.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static int launchGradle(File gradleHome, File projectDir, String[] args)
            throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        File executable = new File(new File(gradleHome, "bin"), windows ? "gradle.bat" : "gradle");
        if (!windows) executable.setExecutable(true);

        List<String> command = new ArrayList<>();
        if (windows) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
        }
        command.add(executable.getAbsolutePath());
        for (String arg : args) command.add(arg);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(projectDir);
        builder.inheritIO();
        return builder.start().waitFor();
    }

    private static File findGradleHome(File installRoot) {
        File[] children = installRoot.listFiles(File::isDirectory);
        if (children == null) return null;
        for (File child : children) {
            if (new File(new File(child, "bin"), isWindows() ? "gradle.bat" : "gradle").isFile()) {
                return child;
            }
        }
        return null;
    }

    private static void deleteExtractedDirectories(File installRoot, String distributionName)
            throws IOException {
        File[] children = installRoot.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) deleteRecursively(child.toPath());
            else if (!child.getName().endsWith(".lck") && !child.getName().equals(distributionName + ".part")) {
                child.delete();
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException e) { throw new RuntimeException(e); }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            throw e;
        }
    }

    private static File resolveGradleUserHome() {
        String configured = System.getenv("GRADLE_USER_HOME");
        if (configured != null && !configured.trim().isEmpty()) return new File(configured);
        return new File(System.getProperty("user.home"), ".gradle");
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) throw new IOException("Missing " + key);
        return value.trim();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xFF));
        return result.toString();
    }
}
