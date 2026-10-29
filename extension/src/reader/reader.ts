import { getBookMetadata, getServerUrl } from '../shared/api.js';
import { getRecentBooks, updateReadingPosition, DEFAULT_SERVER_URL, getWebsiteUrl } from '../shared/storage.js';
import { BookMetadata } from '../shared/types.js';

declare const St: any;

document.addEventListener('DOMContentLoaded', async () => {
  const urlParams = new URLSearchParams(window.location.search);
  const bookId = urlParams.get('id');
  const pageParam = parseInt(urlParams.get('page') || '0', 10);

  const bookTitleEl = document.getElementById('book-title') as HTMLElement;
  const pageCounterText = document.getElementById('page-counter-text') as HTMLElement;
  const bottomCounter = document.getElementById('bottom-counter') as HTMLElement;
  const btnPrev = document.getElementById('btn-prev') as HTMLButtonElement;
  const btnNext = document.getElementById('btn-next') as HTMLButtonElement;
  const btnPageBadge = document.getElementById('btn-page-badge') as HTMLButtonElement;
  const jumpPopover = document.getElementById('page-jump-popover') as HTMLElement;
  const jumpInput = document.getElementById('jump-input') as HTMLInputElement;
  const btnJumpGo = document.getElementById('btn-jump-go') as HTMLButtonElement;
  const btnFullscreen = document.getElementById('btn-fullscreen') as HTMLButtonElement;
  const fullscreenText = document.getElementById('fullscreen-text') as HTMLElement;
  const btnOpenInWebsite = document.getElementById('btn-open-in-website') as HTMLButtonElement;

  const btnDownloadMenu = document.getElementById('btn-download-menu') as HTMLButtonElement | null;
  const downloadDropdown = document.getElementById('download-dropdown-menu') as HTMLElement | null;
  const downloadPdfLink = document.getElementById('download-pdf-link') as HTMLAnchorElement | null;
  const downloadHtmlLink = document.getElementById('download-html-link') as HTMLAnchorElement | null;
  const downloadZipLink = document.getElementById('download-zip-link') as HTMLAnchorElement | null;

  const loadingOverlay = document.getElementById('loading-overlay') as HTMLElement;
  const errorOverlay = document.getElementById('error-overlay') as HTMLElement;
  const errorTitle = document.getElementById('error-title') as HTMLElement;
  const errorDesc = document.getElementById('error-desc') as HTMLElement;
  const btnRetry = document.getElementById('btn-error-retry') as HTMLButtonElement;
  const viewportEl = document.getElementById('reader-viewport') as HTMLElement;
  const bookStageEl = document.getElementById('book-stage') as HTMLElement;

  const resumeToast = document.getElementById('resume-toast') as HTMLElement;
  const resumeToastText = document.getElementById('resume-toast-text') as HTMLElement;
  const btnResumeContinue = document.getElementById('btn-resume-continue') as HTMLButtonElement;
  const btnResumeStart = document.getElementById('btn-resume-start') as HTMLButtonElement;

  let flip: any = null;
  let bookMeta: BookMetadata | null = null;
  let serverUrl = DEFAULT_SERVER_URL;
  let isInitialized = false;

  if (!bookId) {
    showError('Missing Book ID', 'No flipbook identifier was provided in the reader URL.');
    return;
  }

  // Download menu toggle
  if (btnDownloadMenu && downloadDropdown) {
    btnDownloadMenu.addEventListener('click', (e) => {
      e.stopPropagation();
      downloadDropdown.classList.toggle('active');
      jumpPopover.classList.remove('active');
    });

    document.addEventListener('click', () => {
      downloadDropdown.classList.remove('active');
    });
  }

  // Popover toggle
  btnPageBadge.addEventListener('click', (e) => {
    e.stopPropagation();
    jumpPopover.classList.toggle('active');
    if (downloadDropdown) downloadDropdown.classList.remove('active');
    if (jumpPopover.classList.contains('active')) {
      jumpInput.focus();
      jumpInput.select();
    }
  });

  document.addEventListener('click', (e) => {
    if (!jumpPopover.contains(e.target as Node) && e.target !== btnPageBadge) {
      jumpPopover.classList.remove('active');
    }
  });

  btnJumpGo.addEventListener('click', performPageJump);
  jumpInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') performPageJump();
  });

  function performPageJump() {
    if (!flip || !bookMeta || !bookMeta.pages) return;
    const pageNum = parseInt(jumpInput.value, 10);
    if (isNaN(pageNum) || pageNum < 1 || pageNum > bookMeta.pages) {
      jumpInput.style.borderColor = '#ef4444';
      setTimeout(() => (jumpInput.style.borderColor = ''), 1000);
      return;
    }
    flip.flip(pageNum - 1);
    jumpPopover.classList.remove('active');
  }

  // Open in Flip to Read Website button
  btnOpenInWebsite.addEventListener('click', () => {
    const currentPage = flip ? (flip.getCurrentPageIndex() || 0) + 1 : 1;
    const webUrl = `${getWebsiteUrl(serverUrl)}/flipbook.html?id=${encodeURIComponent(bookId)}&page=${currentPage}`;
    chrome.tabs.create({ url: webUrl });
  });

  btnRetry.addEventListener('click', () => {
    errorOverlay.style.display = 'none';
    loadingOverlay.style.display = 'flex';
    loadingOverlay.classList.remove('hidden');
    init();
  });

  async function init() {
    try {
      serverUrl = await getServerUrl();
      bookMeta = await getBookMetadata(bookId!);

      if (!bookMeta || !bookMeta.pages || bookMeta.pages < 1) {
        throw new Error('This flipbook contains no readable pages.');
      }

      const title = bookMeta.title || bookMeta.filename || 'Flipbook';
      bookTitleEl.textContent = title;
      bookTitleEl.title = title;
      document.title = `${title} — Flip to Read`;
      jumpInput.max = String(bookMeta.pages);

      // Configure download links
      if (downloadPdfLink) downloadPdfLink.href = `${serverUrl}/api/books/${encodeURIComponent(bookId!)}/pdf`;
      if (downloadHtmlLink) downloadHtmlLink.href = `${serverUrl}/api/books/${encodeURIComponent(bookId!)}/flipbook`;
      if (downloadZipLink) downloadZipLink.href = `${serverUrl}/api/books/${encodeURIComponent(bookId!)}/download`;

      // Check stored reading position
      const recentList = await getRecentBooks();
      const currentEntry = recentList.find((b) => b.id === bookId);
      const storedPos = currentEntry ? currentEntry.lastPage : 1;

      let startPageIndex = 0;
      if (pageParam >= 1 && pageParam <= bookMeta.pages) {
        startPageIndex = pageParam - 1;
      }

      buildFlipbook(startPageIndex);

      // Show resume toast if returning to book and stored position > 1
      if (pageParam <= 0 && storedPos > 1 && storedPos <= bookMeta.pages) {
        resumeToastText.textContent = `Continue from page ${storedPos}?`;
        resumeToast.style.display = 'flex';

        btnResumeContinue.onclick = () => {
          if (flip) {
            try { flip.flip(storedPos - 1); } catch (e) {}
          }
          resumeToast.style.display = 'none';
        };

        btnResumeStart.onclick = () => {
          resumeToast.style.display = 'none';
        };

        setTimeout(() => {
          resumeToast.style.display = 'none';
        }, 9000);
      }
    } catch (err: any) {
      showError('Unable to Load Flipbook', err.message || 'Error communicating with Flip to Read server.');
    }
  }

  function calculateDimensions() {
    const availW = Math.max(280, viewportEl.clientWidth - 32);
    const availH = Math.max(260, viewportEl.clientHeight - 24);

    const isMobile = window.innerWidth < 768 || availW < availH * 1.05;
    const origW = bookMeta && bookMeta.width && bookMeta.width > 0 ? bookMeta.width : 600;
    const origH = bookMeta && bookMeta.height && bookMeta.height > 0 ? bookMeta.height : 800;
    const aspect = origW / origH;

    let pageW: number, pageH: number;

    if (isMobile) {
      let targetW = Math.min(availW, 680);
      let targetH = targetW / aspect;
      if (targetH > availH) {
        targetH = availH;
        targetW = targetH * aspect;
      }
      pageW = Math.round(targetW);
      pageH = Math.round(targetH);
    } else {
      let targetSpreadW = Math.min(availW, 1500);
      let targetPageW = targetSpreadW / 2;
      let targetH = targetPageW / aspect;
      if (targetH > availH) {
        targetH = availH;
        targetPageW = targetH * aspect;
      }
      pageW = Math.round(targetPageW);
      pageH = Math.round(targetH);
    }

    return {
      width: Math.max(160, pageW),
      height: Math.max(220, pageH),
      isMobile
    };
  }

  function updateCounter() {
    if (!flip || !bookMeta || !bookMeta.pages) return;
    const currentZeroBased = flip.getCurrentPageIndex() || 0;
    const currentPage = currentZeroBased + 1;
    const total = bookMeta.pages;

    const text = `Page ${currentPage} / ${total}`;
    pageCounterText.textContent = text;
    bottomCounter.textContent = text;
    jumpInput.value = String(currentPage);

    btnPrev.disabled = currentZeroBased <= 0;
    btnNext.disabled = currentZeroBased >= total - 1;

    // Save reading position
    if (bookId) {
      updateReadingPosition(bookId, currentPage);
    }
  }

  function buildFlipbook(startPageIndex: number) {
    if (typeof St === 'undefined' || !St.PageFlip) {
      showError('Library Error', 'PageFlip engine could not be initialized.');
      return;
    }

    const dims = calculateDimensions();
    bookStageEl.innerHTML = '<div id="book"></div>';
    const bookEl = document.getElementById('book');

    flip = new St.PageFlip(bookEl, {
      width: dims.width,
      height: dims.height,
      size: 'fixed',
      showCover: true,
      usePortrait: dims.isMobile,
      maxShadowOpacity: 0.55,
      mobileScrollSupport: false
    });

    const pages = Array.from(
      { length: bookMeta!.pages! },
      (_, i) => `${serverUrl}/books/${bookId}/page-${i + 1}.jpg`
    );

    flip.loadFromImages(pages);

    flip.on('flip', updateCounter);
    flip.on('init', () => {
      updateCounter();
      if (startPageIndex > 0) {
        try { flip.flip(startPageIndex); } catch (e) {}
      }
      loadingOverlay.classList.add('hidden');
      setTimeout(() => (loadingOverlay.style.display = 'none'), 350);
      isInitialized = true;
    });

    btnPrev.onclick = () => flip.flipPrev();
    btnNext.onclick = () => flip.flipNext();
  }

  // Keyboard navigation
  window.addEventListener('keydown', (e) => {
    if (!flip || !isInitialized) return;
    if (document.activeElement === jumpInput) return;

    if (e.key === 'ArrowLeft') {
      flip.flipPrev();
    } else if (e.key === 'ArrowRight') {
      flip.flipNext();
    } else if (e.key === 'Home') {
      flip.flip(0);
    } else if (e.key === 'End' && bookMeta && bookMeta.pages) {
      flip.flip(bookMeta.pages - 1);
    }
  });

  // Fullscreen
  btnFullscreen.addEventListener('click', () => {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen().catch(() => {});
    } else {
      document.exitFullscreen().catch(() => {});
    }
  });

  document.addEventListener('fullscreenchange', () => {
    fullscreenText.textContent = document.fullscreenElement ? 'Exit' : 'Fullscreen';
    recalcAndResize();
  });

  let resizeTimeout: any = null;
  function recalcAndResize() {
    if (!flip || !bookMeta || !isInitialized) return;
    clearTimeout(resizeTimeout);
    resizeTimeout = setTimeout(() => {
      const current = flip.getCurrentPageIndex() || 0;
      try { flip.destroy(); } catch (e) {}
      buildFlipbook(current);
    }, 180);
  }

  window.addEventListener('resize', recalcAndResize);

  function showError(title: string, message: string) {
    loadingOverlay.style.display = 'none';
    errorTitle.textContent = title;
    errorDesc.textContent = message;
    errorOverlay.style.display = 'flex';
  }

  init();
});
