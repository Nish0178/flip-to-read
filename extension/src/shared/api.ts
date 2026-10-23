import { BookMetadata } from './types.js';
import { getSettings } from './storage.js';

export async function getServerUrl(): Promise<string> {
  const settings = await getSettings();
  return settings.serverUrl.replace(/\/+$/, '');
}

export async function uploadLocalPdf(file: File): Promise<BookMetadata> {
  const serverUrl = await getServerUrl();
  const formData = new FormData();
  formData.append('file', file);

  try {
    const res = await fetch(`${serverUrl}/api/upload`, {
      method: 'POST',
      body: formData
    });

    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `Upload failed with HTTP ${res.status}`);
    }
    return data as BookMetadata;
  } catch (err: any) {
    if (err.message && err.message.includes('Failed to fetch')) {
      throw new Error(`Flip to Read server is unreachable at ${serverUrl}. Please verify the backend is running.`);
    }
    throw err;
  }
}

export async function convertOnlinePdf(pdfUrl: string): Promise<BookMetadata> {
  const serverUrl = await getServerUrl();

  try {
    const res = await fetch(`${serverUrl}/api/upload-url`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ url: pdfUrl })
    });

    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `Online conversion failed with HTTP ${res.status}`);
    }
    return data as BookMetadata;
  } catch (err: any) {
    if (err.message && err.message.includes('Failed to fetch')) {
      throw new Error(`Flip to Read server is unreachable at ${serverUrl}. Please verify the backend is running.`);
    }
    throw err;
  }
}

export async function getBookMetadata(id: string): Promise<BookMetadata> {
  const serverUrl = await getServerUrl();
  const res = await fetch(`${serverUrl}/api/books/${encodeURIComponent(id)}`);
  if (!res.ok) {
    throw new Error(`Book not found or expired (HTTP ${res.status})`);
  }
  return res.json();
}

export async function getBookPages(id: string): Promise<{ id: string; pageCount: number; pages: string[] }> {
  const serverUrl = await getServerUrl();
  const res = await fetch(`${serverUrl}/api/books/${encodeURIComponent(id)}/pages`);
  if (!res.ok) {
    throw new Error(`Unable to fetch book pages (HTTP ${res.status})`);
  }
  return res.json();
}
