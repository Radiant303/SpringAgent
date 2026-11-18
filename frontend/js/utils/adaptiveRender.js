/**
 * 流式渲染自适应节流器。
 *
 * @author hanbing
 * @since 2026-11-18
 */

/** 上次渲染耗时的倍数，过了这段时间才允许下次渲染 */
const RENDER_RATE = 2;
/** 两次渲染的最长间隔（毫秒） */
const MAX_WAIT = 2000;

/**
 * 创建自适应节流渲染器：首次立即渲染，之后按上次渲染耗时 ×2 间隔渲染（封顶 2s），期间内容合并为最新。
 *
 * @param render 渲染回调，参数为 schedule 传入的目标
 * @return 调度器
 */
export function createAdaptiveRenderer(render) {
  let timer = 0;
  let updatableAt = 0;
  let target = null;
  let dirty = false;

  const renderNow = () => {
    const t0 = performance.now();
    render(target);
    dirty = false;
    updatableAt = performance.now() + Math.min(MAX_WAIT, (performance.now() - t0) * RENDER_RATE);
  };

  return {
    schedule(t) {
      // 换目标（新回合）时重置节流，保证首屏即时
      if (target !== t) {
        target = t;
        updatableAt = 0;
      }
      dirty = true;
      if (timer) return;
      const delay = updatableAt - performance.now();
      if (delay <= 0) {
        renderNow();
        return;
      }
      timer = setTimeout(() => {
        timer = 0;
        if (dirty) renderNow();
      }, delay);
    },
    flush() {
      if (timer) {
        clearTimeout(timer);
        timer = 0;
      }
      if (dirty && target) renderNow();
    },
    discard() {
      if (timer) {
        clearTimeout(timer);
        timer = 0;
      }
      target = null;
      dirty = false;
    },
  };
}
