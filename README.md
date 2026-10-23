# Flip to Read — PDF Flipbook Platform

> **Turn PDFs into an immersive reading experience.**

Flip to Read transforms static PDF documents into interactive 3D flipbooks with realistic physics-based page turns, dual-page desktop spreads, mobile single-page views, reading position memory, standalone single-file HTML downloads, and an official companion browser extension for Chrome and Edge.

---

## Architecture Overview

```
                      FLIP TO READ PLATFORM
                                │
        ┌───────────────────────┼───────────────────────┐
        │                       │                       │
     WEBSITE                EXTENSION             SHARED ENGINE
(Vanilla HTML/CSS/JS)     (Manifest V3)       (St.PageFlip / CSS Tokens)
        │                       │                       │
        └───────────────────────┼───────────────────────┘
                                │
                        SPRING BOOT REST API
                         (Java 21 / Maven)
                                │
                    ┌───────────┴───────────┐
                    │                       │
               PDF STORAGE             CONVERTER
              (source.pdf)         (PyMuPDF / fitz)
```

### Component Roles
- **Website Platform (`java-app/src/main/resources/static`)**: The main web product. Features a modern landing page, 6-step upload flow, recent books shelf with position memory, interactive reader, and triple download options.
- **Browser Extension (`extension/`)**: Built on Chrome Manifest V3 using TypeScript. Detects online PDFs in current tabs, allows 1-click flipbook conversion, converts local files, remembers reading bookmarks, and runs a fully integrated flipbook reader.
- **Backend Service (`java-app/`)**: Spring Boot 3.2.5 REST API running on Java 21. Manages book storage, executes PyMuPDF rasterization, handles SSRF-protected URL fetching, enforces intentional CORS policies, and generates standalone offline flipbooks.
- **PDF Processing Service (`python-service/convert.py`)**: PyMuPDF engine producing high-fidelity page textures, calculating page count, and generating dimension metadata.

---

## Prerequisites

- **Java Development Kit (JDK)**: Java 21 recommended (minimum Java 17).
- **Maven**: Version 3.8+ (or use the included `mvnw.cmd` / `./mvnw`).
- **Python**: Version 3.8+ with `pip`.
- **Node.js**: Version 18+ (Node 24 recommended) and `npm`.
- **Browser**: Google Chrome, Microsoft Edge, Brave, or any Chromium browser.

---

## Quick Start & Setup

### 1. Python Environment Setup
Install PyMuPDF into your active Python environment:
```bash
pip install -r python-service/requirements.txt
```

### 2. Backend & Website Setup
Run the Spring Boot application from the project root:
```bash
# On Windows:
.\mvnw.cmd spring-boot:run

# On Linux / macOS:
./mvnw spring-boot:run
```
The website will be live at **http://localhost:8080**.

### 3. Browser Extension Setup
In a new terminal, build the extension:
```bash
cd extension
npm install
npm run build
```
The bundled, ready-to-load extension will be generated in `extension/dist/`.

#### Installing the Extension in Chrome:
1. Open `chrome://extensions` in Chrome.
2. Toggle on **Developer mode** in the top-right corner.
3. Click **Load unpacked** in the top-left toolbar.
4. Select the `extension/dist` folder.
5. Pin **Flip to Read** to your extensions bar.

#### Installing the Extension in Microsoft Edge:
1. Open `edge://extensions` in Edge.
2. Enable **Developer mode** in the left sidebar.
3. Click **Load unpacked** and select `extension/dist`.

> For deep-dive technical details on Manifest V3 permissions, service worker communication, passive PDF detection, and local storage schema, see the [Extension Architecture Documentation](docs/extension-architecture.md).

---

## Features

### 📖 Immersive Flipbook Reader
- **Zero Scroll Viewport**: Fits inside 100vh with no vertical body scrolling.
- **Two-Page Spread & Mobile View**: Automatically switches to dual-page mode on desktop and single-page view on mobile/portrait screens.
- **Realistic Physics Turns**: Page curl, center spine depth, and dynamic shadows powered by `St.PageFlip`.
- **Full Navigation**: Keyboard navigation (Arrow keys, Home, End), touch swipe, and a quick-jump popover ("Go to page X").
- **Reading Position Resumption**: Remembers your exact page across sessions. Reopening a book prompts *"Continue from page X?"*.

### 💾 Triple Download Options
- **Download PDF**: Original source PDF preserved on disk and downloaded via standard attachment.
- **Download Flipbook (.html)**: True single-file standalone interactive flipbook (`<book>_Flipbook.html`). Embeds vector PDF bytes once and lazily renders pages on demand via bundled offline PDF.js. Requires zero server, zero CDN, and works on `file:///`.
- **Download Offline Flipbook (.zip)**: Complete self-contained package folder with all rendered page textures and standalone reader.

### 🧩 Companion Browser Extension
- **On-Demand PDF Detection**: Detects direct PDF URLs and embedded `<embed>` / `<iframe>` PDFs in the active tab.
- **1-Click Open as Flipbook**: Converts online PDFs directly through the Flip to Read backend.
- **Upload Local PDF**: Directly select and convert local PDFs from the extension popup.
- **Context Menu Integration**: Right-click any PDF link to select *"Open PDF with Flip to Read"*.
- **Integrated Extension Reader**: Includes the complete Flipbook reader inside the extension, with an **[ Open in Flip to Read ]** button to seamlessly transition to the web platform.
- **Reading Position History**: Syncs book titles, total pages, and reading progress in `chrome.storage.local`.

---

## REST API Reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/upload` (or `/api/books/upload`) | Upload PDF (`multipart/form-data`) -> returns book ID and metadata |
| `POST` | `/api/upload-url` | Convert online PDF from URL (`{"url": "https://..."}`) with SSRF protection |
| `GET` | `/api/books/{id}` (or `/api/books/{id}/metadata`) | Get book metadata (pages, dimensions, title, filename) |
| `GET` | `/api/books/{id}/pages` | Get list of all rendered page image URLs and total page count |
| `GET` | `/books/{id}/page-N.jpg` | Serve individual page image |
| `GET` | `/api/books/{id}/pdf` | Download preserved original PDF |
| `GET` | `/api/books/{id}/flipbook` | Download single-file standalone interactive HTML flipbook |
| `GET` | `/api/books/{id}/download` | Download standalone offline flipbook ZIP package |
| `DELETE`| `/api/books/{id}` | Manually delete flipbook and cleanup directory |

---

## Security & Privacy Considerations

1. **SSRF Protection (`/api/upload-url`)**:
   - Only `http` and `https` schemes are accepted.
   - Hostnames are resolved to IP addresses and verified against loopback (`127.0.0.1`, `::1`), private networks (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), link-local metadata addresses (`169.254.0.0/16`), and broadcast addresses.
   - Redirects (up to 3 hops) re-validate IP address safety on each hop.
   - Maximum download size is capped at 50 MB with stream termination.
   - Initial bytes are verified for the `%PDF` signature.

2. **CORS Configuration**:
   - Configured in `WebConfig.java` to explicitly permit requests from `chrome-extension://*`, `http://localhost:*`, and `http://127.0.0.1:*`. Arbitrary wildcard origin access is restricted.

3. **Extension Privacy Ethics**:
   - No background browsing surveillance or tracking.
   - No telemetry or external analytics.
   - PDFs are only processed on explicit user action ("Open as Flipbook" or "Upload Local PDF").
   - Minimal permissions: `["storage", "activeTab", "tabs", "contextMenus"]`. No `<all_urls>` permission.

---

## Standalone Flipbook Architecture

The standalone flipbook (`/api/books/{id}/flipbook`) uses a vector-based offline architecture:
- Embeds original PDF bytes once as Base64.
- Bundles complete offline `pdf.js` and `pdf.worker.js`.
- Bundles `page-flip.browser.js` engine and brand assets.
- Lazily renders canvas page textures as the user flips through the book.
- Operates 100% offline via `file:///` without a local web server or internet connection.
- Preserves actual page counts for large documents (e.g., 220 pages).

---

## Known Limitations

- **Backend Requirement for Conversion**: Converting a new PDF requires the Spring Boot backend and Python environment. Once converted, standalone HTML or offline ZIP packages function 100% offline.
- **Maximum File Size**: Default limit is 50 MB (configurable in `application.properties` via `spring.servlet.multipart.max-file-size`).
- **Encrypted / Password-Protected PDFs**: Password-protected PDFs must be unlocked prior to uploading.

---

## License

Flip to Read &bull; Open-source PDF publishing and reading platform.
