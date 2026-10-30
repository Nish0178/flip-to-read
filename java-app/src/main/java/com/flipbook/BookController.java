package com.flipbook;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api")
public class BookController {

    private static final Pattern ID = Pattern.compile("^[a-f0-9]{12}$");

    private final ConverterService service;
    private final StandaloneFlipbookService standaloneService;

    public BookController(ConverterService service, StandaloneFlipbookService standaloneService) {
        this.service = service;
        this.standaloneService = standaloneService;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @PostMapping({"/upload", "/books/upload"})
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            String id = service.convert(file);
            Map<String, Object> meta = service.meta(id);
            return ResponseEntity.ok(meta != null ? meta : Map.of("id", id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Upload failed"));
        }
    }

    @PostMapping("/upload-url")
    public ResponseEntity<?> uploadUrl(
            @RequestParam(value = "url", required = false) String formUrl,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            String urlStr = formUrl;
            if ((urlStr == null || urlStr.isBlank()) && body != null && body.containsKey("url")) {
                urlStr = body.get("url");
            }
            if (urlStr == null || urlStr.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "URL parameter 'url' is required."));
            }
            String id = service.convertFromUrl(urlStr);
            Map<String, Object> meta = service.meta(id);
            return ResponseEntity.ok(meta != null ? meta : Map.of("id", id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "URL conversion failed"));
        }
    }

    @GetMapping({"/books/{id}", "/books/{id}/metadata"})
    public ResponseEntity<?> book(@PathVariable String id) {
        if (!ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid id"));
        }
        try {
            Map<String, Object> meta = service.meta(id);
            if (meta == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Flipbook not found"));
            }
            return ResponseEntity.ok(meta);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to retrieve book metadata: " + e.getMessage()));
        }
    }

    @GetMapping("/books/{id}/pages")
    public ResponseEntity<?> pages(@PathVariable String id) {
        if (!ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid id"));
        }
        try {
            Map<String, Object> meta = service.meta(id);
            if (meta == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Flipbook not found"));
            }
            int pageCount = meta.get("pages") instanceof Number ? ((Number) meta.get("pages")).intValue() : 0;
            String fmt = (meta.get("format") != null) ? (String) meta.get("format") : "jpg";
            List<String> pageList = new ArrayList<>();
            for (int i = 1; i <= pageCount; i++) {
                pageList.add("/books/" + id + "/page-" + i + "." + fmt);
            }
            return ResponseEntity.ok(Map.of(
                    "id", id,
                    "title", meta.getOrDefault("title", "Flipbook"),
                    "pageCount", pageCount,
                    "pages", pageList
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to list book pages: " + e.getMessage()));
        }
    }

    @GetMapping("/books/{id}/pdf")
    public ResponseEntity<?> downloadPdf(@PathVariable String id) {
        if (!ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid id"));
        }
        try {
            Path pdf = service.getSourcePdf(id);
            if (pdf == null || !Files.exists(pdf)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Original PDF is not available for this flipbook."));
            }

            Map<String, Object> meta = service.meta(id);
            String originalName = meta != null && meta.get("filename") != null
                    ? (String) meta.get("filename")
                    : "document.pdf";
            if (!originalName.toLowerCase().endsWith(".pdf")) {
                originalName += ".pdf";
            }
            String safeDownloadName = ConverterService.sanitizeForFilename(originalName);
            if (!safeDownloadName.toLowerCase().endsWith(".pdf")) {
                safeDownloadName += ".pdf";
            }

            InputStream is = Files.newInputStream(pdf);
            InputStreamResource resource = new InputStreamResource(is);

            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(safeDownloadName)
                    .build();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(Files.size(pdf))
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve PDF: " + e.getMessage()));
        }
    }

    @GetMapping("/books/{id}/download")
    public void downloadFlipbook(@PathVariable String id, HttpServletResponse response) throws IOException {
        if (!ID.matcher(id).matches()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid id");
            return;
        }
        try {
            Path bookDir = service.getBookDir(id);
            if (bookDir == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Flipbook not found");
                return;
            }

            Map<String, Object> meta = service.meta(id);
            String title = meta != null && meta.get("title") != null
                    ? (String) meta.get("title")
                    : "Flipbook";
            String safeZipName = ConverterService.sanitizeForFilename(title) + "_Flipbook.zip";

            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(safeZipName)
                    .build();

            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
            standaloneService.generateZip(id, response.getOutputStream());
            response.flushBuffer();
        } catch (Exception e) {
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to generate flipbook archive: " + e.getMessage());
            }
        }
    }

    @GetMapping("/books/{id}/flipbook")
    public void downloadStandaloneFlipbook(@PathVariable String id, HttpServletResponse response) throws IOException {
        if (!ID.matcher(id).matches()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid id");
            return;
        }
        try {
            Path bookDir = service.getBookDir(id);
            if (bookDir == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Flipbook not found");
                return;
            }

            Map<String, Object> meta = service.meta(id);
            String title = meta != null && meta.get("title") != null
                    ? (String) meta.get("title")
                    : "Flipbook";
            String safeHtmlName = ConverterService.sanitizeForFilename(title) + "_Flipbook.html";

            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(safeHtmlName)
                    .build();

            response.setContentType("text/html;charset=UTF-8");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
            standaloneService.generateStandaloneHtml(id, response.getOutputStream());
            response.flushBuffer();
        } catch (Exception e) {
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to generate standalone flipbook: " + e.getMessage());
            }
        }
    }

    @DeleteMapping("/books/{id}")
    public ResponseEntity<?> deleteBook(@PathVariable String id) {
        if (!ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid id"));
        }
        boolean deleted = service.deleteBook(id);
        return ResponseEntity.ok(Map.of("deleted", deleted, "id", id));
    }

    @PostMapping("/books/{id}/cleanup")
    public ResponseEntity<?> cleanupBook(@PathVariable String id) {
        if (!ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid id"));
        }
        boolean deleted = service.deleteBook(id);
        return ResponseEntity.ok(Map.of("deleted", deleted, "id", id));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxSizeException(org.springframework.web.multipart.MaxUploadSizeExceededException exc) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "File is too large. Maximum supported size is 1 GB."));
    }
}

