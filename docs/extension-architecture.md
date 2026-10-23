# Flip to Read — Browser Extension Architecture

## Overview
The **Flip to Read Browser Extension** is an official companion for the Flip to Read platform. Built with **Manifest V3**, **TypeScript**, and **Vanilla DOM APIs**, it extends the web platform directly into Chrome and Edge browsers.

The extension is designed around a **privacy-first, user-initiated model**:
1. **No background surveillance**: Browsing history and visited URLs are never tracked, logged, or transmitted.
2. **No automatic uploads**: Documents are processed *only* when the user explicitly clicks **"Open as Flipbook"** or **"Upload Local PDF"**.
3. **Shared Flipbook Engine**: Reuses the core `St.PageFlip` engine and design tokens between the web platform and the extension reader.

---

## Architectural Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        BROWSER EXTENSION (MV3)                         │
│                                                                        │
│  ┌───────────────────┐    ┌──────────────────┐    ┌─────────────────┐  │
│  │    POPUP (UI)     │    │  CONTENT SCRIPT  │    │ SERVICE WORKER  │  │
│  │                   │    │                  │    │  (Background)   │  │
│  │ - Detect PDF      │◄───┤ - Passive PDF    │    │                 │  │
│  │ - Local Upload    │    │   DOM detector   │    │ - Context menu  │  │
│  │ - Recent Books    │    │ - On-demand only │    │ - Message route │  │
│  └─────────┬─────────┘    └──────────────────┘    └────────┬────────┘  │
│            │                                               │           │
│            ▼                                               ▼           │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                    SHARED TYPES & STORAGE API                    │  │
│  │  - chrome.storage.local (Settings, Reading Position, Recents)    │  │
│  └─────────────────────────────────┬────────────────────────────────┘  │
│                                    │                                   │
│            ┌───────────────────────┴───────────────────────┐           │
│            ▼                                               ▼           │
│  ┌───────────────────┐                           ┌──────────────────┐  │
│  │ EXTENSION READER  │                           │  REST API CLIENT │  │
│  │                   │                           │                  │  │
│  │ - PageFlip Engine │                           │ - POST /upload   │  │
│  │ - Dual/Single mode│                           │ - POST /upload-url│ │
│  │ - Resume position │                           │ - GET /pages     │  │
│  │ - "Open in Web"   │                           │ - GET /metadata  │  │
│  └───────────────────┘                           └─────────┬────────┘  │
└────────────────────────────────────────────────────────────┼───────────┘
                                                             │
                                                             ▼
                                                ┌────────────────────────┐
                                                │    SPRING BOOT API     │
                                                │ (http://localhost:8080)│
                                                └────────────────────────┘
```

---

## Component Breakdown

### 1. Manifest V3 (`manifest.json`)
- **Permissions**: Restricted to minimal required APIs:
  - `storage`: For storing extension settings and recent reading positions.
  - `activeTab`: Provides temporary access to the active tab upon user interaction.
  - `tabs`: Allows opening reader tabs and querying current tab URL.
  - `contextMenus`: Adds the "Open PDF with Flip to Read" right-click option.
- **Host Permissions**: Restricted strictly to the Flip to Read backend (`http://localhost:8080/*`, `http://127.0.0.1:8080/*`). Broad permissions such as `<all_urls>` are deliberately omitted.

### 2. Service Worker (`background/service-worker.ts`)
- Registers context menu item: `"Open PDF with Flip to Read"` for links matching `*.pdf`.
- Handles context menu clicks by sending the PDF URL to `POST /api/upload-url` and opening the reader upon conversion.
- Listens for tab routing messages and handles fallback navigation.

### 3. Content Script (`content/pdf-detector.ts`)
- Injected passively on `http://*` and `https://*`.
- Remains dormant until the popup explicitly sends a `{ type: "DETECT_PDF" }` message.
- Detection hierarchy:
  1. Checks if current URL path ends with `.pdf`.
  2. Checks if `document.contentType === 'application/pdf'`.
  3. Inspects `<embed type="application/pdf">` or `<embed src="*.pdf">`.
  4. Inspects `<object type="application/pdf">`.
  5. Inspects `<iframe>` referencing `.pdf`.
- Returns detected URL and document title without retaining state.

### 4. Popup (`popup/popup.ts` / `popup.html` / `popup.css`)
- Displays brand identity: **📖 Flip to Read**.
- Shows detection state of the active tab:
  - If PDF found: Displays filename and **[ Open as Flipbook ]** CTA.
  - If no PDF found: Displays clean informational notice with **[ Detect Again ]**.
- Provides **[ Upload Local PDF ]** file picker for local files.
- Provides **[ Open Flip to Read ]** button to jump to the web platform.
- Renders **Recent Books** from `chrome.storage.local` with one-click **Continue Reading**.
- Header settings cog navigates directly to the Options page.

### 5. Extension Reader (`reader/reader.ts` / `reader.html` / `reader.css`)
- Fully integrated reader running inside extension context (`chrome-extension://.../reader/reader.html?id=...`).
- Powered by the shared `St.PageFlip` engine (`lib/page-flip.browser.js`).
- Features:
  - Viewport-fit canvas with zero vertical body scrolling.
  - Dual-page spread on desktop, mobile single-page view.
  - Keyboard shortcuts (Left/Right, Home/End).
  - Page jump popover and counter badge.
  - Fullscreen toggle.
  - **Reading Position Resumption**: Prompts `"Continue from page X?"` when reopening a book.
  - **[ Open in Flip to Read ]**: Opens the book directly in the main website platform.

### 6. Options & Privacy (`options/options.ts` / `options.html`)
- Configurable backend server URL (default: `http://localhost:8080`).
- Toggle switches for automatic opening, position memory, and opening in web vs extension.
- Clear history action.
- Full privacy guarantee disclosure.

---

## Message & Data Flow

### Online PDF Workflow
```
User navigates to example.com/paper.pdf
    │
    ▼
User clicks Flip to Read extension icon
    │
    ▼
Popup sends DETECT_PDF to active tab
    │
    ▼
Content script detects PDF and returns URL & title
    │
    ▼
User clicks [ Open as Flipbook ]
    │
    ▼
Extension calls POST /api/upload-url { url: "https://example.com/paper.pdf" }
    │
    ▼
Spring Boot validates SSRF safety & streams PDF (max 50 MB)
    │
    ▼
PyMuPDF converts pages to textures and stores metadata
    │
    ▼
Backend returns { id: "dc5c9ff44053", pages: 220, ... }
    │
    ▼
Extension saves book to chrome.storage.local (page 1)
    │
    ▼
Extension opens reader/reader.html?id=dc5c9ff44053
```

### Local PDF Workflow
```
User clicks [ Upload Local PDF ] in extension popup
    │
    ▼
File dialog opens -> User selects "MyBook.pdf"
    │
    ▼
Extension validates: file is .pdf and <= 50 MB
    │
    ▼
Extension streams file to POST /api/upload
    │
    ▼
Spring Boot & PyMuPDF process flipbook assets
    │
    ▼
Backend returns { id: "...", pages: 140, ... }
    │
    ▼
Extension opens reader/reader.html?id=...
```

---

## Storage Schema (`chrome.storage.local`)

```typescript
// Key: 'settings'
interface ExtensionSettings {
  serverUrl: string;           // e.g. "http://localhost:8080"
  openAutomatically: boolean;  // Default: false
  rememberPosition: boolean;   // Default: true
  openInWebsite: boolean;      // Default: false
}

// Key: 'recent_books'
interface RecentBook {
  id: string;                  // 12-char hex book id
  title: string;               // Document title
  pages: number;               // Total page count
  lastPage: number;            // Last read page number (1-based)
  sourceUrl?: string;          // Original remote URL if online
  timestamp: number;           // Unix epoch timestamp
}
```

---

## Build System
The extension utilizes a lightweight build script powered by `esbuild` and `typescript`:
- `npm run build`: Bundles all TypeScript modules into ES2022 JavaScript, copies static assets (HTML, CSS, icons, manifest, and shared `page-flip.browser.js`) into `extension/dist/`.
- `npm run typecheck`: Validates complete type safety against `@types/chrome`.
