/**
 * Flip to Read — Frontend API & Runtime Configuration
 *
 * Configures the backend API base URL for decoupled frontend deployments
 * (e.g. Render Static Site frontend + Render Web Service backend).
 *
 * Resolution Priority:
 * 1. window.__FLIP_BACKEND_URL__ (runtime override, e.g. set dynamically)
 * 2. Meta tag: <meta name="flip-backend-url" content="..."> in HTML head
 * 3. localStorage override: 'flip_api_base_url' (useful for dev / staging testing)
 * 4. BACKEND_API_URL constant below (configured for production static deployment)
 * 5. Default fallback: "" (empty string = relative path / same origin for local monolithic run)
 */
(function (global) {
  'use strict';

  // =========================================================================
  // PRODUCTION BACKEND URL CONFIGURATION
  // Set to the real Render Web Service URL for decoupled Static Site deployment.
  // =========================================================================
  var BACKEND_API_URL = 'https://flip-to-read.onrender.com';

  function resolveApiBaseUrl() {
    var win = typeof window !== 'undefined' ? window : global;
    var doc = typeof document !== 'undefined' ? document : (win && win.document ? win.document : null);

    // 1. Explicit global variable
    if (win && typeof win.__FLIP_BACKEND_URL__ === 'string' && win.__FLIP_BACKEND_URL__.trim() !== '') {
      return win.__FLIP_BACKEND_URL__.trim().replace(/\/+$/, '');
    }

    // 2. Meta tag inspection (<meta name="flip-backend-url" content="...">)
    try {
      if (doc && typeof doc.querySelector === 'function') {
        var meta = doc.querySelector('meta[name="flip-backend-url"]');
        if (meta && meta.content && meta.content.trim() !== '') {
          return meta.content.trim().replace(/\/+$/, '');
        }
      }
    } catch (e) {}

    // 3. LocalStorage override (for testing or debugging)
    try {
      var storage = (win && win.localStorage) || (typeof localStorage !== 'undefined' ? localStorage : null);
      if (storage) {
        var stored = storage.getItem('flip_api_base_url');
        if (stored && stored.trim() !== '') {
          return stored.trim().replace(/\/+$/, '');
        }
      }
    } catch (e) {}

    // 4. Same origin / local development fallback
    // Always preserve local development behavior (e.g. localhost:8080 or localhost:3000)
    // so local runs never inadvertently call the remote production backend.
    try {
      var loc = win && win.location ? win.location : (typeof location !== 'undefined' ? location : null);
      if (loc) {
        var host = loc.hostname;
        var port = loc.port;
        if (host === 'localhost' || host === '127.0.0.1') {
          if (port && port !== '8080' && port !== '') {
            return 'http://' + host + ':8080';
          }
          return '';
        }
      }
    } catch (e) {}

    // 5. Configured constant for production Static Site deployment
    if (BACKEND_API_URL && BACKEND_API_URL.trim() !== '') {
      return BACKEND_API_URL.trim().replace(/\/+$/, '');
    }

    return '';
  }

  var apiBaseUrl = resolveApiBaseUrl();

  /**
   * Resolves a relative API/backend path against the configured backend base URL.
   * Ensures exactly one leading slash and no duplicate slashes.
   *
   * Example:
   *   getFlipApiUrl('/api/upload')
   *   -> '/api/upload' (if apiBaseUrl is '')
   *   -> 'https://backend.example.com/api/upload' (if apiBaseUrl is configured)
   */
  function getFlipApiUrl(path) {
    if (!path) return apiBaseUrl || '';
    if (path.indexOf('http://') === 0 || path.indexOf('https://') === 0 || path.indexOf('//') === 0) {
      return path; // Already absolute URL
    }
    var cleanPath = '/' + path.replace(/^\/+/, '');
    if (!apiBaseUrl) {
      return cleanPath;
    }
    return apiBaseUrl + cleanPath;
  }

  var flipConfig = {
    get apiBaseUrl() {
      return apiBaseUrl;
    },
    setApiBaseUrl: function (url) {
      apiBaseUrl = (url || '').trim().replace(/\/+$/, '');
    },
    getApiUrl: getFlipApiUrl,
    resolveApiBaseUrl: resolveApiBaseUrl
  };

  if (typeof window !== 'undefined') {
    window.FLIP_CONFIG = flipConfig;
    window.getFlipApiUrl = getFlipApiUrl;
  }
  if (typeof global !== 'undefined') {
    global.FLIP_CONFIG = flipConfig;
    global.getFlipApiUrl = getFlipApiUrl;
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : this));
