package com.flipbook;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Generates a TRUE SINGLE-FILE downloadable interactive flipbook HTML.
 *
 * Architecture:
 * - Embeds complete original source PDF once as Base64.
 * - Decodes Base64 to Uint8Array in browser memory (zero server / zero CDN).
 * - Bundles PDF.js & PDF.js Worker (instantiated via local Blob URL).
 * - Renders PDF pages on demand / lazily to HTML5 canvas.
 * - Powers smooth 3D page curls using bundled PageFlip engine.
 * - Detects true numPages dynamically from the PDF (e.g. 220 pages).
 * - Preserves memory via adjacent-spread rendering and LRU cache eviction.
 * - 100% self-contained: works offline directly via file:/// with 0 network requests.
 */
@Service
public class StandaloneFlipbookService {

    private final ConverterService converterService;
    private final ObjectMapper mapper = new ObjectMapper();

    public StandaloneFlipbookService(ConverterService converterService) {
        this.converterService = converterService;
    }

    /**
     * Builds and streams the standalone HTML directly to the given OutputStream.
     */
    public void generateStandaloneHtml(String id, OutputStream os) throws IOException {
        Path bookDir = converterService.getBookDir(id);
        if (bookDir == null || !Files.exists(bookDir)) {
            throw new IOException("Flipbook not found.");
        }

        Path sourcePdf = converterService.getSourcePdf(id);
        if (sourcePdf == null || !Files.exists(sourcePdf)) {
            throw new IOException("Original source PDF not found for flipbook: " + id);
        }

        Map<String, Object> meta = converterService.meta(id);
        String rawTitle = meta != null && meta.get("title") != null
                ? (String) meta.get("title")
                : "Flipbook";

        byte[] pdfJsBytes = loadResource("static/pdfjs/pdf.min.js");
        byte[] pdfWorkerBytes = loadResource("static/pdfjs/pdf.worker.min.js");
        byte[] pageFlipJsBytes = converterService.loadPageFlipJs();

        String pdfJs = new String(pdfJsBytes, StandardCharsets.UTF_8);
        String pdfWorkerJs = new String(pdfWorkerBytes, StandardCharsets.UTF_8);
        String pageFlipJs = new String(pageFlipJsBytes, StandardCharsets.UTF_8);

        // Load transparent icon for offline standalone branding
        String iconDataUri = "";
        try {
            byte[] iconBytes = loadResource("static/favicon.png");
            iconDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(iconBytes);
        } catch (Exception ignored) {}

        // Stream output directly via BufferedWriter without closing the underlying stream
        java.io.OutputStream nonClosingOs = new java.io.FilterOutputStream(os) {
            @Override
            public void close() throws IOException {
                flush();
            }
        };
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(nonClosingOs, StandardCharsets.UTF_8))) {
            writer.write("<!DOCTYPE html>\n");
            writer.write("<html lang=\"en\">\n");
            writer.write("<head>\n");
            writer.write("  <meta charset=\"UTF-8\">\n");
            writer.write("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n");
            writer.write("  <title>" + ConverterService.escapeHtml(rawTitle) + " — Flip to Read</title>\n");
            if (!iconDataUri.isBlank()) {
                writer.write("  <link rel=\"icon\" type=\"image/png\" href=\"" + iconDataUri + "\">\n");
            }
            writer.write("  <style>\n");
            writer.write(getStandaloneCss());
            writer.write("  </style>\n");
            writer.write("</head>\n");
            writer.write("<body>\n");
            writer.write(getStandaloneHtmlBody(rawTitle, iconDataUri));

            // Embed PDF.js Worker Core directly so pdfjsWorker is globally defined in-memory
            writer.write("\n  <!-- Embedded PDF.js Worker Core -->\n");
            writer.write("  <script>\n");
            writer.write(pdfWorkerJs);
            writer.write("\n  </script>\n");

            // Embed PDF.js Library
            writer.write("\n  <!-- Embedded PDF.js Core Library -->\n");
            writer.write("  <script>\n");
            writer.write(pdfJs);
            writer.write("\n  </script>\n");

            // Embed PageFlip Library
            writer.write("\n  <!-- Embedded PageFlip Engine -->\n");
            writer.write("  <script>\n");
            writer.write(pageFlipJs);
            writer.write("\n  </script>\n");

            // Embedded PDF Base64 & Runner Script
            writer.write("\n  <!-- Application State & Dynamic PDF Flipbook Runner -->\n");
            writer.write("  <script>\n");
            writer.write("    const bookTitle = " + mapper.writeValueAsString(rawTitle) + ";\n");
            writer.write("    const pdfBase64 = \"");

            // Stream base64 encoded original PDF bytes in chunks of 3-byte multiples
            byte[] buffer = new byte[8192 * 3];
            Base64.Encoder encoder = Base64.getEncoder();
            try (InputStream is = Files.newInputStream(sourcePdf)) {
                int read;
                while ((read = is.read(buffer)) != -1) {
                    if (read == buffer.length) {
                        writer.write(encoder.encodeToString(buffer));
                    } else {
                        byte[] tail = new byte[read];
                        System.arraycopy(buffer, 0, tail, 0, read);
                        writer.write(encoder.encodeToString(tail));
                    }
                }
            }
            writer.write("\";\n\n");

            writer.write(getStandaloneRunnerJs());
            writer.write("  </script>\n");
            writer.write("</body>\n");
            writer.write("</html>\n");
            writer.flush();
        }
    }

    /**
     * Generates a truly 100% offline standalone ZIP containing index.html (self-contained embedded PDF)
     * and the original PDF for reference.
     */
    public void generateZip(String id, OutputStream os) throws IOException {
        Path bookDir = converterService.getBookDir(id);
        if (bookDir == null) {
            throw new IOException("Flipbook not found.");
        }
        Path sourcePdf = converterService.getSourcePdf(id);
        if (sourcePdf == null || !Files.exists(sourcePdf)) {
            throw new IOException("Original source PDF not found for flipbook: " + id);
        }

        Map<String, Object> meta = converterService.meta(id);
        String rawTitle = meta != null && meta.get("title") != null
                ? (String) meta.get("title")
                : "Flipbook";
        String folderName = ConverterService.sanitizeForFilename(rawTitle) + "_Flipbook";

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(os)) {
            // 1. index.html (the standalone self-contained HTML flipbook)
            java.util.zip.ZipEntry htmlEntry = new java.util.zip.ZipEntry(folderName + "/index.html");
            zos.putNextEntry(htmlEntry);
            generateStandaloneHtml(id, zos);
            zos.closeEntry();

            // 2. README.txt
            java.util.zip.ZipEntry readmeEntry = new java.util.zip.ZipEntry(folderName + "/README.txt");
            zos.putNextEntry(readmeEntry);
            String readme = buildReadme(rawTitle, meta != null && meta.get("pages") != null ? ((Number) meta.get("pages")).intValue() : 0);
            zos.write(readme.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 3. metadata.json
            if (meta != null) {
                java.util.zip.ZipEntry metaEntry = new java.util.zip.ZipEntry(folderName + "/metadata.json");
                zos.putNextEntry(metaEntry);
                zos.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta));
                zos.closeEntry();
            }

            // 4. Original source PDF
            String originalName = meta != null && meta.get("filename") != null ? (String) meta.get("filename") : "source.pdf";
            String safePdfName = ConverterService.sanitizeForFilename(originalName);
            if (!safePdfName.toLowerCase().endsWith(".pdf")) safePdfName += ".pdf";
            java.util.zip.ZipEntry pdfEntry = new java.util.zip.ZipEntry(folderName + "/" + safePdfName);
            zos.putNextEntry(pdfEntry);
            Files.copy(sourcePdf, zos);
            zos.closeEntry();

            zos.finish();
        }
    }

    private String buildReadme(String title, int pageCount) {
        return "========================================================\n"
             + " " + title + " - Offline Flipbook\n"
             + "========================================================\n\n"
             + "Total Pages: " + pageCount + "\n\n"
             + "HOW TO VIEW:\n"
             + "1. Double-click 'index.html' to open this flipbook in Chrome, Edge, or any browser.\n"
             + "2. No internet connection, server, or backend is required.\n"
             + "3. The complete original PDF and reading engine are 100% bundled.\n\n"
             + "NAVIGATION:\n"
             + "- Click page corners or edges to turn pages with 3D animation\n"
             + "- Use Left / Right arrow keys, Space, PageUp / PageDown\n"
             + "- Use on-screen Prev / Next buttons\n"
             + "- Click the page badge at the top to jump to any page\n"
             + "- Toggle Fullscreen mode for distraction-free reading\n\n"
             + "Created with Flip to Read.\n";
    }

    private byte[] loadResource(String path) throws IOException {
        ClassPathResource cpr = new ClassPathResource(path);
        if (cpr.exists()) {
            try (InputStream is = cpr.getInputStream()) {
                return is.readAllBytes();
            }
        }
        Path[] fallbacks = {
            Path.of("src/main/resources").resolve(path),
            Path.of("java-app/src/main/resources").resolve(path),
            Path.of("../java-app/src/main/resources").resolve(path)
        };
        for (Path fb : fallbacks) {
            if (Files.exists(fb)) {
                return Files.readAllBytes(fb);
            }
        }
        throw new IOException("Could not locate resource: " + path);
    }

    private String getStandaloneCss() {
        return """
    :root {
      --bg-main: #ffffff;
      --bg-gradient: radial-gradient(circle at center, #ffffff 0%, #ebf1f0 100%);
      --surface-card: #ffffff;
      --surface-hover: #f7faf9;
      --surface-toolbar: rgba(255, 255, 255, 0.96);
      --border-subtle: rgba(38, 70, 83, 0.12);
      --border-highlight: rgba(42, 157, 143, 0.45);
      --text-main: #264653;
      --text-muted: #527179;
      --text-dim: #7f999f;
      --accent: #E76F51;
      --accent-hover: #ef8267;
      --accent-light: rgba(231, 111, 81, 0.12);
      --accent-border: rgba(231, 111, 81, 0.35);
      --teal: #2A9D8F;
      --teal-hover: #23857a;
      --teal-light: rgba(42, 157, 143, 0.12);
      --teal-border: rgba(42, 157, 143, 0.35);
      --radius-sm: 8px;
      --radius-md: 12px;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    html, body {
      width: 100vw; height: 100vh; height: 100dvh; overflow: hidden;
      margin: 0; padding: 0;
      background: var(--bg-main); color: var(--text-main);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, Helvetica, Arial, sans-serif;
      user-select: none; -webkit-user-select: none;
    }
    .app {
      display: flex; flex-direction: column; width: 100vw; height: 100vh; height: 100dvh; overflow: hidden; position: relative;
    }
    header {
      height: 52px; padding: 0 16px; flex-shrink: 0;
      display: flex; align-items: center; justify-content: space-between; gap: 12px;
      background: var(--surface-toolbar); border-bottom: 1px solid var(--border-subtle);
      backdrop-filter: blur(12px); z-index: 50;
    }
    .title-area {
      display: flex; align-items: center; gap: 10px; min-width: 0; flex: 1;
    }
    .brand-icon-img {
      height: 28px; width: auto; display: block; flex-shrink: 0;
      background: transparent !important; border: none !important;
      border-radius: 0 !important; box-shadow: none !important;
      filter: drop-shadow(0 2px 6px rgba(42, 157, 143, 0.22));
    }
    .brand-text {
      font-size: 1.05rem; font-weight: 800; display: inline-flex;
      align-items: baseline; gap: 2px; flex-shrink: 0; white-space: nowrap;
    }
    .brand-flip { color: #264653; }
    .brand-to { color: #64748b; font-size: 0.85rem; font-weight: 600; margin: 0 1px; }
    .brand-read {
      background: linear-gradient(135deg, #F4A261 0%, #E76F51 100%);
      -webkit-background-clip: text; -webkit-text-fill-color: transparent;
    }
    .brand-divider {
      width: 1px; height: 18px; background: var(--border-subtle); margin: 0 4px; flex-shrink: 0;
    }
    .book-title {
      font-size: 0.95rem; font-weight: 600; color: var(--text-main);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 380px;
    }
    .header-center {
      display: flex; align-items: center; gap: 8px; flex-shrink: 0; position: relative;
    }
    .page-counter-btn {
      display: inline-flex; align-items: center; gap: 6px;
      background: rgba(38, 70, 83, 0.05); border: 1px solid var(--border-subtle);
      color: var(--text-main); padding: 5px 12px; border-radius: var(--radius-sm);
      font-size: 0.85rem; font-weight: 600; cursor: pointer; transition: all 0.15s ease;
    }
    .page-counter-btn:hover {
      background: rgba(38, 70, 83, 0.1); border-color: var(--teal-border);
    }
    .jump-popover {
      display: none; position: absolute; top: calc(100% + 8px); left: 50%;
      transform: translateX(-50%); background: #ffffff; border: 1px solid var(--teal-border);
      border-radius: var(--radius-md); padding: 10px 14px; box-shadow: 0 16px 36px rgba(38, 70, 83, 0.15);
      z-index: 100; white-space: nowrap; align-items: center; gap: 8px;
    }
    .jump-popover.active { display: flex; }
    .jump-input {
      width: 60px; background: #f7faf9; border: 1px solid var(--border-subtle);
      border-radius: 6px; padding: 5px 8px; color: var(--text-main);
      font-size: 0.88rem; text-align: center; outline: none;
    }
    .jump-input:focus { border-color: var(--teal); }
    .btn-jump {
      background: var(--accent); color: #ffffff; border: 0;
      border-radius: 6px; padding: 5px 12px; font-size: 0.82rem; font-weight: 600; cursor: pointer;
    }
    .btn-jump:hover { background: var(--accent-hover); }
    .header-right {
      display: flex; align-items: center; justify-content: flex-end; gap: 8px; flex: 1;
    }
    .btn-tool {
      display: inline-flex; align-items: center; gap: 6px;
      background: rgba(38, 70, 83, 0.05); border: 1px solid var(--border-subtle);
      color: var(--text-main); padding: 5px 12px; border-radius: var(--radius-sm);
      font-size: 0.82rem; font-weight: 500; cursor: pointer; transition: all 0.15s ease;
    }
    .btn-tool:hover {
      background: rgba(38, 70, 83, 0.1); border-color: rgba(38, 70, 83, 0.25);
    }
    .viewport {
      flex: 1; min-height: 0; width: 100%; position: relative;
      display: flex; align-items: center; justify-content: center; overflow: hidden;
      padding: 14px; background: var(--bg-gradient);
    }
    #book-stage {
      display: flex; align-items: center; justify-content: center;
      max-width: 100%; max-height: 100%; position: relative;
    }
    #book {
      box-shadow: 0 22px 50px -10px rgba(38, 70, 83, 0.25), 0 0 1px 1px rgba(38, 70, 83, 0.08);
      transition: opacity 0.3s ease;
    }
    footer {
      height: 52px; padding: 0 16px; flex-shrink: 0;
      display: flex; align-items: center; justify-content: center; gap: 16px;
      background: var(--surface-toolbar); border-top: 1px solid var(--border-subtle);
      backdrop-filter: blur(12px); z-index: 50;
    }
    .btn-ctrl {
      display: inline-flex; align-items: center; justify-content: center; gap: 6px;
      background: rgba(38, 70, 83, 0.05); border: 1px solid var(--border-subtle);
      color: var(--text-main); padding: 6px 16px; border-radius: var(--radius-sm);
      font-size: 0.86rem; font-weight: 600; cursor: pointer; transition: all 0.15s ease;
    }
    .btn-ctrl:hover:not(:disabled) {
      background: var(--teal); border-color: var(--teal); color: #ffffff;
    }
    .btn-ctrl:disabled { opacity: 0.3; cursor: not-allowed; }
    .bottom-counter {
      font-size: 0.88rem; font-weight: 600; color: var(--text-muted); min-width: 110px; text-align: center;
    }
    .loading-overlay {
      position: absolute; inset: 0; display: flex; flex-direction: column;
      align-items: center; justify-content: center; gap: 14px;
      background: rgba(255, 255, 255, 0.95); z-index: 80; transition: opacity 0.3s ease;
    }
    .spinner {
      width: 40px; height: 40px; border: 3px solid rgba(38, 70, 83, 0.15);
      border-top-color: var(--accent); border-radius: 50%; animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
    @media (max-width: 768px) {
      .book-title { max-width: 140px; font-size: 0.85rem; }
      .btn-tool span { display: none; }
      .viewport { padding: 6px; }
    }
    @media (max-width: 480px) {
      .book-title { display: none; }
    }
""";
    }

    private String getStandaloneHtmlBody(String title, String iconDataUri) {
        String escaped = ConverterService.escapeHtml(title);
        String iconHtml = !iconDataUri.isBlank()
                ? "        <img src=\"" + iconDataUri + "\" class=\"brand-icon-img\" alt=\"Flip to Read\">\n"
                : "";
        return "  <div class=\"app\">\n"
             + "    <header>\n"
             + "      <div class=\"title-area\">\n"
             + iconHtml
             + "        <span class=\"brand-text\"><span class=\"brand-flip\">Flip</span><span class=\"brand-to\">to</span><span class=\"brand-read\">Read</span></span>\n"
             + "        <div class=\"brand-divider\"></div>\n"
             + "        <span class=\"book-title\" title=\"" + escaped + "\">" + escaped + "</span>\n"
             + "      </div>\n"
             + "      <div class=\"header-center\">\n"
             + "        <button type=\"button\" class=\"page-counter-btn\" id=\"btn-page-badge\" title=\"Click to jump to a page\">\n"
             + "          <span id=\"counter-text\">Loading...</span>\n"
             + "          <svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M7 10l5 5 5-5z\"/></svg>\n"
             + "        </button>\n"
             + "        <div class=\"jump-popover\" id=\"jump-popover\">\n"
             + "          <label for=\"jump-input\" style=\"font-size: 0.8rem; color: var(--text-muted);\">Go to:</label>\n"
             + "          <input type=\"number\" id=\"jump-input\" class=\"jump-input\" min=\"1\" value=\"1\">\n"
             + "          <button type=\"button\" class=\"btn-jump\" id=\"btn-jump-go\">Go</button>\n"
             + "        </div>\n"
             + "      </div>\n"
             + "      <div class=\"header-right\">\n"
             + "        <button type=\"button\" class=\"btn-tool\" id=\"btn-full\" title=\"Toggle Fullscreen\">\n"
             + "          <svg width=\"15\" height=\"15\" viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z\"/></svg>\n"
             + "          <span id=\"full-text\">Fullscreen</span>\n"
             + "        </button>\n"
             + "      </div>\n"
             + "    </header>\n"
             + "    <main class=\"viewport\" id=\"viewport\">\n"
             + "      <div class=\"loading-overlay\" id=\"loading\">\n"
             + "        <div class=\"spinner\"></div>\n"
             + "        <span id=\"loading-label\">Loading flipbook...</span>\n"
             + "      </div>\n"
             + "      <div id=\"book-stage\">\n"
             + "        <div id=\"book\"></div>\n"
             + "      </div>\n"
             + "    </main>\n"
             + "    <footer>\n"
             + "      <button type=\"button\" class=\"btn-ctrl\" id=\"btn-prev\" aria-label=\"Previous Page\" disabled>\n"
             + "        <svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><line x1=\"19\" y1=\"12\" x2=\"5\" y2=\"12\"></line><polyline points=\"12 19 5 12 12 5\"></polyline></svg>\n"
             + "        <span>Prev</span>\n"
             + "      </button>\n"
             + "      <div class=\"bottom-counter\" id=\"bottom-counter\">Page ...</div>\n"
             + "      <button type=\"button\" class=\"btn-ctrl\" id=\"btn-next\" aria-label=\"Next Page\" disabled>\n"
             + "        <span>Next</span>\n"
             + "        <svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"></line><polyline points=\"12 5 19 12 12 19\"></polyline></svg>\n"
             + "      </button>\n"
             + "    </footer>\n"
             + "  </div>\n";
    }

    private String getStandaloneRunnerJs() {
        return """
    const PLACEHOLDER = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='800' height='1100'><rect width='100%' height='100%' fill='%23ffffff'/><circle cx='400' cy='550' r='18' fill='none' stroke='%232a9d8f' stroke-width='3' stroke-dasharray='60 30'/><text x='400' y='600' fill='%23527179' font-size='15' text-anchor='middle' font-family='sans-serif'>Rendering...</text></svg>";

    let pdfDoc = null;
    let flip = null;
    let totalPages = 0;
    let pageAspect = 0.707;
    const renderCache = new Map();
    const renderTasks = new Map();
    let renderQueue = Promise.resolve();

    const viewport = document.getElementById('viewport');
    const bookStage = document.getElementById('book-stage');
    const counterText = document.getElementById('counter-text');
    const bottomCounter = document.getElementById('bottom-counter');
    const btnPageBadge = document.getElementById('btn-page-badge');
    const jumpPopover = document.getElementById('jump-popover');
    const jumpInput = document.getElementById('jump-input');
    const btnJumpGo = document.getElementById('btn-jump-go');
    const btnPrev = document.getElementById('btn-prev');
    const btnNext = document.getElementById('btn-next');
    const btnFull = document.getElementById('btn-full');
    const fullText = document.getElementById('full-text');
    const loading = document.getElementById('loading');
    const loadingLabel = document.getElementById('loading-label');

    function base64ToUint8Array(base64) {
      const binaryString = atob(base64);
      const len = binaryString.length;
      const bytes = new Uint8Array(len);
      for (let i = 0; i < len; i++) {
        bytes[i] = binaryString.charCodeAt(i);
      }
      return bytes;
    }

    async function doRenderPage(pageNum, targetW, targetH) {
      const page = await pdfDoc.getPage(pageNum);
      const unscaledVp = page.getViewport({ scale: 1.0 });

      // High-resolution scale calculation: preserves crisp vector quality on all displays
      const dpr = Math.min(Math.max(window.devicePixelRatio || 1, 1), 3);
      const baseScale = targetW / unscaledVp.width;
      // Quality multiplier: 2.0x display resolution, ensuring minimum scale of 2.2 (~160-200 DPI minimum)
      let scale = Math.max(baseScale * dpr * 2.0, 2.2);

      let vp = page.getViewport({ scale: scale });
      const MAX_CANVAS_DIM = 4096;
      if (vp.width > MAX_CANVAS_DIM || vp.height > MAX_CANVAS_DIM) {
        const cap = Math.min(MAX_CANVAS_DIM / vp.width, MAX_CANVAS_DIM / vp.height);
        scale *= cap;
        vp = page.getViewport({ scale: scale });
      }

      const canvas = document.createElement('canvas');
      canvas.width = Math.round(vp.width);
      canvas.height = Math.round(vp.height);
      const ctx = canvas.getContext('2d', { alpha: false });
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = 'high';
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, canvas.width, canvas.height);

      await page.render({
        canvasContext: ctx,
        viewport: vp,
        intent: 'display'
      }).promise;

      console.log(`[Diagnostic] Page ${pageNum}: PDF page size: ${Math.round(unscaledVp.width)}x${Math.round(unscaledVp.height)}, viewport: ${Math.round(vp.width)}x${Math.round(vp.height)}, dpr: ${dpr}, render scale: ${scale.toFixed(2)}, canvas backing: ${canvas.width}x${canvas.height}, target display: ${Math.round(targetW)}x${Math.round(targetH)}`);

      return canvas;
    }

    function renderPage(pageNum, targetW, targetH) {
      if (renderCache.has(pageNum)) {
        return Promise.resolve(renderCache.get(pageNum));
      }
      if (renderTasks.has(pageNum)) {
        return renderTasks.get(pageNum);
      }

      const task = new Promise((resolve) => {
        renderQueue = renderQueue.then(async () => {
          if (renderCache.has(pageNum)) {
            resolve(renderCache.get(pageNum));
            return;
          }
          try {
            const canvas = await doRenderPage(pageNum, targetW, targetH);
            renderCache.set(pageNum, canvas);

            // Bounded LRU cache: keep closest 24 pages
            if (renderCache.size > 24) {
              const curr = (flip ? flip.getCurrentPageIndex() : 0) + 1;
              for (let [k, c] of renderCache.entries()) {
                if (Math.abs(k - curr) > 10) {
                  if (typeof HTMLCanvasElement !== 'undefined' && c instanceof HTMLCanvasElement) {
                    c.width = 1;
                    c.height = 1;
                  }
                  renderCache.delete(k);
                }
              }
            }
            resolve(canvas);
          } catch (err) {
            console.error('Failed to render page', pageNum, err);
            resolve(PLACEHOLDER);
          } finally {
            renderTasks.delete(pageNum);
          }
        });
      });

      renderTasks.set(pageNum, task);
      return task;
    }

    function applyPageImageToFlip(pageIndex, source) {
      const collection = flip ? flip.getPageCollection() : null;
      if (!collection || !collection.pages) return;
      const pageObj = collection.pages[pageIndex];
      if (!pageObj) return;

      if (typeof HTMLCanvasElement !== 'undefined' && source instanceof HTMLCanvasElement) {
        if (pageObj.image === source && pageObj.isLoad) return;
        pageObj.image = source;
        pageObj.isLoad = true;
        if (flip.getRender()) flip.getRender().drawFrame();
        return;
      }

      if (typeof source === 'string') {
        if (pageObj.image && pageObj.image.src === source && pageObj.isLoad) {
          return;
        }

        const img = new Image();
        img.onload = () => {
          pageObj.image = img;
          pageObj.isLoad = true;
          if (flip.getRender()) flip.getRender().drawFrame();
        };
        img.onerror = () => {
          console.error('Image load failed for page index', pageIndex);
          pageObj.isLoad = true;
        };
        img.src = source;
        if (img.complete && img.naturalWidth > 0) {
          pageObj.image = img;
          pageObj.isLoad = true;
          if (flip.getRender()) flip.getRender().drawFrame();
        }
      }
    }

    function getSpreadPages(pageIndex) {
      if (!flip || !flip.getPageCollection()) return [pageIndex + 1];
      const col = flip.getPageCollection();
      const spIdx = col.getSpreadIndexByPage(pageIndex);
      if (spIdx !== null && col.getSpread && col.getSpread()[spIdx]) {
        return col.getSpread()[spIdx].map(i => i + 1);
      }
      return [pageIndex + 1];
    }

    async function warmPagesAround(pageIndex, targetW, targetH) {
      const pagesToWarm = new Set();

      // Current spread
      getSpreadPages(pageIndex).forEach(p => pagesToWarm.add(p));

      // Next spread (+1)
      if (pageIndex + 2 < totalPages) {
        getSpreadPages(pageIndex + 2).forEach(p => pagesToWarm.add(p));
      }
      // Previous spread (-1)
      if (pageIndex - 1 >= 0) {
        getSpreadPages(pageIndex - 1).forEach(p => pagesToWarm.add(p));
      }
      // Spread (+2)
      if (pageIndex + 4 < totalPages) {
        getSpreadPages(pageIndex + 4).forEach(p => pagesToWarm.add(p));
      }

      for (const pNum of pagesToWarm) {
        if (pNum >= 1 && pNum <= totalPages) {
          renderPage(pNum, targetW, targetH).then(dataUrl => {
            applyPageImageToFlip(pNum - 1, dataUrl);
          });
        }
      }
    }

    function calcDimensions() {
      const vw = Math.max(280, viewport.clientWidth - 32);
      const vh = Math.max(260, viewport.clientHeight - 24);
      const isMobile = window.innerWidth < 768 || vw < vh * 1.05;

      let pageW, pageH;
      if (isMobile) {
        let targetW = Math.min(vw, 680);
        let targetH = targetW / pageAspect;
        if (targetH > vh) {
          targetH = vh;
          targetW = targetH * pageAspect;
        }
        pageW = Math.round(targetW);
        pageH = Math.round(targetH);
      } else {
        let targetSpreadW = Math.min(vw, 1500);
        let targetPageW = targetSpreadW / 2;
        let targetH = targetPageW / pageAspect;
        if (targetH > vh) {
          targetH = vh;
          targetPageW = targetH * pageAspect;
        }
        pageW = Math.round(targetPageW);
        pageH = Math.round(targetH);
      }

      return {
        width: Math.max(160, pageW),
        height: Math.max(220, pageH),
        isMobile: isMobile
      };
    }

    function updateControls() {
      if (!flip) return;
      const idx = flip.getCurrentPageIndex() || 0;
      const curr = idx + 1;
      const label = 'Page ' + curr + ' / ' + totalPages;
      counterText.textContent = label;
      bottomCounter.textContent = label;
      jumpInput.value = curr;
      btnPrev.disabled = idx <= 0;
      btnNext.disabled = idx >= totalPages - 1;

      const dims = calcDimensions();
      warmPagesAround(idx, dims.width, dims.height);
    }

    async function initFlipbook(startIdx = 0) {
      const dims = calcDimensions();
      bookStage.innerHTML = '<div id="book"></div>';
      const bookEl = document.getElementById('book');

      flip = new St.PageFlip(bookEl, {
        width: dims.width,
        height: dims.height,
        size: 'fixed',
        showCover: true,
        usePortrait: dims.isMobile,
        startPage: Math.min(startIdx, totalPages - 1),
        maxShadowOpacity: 0.5,
        mobileScrollSupport: false
      });

      // Populate array of placeholders for all pages
      const initialPages = new Array(totalPages).fill(PLACEHOLDER);

      // Pre-render starting spread immediately so first view is instant
      const startSpread = getSpreadPages(startIdx);
      await Promise.all(startSpread.map(async (p) => {
        const dataUrl = await renderPage(p, dims.width, dims.height);
        initialPages[p - 1] = dataUrl;
      }));

      flip.loadFromImages(initialPages);

      flip.on('flip', updateControls);

      flip.on('init', () => {
        updateControls();
        loading.style.opacity = '0';
        setTimeout(() => { loading.style.display = 'none'; }, 300);
      });
    }

    btnPrev.onclick = () => {
      if (!flip) return;
      const idx = flip.getCurrentPageIndex() || 0;
      if (idx > 0) {
        const dims = calcDimensions();
        const prevPages = getSpreadPages(Math.max(0, idx - 2));
        prevPages.forEach(p => renderPage(p, dims.width, dims.height).then(dataUrl => applyPageImageToFlip(p - 1, dataUrl)));
        flip.flipPrev();
      }
    };

    btnNext.onclick = () => {
      if (!flip) return;
      const idx = flip.getCurrentPageIndex() || 0;
      if (idx < totalPages - 1) {
        const dims = calcDimensions();
        const nextPages = getSpreadPages(Math.min(totalPages - 1, idx + 2));
        nextPages.forEach(p => renderPage(p, dims.width, dims.height).then(dataUrl => applyPageImageToFlip(p - 1, dataUrl)));
        flip.flipNext();
      }
    };

    btnPageBadge.onclick = (e) => {
      e.stopPropagation();
      jumpPopover.classList.toggle('active');
      if (jumpPopover.classList.contains('active')) {
        jumpInput.focus();
        jumpInput.select();
      }
    };

    document.addEventListener('click', () => {
      jumpPopover.classList.remove('active');
    });

    jumpPopover.onclick = (e) => {
      e.stopPropagation();
    };

    async function performJump() {
      const target = parseInt(jumpInput.value, 10);
      if (isNaN(target) || target < 1 || target > totalPages) {
        jumpInput.style.borderColor = 'var(--accent)';
        setTimeout(() => { jumpInput.style.borderColor = ''; }, 1000);
        return;
      }
      jumpPopover.classList.remove('active');
      const targetIdx = target - 1;
      const dims = calcDimensions();

      // Find all pages on target spread
      const targetPages = getSpreadPages(targetIdx);

      // Pre-render destination spread so it is ready BEFORE flipping
      await Promise.all(targetPages.map(p => renderPage(p, dims.width, dims.height).then(dataUrl => {
        applyPageImageToFlip(p - 1, dataUrl);
      })));

      if (flip) {
        flip.flip(targetIdx);
      }
    }

    btnJumpGo.onclick = performJump;
    jumpInput.onkeydown = (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        performJump();
      }
    };

    window.addEventListener('keydown', (e) => {
      if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA')) {
        return;
      }
      if (e.key === 'ArrowLeft' || e.key === 'PageUp') {
        if (flip) {
          const idx = flip.getCurrentPageIndex() || 0;
          if (idx > 0) {
            const dims = calcDimensions();
            const prevPages = getSpreadPages(Math.max(0, idx - 2));
            prevPages.forEach(p => renderPage(p, dims.width, dims.height).then(dataUrl => applyPageImageToFlip(p - 1, dataUrl)));
            flip.flipPrev();
          }
        }
      } else if (e.key === 'ArrowRight' || e.key === 'PageDown' || e.key === ' ') {
        if (flip) {
          const idx = flip.getCurrentPageIndex() || 0;
          if (idx < totalPages - 1) {
            const dims = calcDimensions();
            const nextPages = getSpreadPages(Math.min(totalPages - 1, idx + 2));
            nextPages.forEach(p => renderPage(p, dims.width, dims.height).then(dataUrl => applyPageImageToFlip(p - 1, dataUrl)));
            flip.flipNext();
          }
        }
      } else if (e.key === 'Home') {
        if (flip) {
          jumpInput.value = 1;
          performJump();
        }
      } else if (e.key === 'End') {
        if (flip) {
          jumpInput.value = totalPages;
          performJump();
        }
      }
    });

    btnFull.onclick = () => {
      if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(() => {});
      } else {
        document.exitFullscreen().catch(() => {});
      }
    };

    document.addEventListener('fullscreenchange', () => {
      const isFull = !!document.fullscreenElement;
      fullText.textContent = isFull ? 'Exit Full' : 'Fullscreen';
    });

    let resizeTimer = null;
    window.addEventListener('resize', () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => {
        if (!flip) return;
        const curr = flip.getCurrentPageIndex() || 0;
        initFlipbook(curr);
      }, 200);
    });

    async function startApplication() {
      try {
        loadingLabel.textContent = 'Decoding PDF document...';
        const pdfData = base64ToUint8Array(pdfBase64);

        // Configure PDF.js worker: when under file:///, disable worker thread to avoid origin 'null' restrictions
        const isFileOrigin = window.location.protocol === 'file:';
        if (isFileOrigin && window.pdfjsLib && window.pdfjsLib.PDFWorkerUtil) {
          window.pdfjsLib.PDFWorkerUtil.isWorkerDisabled = true;
          pdfjsLib.GlobalWorkerOptions.workerSrc = '';
        }
        if (window.pdfjsLib) {
          pdfjsLib.verbosity = 0;
        }

        loadingLabel.textContent = 'Reading pages...';
        const loadingTask = pdfjsLib.getDocument({
          data: pdfData,
          isEvalSupported: true,
          useSystemFonts: true
        });
        pdfDoc = await loadingTask.promise;
        totalPages = pdfDoc.numPages;

        jumpInput.max = totalPages;

        const firstPage = await pdfDoc.getPage(1);
        const unscaled = firstPage.getViewport({ scale: 1.0 });
        pageAspect = unscaled.width / unscaled.height;

        loadingLabel.textContent = 'Rendering flipbook...';
        await initFlipbook(0);
      } catch (err) {
        console.error('Initialization error:', err);
        loading.innerHTML = `
          <div style="background: #ffffff; padding: 24px 32px; border-radius: 12px; box-shadow: 0 10px 30px rgba(0,0,0,0.15); text-align: center; max-width: 420px;">
            <div style="color: #E76F51; font-size: 28px; margin-bottom: 8px;">&#9888;</div>
            <div style="font-weight: 700; font-size: 1.1rem; color: #264653; margin-bottom: 8px;">Unable to Load Flipbook</div>
            <div style="color: #527179; font-size: 0.9rem; margin-bottom: 16px;">${err.message || 'An error occurred while loading the PDF document.'}</div>
            <button onclick="window.location.reload()" style="background: #2A9D8F; color: #fff; border: none; padding: 8px 18px; border-radius: 8px; font-weight: 600; cursor: pointer;">Reload Book</button>
          </div>
        `;
      }
    }

    window.addEventListener('DOMContentLoaded', startApplication);
""";
    }
}
