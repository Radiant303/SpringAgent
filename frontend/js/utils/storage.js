/**
 * 存储变更的合并刷新通知。
 *
 * @author hanbing
 * @since 2026-10-17
 */

let refreshTimer = null;

export function notifyStorageChanged() {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => {
    refreshTimer = null;
    window.dispatchEvent(new CustomEvent("storage-changed"));
  }, 120);
}
