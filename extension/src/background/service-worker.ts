import { convertOnlinePdf } from '../shared/api.js';
import { getSettings, saveRecentBook } from '../shared/storage.js';

// Setup context menu on installation
chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: 'open-pdf-flipbook',
    title: 'Open PDF with Flip to Read',
    contexts: ['link'],
    targetUrlPatterns: ['*://*/*.pdf*', '*://*/*.PDF*']
  });
});

// Handle context menu click
chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId === 'open-pdf-flipbook' && info.linkUrl) {
    const pdfUrl = info.linkUrl;
    try {
      // Notify badge or tab that processing has started
      if (tab?.id) {
        chrome.action.setBadgeText({ text: '⏳', tabId: tab.id });
        chrome.action.setBadgeBackgroundColor({ color: '#E76F51', tabId: tab.id });
      }

      const book = await convertOnlinePdf(pdfUrl);

      // Save to recent books
      await saveRecentBook({
        id: book.id,
        title: book.title || 'PDF Document',
        pages: book.pages || 1,
        lastPage: 1,
        sourceUrl: pdfUrl,
        timestamp: Date.now()
      });

      if (tab?.id) {
        chrome.action.setBadgeText({ text: '', tabId: tab.id });
      }

      // Check user setting: open in website vs internal extension reader
      const settings = await getSettings();
      let readerUrl = chrome.runtime.getURL(`reader/reader.html?id=${encodeURIComponent(book.id)}`);
      if (settings.openInWebsite) {
        const base = settings.serverUrl.replace(/\/+$/, '');
        readerUrl = `${base}/flipbook.html?id=${encodeURIComponent(book.id)}`;
      }

      chrome.tabs.create({ url: readerUrl });
    } catch (err: any) {
      if (tab?.id) {
        chrome.action.setBadgeText({ text: 'ERR', tabId: tab.id });
        chrome.action.setBadgeBackgroundColor({ color: '#ef4444', tabId: tab.id });
        setTimeout(() => chrome.action.setBadgeText({ text: '', tabId: tab.id }), 4000);
      }
      console.error('Context menu PDF conversion failed:', err);
    }
  }
});

// Message listener for background tasks
chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message && message.type === 'OPEN_READER') {
    (async () => {
      const settings = await getSettings();
      let targetUrl = chrome.runtime.getURL(`reader/reader.html?id=${encodeURIComponent(message.id)}`);
      if (settings.openInWebsite) {
        const base = settings.serverUrl.replace(/\/+$/, '');
        targetUrl = `${base}/flipbook.html?id=${encodeURIComponent(message.id)}`;
      }
      chrome.tabs.create({ url: targetUrl });
      sendResponse({ success: true });
    })();
    return true;
  }
});
