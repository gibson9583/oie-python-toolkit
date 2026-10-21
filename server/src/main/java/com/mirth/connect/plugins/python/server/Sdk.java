/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.mirth.connect.plugins.python.CanonicalJson;

/**
 * The plugin-owned Python SDK. Sources ship inside the server jar. The runtime
 * extracts them for compile-only validation and builds a reproducible wheel
 * (stored entries, fixed timestamps) that every environment installs with a
 * pinned hash. Users cannot replace it: requirements that name it are
 * rejected.
 */
public final class Sdk {

    public static final String VERSION = "1.0.0";
    public static final int PROTOCOL_VERSION = 1;
    public static final String DISTRIBUTION = "oie-python";
    static final String[] MODULES = { "__init__.py", "_types.py", "_result_types.py", "_codec.py", "_maps.py", "_context.py", "_script.py", "worker.py", "validate.py" };
    private static final java.time.LocalDateTime FIXED_TIME = java.time.LocalDateTime.of(1980, 1, 2, 0, 0);

    private final Map<String, byte[]> sources;
    private final byte[] wheel;
    private final String wheelSha256;
    private final Path root;
    private final Path sourceDir;
    private final Path wheelFile;

    public Sdk(Path cacheDir) throws IOException {
        sources = loadSources();
        wheel = buildWheel(sources);
        wheelSha256 = CanonicalJson.sha256Hex(wheel);
        root = cacheDir.resolve("sdk").resolve(VERSION + "-" + wheelSha256.substring(0, 16));
        sourceDir = root.resolve("src");
        wheelFile = root.resolve(wheelName());
        install();
    }

    public String wheelName() {
        return "oie_python-" + VERSION + "-py3-none-any.whl";
    }

    public String wheelSha256() {
        return wheelSha256;
    }

    public Path wheelFile() {
        return wheelFile;
    }

    /** Verified bundled package source for compile-only and contained worker bootstrap. */
    public Path sourceDir() {
        return sourceDir;
    }

    /** One-line bootstrap that runs a verified SDK module under -I -S. */
    public String bootstrap(String module) {
        return "import sys; sys.path.insert(0, " + pythonString(sourceDir.toString()) + "); import " + module + " as m; m.main()";
    }

    private static String pythonString(String s) {
        StringBuilder out = new StringBuilder("'");
        for (char c : s.toCharArray()) {
            if (c == '\\' || c == '\'') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append('\'').toString();
    }

    private void install() throws IOException {
        Path pkg = sourceDir.resolve("oie_python");
        Files.createDirectories(pkg);
        for (Map.Entry<String, byte[]> entry : sources.entrySet()) {
            writeIfDifferent(pkg.resolve(entry.getKey()), entry.getValue());
        }
        writeIfDifferent(wheelFile, wheel);
    }

    private static void writeIfDifferent(Path target, byte[] content) throws IOException {
        if (Files.exists(target) && java.util.Arrays.equals(Files.readAllBytes(target), content)) {
            return;
        }
        Path tmp = Files.createTempFile(target.getParent(), ".sdk", ".tmp");
        Files.write(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static Map<String, byte[]> loadSources() throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String module : MODULES) {
            try (InputStream in = Sdk.class.getResourceAsStream("/python-sdk/oie_python/" + module)) {
                if (in == null) {
                    throw new IOException("The plugin jar is missing the Python SDK module " + module);
                }
                out.put(module, in.readAllBytes());
            }
        }
        return out;
    }

    static byte[] buildWheel(Map<String, byte[]> sources) throws IOException {
        String distInfo = "oie_python-" + VERSION + ".dist-info/";
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : sources.entrySet()) {
            files.put("oie_python/" + entry.getKey(), entry.getValue());
        }
        files.put(distInfo + "METADATA", ("Metadata-Version: 2.1\nName: " + DISTRIBUTION + "\nVersion: " + VERSION
                + "\nSummary: OIE Python SDK and worker (managed by the Python Runtime extension)\nLicense: MPL-2.0\nRequires-Python: >=3.11\n")
                        .getBytes(StandardCharsets.UTF_8));
        files.put(distInfo + "WHEEL", "Wheel-Version: 1.0\nGenerator: oie-python-toolkit\nRoot-Is-Purelib: true\nTag: py3-none-any\n"
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder record = new StringBuilder();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            record.append(entry.getKey()).append(",sha256=").append(urlsafeSha256(entry.getValue())).append(',')
                    .append(entry.getValue().length).append('\n');
        }
        record.append(distInfo).append("RECORD,,\n");
        files.put(distInfo + "RECORD", record.toString().getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                ZipEntry ze = new ZipEntry(entry.getKey());
                CRC32 crc = new CRC32();
                crc.update(entry.getValue());
                ze.setMethod(ZipEntry.STORED);
                ze.setSize(entry.getValue().length);
                ze.setCompressedSize(entry.getValue().length);
                ze.setCrc(crc.getValue());
                ze.setTimeLocal(FIXED_TIME);
                zip.putNextEntry(ze);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String urlsafeSha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
