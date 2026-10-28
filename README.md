<p align="center">
  <img src="java-app/src/main/resources/static/logo-icon.png" width="96" height="74" alt="Flip to Read Logo" />
</p>

<h1 align="center">FLIP TO READ</h1>

<p align="center">
  <strong>Turn PDFs into an immersive reading experience.</strong>
</p>

<p align="center">
  Flip to Read transforms static PDF documents into interactive 3D flipbooks featuring realistic physics-based page curls, dual-page desktop spreads, mobile single-page views, high-DPI vector canvas rendering, reading position memory, self-contained standalone HTML downloads, and an official Manifest V3 companion browser extension for Chrome and Edge.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=for-the-badge" alt="Apache 2.0 License" /></a>
  <img src="https://img.shields.io/badge/Java-17%20%2F%2021-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 17/21" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.2.5-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot 3.2.5" />
  <img src="https://img.shields.io/badge/Python-3.8+-3776AB?style=for-the-badge&logo=python&logoColor=white" alt="Python 3.8+" />
  <img src="https://img.shields.io/badge/TypeScript-5.4-3178C6?style=for-the-badge&logo=typescript&logoColor=white" alt="TypeScript 5.4" />
  <img src="https://img.shields.io/badge/Chrome_Extension-Manifest_V3-4285F4?style=for-the-badge&logo=googlechrome&logoColor=white" alt="Manifest V3" />
</p>

---

## What is Flip to Read?

Traditional PDF viewers rely on flat, continuous vertical scrolling that treats books, magazines, research papers, and pitch decks like endless web pages. 

**Flip to Read** reintroduces the tactile feel of physical reading to digital media. Built on top of a Spring Boot backend and PyMuPDF metadata & rasterization engine, it turns any uploaded file or remote PDF link into a realistic 3D book. 

The production reader uses the original PDF with **PDF.js** and high-DPI canvas rendering to ensure ultra-sharp vector clarity on Retina and 4K displays. Readers can flip through spreads with natural curl physics, navigate seamlessly via keyboard shortcuts or touch gestures, automatically resume reading from their last position across sessions, export fully self-contained offline HTML flipbooks that run on `file:///` without servers or CDNs, and convert online documents instantly with the companion browser extension.

---

## Why Flip to Read?

| The Traditional PDF Experience | The Flip to Read Experience |
|---|---|
| Rigid, endless vertical scrolling with no spatial awareness | Natural 3D page curl, center spine depth, and realistic lighting shadows |
| Blurry fixed-resolution images or awkward zooming | Direct PDF.js vector rendering to high-DPI canvas for crisp typography |
| Awkward zooming on mobile and awkward margins on desktop | Adaptive layout: dual-page spread on desktop, single-page view on mobile |
| Distracting reader toolbars and fragmented UI controls | Zero-scroll viewport strictly fitted inside 100vh for distraction-free reading |
| Requires internet connections, specialized readers, or heavy apps | True single-file standalone HTML (`file:///`) with zero external dependencies |
| Clunky copy-paste URLs and manual file saving | 1-click conversion directly from your browser via Chrome/Edge extension |

---

## Core Features

### 📄 PDF Conversion Engine
- **Local PDF Upload**: Multipart file ingestion supporting documents up to 50 MB (`/api/upload`).
- **Remote PDF URL Fetching**: Direct conversion of online PDFs with comprehensive SSRF protection and multi-hop validation (`/api/upload-url`).
- **Magic-Byte Signature Validation**: Enforces `%PDF` header inspection on all incoming streams prior to disk allocation.
- **High-Fidelity Metadata & Rasterization**: PyMuPDF extraction engine generating exact aspect ratio metadata, page dimensions, and fallback raster textures.

### 📖 Interactive Reader
- **High-DPI Vector Canvas Rendering**: Renders original PDF vectors on demand using PDF.js scaled to the device pixel ratio (DPR), ensuring tack-sharp text on HiDPI and 4K monitors.
- **Physics-Based Page Turns**: Powered by `St.PageFlip` with dynamic lighting, shadow depth, and realistic corner curl.
- **Adaptive Layout**: Automatically switches between dual-page book spreads on landscape displays and single-page view on mobile/portrait screens.
- **Distraction-Free Zero Scroll**: Viewport locks strictly to 100vh with zero vertical body scrolling.
- **Full Navigation**: Arrow keys (`←` / `→`), `Home` / `End`, mouse drag, touch swipe, and a quick-jump popover (*"Go to page X"*).
- **Reading Position Resumption**: Automatically saves current page progress to browser storage and prompts *"Continue from page X?"* upon reopening.
- **Fullscreen Mode**: One-click distraction-free reading toggle.

### 💾 Triple Download Options
- **Download Original PDF**: Access the pristine uploaded source document directly from storage (`/api/books/{id}/pdf`).
- **Standalone Single-File Flipbook (`.html`)**: Downloads a self-contained `<book>_Flipbook.html` embedding the vector PDF data, bundled offline PDF.js engine, and PageFlip library. Double-click to read anywhere offline (`/api/books/{id}/flipbook`).
- **Offline Flipbook Package (`.zip`)**: Exports a complete archive containing pre-rendered page textures, offline assets, and standalone web reader (`/api/books/{id}/download`).

### 🧩 Companion Browser Extension
- **Manifest V3 Architecture**: Modern, secure, and privacy-compliant extension for Google Chrome and Microsoft Edge.
- **On-Demand Passive PDF Detection**: Detects direct PDF URLs and embedded `<embed>`, `<iframe>`, or `<object>` elements without background surveillance.
- **1-Click Conversion**: Converts the active tab's PDF directly into a flipbook.
- **Local PDF Upload in Popup**: Convert local files directly from the extension interface.
- **Context Menu Integration**: Right-click any PDF link to select *"Open PDF with Flip to Read"*.
- **Integrated Extension Reader**: Read flipbooks inside the extension or click **[ Open in Flip to Read ]** to jump into the web platform.
- **Reading History**: Stores recent books and last read pages locally in `chrome.storage.local`.

### 🛡️ Security & Privacy
- **SSRF Protection**: Resolves remote hostnames against loopback, private IPv4/IPv6 CIDR blocks, and link-local addresses.
- **Stream Termination**: Capped download sizes (50 MB) protect against disk exhaustion attacks.
- **Strict CORS Policy**: Restricted strictly to authorized extension IDs, `localhost`, and `127.0.0.1`, with optional production domain configuration.
- **Zero Surveillance**: No background browsing tracking, no analytics, and no telemetry.

---

## Product Architecture

```text
                             FLIP TO READ PLATFORM
                                       │
            ┌──────────────────────────┼──────────────────────────┐
            │                          │                          │
      WEBSITE CLIENT           BROWSER EXTENSION            SHARED ENGINE
   (HTML5 / CSS3 / JS)           (Manifest V3)         (St.PageFlip / Tokens)
            │                          │                          │
            └──────────────────────────┼──────────────────────────┘
                                       │
                              SPRING BOOT REST API
                           (Java 17/21 / Maven 3.8+)
                                       │
                      ┌────────────────┴────────────────┐
                      │                                 │
                 PDF STORAGE                   CONVERSION SERVICE
                 (source.pdf)                   (PyMuPDF / fitz)
```

### Standalone Single-File Flipbook Architecture

Unlike legacy solutions that convert every page into a Base64-encoded JPEG (ballooning file sizes to hundreds of megabytes), Flip to Read uses a **vector-embedded offline architecture**:

```text
Original PDF Source
        ↓
Embed raw PDF bytes once (Base64)
        ↓
Bundled offline PDF.js & Worker (Blob URL)
        ↓
On-demand / lazy canvas rendering with LRU cache
        ↓
St.PageFlip 3D Engine
        ↓
Single self-contained HTML file (opens directly on file:///)
```

- **Vector Precision**: Preserves original PDF vector typography at any magnification.
- **Compact File Size**: Embedding raw PDF bytes once produces standalone files that are 80-90% smaller than multi-image Base64 bundles.
- **Lazy Rendering**: Only active and adjacent pages are rendered into canvas memory, preventing memory exhaustion on documents with hundreds of pages.
- **Zero Network Calls**: Runs 100% offline via `file:///` without local web servers or external CDNs.

---

## Technology Stack

| Layer | Technology | Purpose |
|---|---|---|
| **Backend** | Java 17 / 21 | High-performance core runtime |
| **Framework** | Spring Boot 3.2.5 | REST API, SSRF validation, file streaming, scheduled cleanup |
| **Build Tool** | Maven 3.8+ (Multi-module) | Lifecycle management, packaging, dependency caching |
| **PDF Processing** | Python 3.8+ & PyMuPDF (`fitz`) | High-speed PDF dimension extraction and fallback rasterization |
| **Web Frontend** | HTML5, CSS3, Vanilla JavaScript | Landing page, multi-step upload flow, reader UI |
| **Flipbook Engine** | `St.PageFlip` | Physics-based 3D page curl simulation |
| **PDF Rendering** | PDF.js (v3.11.174) | High-DPI client-side vector PDF rendering for web & standalone reader |
| **Extension** | TypeScript 5.4 + Manifest V3 | Companion browser extension for Chrome and Edge |
| **Extension Bundler** | `esbuild` | Fast TypeScript compilation and asset distribution |
| **Browser Storage** | Chrome Storage API / localStorage | Local bookmarks, reading history, and position persistence |

---

## Project Structure

```text
pdf-flipbook/
├── pom.xml                             # Aggregator Maven POM (multi-module)
├── mvnw / mvnw.cmd                     # Maven wrapper scripts (Linux & Windows)
├── Dockerfile                          # Multi-stage production container build
├── docker-compose.yml                  # Production compose orchestration
├── LICENSE                             # Apache License 2.0
├── README.md                           # Project documentation
├── java-app/                           # Spring Boot backend application
│   ├── pom.xml                         # Backend Maven build configuration
│   ├── mvnw / mvnw.cmd                 # Module-level Maven wrappers
│   ├── src/main/java/com/flipbook/
│   │   ├── BookController.java         # REST endpoints (upload, convert, download, pages)
│   │   ├── ConverterService.java       # Storage management, PyMuPDF bridge, SSRF guard, cleaner
│   │   ├── StandaloneFlipbookService.java # Single-file vector HTML generator & ZIP builder
│   │   ├── WebConfig.java              # CORS configuration & resource handlers
│   │   └── FlipbookApplication.java    # Spring Boot entry point
│   └── src/main/resources/
│       ├── application.properties      # Server port, upload limits, Python command
│       └── static/                     # Web platform (index.html, flipbook.html, CSS, brand assets)
├── extension/                          # Companion browser extension (Manifest V3)
│   ├── manifest.json                   # Chrome/Edge Manifest V3 declaration
│   ├── package.json                    # Extension dependencies and build scripts
│   ├── tsconfig.json                   # TypeScript configuration
│   ├── build.js                        # esbuild bundling script
│   ├── src/
│   │   ├── background/service-worker.ts # Context menu & tab message routing
│   │   ├── content/pdf-detector.ts      # Passive on-demand PDF detector
│   │   ├── popup/                       # Extension popup UI & controller
│   │   ├── reader/                      # Embedded extension flipbook reader
│   │   ├── options/                     # Extension settings & privacy configuration
│   │   └── shared/                      # Storage schema, REST API client, shared types
│   └── public/icons/                   # Multi-resolution extension icons (16, 32, 48, 128px)
├── python-service/
│   ├── convert.py                      # PyMuPDF metadata & rasterization engine
│   └── requirements.txt                # Python dependencies (PyMuPDF)
├── docs/
│   └── extension-architecture.md       # Browser extension architecture deep-dive
└── scripts/
    └── generate_extension_icons.py     # Icon generation utility
```

---

## Getting Started

### Prerequisites

- **Java Development Kit (JDK)**: Java 17 or Java 21 (Eclipse Temurin recommended).
- **Maven**: Version 3.8+ (or use the included `mvnw.cmd` / `./mvnw`).
- **Python**: Version 3.8+ with `pip`.
- **Node.js**: Version 18+ (Node 20+ recommended) and `npm`.
- **Browser**: Google Chrome, Microsoft Edge, Brave, or any Chromium-based browser.

---

### Step 1: Clone the Repository

```bash
git clone https://github.com/Nish0178/flip-to-read.git
cd flip-to-read
```

---

### Step 2: Python Environment Setup

Install PyMuPDF into your active Python environment:

```bash
pip install -r python-service/requirements.txt
```

---

### Step 3: Run the Backend & Website

Compile and package the application using the Maven wrapper:

```bash
# On Windows:
.\mvnw.cmd clean package
java -jar java-app/target/pdf-flipbook-1.0.0.jar

# On Linux / macOS:
./mvnw clean package
java -jar java-app/target/pdf-flipbook-1.0.0.jar
```

Alternatively, run directly in development mode:

```bash
# On Windows:
.\mvnw.cmd spring-boot:run -pl java-app

# On Linux / macOS:
./mvnw spring-boot:run -pl java-app
```

Once started, the web application is live at **http://localhost:8080**.

---

### Docker Deployment (Containerized Run)

Flip to Read provides a production-grade multi-stage `Dockerfile` and `docker-compose.yml` packaging Java 21, Spring Boot, Python 3, PyMuPDF, and the bundled frontend into a single self-contained, non-root runtime container.

#### Prerequisites
- **Docker Engine** (20.10+) or **Docker Desktop**
- **Docker Compose** (v2+)

#### Option A: Docker Compose (Quickest)
```bash
# Start container in detached mode
docker compose up -d

# View live logs
docker compose logs -f

# Stop container
docker compose down
```

#### Option B: Docker CLI
```bash
# Build the production image
docker build -t flip-to-read .

# Run the container
docker run -d \
  --name flip-to-read \
  -p 8080:8080 \
  -e PORT=8080 \
  -e APP_DPI=180 \
  -e MAX_FILE_SIZE=50MB \
  flip-to-read
```

#### Configurable Environment Variables

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | HTTP port on which Spring Boot listens |
| `PYTHON_CMD` | `python3` (container) / `python` (local) | Python executable name or path |
| `PYTHON_SCRIPT` | `python-service/convert.py` | Path to PyMuPDF conversion script |
| `STORAGE_DIR` | `/app/storage/books` (container) / `storage/books` (local) | Path to temporary writable book storage directory |
| `APP_DPI` | `180` | Fallback PDF page rendering raster resolution (DPI) |
| `APP_IMAGE_FORMAT` | `jpg` | Fallback raster page format (`jpg` or `png`) |
| `MAX_FILE_SIZE` | `50MB` | Maximum allowed individual upload file size |
| `MAX_REQUEST_SIZE` | `50MB` | Maximum allowed multipart request size |
| `CORS_ALLOWED_ORIGINS` | *(empty)* | Optional comma-separated additional CORS origins (e.g. `https://yourdomain.com`) |

#### Temporary Storage Behavior
- Uploaded PDFs and generated assets are written to `STORAGE_DIR` (`storage/books`).
- The built-in Spring Boot scheduled worker automatically purges books older than 15 minutes every 60 seconds (`@Scheduled(fixedDelay = 60000)`).
- No external database or persistent cloud volume is required.

#### Production Deployment Notes
- The final runtime image runs under an unprivileged user (`appuser`, UID 10001).
- Container health is monitored via `curl -f http://localhost:${PORT}/`.
- On cloud container platforms (AWS ECS, GCP Cloud Run, DigitalOcean App Platform, Render), set the container port to `$PORT` and configure `CORS_ALLOWED_ORIGINS` with your public front-facing domain.

---

### Step 4: Build the Browser Extension

In a separate terminal, install dependencies, typecheck, and bundle the extension:

```bash
cd extension
npm install
npm run typecheck
npm run build
```

The compiled, ready-to-load extension will be generated in `extension/dist/`.

#### Installing in Chrome:
1. Open `chrome://extensions` in Google Chrome.
2. Toggle on **Developer mode** (top-right corner).
3. Click **Load unpacked** (top-left toolbar).
4. Select the `extension/dist` directory.
5. Pin **Flip to Read** to your toolbar.

#### Installing in Microsoft Edge:
1. Open `edge://extensions` in Microsoft Edge.
2. Enable **Developer mode** (left sidebar).
3. Click **Load unpacked** and select `extension/dist`.

> For complete technical details on Manifest V3 permissions, service worker message routing, passive PDF detection, and storage schemas, read the [Extension Architecture Documentation](docs/extension-architecture.md).

---

## Usage Guide

### Web Platform Workflow
1. Navigate to **http://localhost:8080**.
2. **Upload a Local PDF** via drag-and-drop or **Enter a Remote PDF URL**.
3. The platform processes the document through PyMuPDF, extracting document metadata, dimensions, and caching the source PDF.
4. The interactive flipbook opens automatically in full-viewport mode using direct high-DPI PDF.js rendering.
5. Use the arrow keys (`←` / `→`), mouse curl, or page popover to browse.
6. Revisit anytime—your reading position is automatically saved to browser storage.
7. Click **Download** in the toolbar to save the source PDF, a standalone offline `.html` flipbook, or an offline `.zip` package.

### Browser Extension Workflow
1. Visit any web page containing a PDF (or direct `.pdf` link).
2. Click the **Flip to Read** extension icon in your toolbar.
3. The popup automatically detects the document and presents **[ Open as Flipbook ]**.
4. Alternatively, use **[ Upload Local PDF ]** directly inside the popup.
5. Or right-click any link ending in `.pdf` and select *"Open PDF with Flip to Read"*.

---

## REST API Reference

| HTTP Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/upload`<br>*(alias: `/api/books/upload`)* | Upload PDF file (`multipart/form-data`). Returns book ID and metadata. |
| `POST` | `/api/upload-url` | Convert remote PDF from URL (`{"url": "https://..."}`). Enforces SSRF checks. |
| `GET` | `/api/books/{id}`<br>*(alias: `/api/books/{id}/metadata`)* | Retrieve document metadata (page count, dimensions, filename, title). |
| `GET` | `/api/books/{id}/pages` | Retrieve list of all rendered page image URLs. |
| `GET` | `/books/{id}/page-{N}.jpg` | Serve individual rendered page texture. |
| `GET` | `/api/books/{id}/pdf` | Download original uploaded source PDF. |
| `GET` | `/api/books/{id}/flipbook` | Download true single-file standalone interactive HTML flipbook. |
| `GET` | `/api/books/{id}/download` | Download standalone offline ZIP package containing all assets. |
| `DELETE` | `/api/books/{id}` | Delete flipbook and purge associated storage directory immediately. |
| `POST` | `/api/books/{id}/cleanup` | Trigger explicit storage cleanup for the specified book. |

---

## Standalone Flipbook Deep-Dive

One of Flip to Read's most powerful capabilities is the **Single-File Standalone Flipbook** (`/api/books/{id}/flipbook`).

- **Single Self-Contained File**: Output is a single `.html` document (e.g., `MyDocument_Flipbook.html`).
- **Completely Offline**: Double-click to open in any web browser directly via `file:///` protocol.
- **Zero Dependencies**: Requires no local server, no internet connection, and no external CDN calls.
- **Vector-Embedded**: Embeds original PDF bytes once, using client-side PDF.js worker execution via Blob URLs to lazily rasterize canvas pages on demand.
- **Full Feature Parity**: Retains physics page curl, two-page/single-page responsiveness, keyboard controls, and page jumping.

---

## Browser Extension Deep-Dive

The companion extension operates under a **privacy-first, user-initiated model**:
- **Zero Surveillance**: Extension content scripts remain dormant until the user opens the popup.
- **Minimal Manifest Permissions**:
  - `storage`: Preserves user settings and recent reading history.
  - `activeTab` & `tabs`: Queries the current tab URL on user invocation and opens reader tabs.
  - `contextMenus`: Adds the right-click shortcut for `.pdf` links.
- **Strict Host Scoping**: Host permissions are limited strictly to the backend server (`http://localhost:8080/*` and `http://127.0.0.1:8080/*`). Broad wildcards such as `<all_urls>` are intentionally omitted.
- **Integrated Options Page**: Customize the backend API URL, toggle automatic flipbook conversion, and manage reading history.

For comprehensive architectural specifications, refer to [docs/extension-architecture.md](docs/extension-architecture.md).

---

## Security & Privacy Considerations

1. **Server-Side Request Forgery (SSRF) Guard**:
   - Accepts only `http://` and `https://` schemes.
   - Resolves hostnames to IP addresses before initiating connections.
   - Blocks loopback addresses (`127.0.0.1`, `::1`), private subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), and link-local cloud metadata addresses (`169.254.0.0/16`).
   - Follows up to 3 redirects, re-verifying IP safety on every hop.
2. **Stream Termination & Size Quotas**:
   - Upload and stream sizes are hard-capped at 50 MB to prevent disk exhaustion attacks.
   - Incoming byte streams are validated against the `%PDF` file header signature before allocation.
3. **Cross-Origin Resource Sharing (CORS)**:
   - Configured in `WebConfig.java` to explicitly authorize `chrome-extension://*`, `http://localhost:*`, and `http://127.0.0.1:*`. Arbitrary wildcard origin access is rejected.
4. **No Telemetry**:
   - Zero analytics SDKs, trackers, or third-party tracking pixels exist in the codebase.

---

## Production & Deployment Notes

- **Local Development**: The project is pre-configured out-of-the-box for local execution with Spring Boot running on port `8080`.
- **Production Backend**: When deploying the backend to cloud environments (e.g. Docker, AWS, Render, DigitalOcean), set environment variables or override `application.properties` (e.g. `PORT`, `PYTHON_CMD`, `STORAGE_DIR`, `MAX_FILE_SIZE`).
- **Browser Extension Production Distribution**: Before packaging and submitting the browser extension to the Chrome Web Store or Microsoft Edge Add-ons catalog, update the default backend server URL in `extension/src/shared/storage.ts` and the `host_permissions` in `extension/manifest.json` to point to your live production domain.

---

## Roadmap

- [x] Production cloud deployment configurations (Dockerfile & Docker Compose)
- [x] High-DPI client-side vector rendering via PDF.js canvas integration
- [x] Single-file standalone HTML flipbook export (`file:///` compatible)
- [x] Offline ZIP flipbook bundle export
- [x] Manifest V3 companion browser extension for Chrome & Edge
- [ ] Chrome Web Store & Edge Add-ons official store listings
- [ ] Automated CI/CD pipeline for Maven packaging and TypeScript typechecking
- [ ] Bookmark annotation and text search within the flipbook reader
- [ ] Additional browser support (Firefox WebExtensions manifest adaptation)
- [ ] Dark / sepia reading themes and ambient background sound effects (page turn audio)

---

## Contributing

Contributions to Flip to Read are welcome:

1. **Fork** the repository (`https://github.com/Nish0178/flip-to-read`).
2. **Create a Feature Branch** (`git checkout -b feature/amazing-feature`).
3. **Commit your changes** (`git commit -m 'feat: add amazing feature'`).
4. **Ensure Verification Passes**: Run `.\mvnw.cmd clean package` and `npm run typecheck` in `extension/`.
5. **Push to the branch** (`git push origin feature/amazing-feature`).
6. **Open a Pull Request**.

---

## License

This project is licensed under the Apache License 2.0.

You may use, modify, distribute, and reproduce this software in accordance with the terms of the Apache License 2.0.

See the [LICENSE](LICENSE) file for the complete license text.

Copyright © 2026 Nishant Trivedi

---

## Developer

**Nishant Trivedi**  
Full Stack Developer • Android Developer  

- **GitHub**: [https://github.com/Nish0178](https://github.com/Nish0178)  
- **LinkedIn**: [https://www.linkedin.com/in/nishant-trivedi-363ba3249](https://www.linkedin.com/in/nishant-trivedi-363ba3249)  

---

### ⭐ Support the Project

If you find **Flip to Read** useful, interesting, or helpful, please consider giving the repository a **⭐ Star on GitHub**.

Your star helps the project gain visibility and motivates continued development.

**Give the repository a star and support the project! ⭐**

Repository: [https://github.com/Nish0178/flip-to-read](https://github.com/Nish0178/flip-to-read)
