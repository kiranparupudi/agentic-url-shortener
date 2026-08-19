package com.agentic.orchestrator.agents;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Copies a named template set from classpath resources (orchestrator/src/main/resources/templates/) onto disk. */
final class TemplateWriter {

    private TemplateWriter() {
    }

    /** Writes every .java file under {@code templates/<templateSet>/} to {@code targetDir}. */
    static List<String> writeAll(String templateSet, Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            List<String> names = listResourceFiles(templateSet);
            List<String> written = new ArrayList<>();
            for (String name : names) {
                String resourcePath = "templates/" + templateSet + "/" + name;
                try (InputStream in = TemplateWriter.class.getClassLoader().getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        throw new IllegalStateException("template resource not found: " + resourcePath);
                    }
                    Files.write(targetDir.resolve(name), in.readAllBytes());
                    written.add(name);
                }
            }
            return written;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Same as {@link #writeAll}, but calls back with each file's old content before overwriting it. */
    static List<String> writeAllTracked(String templateSet, Path targetDir, BiConsumer<String, String> beforeOverwrite) {
        try {
            Files.createDirectories(targetDir);
            List<String> names = listResourceFiles(templateSet);
            List<String> written = new ArrayList<>();
            for (String name : names) {
                String resourcePath = "templates/" + templateSet + "/" + name;
                Path dest = targetDir.resolve(name);
                String previous = Files.exists(dest) ? Files.readString(dest) : null;
                try (InputStream in = TemplateWriter.class.getClassLoader().getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        throw new IllegalStateException("template resource not found: " + resourcePath);
                    }
                    beforeOverwrite.accept(name, previous);
                    Files.write(dest, in.readAllBytes());
                    written.add(name);
                }
            }
            return written;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> listResourceFiles(String templateSet) throws IOException {
        String resourceDir = "templates/" + templateSet;
        URL url = TemplateWriter.class.getClassLoader().getResource(resourceDir);
        if (url == null) {
            throw new IllegalStateException("template set not found on classpath: " + resourceDir);
        }
        List<String> result = new ArrayList<>();
        if (url.getProtocol().equals("file")) {
            Path dir;
            try {
                dir = Path.of(url.toURI());
            } catch (URISyntaxException e) {
                throw new IOException(e);
            }
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> result.add(p.getFileName().toString()));
            }
        } else if (url.getProtocol().equals("jar")) {
            String jarPath = url.getPath().substring(5, url.getPath().indexOf("!"));
            try (var jar = new java.util.jar.JarFile(java.net.URLDecoder.decode(jarPath, java.nio.charset.StandardCharsets.UTF_8))) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith(resourceDir + "/") && name.endsWith(".java")) {
                        result.add(name.substring((resourceDir + "/").length()));
                    }
                }
            }
        }
        return result;
    }
}
