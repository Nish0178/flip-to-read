import { ExtensionSettings, RecentBook } from './types.js';

const DEFAULT_SETTINGS: ExtensionSettings = {
  serverUrl: 'http://localhost:8080',
  openAutomatically: false,
  rememberPosition: true,
  openInWebsite: false
};

export async function getSettings(): Promise<ExtensionSettings> {
  return new Promise((resolve) => {
    chrome.storage.local.get(['settings'], (result) => {
      resolve({ ...DEFAULT_SETTINGS, ...(result.settings || {}) });
    });
  });
}

export async function saveSettings(settings: Partial<ExtensionSettings>): Promise<void> {
  const current = await getSettings();
  const updated = { ...current, ...settings };
  return new Promise((resolve) => {
    chrome.storage.local.set({ settings: updated }, () => resolve());
  });
}

export async function getRecentBooks(): Promise<RecentBook[]> {
  return new Promise((resolve) => {
    chrome.storage.local.get(['recent_books'], (result) => {
      resolve(result.recent_books || []);
    });
  });
}

export async function saveRecentBook(book: RecentBook): Promise<void> {
  const list = await getRecentBooks();
  const filtered = list.filter((b) => b.id !== book.id);
  filtered.unshift(book);
  const trimmed = filtered.slice(0, 10);
  return new Promise((resolve) => {
    chrome.storage.local.set({ recent_books: trimmed }, () => resolve());
  });
}

export async function updateReadingPosition(bookId: string, page: number): Promise<void> {
  const list = await getRecentBooks();
  const index = list.findIndex((b) => b.id === bookId);
  if (index !== -1) {
    list[index].lastPage = page;
    list[index].timestamp = Date.now();
    return new Promise((resolve) => {
      chrome.storage.local.set({ recent_books: list }, () => resolve());
    });
  }
}

export async function clearRecentBooks(): Promise<void> {
  return new Promise((resolve) => {
    chrome.storage.local.remove(['recent_books'], () => resolve());
  });
}
