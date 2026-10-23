# FlipBook — Modern PDF to Flipbook Publishing Engine

Transform any PDF into a realistic, interactive page-turning flipbook with fullscreen reading and standalone offline download.

- **Backend (Spring Boot 3.2.5 / Java 21)**: REST API, document management, PDF preservation, offline ZIP generation
- **Converter (Python / PyMuPDF)**: High-fidelity page rasterization preserving crisp typography and vectors
- **Frontend (Vanilla HTML5 / CSS3 / JavaScript)**: Zero-scroll 100vh reader, realistic 3D page curls, responsive viewport containment

---

## Requirements
- Java 17+ (Java 21 recommended) & Maven (or included Maven Wrapper)
- Python 3.8+ with PyMuPDF

---

## Setup & Running

```bash
# 1. Install Python dependency
pip install -r python-service/requirements.txt

# 2. Run the application
mvn spring-boot:run
# Or using the Maven wrapper:
# .\mvnw.cmd spring-boot:run (Windows) or ./mvnw spring-boot:run (Linux/macOS)
```

Open **http://localhost:8080** in your browser.

> You can run `mvn spring-boot:run` from either the repository root or from `java-app/`. Python (`python`, `py`, or `python3`) is auto-detected.

---

## Features

- **Viewport-Fit Reader**: The reader strictly fits inside 100vh with zero vertical body scrolling.
- **Two-Page Spread & Mobile Single-Page**: Automatically switches to single-page mode on phones/portrait orientation and two-page spread on desktop.
- **Realistic Page Turns**: Physics-based curls, realistic center spine, and depth shadows powered by PageFlip.
- **Keyboard Navigation & Quick Jump**: ArrowLeft/Right to turn, Home/End for first/last page, and direct "Go to page X" popover.
- **Triple Downloads**:
  - **Download PDF**: Preserves and downloads the original uploaded PDF.
  - **Download Flipbook (.html)**: Downloads a **true single-file standalone interactive flipbook** (`<book-name>_Flipbook.html`) with all pages embedded as Base64 data URIs. Opens offline by double-clicking anywhere with zero dependencies.
  - **Download Offline Flipbook (.zip)**: Generates a self-contained offline package folder that opens by double-clicking `index.html` anywhere.

---

## REST API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/upload` | Upload PDF (multipart/form-data `file`) -> returns book metadata and `id` |
| `GET` | `/api/books/{id}` | Get book metadata (pages, dimensions, title, filename) |
| `GET` | `/books/{id}/page-N.jpg` | Serve individual page images |
| `GET` | `/api/books/{id}/pdf` | Download preserved original PDF |
| `GET` | `/api/books/{id}/flipbook` | Download true single-file standalone interactive flipbook HTML |
| `GET` | `/api/books/{id}/download` | Download standalone offline flipbook ZIP package |

---

## Settings (`java-app/src/main/resources/application.properties`)

| Key | Description | Default |
|---|---|---|
| `server.port` | HTTP server port | `8080` |
| `app.python` | Python command (`python`, `py`, `python3`) | `python` (auto-falls back) |
| `app.dpi` | Rasterization DPI (higher = sharper) | `110` |
| `spring.servlet.multipart.max-file-size` | Maximum allowed upload size | `50MB` |
