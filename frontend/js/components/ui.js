/**
 * UI 门面模块（统一对外接口）
 */

import { dialog, confirm, prompt, toast } from "./dialog.js";
import {
  saveSkeleton, showSkeleton, clearSkeleton,
  tableSkeleton, emptyRow, loadTable, renderRows, settleSkeletons
} from "./skeleton.js";
import { enhance, enhanceSelect, enhanceDate, openPopover, closePopover } from "./controls.js";
import { renderPager } from "./pager.js";

export const UI = {
  dialog, confirm, prompt, toast,
  saveSkeleton, showSkeleton, clearSkeleton,
  tableSkeleton, emptyRow, loadTable, renderRows, settleSkeletons,
  enhance, enhanceSelect, enhanceDate, openPopover, closePopover,
  renderPager,
};

if (typeof window !== "undefined") {
  window.UI = UI;
}

export {
  dialog, confirm, prompt, toast,
  saveSkeleton, showSkeleton, clearSkeleton,
  tableSkeleton, emptyRow, loadTable, renderRows, settleSkeletons,
  enhance, enhanceSelect, enhanceDate, openPopover, closePopover,
  renderPager,
};

export default UI;
