import { getSettings, saveSettings, clearRecentBooks } from '../shared/storage.js';

document.addEventListener('DOMContentLoaded', async () => {
  const serverUrlInput = document.getElementById('server-url') as HTMLInputElement;
  const toggleRememberPos = document.getElementById('toggle-remember-pos') as HTMLInputElement;
  const toggleOpenInWebsite = document.getElementById('toggle-open-in-website') as HTMLInputElement;
  const toggleAutoOpen = document.getElementById('toggle-auto-open') as HTMLInputElement;
  const btnSaveSettings = document.getElementById('btn-save-settings') as HTMLButtonElement;
  const saveMsg = document.getElementById('save-msg') as HTMLElement;
  const btnClearHistory = document.getElementById('btn-clear-history') as HTMLButtonElement;

  // Load existing settings
  const settings = await getSettings();
  serverUrlInput.value = settings.serverUrl || 'http://localhost:8080';
  toggleRememberPos.checked = settings.rememberPosition ?? true;
  toggleOpenInWebsite.checked = settings.openInWebsite ?? false;
  toggleAutoOpen.checked = settings.openAutomatically ?? false;

  // Save Settings
  btnSaveSettings.addEventListener('click', async () => {
    let url = serverUrlInput.value.trim();
    if (!url) url = 'http://localhost:8080';

    await saveSettings({
      serverUrl: url,
      rememberPosition: toggleRememberPos.checked,
      openInWebsite: toggleOpenInWebsite.checked,
      openAutomatically: toggleAutoOpen.checked
    });

    saveMsg.style.display = 'inline';
    setTimeout(() => {
      saveMsg.style.display = 'none';
    }, 3000);
  });

  // Clear History
  btnClearHistory.addEventListener('click', async () => {
    if (confirm('Are you sure you want to clear your local flipbook reading history?')) {
      await clearRecentBooks();
      alert('Local reading history cleared.');
    }
  });
});
