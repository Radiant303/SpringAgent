/**
 * 液态玻璃折射滤镜生成器。
 */
(function (global) {
  "use strict";

  var SVG_NS = "http://www.w3.org/2000/svg";
  var XLINK_NS = "http://www.w3.org/1999/xlink";
  var filterCounter = 0;
  var defsContainer = null;

  var supported = typeof CSS !== "undefined" && typeof CSS.supports === "function" && CSS.supports("backdrop-filter", 'url("#lg-probe")');
  if (!supported && typeof document !== "undefined" && document.documentElement) {
    document.documentElement.classList.add("glass-fallback");
  }

  function getDefs() {
    if (defsContainer && defsContainer.isConnected) return defsContainer;
    var svg = document.getElementById("glass-svg-container");
    if (!svg) {
      svg = document.createElementNS(SVG_NS, "svg");
      svg.id = "glass-svg-container";
      svg.setAttribute("width", "0");
      svg.setAttribute("height", "0");
      svg.setAttribute("aria-hidden", "true");
      svg.setAttribute("style", "position:fixed;left:0;top:0;pointer-events:none;overflow:hidden");
      var defs = document.createElementNS(SVG_NS, "defs");
      svg.appendChild(defs);
      var mount = function () {
        if (document.body && !svg.isConnected) {
          document.body.insertBefore(svg, document.body.firstChild);
        }
      };
      if (document.body) {
        mount();
      } else {
        document.addEventListener("DOMContentLoaded", mount);
      }
      defsContainer = defs;
    } else {
      defsContainer = svg.querySelector("defs");
    }
    return defsContainer;
  }

  function sdfRoundRect(x, y, w, h, r) {
    var qx = Math.abs(x) - w + r;
    var qy = Math.abs(y) - h + r;
    return Math.min(Math.max(qx, qy), 0) + Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) - r;
  }

  function smoothStep(a, b, t) {
    t = Math.min(Math.max((t - a) / (b - a), 0), 1);
    return t * t * (3 - 2 * t);
  }

  function attachGlass(el) {
    if (!el || !supported) return;
    if (el._glassAttached) return;
    el._glassAttached = true;

    var filterId = "lg-" + (filterCounter++);
    var filterEl = null;
    var feImageMap = null;
    var feDisp = null;
    var feImageMask = null;
    var feComp = null;
    var mapCanvas = document.createElement("canvas");
    var maskCanvas = document.createElement("canvas");
    var isFilterApplied = false;

    function build() {
      var w = el.offsetWidth;
      var h = el.offsetHeight;
      if (w < 4 || h < 4) return;

      var rawR = parseFloat(el.dataset.radius || "999");
      var B = parseFloat(el.dataset.bezel || "16");
      var rim = parseFloat(el.dataset.rim || "9");
      var mag = parseFloat(el.dataset.mag || "0.09");
      var R = Math.min(rawR, w / 2, h / 2);

      var dpr = Math.min(window.devicePixelRatio || 1, window.innerWidth <= 700 ? 1 : 1.5);
      var cw = Math.max(2, Math.round(w * dpr));
      var ch = Math.max(2, Math.round(h * dpr));

      mapCanvas.width = cw;
      mapCanvas.height = ch;
      var mapCtx = mapCanvas.getContext("2d");
      var total = cw * ch;
      var dxArr = new Float32Array(total);
      var dyArr = new Float32Array(total);
      var maxDisp = 0.001;

      var idx = 0;
      for (var iy = 0; iy < ch; iy++) {
        var v = (iy + 0.5) / ch;
        var py = (v - 0.5) * h;
        for (var ix = 0; ix < cw; ix++) {
          var u = (ix + 0.5) / cw;
          var px = (u - 0.5) * w;
          var d = sdfRoundRect(px, py, w / 2, h / 2, R);
          if (d > 1.5) {
            dxArr[idx] = 0;
            dyArr[idx] = 0;
          } else {
            var s = smoothStep(0, -B, d);
            var pull = rim * (1 - s) * (1 - s);
            var nx = px / (w / 2);
            var ny = py / (h / 2);
            var xOut = 0.5 + (u - 0.5) * (1 - mag) - (nx * pull) / w;
            var yOut = 0.5 + (v - 0.5) * (1 - mag) - (ny * pull) / h;
            var dx = xOut * w - u * w;
            var dy = yOut * h - v * h;
            dxArr[idx] = dx;
            dyArr[idx] = dy;
            var absX = Math.abs(dx);
            var absY = Math.abs(dy);
            if (absX > maxDisp) maxDisp = absX;
            if (absY > maxDisp) maxDisp = absY;
          }
          idx++;
        }
      }

      var mapImgData = mapCtx.createImageData(cw, ch);
      var data = mapImgData.data;
      for (var i = 0; i < total; i++) {
        var p = i * 4;
        data[p] = Math.round((0.5 + (0.5 * dxArr[i]) / maxDisp) * 255);
        data[p + 1] = Math.round((0.5 + (0.5 * dyArr[i]) / maxDisp) * 255);
        data[p + 2] = 0;
        data[p + 3] = 255;
      }
      mapCtx.putImageData(mapImgData, 0, 0);
      var mapDataUrl = mapCanvas.toDataURL("image/png");

      maskCanvas.width = cw;
      maskCanvas.height = ch;
      var maskCtx = maskCanvas.getContext("2d");
      maskCtx.clearRect(0, 0, cw, ch);
      maskCtx.fillStyle = "#ffffff";
      maskCtx.beginPath();
      var o = 2 * dpr;
      var mr = R * dpr;
      if (typeof maskCtx.roundRect === "function") {
        maskCtx.roundRect(-o, -o, cw + 2 * o, ch + 2 * o, mr + o);
      } else {
        var rx = -o;
        var ry = -o;
        var rw = cw + 2 * o;
        var rh = ch + 2 * o;
        var rad = mr + o;
        maskCtx.moveTo(rx + rad, ry);
        maskCtx.arcTo(rx + rw, ry, rx + rw, ry + rh, rad);
        maskCtx.arcTo(rx + rw, ry + rh, rx, ry + rh, rad);
        maskCtx.arcTo(rx, ry + rh, rx, ry, rad);
        maskCtx.arcTo(rx, ry, rx + rw, ry, rad);
        maskCtx.closePath();
      }
      maskCtx.fill();
      var maskDataUrl = maskCanvas.toDataURL("image/png");

      var defs = getDefs();
      if (!filterEl) {
        filterEl = document.createElementNS(SVG_NS, "filter");
        filterEl.setAttribute("id", filterId);
        filterEl.setAttribute("filterUnits", "userSpaceOnUse");
        filterEl.setAttribute("x", "0");
        filterEl.setAttribute("y", "0");
        filterEl.setAttribute("width", String(w));
        filterEl.setAttribute("height", String(h));
        filterEl.setAttribute("color-interpolation-filters", "sRGB");

        feImageMap = document.createElementNS(SVG_NS, "feImage");
        feImageMap.setAttribute("href", mapDataUrl);
        feImageMap.setAttributeNS(XLINK_NS, "xlink:href", mapDataUrl);
        feImageMap.setAttribute("width", String(w));
        feImageMap.setAttribute("height", String(h));
        feImageMap.setAttribute("preserveAspectRatio", "none");
        feImageMap.setAttribute("result", "map");
        filterEl.appendChild(feImageMap);

        feDisp = document.createElementNS(SVG_NS, "feDisplacementMap");
        feDisp.setAttribute("in", "SourceGraphic");
        feDisp.setAttribute("in2", "map");
        feDisp.setAttribute("xChannelSelector", "R");
        feDisp.setAttribute("yChannelSelector", "G");
        feDisp.setAttribute("scale", String(2 * maxDisp));
        feDisp.setAttribute("result", "disp");
        filterEl.appendChild(feDisp);

        feImageMask = document.createElementNS(SVG_NS, "feImage");
        feImageMask.setAttribute("href", maskDataUrl);
        feImageMask.setAttributeNS(XLINK_NS, "xlink:href", maskDataUrl);
        feImageMask.setAttribute("width", String(w));
        feImageMask.setAttribute("height", String(h));
        feImageMask.setAttribute("preserveAspectRatio", "none");
        feImageMask.setAttribute("result", "mask");
        filterEl.appendChild(feImageMask);

        feComp = document.createElementNS(SVG_NS, "feComposite");
        feComp.setAttribute("in", "disp");
        feComp.setAttribute("in2", "mask");
        feComp.setAttribute("operator", "in");
        filterEl.appendChild(feComp);

        defs.appendChild(filterEl);
      } else {
        filterEl.setAttribute("width", String(w));
        filterEl.setAttribute("height", String(h));

        feImageMap.setAttribute("href", mapDataUrl);
        feImageMap.setAttributeNS(XLINK_NS, "xlink:href", mapDataUrl);
        feImageMap.setAttribute("width", String(w));
        feImageMap.setAttribute("height", String(h));

        feDisp.setAttribute("scale", String(2 * maxDisp));

        feImageMask.setAttribute("href", maskDataUrl);
        feImageMask.setAttributeNS(XLINK_NS, "xlink:href", maskDataUrl);
        feImageMask.setAttribute("width", String(w));
        feImageMask.setAttribute("height", String(h));
      }

      if (!isFilterApplied) {
        isFilterApplied = true;
        requestAnimationFrame(function () {
          el.style.backdropFilter = "url(#" + filterId + ")";
          el.style.webkitBackdropFilter = "url(#" + filterId + ")";
        });
      }
    }

    var resizeTimer = null;
    if (typeof ResizeObserver !== "undefined") {
      var ro = new ResizeObserver(function () {
        if (resizeTimer) clearTimeout(resizeTimer);
        resizeTimer = setTimeout(build, 140);
      });
      ro.observe(el);
    }

    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(build);
    }

    build();
  }

  function init() {
    document.querySelectorAll("[data-glass]").forEach(attachGlass);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }

  global.attachGlass = attachGlass;
})(typeof window !== "undefined" ? window : this);
