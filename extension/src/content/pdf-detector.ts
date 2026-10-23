// Flip to Read - Privacy-conscious on-demand PDF detector
// Runs passively and responds when queried by the popup or context menu.

chrome.runtime.onMessage.addListener((request, _sender, sendResponse) => {
  if (request && request.type === 'DETECT_PDF') {
    const result = detectPdfInDocument();
    sendResponse(result);
  }
  return true;
});

function detectPdfInDocument() {
  const currentUrl = window.location.href;

  // 1. Direct PDF URL
  if (isPdfUrl(currentUrl)) {
    return {
      isPdf: true,
      url: currentUrl,
      title: extractDocumentTitle(currentUrl),
      source: 'url'
    };
  }

  // 2. MIME type application/pdf
  if (document.contentType === 'application/pdf') {
    return {
      isPdf: true,
      url: currentUrl,
      title: extractDocumentTitle(currentUrl),
      source: 'content-type'
    };
  }

  // 3. Embedded PDF (<embed type="application/pdf"> or <embed src="...pdf">)
  const embed = document.querySelector('embed[type="application/pdf"], embed[src*=".pdf"]') as HTMLEmbedElement | null;
  if (embed && embed.src) {
    const absUrl = new URL(embed.src, window.location.href).href;
    return {
      isPdf: true,
      url: absUrl,
      title: extractDocumentTitle(absUrl),
      source: 'embed'
    };
  }

  // 4. Object PDF (<object data="...pdf">)
  const obj = document.querySelector('object[type="application/pdf"], object[data*=".pdf"]') as HTMLObjectElement | null;
  if (obj && obj.data) {
    const absUrl = new URL(obj.data, window.location.href).href;
    return {
      isPdf: true,
      url: absUrl,
      title: extractDocumentTitle(absUrl),
      source: 'embed'
    };
  }

  // 5. Iframe showing PDF
  const iframe = document.querySelector('iframe[src*=".pdf"]') as HTMLIFrameElement | null;
  if (iframe && iframe.src) {
    const absUrl = new URL(iframe.src, window.location.href).href;
    return {
      isPdf: true,
      url: absUrl,
      title: extractDocumentTitle(absUrl),
      source: 'iframe'
    };
  }

  return {
    isPdf: false,
    url: '',
    title: '',
    source: 'none'
  };
}

function isPdfUrl(url: string): boolean {
  try {
    const parsed = new URL(url);
    const pathname = parsed.pathname.toLowerCase();
    return pathname.endsWith('.pdf') || pathname.includes('.pdf/') || parsed.search.toLowerCase().includes('.pdf');
  } catch (e) {
    return false;
  }
}

function extractDocumentTitle(url: string): string {
  if (document.title && !document.title.toLowerCase().includes('pdf.js') && document.title.trim().length > 0) {
    return document.title.trim();
  }
  try {
    const parsed = new URL(url);
    const parts = parsed.pathname.split('/');
    const last = parts[parts.length - 1];
    if (last && last.toLowerCase().endsWith('.pdf')) {
      return decodeURIComponent(last.replace(/\.pdf$/i, '').replace(/[_-]/g, ' '));
    }
  } catch (e) {}
  return 'PDF Document';
}
