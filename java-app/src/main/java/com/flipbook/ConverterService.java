package com.flipbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class ConverterService {

    private static final Pattern SAFE_ID = Pattern.compile("^[a-f0-9]{12}$");
    private final ObjectMapper mapper = new ObjectMapper();

    // Bounded executor for background rasterization (max 2 parallel tasks, daemon threads)
    private final ExecutorService rasterExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "flipbook-rasterizer-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    // Active conversions tracking to protect from premature TTL cleanup and race conditions
    private final ConcurrentHashMap<String, CompletableFuture<Void>> activeConversions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    @Value("${app.python}")
    private String python;

    @Value("${app.python-script}")
    private String script;

    @Value("${app.dpi}")
    private int dpi;

    @Value("${app.image-format:jpg}")
    private String imageFormat;

    @Value("${app.storage-dir}")
    private String storageDir;

    @Value("${app.max-file-size-bytes:1073741824}")
    private long maxPdfBytes = 1024L * 1024 * 1024L; // 1 GB default (1073741824 bytes)

    private Path root() throws IOException {
        Path p = Paths.get(storageDir);
        if (!Files.exists(p) && Files.exists(Paths.get("java-app", storageDir))) {
            p = Paths.get("java-app", storageDir);
        }
        Path root = p.toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root;
    }

    public Path getBookDir(String id) throws IOException {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            return null;
        }
        Path dir = root().resolve(id).normalize();
        if (!dir.startsWith(root()) || !Files.isDirectory(dir)) {
            return null;
        }
        return dir;
    }

    public boolean deleteBook(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            return false;
        }
        CompletableFuture<Void> future = activeConversions.remove(id);
        if (future != null) {
            future.cancel(true);
        }
        Process proc = activeProcesses.remove(id);
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
            try {
                proc.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        }
        try {
            Path bookDir = getBookDir(id);
            if (bookDir == null || !Files.exists(bookDir)) {
                return false;
            }
            for (int attempt = 0; attempt < 5; attempt++) {
                deleteDirectoryRecursively(bookDir);
                if (!Files.exists(bookDir)) {
                    return true;
                }
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            return !Files.exists(bookDir);
        } catch (Exception e) {
            return false;
        }
    }

    public void deleteDirectoryRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        Files.walk(dir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception e) {
                        p.toFile().deleteOnExit();
                    }
                });
    }

    @PostConstruct
    public void onStartup() {
        cleanupAllBooks();
    }

    @PreDestroy
    public void onShutdown() {
        rasterExecutor.shutdown();
        try {
            if (!rasterExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                rasterExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            rasterExecutor.shutdownNow();
        }
        cleanupAllBooks();
    }

    public void cleanupAllBooks() {
        try {
            Path root = root();
            if (!Files.exists(root)) return;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path bookDir : stream) {
                    if (Files.isDirectory(bookDir)) {
                        deleteDirectoryRecursively(bookDir);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Automatically clean up any books older than 15 minutes to guarantee no data lingers.
     * Skips books currently undergoing background conversion.
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupExpiredBooks() {
        try {
            Path root = root();
            if (!Files.exists(root)) return;
            long now = System.currentTimeMillis();
            long maxAge = 15 * 60 * 1000L; // 15 minutes TTL

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path bookDir : stream) {
                    if (Files.isDirectory(bookDir)) {
                        String id = bookDir.getFileName().toString();
                        // NEVER delete a book currently being converted
                        if (isConversionActive(id)) {
                            continue;
                        }
                        long lastModified = Files.getLastModifiedTime(bookDir).toMillis();
                        if (now - lastModified > maxAge) {
                            deleteDirectoryRecursively(bookDir);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public boolean isConversionActive(String id) {
        if (id == null) return false;
        CompletableFuture<Void> future = activeConversions.get(id);
        return future != null && !future.isDone();
    }

    public boolean awaitConversion(String id, long timeout, TimeUnit unit) {
        if (id == null) return true;
        CompletableFuture<Void> future = activeConversions.get(id);
        if (future == null || future.isDone()) return true;
        try {
            future.get(timeout, unit);
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    private Path resolveScriptPath() {
        Path p = Paths.get(script);
        if (Files.isRegularFile(p)) {
            return p.toAbsolutePath().normalize();
        }
        Path p1 = Paths.get("python-service", "convert.py");
        if (Files.isRegularFile(p1)) {
            return p1.toAbsolutePath().normalize();
        }
        Path p2 = Paths.get("..", "python-service", "convert.py");
        if (Files.isRegularFile(p2)) {
            return p2.toAbsolutePath().normalize();
        }
        Path current = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < 4 && current != null; i++) {
            Path candidate = current.resolve("python-service").resolve("convert.py");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
            current = current.getParent();
        }
        return p.toAbsolutePath().normalize();
    }

    private String resolvePythonCommand() {
        if (isCommandAvailable(python)) {
            return python;
        }
        for (String candidate : new String[]{"python", "py", "python3"}) {
            if (isCommandAvailable(candidate)) {
                return candidate;
            }
        }
        return python;
    }

    private boolean isCommandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version").start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fast-first conversion:
     * Saves source.pdf, extracts page count & dimensions in ~100ms, enriches metadata,
     * triggers bounded background PyMuPDF rasterization, and immediately returns the book ID.
     */
    public String convert(MultipartFile file) throws IOException, InterruptedException {
        if (activeConversions.size() >= 15) {
            throw new IOException("Server is currently experiencing high conversion traffic. Please try again in a few moments.");
        }
        validate(file);
        checkAvailableDiskSpace(file.getSize());

        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path bookDir = root().resolve(id);
        Files.createDirectories(bookDir);

        // 1. Preserve original source.pdf synchronously before returning ID (streamed via transferTo)
        Path pdf = bookDir.resolve("source.pdf");
        file.transferTo(pdf);

        // 2. Extract metadata quickly using PyMuPDF (--meta-only)
        try {
            extractQuickMetadata(pdf, bookDir);
        } catch (Exception e) {
            deleteDirectoryRecursively(bookDir);
            throw new IOException("Failed to process PDF: " + e.getMessage(), e);
        }

        // 3. Enrich meta.json with full document metadata (id, filename, title, size, createdAt)
        enrichMetadata(bookDir, id, file);

        // 4. Start PyMuPDF page rasterization in the background
        triggerBackgroundRasterization(id, pdf, bookDir);

        // 5. Return book ID immediately so flipbook opens in client PDF.js reader
        return id;
    }

    private void extractQuickMetadata(Path pdf, Path bookDir) throws IOException, InterruptedException {
        Path scriptPath = resolveScriptPath();
        String pythonCmd = resolvePythonCommand();

        ProcessBuilder pb = new ProcessBuilder(
                pythonCmd, scriptPath.toString(), pdf.toString(), bookDir.toString(),
                String.valueOf(dpi), imageFormat, "--meta-only");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Metadata extraction timed out.");
        }

        if (process.exitValue() != 0) {
            throw new IOException("Metadata extraction failed: " + (output.isBlank() ? "Exit code " + process.exitValue() : output.trim()));
        }
    }

    private void triggerBackgroundRasterization(String id, Path pdf, Path bookDir) {
        Path scriptPath = resolveScriptPath();
        String pythonCmd = resolvePythonCommand();

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        pythonCmd, scriptPath.toString(), pdf.toString(), bookDir.toString(),
                        String.valueOf(dpi), imageFormat);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                activeProcesses.put(id, process);

                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                boolean finished = process.waitFor(1200, TimeUnit.SECONDS); // 20 min max for very large PDFs
                if (!finished) {
                    process.destroyForcibly();
                    System.err.println("[ConverterService] Background conversion timed out for " + id);
                    return;
                }
                if (process.exitValue() != 0) {
                    System.err.println("[ConverterService] Background conversion exit code "
                            + process.exitValue() + " for " + id + ": " + output.trim());
                    // NOTE: Do not delete bookDir or source.pdf; the book is still readable via PDF.js.
                }
            } catch (Exception e) {
                System.err.println("[ConverterService] Background rasterization error for " + id + ": " + e.getMessage());
            } finally {
                activeProcesses.remove(id);
                activeConversions.remove(id);
            }
        }, rasterExecutor);

        activeConversions.put(id, future);
    }

    private void enrichMetadata(Path bookDir, String id, MultipartFile file) {
        Path metaFile = bookDir.resolve("meta.json");
        Map<String, Object> meta = new HashMap<>();
        if (Files.exists(metaFile)) {
            try {
                meta = mapper.readValue(metaFile.toFile(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "document.pdf";
        }
        String cleanTitle = sanitizeTitle(originalName);

        meta.put("id", id);
        meta.put("filename", originalName);
        meta.put("title", cleanTitle);
        meta.put("size", file.getSize());
        meta.put("createdAt", Instant.now().toString());

        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(metaFile.toFile(), meta);
        } catch (Exception ignored) {}
    }

    public static String sanitizeTitle(String filename) {
        if (filename == null || filename.isBlank()) {
            return "Document";
        }
        String name = filename;
        if (name.toLowerCase().endsWith(".pdf")) {
            name = name.substring(0, name.length() - 4);
        }
        name = name.replace('_', ' ').replace('-', ' ').trim();
        name = name.replaceAll("\\s+", " ");
        return name.isBlank() ? "Document" : name;
    }

    public static String sanitizeForFilename(String name) {
        if (name == null || name.isBlank()) {
            return "Flipbook";
        }
        String safe = name.replaceAll("[^a-zA-Z0-9_.-]", "_");
        safe = safe.replaceAll("_+", "_");
        if (safe.startsWith("_")) safe = safe.substring(1);
        if (safe.endsWith("_")) safe = safe.substring(0, safe.length() - 1);
        return safe.isBlank() ? "Flipbook" : safe;
    }

    public Map<String, Object> meta(String id) throws IOException {
        Path bookDir = getBookDir(id);
        if (bookDir == null) {
            return null;
        }
        Path metaFile = bookDir.resolve("meta.json");
        if (!Files.exists(metaFile)) {
            return null;
        }
        Map<String, Object> data = mapper.readValue(metaFile.toFile(), new TypeReference<Map<String, Object>>() {});
        // Ensure backwards compatibility if fields are missing
        if (!data.containsKey("id")) {
            data.put("id", id);
        }
        if (!data.containsKey("title")) {
            String fn = (String) data.get("filename");
            data.put("title", fn != null ? sanitizeTitle(fn) : "Document " + id);
        }
        return data;
    }

    public Path getSourcePdf(String id) throws IOException {
        Path bookDir = getBookDir(id);
        if (bookDir == null) {
            return null;
        }
        Path pdf = bookDir.resolve("source.pdf");
        if (Files.isRegularFile(pdf)) {
            return pdf;
        }
        return null;
    }

    /**
     * Generates a 100% self-contained offline flipbook ZIP package and streams it directly to OutputStream.
     */
    public void generateZip(String id, OutputStream os) throws IOException {
        Path bookDir = getBookDir(id);
        if (bookDir == null) {
            throw new IOException("Book not found.");
        }

        // Wait for background rasterization if active, up to 60 seconds
        awaitConversion(id, 60, TimeUnit.SECONDS);

        Map<String, Object> meta = meta(id);
        if (meta == null) {
            throw new IOException("Book metadata not found.");
        }

        String rawTitle = (String) meta.getOrDefault("title", "Flipbook");
        String folderName = sanitizeForFilename(rawTitle) + "_Flipbook";

        // Collect all page-N.jpg files in order
        List<Path> pages = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(bookDir, "page-*." + meta.getOrDefault("format", "jpg"))) {
            for (Path entry : stream) {
                pages.add(entry);
            }
        }
        pages.sort((a, b) -> {
            int numA = extractPageNumber(a.getFileName().toString());
            int numB = extractPageNumber(b.getFileName().toString());
            return Integer.compare(numA, numB);
        });

        if (pages.isEmpty()) {
            throw new IOException("No page images found for this book.");
        }

        byte[] pageFlipJs = loadPageFlipJs();
        String pageFlipJsStr = new String(pageFlipJs, StandardCharsets.UTF_8);
        String offlineHtml = buildOfflineHtml(meta, pages.size(), rawTitle, pageFlipJsStr);
        String readmeTxt = buildReadme(rawTitle, pages.size());

        try (ZipOutputStream zos = new ZipOutputStream(os)) {
            // 1. index.html (self-contained with inlined PageFlip JS engine)
            ZipEntry htmlEntry = new ZipEntry(folderName + "/index.html");
            zos.putNextEntry(htmlEntry);
            zos.write(offlineHtml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 2. README.txt
            ZipEntry readmeEntry = new ZipEntry(folderName + "/README.txt");
            zos.putNextEntry(readmeEntry);
            zos.write(readmeTxt.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 3. js/page-flip.browser.js (also kept for modular directory structure)
            ZipEntry jsEntry = new ZipEntry(folderName + "/js/page-flip.browser.js");
            zos.putNextEntry(jsEntry);
            zos.write(pageFlipJs);
            zos.closeEntry();

            // 4. metadata.json
            ZipEntry metaEntry = new ZipEntry(folderName + "/metadata.json");
            zos.putNextEntry(metaEntry);
            zos.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta));
            zos.closeEntry();

            // 5. Original source PDF if available
            Path sourcePdf = getSourcePdf(id);
            if (sourcePdf != null && Files.exists(sourcePdf)) {
                String originalName = meta.get("filename") != null ? (String) meta.get("filename") : "source.pdf";
                String safePdfName = sanitizeForFilename(originalName);
                if (!safePdfName.toLowerCase().endsWith(".pdf")) safePdfName += ".pdf";
                ZipEntry pdfEntry = new ZipEntry(folderName + "/" + safePdfName);
                zos.putNextEntry(pdfEntry);
                Files.copy(sourcePdf, zos);
                zos.closeEntry();
            }

            // 6. assets/page-1.jpg, page-2.jpg ...
            byte[] buffer = new byte[16384];
            for (Path pagePath : pages) {
                String fileName = pagePath.getFileName().toString();
                ZipEntry pageEntry = new ZipEntry(folderName + "/assets/" + fileName);
                zos.putNextEntry(pageEntry);
                try (InputStream is = Files.newInputStream(pagePath)) {
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
            }
            zos.finish();
        }
    }

    public byte[] loadPageFlipJs() throws IOException {
        String[] possiblePaths = {
            "java-app/src/main/resources/static/page-flip.browser.js",
            "src/main/resources/static/page-flip.browser.js",
            "java-app/target/classes/static/page-flip.browser.js",
            "target/classes/static/page-flip.browser.js"
        };
        for (String pStr : possiblePaths) {
            Path p = Paths.get(pStr);
            if (Files.isRegularFile(p)) {
                return Files.readAllBytes(p);
            }
        }
        // Try ClassLoader resource
        try (InputStream is = ConverterService.class.getResourceAsStream("/static/page-flip.browser.js")) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (Exception ignored) {}

        // Try Spring ClassPathResource
        try {
            ClassPathResource cpr = new ClassPathResource("static/page-flip.browser.js");
            if (cpr.exists()) {
                try (InputStream is = cpr.getInputStream()) {
                    return is.readAllBytes();
                }
            }
        } catch (Exception ignored) {}

        throw new IOException("page-flip.browser.js library could not be located on server.");
    }


    public static int extractPageNumber(String name) {
        try {
            String num = name.replaceAll("[^0-9]", "");
            return Integer.parseInt(num);
        } catch (Exception e) {
            return 0;
        }
    }

    private String buildReadme(String title, int pageCount) {
        return "========================================================\n"
             + " " + title + " - Offline Flipbook\n"
             + "========================================================\n\n"
             + "Total Pages: " + pageCount + "\n\n"
             + "HOW TO VIEW:\n"
             + "1. Simply double-click 'index.html' to open this flipbook in any browser.\n"
             + "2. No internet connection, server, or installation is required.\n"
             + "3. All assets, scripts, and pages are bundled 100% locally.\n\n"
             + "NAVIGATION:\n"
             + "- Click page corners or edges to turn pages\n"
             + "- Use Left / Right arrow keys on your keyboard\n"
             + "- Use the on-screen Previous / Next buttons\n"
             + "- Toggle Fullscreen mode using the controls bar\n\n"
             + "Created with FlipBook.\n";
    }

    private String buildOfflineHtml(Map<String, Object> meta, int pageCount, String title, String pageFlipJs) {
        String metaJson;
        try {
            metaJson = mapper.writeValueAsString(meta);
        } catch (Exception e) {
            metaJson = "{\"pages\":" + pageCount + ",\"width\":600,\"height\":800}";
        }

        return "<!DOCTYPE html>\n"
             + "<html lang=\"en\">\n"
             + "<head>\n"
             + "  <meta charset=\"UTF-8\">\n"
             + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n"
             + "  <title>" + escapeHtml(title) + " — Flip to Read</title>\n"
             + "  <style>\n"
             + "    * { box-sizing: border-box; margin: 0; padding: 0; }\n"
             + "    html, body {\n"
             + "      width: 100%; height: 100%; height: 100dvh; overflow: hidden;\n"
             + "      background: #ffffff; color: #264653; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n"
             + "      user-select: none; -webkit-user-select: none;\n"
             + "    }\n"
             + "    .app {\n"
             + "      display: flex; flex-direction: column; width: 100vw; height: 100vh; height: 100dvh; overflow: hidden;\n"
             + "    }\n"
             + "    header {\n"
             + "      height: 52px; padding: 0 16px; display: flex; align-items: center; justify-content: space-between;\n"
             + "      background: rgba(255, 255, 255, 0.96); border-bottom: 1px solid rgba(38, 70, 83, 0.12);\n"
             + "      backdrop-filter: blur(8px); z-index: 10;\n"
             + "    }\n"
             + "    .title-area { display: flex; align-items: center; gap: 10px; min-width: 0; }\n"
             + "    .badge {\n"
             + "      font-size: 0.72rem; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase;\n"
             + "      padding: 3px 8px; border-radius: 6px; background: rgba(42, 157, 143, 0.12);\n"
             + "      color: #2A9D8F; border: 1px solid rgba(42, 157, 143, 0.35);\n"
             + "    }\n"
             + "    .book-title {\n"
             + "      font-size: 0.95rem; font-weight: 600; white-space: nowrap;\n"
             + "      overflow: hidden; text-overflow: ellipsis; color: #264653;\n"
             + "    }\n"
             + "    .viewport {\n"
             + "      flex: 1; min-height: 0; position: relative; display: flex;\n"
             + "      align-items: center; justify-content: center; overflow: hidden; padding: 12px;\n"
             + "      background: radial-gradient(circle at center, #ffffff 0%, #ebf1f0 100%);\n"
             + "    }\n"
             + "    #book-container { display: flex; align-items: center; justify-content: center; }\n"
             + "    #book {\n"
             + "      box-shadow: 0 22px 50px -10px rgba(38, 70, 83, 0.25), 0 0 1px 1px rgba(38, 70, 83, 0.08);\n"
             + "      transition: opacity 0.3s ease;\n"
             + "    }\n"
             + "    footer {\n"
             + "      height: 54px; padding: 0 16px; display: flex; align-items: center;\n"
             + "      justify-content: center; gap: 14px;\n"
             + "      background: rgba(255, 255, 255, 0.96); border-top: 1px solid rgba(38, 70, 83, 0.12);\n"
             + "      backdrop-filter: blur(8px); z-index: 10;\n"
             + "    }\n"
             + "    .btn {\n"
             + "      display: inline-flex; align-items: center; justify-content: center; gap: 6px;\n"
             + "      background: rgba(38, 70, 83, 0.05); color: #264653; border: 1px solid rgba(38, 70, 83, 0.12);\n"
             + "      border-radius: 8px; padding: 8px 16px; font-size: 0.86rem; font-weight: 600;\n"
             + "      cursor: pointer; transition: all 0.15s ease;\n"
             + "    }\n"
             + "    .btn:hover:not(:disabled) {\n"
             + "      background: #2A9D8F; border-color: #2A9D8F; color: #ffffff;\n"
             + "    }\n"
             + "    .btn:disabled { opacity: 0.35; cursor: not-allowed; }\n"
             + "    .page-badge-btn {\n"
             + "      display: inline-flex; align-items: center; justify-content: center;\n"
             + "      background: rgba(42, 157, 143, 0.1); color: #2A9D8F; border: 1px solid rgba(42, 157, 143, 0.3);\n"
             + "      border-radius: 6px; padding: 6px 14px; font-size: 0.88rem; font-weight: 600;\n"
             + "      cursor: pointer; transition: all 0.15s ease;\n"
             + "    }\n"
             + "    .page-badge-btn:hover { background: rgba(42, 157, 143, 0.2); border-color: #2A9D8F; }\n"
             + "    .jump-popover {\n"
             + "      display: none; position: absolute; bottom: 62px; left: 50%; transform: translateX(-50%);\n"
             + "      background: #ffffff; border: 1px solid rgba(38, 70, 83, 0.15); border-radius: 10px;\n"
             + "      box-shadow: 0 10px 25px rgba(38, 70, 83, 0.18); padding: 12px 14px; z-index: 50;\n"
             + "      flex-direction: column; gap: 8px; min-width: 170px;\n"
             + "    }\n"
             + "    .jump-popover.active { display: flex; }\n"
             + "    .jump-label { font-size: 0.78rem; font-weight: 600; color: #527179; text-transform: uppercase; letter-spacing: 0.04em; }\n"
             + "    .jump-row { display: flex; gap: 6px; }\n"
             + "    .jump-input {\n"
             + "      width: 70px; padding: 6px 8px; border: 1px solid rgba(38, 70, 83, 0.2); border-radius: 6px;\n"
             + "      font-size: 0.9rem; font-weight: 600; color: #264653; text-align: center; outline: none;\n"
             + "    }\n"
             + "    .jump-input:focus { border-color: #2A9D8F; }\n"
             + "    .jump-btn {\n"
             + "      padding: 6px 12px; background: #2A9D8F; color: #ffffff; border: none; border-radius: 6px;\n"
             + "      font-size: 0.84rem; font-weight: 600; cursor: pointer; transition: background 0.15s ease;\n"
             + "    }\n"
             + "    .jump-btn:hover { background: #238276; }\n"
             + "    .loading-overlay {\n"
             + "      position: absolute; inset: 0; display: flex; flex-direction: column;\n"
             + "      align-items: center; justify-content: center; gap: 14px;\n"
             + "      background: rgba(255, 255, 255, 0.95); z-index: 30; transition: opacity 0.3s ease;\n"
             + "    }\n"
             + "    .spinner {\n"
             + "      width: 38px; height: 38px; border: 3px solid rgba(38, 70, 83, 0.15);\n"
             + "      border-top-color: #E76F51; border-radius: 50%; animation: spin 0.8s linear infinite;\n"
             + "    }\n"
             + "    @keyframes spin { to { transform: rotate(360deg); } }\n"
             + "  </style>\n"
             + "</head>\n"
             + "<body>\n"
             + "  <div class=\"app\">\n"
             + "    <header>\n"
             + "      <div class=\"title-area\">\n"
             + "        <span class=\"badge\"><span style=\"color:#264653;\">Flip</span> <span style=\"color:#64748b;\">to</span> <span style=\"color:#E76F51;\">Read</span></span>\n"
             + "        <span class=\"book-title\" title=\"" + escapeHtml(title) + "\">" + escapeHtml(title) + "</span>\n"
             + "      </div>\n"
             + "    </header>\n"
             + "    <main class=\"viewport\" id=\"viewport\">\n"
             + "      <div class=\"loading-overlay\" id=\"loading\">\n"
             + "        <div class=\"spinner\"></div>\n"
             + "        <span id=\"loading-text\">Loading book...</span>\n"
             + "      </div>\n"
             + "      <div id=\"book-container\">\n"
             + "        <div id=\"book\"></div>\n"
             + "      </div>\n"
             + "    </main>\n"
             + "    <footer>\n"
             + "      <button class=\"btn\" id=\"btn-prev\" aria-label=\"Previous page\">&lsaquo; Prev</button>\n"
             + "      <div style=\"position: relative;\">\n"
             + "        <button type=\"button\" class=\"page-badge-btn\" id=\"btn-page-badge\">Page 1 / " + pageCount + "</button>\n"
             + "        <div class=\"jump-popover\" id=\"jump-popover\">\n"
             + "          <span class=\"jump-label\">Go to page:</span>\n"
             + "          <div class=\"jump-row\">\n"
             + "            <input type=\"number\" id=\"jump-input\" min=\"1\" max=\"" + pageCount + "\" value=\"1\" class=\"jump-input\">\n"
             + "            <button type=\"button\" id=\"btn-jump-go\" class=\"jump-btn\">Go</button>\n"
             + "          </div>\n"
             + "        </div>\n"
             + "      </div>\n"
             + "      <button class=\"btn\" id=\"btn-next\" aria-label=\"Next page\">Next &rsaquo;</button>\n"
             + "      <button class=\"btn\" id=\"btn-full\" aria-label=\"Fullscreen\">Fullscreen</button>\n"
             + "    </footer>\n"
             + "  </div>\n"
             + "  <!-- Bundled Self-Contained PageFlip Engine -->\n"
             + "  <script>\n" + pageFlipJs + "\n  </script>\n"
             + "  <script>\n"
             + "    const meta = " + metaJson + ";\n"
             + "    const totalPages = " + pageCount + ";\n"
             + "    let flip = null;\n"
             + "    const viewport = document.getElementById('viewport');\n"
             + "    const bookContainer = document.getElementById('book-container');\n"
             + "    const btnPageBadge = document.getElementById('btn-page-badge');\n"
             + "    const jumpPopover = document.getElementById('jump-popover');\n"
             + "    const jumpInput = document.getElementById('jump-input');\n"
             + "    const btnJumpGo = document.getElementById('btn-jump-go');\n"
             + "    const btnPrev = document.getElementById('btn-prev');\n"
             + "    const btnNext = document.getElementById('btn-next');\n"
             + "    const btnFull = document.getElementById('btn-full');\n"
             + "    const loading = document.getElementById('loading');\n"
             + "    const loadingText = document.getElementById('loading-text');\n\n"
             + "    function calcDimensions() {\n"
             + "      const vw = Math.max(300, viewport.clientWidth - 32);\n"
             + "      const vh = Math.max(300, viewport.clientHeight - 24);\n"
             + "      const isMobile = window.innerWidth < 768 || vw < vh;\n"
             + "      const origW = (meta && meta.width > 0) ? meta.width : 600;\n"
             + "      const origH = (meta && meta.height > 0) ? meta.height : 800;\n"
             + "      const aspect = origW / origH;\n"
             + "      let pageW, pageH;\n"
             + "      if (isMobile) {\n"
             + "        let targetW = Math.min(vw, 680);\n"
             + "        let targetH = targetW / aspect;\n"
             + "        if (targetH > vh) { targetH = vh; targetW = targetH * aspect; }\n"
             + "        pageW = Math.round(targetW); pageH = Math.round(targetH);\n"
             + "      } else {\n"
             + "        let targetTotalW = Math.min(vw, 1500);\n"
             + "        let targetPageW = targetTotalW / 2;\n"
             + "        let targetH = targetPageW / aspect;\n"
             + "        if (targetH > vh) { targetH = vh; targetPageW = targetH * aspect; }\n"
             + "        pageW = Math.round(targetPageW); pageH = Math.round(targetH);\n"
             + "      }\n"
             + "      return { width: Math.max(180, pageW), height: Math.max(240, pageH), isMobile };\n"
             + "    }\n\n"
             + "    function updateControls() {\n"
             + "      if (!flip) return;\n"
             + "      const idx = flip.getCurrentPageIndex() || 0;\n"
             + "      const curr = idx + 1;\n"
             + "      btnPageBadge.textContent = 'Page ' + curr + ' / ' + totalPages;\n"
             + "      jumpInput.value = curr;\n"
             + "      btnPrev.disabled = idx <= 0;\n"
             + "      btnNext.disabled = idx >= totalPages - 1;\n"
             + "    }\n\n"
             + "    function hideLoading() {\n"
             + "      if (loading && loading.style.display !== 'none') {\n"
             + "        loading.style.opacity = '0';\n"
             + "        setTimeout(() => { loading.style.display = 'none'; }, 300);\n"
             + "      }\n"
             + "    }\n\n"
             + "    function performJump() {\n"
             + "      const target = parseInt(jumpInput.value, 10);\n"
             + "      if (!isNaN(target) && target >= 1 && target <= totalPages) {\n"
             + "        jumpPopover.classList.remove('active');\n"
             + "        if (flip) flip.flip(target - 1);\n"
             + "      }\n"
             + "    }\n\n"
             + "    function initFlipbook() {\n"
             + "      if (typeof St === 'undefined' || !St.PageFlip) {\n"
             + "        if (loadingText) loadingText.textContent = 'Flipbook engine failed to load.';\n"
             + "        return;\n"
             + "      }\n"
             + "      const dims = calcDimensions();\n"
             + "      bookContainer.innerHTML = '<div id=\"book\"></div>';\n"
             + "      const bookEl = document.getElementById('book');\n"
             + "      flip = new St.PageFlip(bookEl, {\n"
             + "        width: dims.width,\n"
             + "        height: dims.height,\n"
             + "        size: 'fixed',\n"
             + "        showCover: true,\n"
             + "        usePortrait: dims.isMobile,\n"
             + "        maxShadowOpacity: 0.5,\n"
             + "        mobileScrollSupport: false\n"
             + "      });\n"
             + "      flip.on('flip', updateControls);\n"
             + "      flip.on('init', () => {\n"
             + "        updateControls();\n"
             + "        hideLoading();\n"
             + "      });\n"
             + "      const pages = Array.from({ length: totalPages }, (_, i) => 'assets/page-' + (i + 1) + '.jpg');\n"
             + "      flip.loadFromImages(pages);\n"
             + "      setTimeout(hideLoading, 500);\n"
             + "      btnPrev.onclick = () => { if (flip) flip.flipPrev(); };\n"
             + "      btnNext.onclick = () => { if (flip) flip.flipNext(); };\n"
             + "      btnFull.onclick = () => {\n"
             + "        if (document.fullscreenElement) document.exitFullscreen();\n"
             + "        else document.documentElement.requestFullscreen().catch(() => {});\n"
             + "      };\n"
             + "      btnPageBadge.onclick = (e) => {\n"
             + "        e.stopPropagation();\n"
             + "        jumpPopover.classList.toggle('active');\n"
             + "        if (jumpPopover.classList.contains('active')) {\n"
             + "          jumpInput.focus();\n"
             + "          jumpInput.select();\n"
             + "        }\n"
             + "      };\n"
             + "      document.addEventListener('click', () => { jumpPopover.classList.remove('active'); });\n"
             + "      jumpPopover.onclick = (e) => { e.stopPropagation(); };\n"
             + "      btnJumpGo.onclick = performJump;\n"
             + "      jumpInput.addEventListener('keydown', (e) => {\n"
             + "        if (e.key === 'Enter') performJump();\n"
             + "      });\n"
             + "      window.addEventListener('keydown', e => {\n"
             + "        if (!flip || document.activeElement === jumpInput) return;\n"
             + "        if (e.key === 'ArrowLeft') flip.flipPrev();\n"
             + "        else if (e.key === 'ArrowRight') flip.flipNext();\n"
             + "        else if (e.key === 'Home') flip.flip(0);\n"
             + "        else if (e.key === 'End') flip.flip(totalPages - 1);\n"
             + "      });\n"
             + "    }\n\n"
             + "    let resizeTimer = null;\n"
             + "    window.addEventListener('resize', () => {\n"
             + "      clearTimeout(resizeTimer);\n"
             + "      resizeTimer = setTimeout(() => {\n"
             + "        if (!flip) return;\n"
             + "        const curr = flip.getCurrentPageIndex() || 0;\n"
             + "        const dims = calcDimensions();\n"
             + "        flip.destroy();\n"
             + "        initFlipbook();\n"
             + "        try { flip.flip(curr); } catch (e) {}\n"
             + "      }, 200);\n"
             + "    });\n\n"
             + "    if (document.readyState === 'loading') {\n"
             + "      window.addEventListener('DOMContentLoaded', initFlipbook);\n"
             + "    } else {\n"
             + "      initFlipbook();\n"
             + "    }\n"
             + "  </script>\n"
             + "</body>\n"
             + "</html>";
    }

    public static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private void checkAvailableDiskSpace(long requiredBytes) throws IOException {
        Path r = root();
        try {
            java.nio.file.FileStore store = Files.getFileStore(r);
            long usable = store.getUsableSpace();
            // Ensure at least requiredBytes + 200MB free safety headroom
            if (usable < requiredBytes + (200L * 1024 * 1024)) {
                throw new IOException("Server storage is temporarily full. Please try again later.");
            }
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("storage is temporarily full")) {
                throw e;
            }
        }
    }

    private void validate(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IOException("No file uploaded.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".pdf")) {
            throw new IOException("Only PDF files are allowed.");
        }
        if (file.getSize() > maxPdfBytes) {
            throw new IOException("File is too large. Maximum supported size is 1 GB.");
        }
        try (InputStream is = file.getInputStream()) {
            byte[] head = is.readNBytes(5);
            if (!new String(head, StandardCharsets.US_ASCII).startsWith("%PDF")) {
                throw new IOException("This file is not a valid PDF (missing %PDF signature).");
            }
        }
    }

    private static final Pattern FILENAME_FROM_DISP = Pattern.compile("filename\\*?=(?:UTF-8'')?[\"']?([^\"';\\n]+)[\"']?", Pattern.CASE_INSENSITIVE);

    public String convertFromUrl(String urlStr) throws IOException, InterruptedException {
        if (activeConversions.size() >= 15) {
            throw new IOException("Server is currently experiencing high conversion traffic. Please try again in a few moments.");
        }
        if (urlStr == null || urlStr.isBlank()) {
            throw new IOException("URL cannot be empty.");
        }
        urlStr = urlStr.trim();
        URI uri;
        try {
            uri = URI.create(urlStr);
        } catch (Exception e) {
            throw new IOException("Invalid URL format: " + e.getMessage());
        }

        validateUrlSafety(uri);

        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path bookDir = root().resolve(id);
        Files.createDirectories(bookDir);
        Path pdf = bookDir.resolve("source.pdf");

        String detectedFilename = "document.pdf";
        long totalBytes = 0;

        try {
            URI currentUri = uri;
            HttpURLConnection conn = null;
            int redirects = 0;

            while (true) {
                if (redirects >= 4) {
                    throw new IOException("Too many HTTP redirects (exceeded maximum of 4).");
                }
                validateUrlSafety(currentUri);
                URL url = currentUri.toURL();
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 FlipToRead/1.0");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);

                int status = conn.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("Redirect location missing from remote server.");
                    }
                    try {
                        currentUri = currentUri.resolve(URI.create(location.trim()));
                    } catch (Exception e) {
                        throw new IOException("Invalid redirect URL: " + location);
                    }
                    redirects++;
                    conn.disconnect();
                    continue;
                }

                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Remote server returned HTTP " + status + " (" + conn.getResponseMessage() + ")");
                }
                break;
            }

            if (conn == null) {
                throw new IOException("Failed to establish connection to URL.");
            }

            String disp = conn.getHeaderField("Content-Disposition");
            if (disp != null) {
                Matcher m = FILENAME_FROM_DISP.matcher(disp);
                if (m.find()) {
                    try {
                        detectedFilename = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                        detectedFilename = m.group(1);
                    }
                }
            }
            if ("document.pdf".equals(detectedFilename) && currentUri.getPath() != null) {
                String path = currentUri.getPath();
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                    String part = path.substring(lastSlash + 1);
                    try {
                        part = URLDecoder.decode(part, StandardCharsets.UTF_8);
                    } catch (Exception ignored) {}
                    if (part.toLowerCase().endsWith(".pdf")) {
                        detectedFilename = part;
                    }
                }
            }

            long contentLength = conn.getContentLengthLong();
            if (contentLength > maxPdfBytes) {
                throw new IOException("The PDF file exceeds the maximum size limit of 1 GB.");
            }

            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(pdf)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    totalBytes += n;
                    if (totalBytes > maxPdfBytes) {
                        throw new IOException("The PDF file exceeds the maximum size limit of 1 GB.");
                    }
                    out.write(buf, 0, n);
                }
            } finally {
                conn.disconnect();
            }

            if (totalBytes < 5) {
                throw new IOException("The downloaded file is empty or corrupted.");
            }
            try (InputStream testIn = Files.newInputStream(pdf)) {
                byte[] head = testIn.readNBytes(5);
                if (!new String(head, StandardCharsets.US_ASCII).startsWith("%PDF")) {
                    throw new IOException("The remote file is not a valid PDF document (missing %PDF signature).");
                }
            }

            // 2. Extract metadata quickly using PyMuPDF (--meta-only)
            try {
                extractQuickMetadata(pdf, bookDir);
            } catch (Exception e) {
                deleteDirectoryRecursively(bookDir);
                throw new IOException("Failed to process remote PDF: " + e.getMessage(), e);
            }

            // 3. Enrich metadata for URL
            enrichMetadataForUrl(bookDir, id, detectedFilename, totalBytes, urlStr);

            // 4. Start background rasterization
            triggerBackgroundRasterization(id, pdf, bookDir);

            // 5. Return book ID immediately
            return id;
        } catch (Exception e) {
            deleteDirectoryRecursively(bookDir);
            throw e;
        }
    }

    private void enrichMetadataForUrl(Path bookDir, String id, String filename, long size, String sourceUrl) {
        Path metaFile = bookDir.resolve("meta.json");
        Map<String, Object> meta = new HashMap<>();
        if (Files.exists(metaFile)) {
            try {
                meta = mapper.readValue(metaFile.toFile(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
        }

        if (filename == null || filename.isBlank()) {
            filename = "document.pdf";
        }
        if (!filename.toLowerCase().endsWith(".pdf")) {
            filename += ".pdf";
        }
        String cleanTitle = sanitizeTitle(filename);

        meta.put("id", id);
        meta.put("filename", filename);
        meta.put("title", cleanTitle);
        meta.put("size", size);
        meta.put("sourceUrl", sourceUrl);
        meta.put("createdAt", Instant.now().toString());

        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(metaFile.toFile(), meta);
        } catch (Exception ignored) {}
    }

    private void validateUrlSafety(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Only HTTP and HTTPS URLs are supported.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("Invalid URL host.");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new IOException("Cannot resolve host: " + host);
        }

        for (InetAddress addr : addresses) {
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                throw new IOException("Access to internal, local, or private network addresses is forbidden.");
            }
            byte[] bytes = addr.getAddress();
            if (bytes.length == 4) {
                int b0 = bytes[0] & 0xFF;
                int b1 = bytes[1] & 0xFF;
                if (b0 == 127 || (b0 == 169 && b1 == 254) || (b0 == 10) || (b0 == 172 && (b1 >= 16 && b1 <= 31)) || (b0 == 192 && b1 == 168) || b0 == 0) {
                    throw new IOException("Access to internal or private IP ranges is forbidden.");
                }
            } else if (bytes.length == 16) {
                int b0 = bytes[0] & 0xFF;
                // IPv6 Unique Local Address (ULA) fc00::/7 (0xfc or 0xfd)
                if ((b0 & 0xFE) == 0xFC) {
                    throw new IOException("Access to internal, local, or private network addresses is forbidden.");
                }
                // Check IPv4-mapped IPv6 address (::ffff:x.x.x.x)
                boolean isIPv4Mapped = true;
                for (int i = 0; i < 10; i++) {
                    if (bytes[i] != 0) { isIPv4Mapped = false; break; }
                }
                if (isIPv4Mapped && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
                    int ip0 = bytes[12] & 0xFF;
                    int ip1 = bytes[13] & 0xFF;
                    if (ip0 == 127 || (ip0 == 169 && ip1 == 254) || (ip0 == 10) || (ip0 == 172 && (ip1 >= 16 && ip1 <= 31)) || (ip0 == 192 && ip1 == 168) || ip0 == 0) {
                        throw new IOException("Access to internal or private IP ranges is forbidden.");
                    }
                }
            }
        }
    }
}
