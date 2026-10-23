export interface BookMetadata {
  id: string;
  filename?: string;
  title?: string;
  pages?: number;
  width?: number;
  height?: number;
  size?: number;
  createdAt?: string;
  sourceUrl?: string;
  error?: string;
}

export interface RecentBook {
  id: string;
  title: string;
  pages: number;
  lastPage: number;
  sourceUrl?: string;
  timestamp: number;
}

export interface ExtensionSettings {
  serverUrl: string;
  openAutomatically: boolean;
  rememberPosition: boolean;
  openInWebsite: boolean;
}

export interface PdfDetectionResult {
  isPdf: boolean;
  url: string;
  title: string;
  pageCount?: number;
  source: 'url' | 'embed' | 'iframe' | 'content-type' | 'none';
}
