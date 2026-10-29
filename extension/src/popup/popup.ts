import { convertOnlinePdf, uploadLocalPdf, getServerUrl } from '../shared/api.js';
import { getRecentBooks, saveRecentBook, clearRecentBooks, getSettings, getWebsiteUrl } from '../shared/storage.js';
import { PdfDetectionResult, RecentBook } from '../shared/types.js';

document.addEventListener('DOMContentLoaded', async () => {
  const detectorStateLoading = document.getElementById('detector-state-loading') as HTMLElement;
  const detectorStateFound = document.getElementById('detector-state-found') as HTMLElement;
  const detectorStateNone = document.getElementById('detector-state-none') as HTMLElement;
  const detectedTitle = document.getElementById('detected-title') as HTMLElement;
  const detectedUrl = document.getElementById('detected-url') as HTMLElement;
  const btnOpenFlipbook = document.getElementById('btn-open-flipbook') as HTMLButtonElement;
  const btnReDetect = document.getElementById('btn-re-detect') as HTMLButtonElement;

  const popupProgress = document.getElementById('popup-progress') as HTMLElement;
  const progressStatusText = document.getElementById('progress-status-text') as HTMLElement;
  const popupError = document.getElementById('popup-error') as HTMLElement;
  const popupErrorText = document.getElementById('popup-error-text') as HTMLElement;

  const btnUploadLocal = document.getElementById('btn-upload-local') as HTMLButtonElement;
  const localFileInput = document.getElementById('local-file-input') as HTMLInputElement;
  const btnOpenWebsite = document.getElementById('btn-open-website') as HTMLButtonElement;
  const btnOptions = document.getElementById('btn-options') as HTMLButtonElement;

  const recentList = document.getElementById('recent-list') as HTMLElement;
  const btnClearRecent = document.getElementById('btn-clear-recent') as HTMLButtonElement;

  let currentDetectedPdf: PdfDetectionResult | null = null;

  // Options page navigation
  btnOptions.addEventListener('click', () => {
    if (chrome.runtime.openOptionsPage) {
      chrome.runtime.openOptionsPage();
    } else {
      window.open(chrome.runtime.getURL('options/options.html'));
    }
  });

  // Open main website
  btnOpenWebsite.addEventListener('click', async () => {
    const settings = await getSettings();
    const url = getWebsiteUrl(settings.serverUrl);
    chrome.tabs.create({ url: url });
  });

  // Re-detect button
  btnReDetect.addEventListener('click', () => {
    runDetection();
  });

  // Initial detection
  await runDetection();
  await renderRecent();

  async function runDetection() {
    clearError();
    detectorStateLoading.style.display = 'flex';
    detectorStateFound.style.display = 'none';
    detectorStateNone.style.display = 'none';

    try {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      if (!tab || !tab.id || !tab.url) {
        showNoPdfDetected();
        return;
      }

      const tabUrl = tab.url;

      // 1. Direct URL check (.pdf)
      if (tabUrl.toLowerCase().includes('.pdf')) {
        const title = tab.title && !tab.title.toLowerCase().includes('pdf.js')
          ? tab.title
          : extractPdfFilename(tabUrl);
        showDetectedPdf({
          isPdf: true,
          url: tabUrl,
          title: title,
          source: 'url'
        });
        return;
      }

      // 2. Query content script in tab
      try {
        const response: PdfDetectionResult = await new Promise((resolve) => {
          chrome.tabs.sendMessage(tab.id!, { type: 'DETECT_PDF' }, (res) => {
            if (chrome.runtime.lastError || !res) {
              resolve({ isPdf: false, url: '', title: '', source: 'none' });
            } else {
              resolve(res);
            }
          });
        });

        if (response && response.isPdf) {
          showDetectedPdf(response);
          return;
        }
      } catch (ignored) {}

      showNoPdfDetected();
    } catch (e) {
      showNoPdfDetected();
    }
  }

  function showDetectedPdf(pdf: PdfDetectionResult) {
    currentDetectedPdf = pdf;
    detectedTitle.textContent = pdf.title || 'PDF Document';
    detectedUrl.textContent = pdf.url;
    detectorStateLoading.style.display = 'none';
    detectorStateFound.style.display = 'flex';
    detectorStateNone.style.display = 'none';
  }

  function showNoPdfDetected() {
    currentDetectedPdf = null;
    detectorStateLoading.style.display = 'none';
    detectorStateFound.style.display = 'none';
    detectorStateNone.style.display = 'flex';
  }

  // Open detected online PDF as flipbook
  btnOpenFlipbook.addEventListener('click', async () => {
    if (!currentDetectedPdf || !currentDetectedPdf.url) return;

    clearError();
    showProgress('Converting online PDF...');
    btnOpenFlipbook.disabled = true;

    try {
      const book = await convertOnlinePdf(currentDetectedPdf.url);

      await saveRecentBook({
        id: book.id,
        title: book.title || currentDetectedPdf.title || 'PDF Document',
        pages: book.pages || 1,
        lastPage: 1,
        sourceUrl: currentDetectedPdf.url,
        timestamp: Date.now()
      });

      hideProgress();
      btnOpenFlipbook.disabled = false;
      openReader(book.id, 1);
    } catch (err: any) {
      hideProgress();
      btnOpenFlipbook.disabled = false;
      showError(err.message || 'Failed to convert online PDF.');
    }
  });

  // Upload Local PDF flow
  btnUploadLocal.addEventListener('click', () => {
    localFileInput.click();
  });

  localFileInput.addEventListener('change', async () => {
    if (!localFileInput.files || !localFileInput.files[0]) return;
    const file = localFileInput.files[0];

    if (!file.name.toLowerCase().endsWith('.pdf')) {
      showError('Please select a valid PDF file (.pdf).');
      return;
    }
    if (file.size > 1024 * 1024 * 1024) {
      showError('PDF file is too large (max 1 GB).');
      return;
    }

    clearError();
    showProgress('Uploading local PDF...');
    btnUploadLocal.disabled = true;

    try {
      const book = await uploadLocalPdf(file);

      await saveRecentBook({
        id: book.id,
        title: book.title || file.name.replace(/\.[^/.]+$/, ''),
        pages: book.pages || 1,
        lastPage: 1,
        timestamp: Date.now()
      });

      hideProgress();
      btnUploadLocal.disabled = false;
      openReader(book.id, 1);
    } catch (err: any) {
      hideProgress();
      btnUploadLocal.disabled = false;
      showError(err.message || 'Failed to upload local PDF.');
    }
  });

  // Recent Books
  async function renderRecent() {
    const list = await getRecentBooks();
    if (!list || list.length === 0) {
      recentList.innerHTML = '<div class="recent-empty">No recent books yet.</div>';
      btnClearRecent.style.display = 'none';
      return;
    }

    btnClearRecent.style.display = 'inline';
    recentList.innerHTML = list.map((b: RecentBook) => `
      <div class="recent-item" data-id="${b.id}" data-page="${b.lastPage || 1}">
        <div class="recent-item-info">
          <span class="recent-item-title" title="${escapeHtml(b.title)}">${escapeHtml(b.title)}</span>
          <span class="recent-item-page">Page ${b.lastPage || 1} / ${b.pages || 1}</span>
        </div>
        <span class="recent-item-btn">Continue &rsaquo;</span>
      </div>
    `).join('');

    recentList.querySelectorAll('.recent-item').forEach((el) => {
      el.addEventListener('click', () => {
        const id = el.getAttribute('data-id');
        const page = parseInt(el.getAttribute('data-page') || '1', 10);
        if (id) openReader(id, page);
      });
    });
  }

  btnClearRecent.addEventListener('click', async () => {
    await clearRecentBooks();
    renderRecent();
  });

  async function openReader(bookId: string, page: number = 1) {
    const settings = await getSettings();
    let url = chrome.runtime.getURL(`reader/reader.html?id=${encodeURIComponent(bookId)}&page=${page}`);
    if (settings.openInWebsite) {
      const base = getWebsiteUrl(settings.serverUrl);
      url = `${base}/flipbook.html?id=${encodeURIComponent(bookId)}&page=${page}`;
    }
    chrome.tabs.create({ url: url });
    window.close();
  }

  function showProgress(msg: string) {
    progressStatusText.textContent = msg;
    popupProgress.style.display = 'flex';
  }

  function hideProgress() {
    popupProgress.style.display = 'none';
  }

  function showError(msg: string) {
    popupErrorText.textContent = msg;
    popupError.style.display = 'flex';
  }

  function clearError() {
    popupError.style.display = 'none';
  }

  function extractPdfFilename(url: string): string {
    try {
      const parsed = new URL(url);
      const parts = parsed.pathname.split('/');
      const last = parts[parts.length - 1];
      if (last) return decodeURIComponent(last.replace(/[_-]/g, ' '));
    } catch (e) {}
    return 'document.pdf';
  }

  function escapeHtml(str: string): string {
    return (str || '').replace(/[&<>'"]/g, (tag) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
    }[tag] || tag));
  }
});
