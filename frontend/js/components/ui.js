/**
 * UI 门面模块（统一对外接口）
 */

import { dialog, confirm, prompt, toast } from "./dialog.js";
import {
  saveSkeleton, showSkeleton, clearSkeleton,
  tableSkeleton, emptyRow, loadTable, renderRows, settleSkeletons
} from "./skeleton.js";
import { enhance, enhanceSelect, enhanceDate, openPopover, closePopover } from "./controls.js";

export const UI = {
  dialog, confirm, prompt, toast,
  saveSkeleton, showSkeleton, clearSkeleton,
  tableSkeleton, emptyRow, loadTable, renderRows, settleSkeletons,
  enhance, enhanceSelect, enhanceDate, openPopover, closePopover,
};

if (typeof window !== "undefined") {
  window.UI = UI;
}

export {
  dialog, confirm, prompt, toast,
  saveSkeleton, showSkeleton, clearSkeleton,
  tableSkeleton, emptyRow, loadTable, renderRows, settleSkeletons,
  enhance, enhanceSelect, enhanceDate, openPopover, closePopover,
};

export default UI;
