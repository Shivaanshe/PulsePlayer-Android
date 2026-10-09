/*
 * Spotify procedural spider crawler (Deliberate Leg Cadence, Swift Scroll & Graceful Upward Glide)
 * Reference: https://www.youtube.com/shorts/8J0ZoUDYOxo
 *
 * Features:
 * - Slower, deliberate, organic leg cadence (~210ms steps, zero twitching)
 * - Faster, responsive downward camera scrolling (smooth 12px/frame tracking)
 * - Graceful upward animation (soft spring physics & upward leg strides, zero teleportation)
 * - Strict real-time DOM element tracking for neon highlight boxes
 * - Single-pass continuous crawl (no jump-to-top resets or reverse scroll jerks)
 * - Locked downward heading (strictly clamped banking, zero 360° spins)
 */
(async function spiderCrawl(opts = {}) {
  // ---- Spotify Telemetry Guard --------------------------------------------
  window.addEventListener('unhandledrejection', function(e) {
    if (e && e.reason && (e.reason.message || e.reason || '').toString().includes('RejectedClientEventNonAuth')) {
      e.preventDefault();
    }
  });

  // ---- Auto-Dismiss Overlays & Cookie Banners (PRD §8) -------------------
  try {
    const hideStyle = document.createElement('style');
    hideStyle.innerHTML = `
      #onetrust-consent-sdk,
      #onetrust-banner-sdk,
      [data-testid="signup-bar"],
      div[data-testid="cookie-notice"],
      .open-in-app,
      [data-testid="open-in-app-button"] {
        display: none !important;
        pointer-events: none !important;
      }
    `;
    document.head.appendChild(hideStyle);
    document.querySelector('#onetrust-accept-btn-handler')?.click();
  } catch (_) {}

  // ---- cleanup previous run ----------------------------------------------
  window.__spider?.stop?.();
  document.getElementById('__spider_canvas')?.remove();

  const CFG = Object.assign({
    expected: null,     // null = read from page header
    stepRatio: 0.60,    // scroll step = 60% of container height
    topInset: 110,      // px at top of container hidden by sticky header
    bottomInset: 30,
    visitMs: 80,        // fast pacing per item
    scrollMs: 240,      // swift, smooth scroll transitions
    scale: null,        // spider scale multiplier
    glitch: true,       // brief text warp where foot anchors
    maxMs: 12 * 60 * 1000,
    maxIdle: 8,
  }, opts);

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  async function waitFor(fn, timeout, step = 80) {
    const t = performance.now();
    while (performance.now() - t < timeout) {
      if (fn()) return true;
      await sleep(step);
    }
    return false;
  }
  const clean = (s) => (s || '').replace(/[\u200b-\u200f\u2060\ufeff]/g, '').replace(/\s+/g, ' ').trim();
  const emit = (m) => {
    const msg = { v: 1, ...m };
    try {
      if (window.PulseBridge && window.PulseBridge.postMessage) {
        window.PulseBridge.postMessage(JSON.stringify(msg));
      } else {
        window.__pulseQueue = window.__pulseQueue || [];
        window.__pulseQueue.push(JSON.stringify(msg));
      }
    } catch (_) {}
  };

  const state = { stopped: false, done: false, raf: 0 };
  const tracks = new Map();
  const visited = new Set();
  const emittedIndexes = new Set();
  let base = null;

  // DOM helpers
  const rowsNow = () => [...document.querySelectorAll('[data-testid="tracklist-row"]')];
  const rawIdx = (row) => {
    const n = parseInt(row.closest('[aria-rowindex]')?.getAttribute('aria-rowindex'), 10);
    return Number.isFinite(n) ? n : null;
  };

  function findScroller() {
    const explicit = document.querySelector('[data-overlayscrollbars-viewport]') ||
                     document.querySelector('.Root__main-view') ||
                     document.querySelector('main');
    if (explicit && explicit.scrollHeight > explicit.clientHeight) return explicit;

    let el = rowsNow()[0]?.parentElement;
    while (el && el !== document.documentElement) {
      const oy = getComputedStyle(el).overflowY;
      if ((oy === 'auto' || oy === 'scroll' || oy === 'overlay') && el.scrollHeight > el.clientHeight + 4) return el;
      el = el.parentElement;
    }
    return document.scrollingElement;
  }

  let scroller = findScroller();
  const getScrollY = () => {
    if (!scroller || scroller === document.scrollingElement) return window.scrollY;
    return scroller.scrollTop;
  };

  window.__spider = {
    stop() {
      state.stopped = true;
      state.done = true;
      if (state.raf) cancelAnimationFrame(state.raf);
      document.getElementById('__spider_canvas')?.remove();
    },
    tracks: null
  };
  const t0 = performance.now();

  function parseExpected() {
    const meta = document.querySelector('meta[property="og:description"]')?.content || '';
    const m = meta.match(/([\d][\d,.\u00a0\s]*)\s*(songs|items|tracks)/i) ||
              document.body.innerText.match(/([\d][\d,]*)\s+songs?\b/i);
    const n = m ? parseInt(m[1].replace(/[^\d]/g, ''), 10) : NaN;
    return n > 0 && n <= 10000 ? n : null;
  }

  function getPlaylistTitle() {
    const entityTitle = document.querySelector('[data-testid="entityTitle"]')?.innerText ||
                        document.querySelector('h1[dir="auto"]')?.innerText;
    if (entityTitle && entityTitle.trim()) return clean(entityTitle);

    const ogTitle = document.querySelector('meta[property="og:title"]')?.content;
    if (ogTitle && ogTitle.trim()) return clean(ogTitle);

    const h1s = [...document.querySelectorAll('h1')].map(h => clean(h.innerText));
    const validH1 = h1s.find(t => t && t !== 'Your Library' && t !== 'Home' && t !== 'Search' && t !== 'Spotify');
    if (validH1) return validH1;

    return document.title.replace(' | Spotify', '').replace(' - Spotify', '').trim() || 'Spotify Playlist';
  }

  function readRow(row, raw) {
    const link = row.querySelector('a[href*="/track/"]');
    const id = (link?.getAttribute('href') || '').match(/\/track\/([A-Za-z0-9]{22})/)?.[1] || null;
    const titleEl = link?.querySelector('div[dir="auto"]') || row.querySelector('div[dir="auto"]');
    const title = clean(titleEl?.innerText || link?.innerText);
    if (!title) return null;

    const artists = [...row.querySelectorAll('a[href*="/artist/"]')].map((a) => clean(a.innerText)).filter(Boolean).join(', ');
    const album = clean(row.querySelector('a[href*="/album/"]')?.innerText);

    const img = row.querySelector('img[src]');
    const thumbnailUrl = img ? (img.getAttribute('src') || img.src) : null;

    const dur = (row.innerText.match(/\b(?:\d+:)?\d{1,2}:\d{2}\b/g) || []).pop();
    let durationMs = null;
    if (dur) {
      const p = dur.split(':').map(Number);
      durationMs = p.reduce((a, v) => a * 60 + v, 0) * 1000;
    }
    const rowType = id ? 'TRACK' : row.querySelector('a[href*="/episode/"]') ? 'EPISODE' : 'UNAVAILABLE';
    return { rowRaw: raw, rowType, spotifyId: id, title, artists, album, thumbnailUrl, durationMs };
  }

  const withIndex = (r) => ({ ...r, rowIndex: base === null ? null : r.rowRaw - base });

  // ---- Canvas setup (capped DPR for optimal 60fps) -------------------------
  const canvas = document.createElement('canvas');
  canvas.id = '__spider_canvas';
  Object.assign(canvas.style, { position: 'fixed', top: 0, left: 0, zIndex: 999999, pointerEvents: 'none' });
  document.body.appendChild(canvas);
  const ctx = canvas.getContext('2d');

  let currentDpr = 1;
  const fit = () => {
    currentDpr = Math.min(window.devicePixelRatio || 1, 1.25);
    canvas.width = Math.round(innerWidth * currentDpr);
    canvas.height = Math.round(innerHeight * currentDpr);
    canvas.style.width = innerWidth + 'px';
    canvas.style.height = innerHeight + 'px';
    ctx.setTransform(currentDpr, 0, 0, currentDpr, 0, 0);
  };
  fit();
  window.addEventListener('resize', fit);

  // ---- Spider Anatomy & Proportional Arachnid Rig --------------------------
  const S = CFG.scale || Math.min(1.25, Math.max(0.9, innerWidth / 1300));
  const L1 = 78 * S;  // Femur length
  const L2 = 96 * S;  // Tibia length
  const REACH = L1 + L2; // ~174 * S

  const LEG_CONFIGS = [
    // LEFT LEGS (side = -1)
    { id: 0, side: -1, group: 0, hip: { x: -9, y: 12 },  rest: { x: -125, y: 55 } },   // L1: Front-left
    { id: 1, side: -1, group: 1, hip: { x: -12, y: 4 },  rest: { x: -155, y: 18 } },   // L2: Mid-front-left
    { id: 2, side: -1, group: 0, hip: { x: -12, y: -4 }, rest: { x: -150, y: -18 } },  // L3: Mid-rear-left
    { id: 3, side: -1, group: 1, hip: { x: -9, y: -12 }, rest: { x: -115, y: -55 } },  // L4: Rear-left

    // RIGHT LEGS (side = 1)
    { id: 4, side: 1,  group: 1, hip: { x: 9, y: 12 },   rest: { x: 125, y: 55 } },    // R1: Front-right
    { id: 5, side: 1,  group: 0, hip: { x: 12, y: 4 },   rest: { x: 155, y: 18 } },    // R2: Mid-front-right
    { id: 6, side: 1,  group: 1, hip: { x: 12, y: -4 },  rest: { x: 150, y: -18 } },   // R3: Mid-rear-right
    { id: 7, side: 1,  group: 0, hip: { x: 10, y: -12 }, rest: { x: 115, y: -55 } },   // R4: Rear-right
  ];

  const body = { x: innerWidth * 0.40, y: 220, vx: 0, vy: 0, angle: 0 };
  const target = { x: body.x, y: body.y };
  const ease = (p) => (p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2);
  const rnd = (a, b) => a + Math.random() * (b - a);

  function lerpAngle(current, target, factor) {
    let diff = (target - current) % (Math.PI * 2);
    if (diff < -Math.PI) diff += Math.PI * 2;
    if (diff > Math.PI) diff -= Math.PI * 2;
    return current + diff * factor;
  }

  function localToWorld(lx, ly) {
    const cos = Math.cos(body.angle);
    const sin = Math.sin(body.angle);
    return {
      x: body.x + (lx * cos - ly * sin) * S,
      y: body.y + (lx * sin + ly * cos) * S,
    };
  }

  function solveLeg(hx, hy, fx, fy, side) {
    const dx = fx - hx, dy = fy - hy;
    const dist = Math.hypot(dx, dy) || 0.001;
    const d = Math.min(REACH - 1, Math.max(Math.abs(L1 - L2) + 2, dist));
    const ux = dx / dist, uy = dy / dist;
    const a = (L1 * L1 - L2 * L2 + d * d) / (2 * d);
    const h = Math.sqrt(Math.max(0, L1 * L1 - a * a));

    const bx = hx + ux * a;
    const by = hy + uy * a;

    const cos = Math.cos(body.angle);
    const sin = Math.sin(body.angle);
    const outX = cos * side;
    const outY = sin * side;

    const dot1 = (-uy) * outX + ux * outY;
    const nx = dot1 >= 0 ? -uy : uy;
    const ny = dot1 >= 0 ? ux : -ux;

    return {
      knee: { x: bx + nx * h, y: by + ny * h },
      foot: { x: hx + ux * d, y: hy + uy * d },
    };
  }

  const legs = LEG_CONFIGS.map((cfg) => {
    const restWorld = localToWorld(cfg.rest.x, cfg.rest.y);
    return {
      cfg,
      x: restWorld.x,
      y: restWorld.y,
      step: null,
      isAnchor: false,
      anchorKind: null,
      lift: 0,
      lastStep: 0,
    };
  });

  function startStep(leg, tx, ty, dur = 120) {
    leg.step = { fx: leg.x, fy: leg.y, tx, ty, t0: performance.now(), dur };
  }

  function getBestVisibleRow() {
    const rows = rowsNow();
    if (!rows.length) return null;
    const vMid = innerHeight * 0.44;
    let best = null, minDiff = Infinity;
    for (const row of rows) {
      const r = row.getBoundingClientRect();
      if (r.bottom > 130 && r.top < innerHeight - 60) {
        const diff = Math.abs((r.top + r.bottom) / 2 - vMid);
        if (diff < minDiff) { minDiff = diff; best = row; }
      }
    }
    return best;
  }

  async function glideSpiderTo(destX, destY, durationMs = 300) {
    target.x = destX;
    target.y = destY;
    const start = performance.now();
    while (performance.now() - start < durationMs && !state.stopped) {
      await sleep(20);
    }
  }

  async function smoothRepositionToVisibleRow(durationMs = 300) {
    const row = getBestVisibleRow();
    if (!row) return;
    const r = row.getBoundingClientRect();
    const destX = Math.max(180, r.left + 140);
    const destY = Math.max(160, Math.min(innerHeight - 90, (r.top + r.bottom) / 2));
    await glideSpiderTo(destX, destY, durationMs);
  }

  const boxes = [];
  const strands = [];

  const STYLE = {
    title:  { c: [0, 240, 255],   fill: 0.16 }, // Electric Cyan
    artist: { c: [255, 42, 133],  fill: 0.20 }, // Neon Magenta
    album:  { c: [179, 71, 255],  fill: 0.16 }, // Electric Violet
    time:   { c: [0, 240, 255],   fill: 0.14 }, // Electric Cyan
  };
  const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;

  // ---- 60 FPS Animation Loop ------------------------------------------------
  let lastTs = performance.now();
  let lastScrollTop = getScrollY();

  function animate(ts) {
    ts = ts || performance.now();
    const dt = Math.min(0.05, Math.max(0.001, (ts - (lastTs || ts)) / 1000 || 0.016));
    lastTs = ts;

    if (state.done && !boxes.length && !strands.length) return;
    ctx.clearRect(0, 0, innerWidth, innerHeight);

    const currentScrollY = getScrollY();
    const scrollDelta = currentScrollY - lastScrollTop;
    lastScrollTop = currentScrollY;

    if (Math.abs(scrollDelta) > 0.01) {
      body.y -= scrollDelta;
      target.y -= scrollDelta;
      legs.forEach((l) => {
        l.y -= scrollDelta;
        if (l.step) { l.step.fy -= scrollDelta; l.step.ty -= scrollDelta; }
      });
      boxes.forEach((b) => { b.y -= scrollDelta; });
    }

    if (body.y < 150) {
      body.y += (150 - body.y) * (1 - Math.exp(-dt * 5.0));
      if (target.y < 160) target.y = 160;
    } else if (body.y > innerHeight - 85) {
      body.y += (innerHeight - 85 - body.y) * (1 - Math.exp(-dt * 5.0));
      if (target.y > innerHeight - 85) target.y = innerHeight - 85;
    }

    legs.forEach((l) => {
      if (l.isAnchor) return;
      const rw = localToWorld(l.cfg.rest.x, l.cfg.rest.y);
      if (Math.hypot(l.x - rw.x, l.y - rw.y) > 65 * S) {
        l.x = rw.x;
        l.y = rw.y;
        l.step = null;
      }
    });

    for (let i = boxes.length - 1; i >= 0; i--) {
      const b = boxes[i];
      b.life -= dt * (b.rate || 2.4);
      if (b.life <= 0) { boxes.splice(i, 1); continue; }

      let bx = b.x, by = b.y, bw = b.w, bh = b.h;
      if (b.el && b.el.isConnected) {
        const fresh = b.el.getBoundingClientRect();
        bx = fresh.left - 4;
        by = fresh.top - 2;
        bw = fresh.width + 8;
        bh = fresh.height + 4;
        b.x = bx; b.y = by; b.w = bw; b.h = bh;
      }

      if (by + bh < -10 || by > innerHeight + 10) continue;

      const a = Math.min(1, b.life);
      ctx.fillStyle = rgba(b.s.c, a * b.s.fill);
      ctx.fillRect(bx, by, bw, bh);
      ctx.strokeStyle = rgba(b.s.c, a * 0.95);
      ctx.lineWidth = 1.4;
      ctx.strokeRect(bx, by, bw, bh);
    }

    for (let i = strands.length - 1; i >= 0; i--) {
      const s = strands[i];
      s.life -= dt * (s.rate || 3.5);
      if (s.life <= 0) { strands.splice(i, 1); continue; }

      let targetX = s.x2, targetY = s.y2;
      if (s.el && s.el.isConnected) {
        const fresh = s.el.getBoundingClientRect();
        targetX = fresh.left + fresh.width * 0.5;
        targetY = fresh.top + fresh.height * 0.5;
      }

      const c = s.color || [0, 240, 255];
      ctx.strokeStyle = `rgba(${c[0]}, ${c[1]}, ${c[2]}, ${s.life * 0.85})`;
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(s.leg ? s.leg.x : s.x1, s.leg ? s.leg.y : s.y1);
      ctx.lineTo(targetX, targetY);
      ctx.stroke();
    }

    if (!state.done) {
      const px = body.x, py = body.y;
      const smoothK = 1 - Math.exp(-dt * 5.5);
      body.x += (target.x - body.x) * smoothK;
      body.y += (target.y - body.y) * smoothK;
      body.vx = (body.x - px) / Math.max(dt, 0.001);
      body.vy = (body.y - py) / Math.max(dt, 0.001);

      const lateralOffset = target.x - body.x;
      const lateralBank = -Math.atan2(lateralOffset, 85 * S);
      const targetHeading = Math.max(-0.45, Math.min(0.45, lateralBank));
      body.angle = lerpAngle(body.angle, targetHeading, 1 - Math.exp(-dt * 6.0));

      if (scroller && body.y > innerHeight * 0.40) {
        const scrollStep = Math.min(14, (body.y - innerHeight * 0.40) * 0.20);
        scroller.scrollTop += scrollStep;
      }

      legs.forEach((l) => {
        if (l.step) {
          const p = Math.min(1, (ts - l.step.t0) / l.step.dur);
          const e = ease(p);
          l.x = l.step.fx + (l.step.tx - l.step.fx) * e;
          l.y = l.step.fy + (l.step.ty - l.step.fy) * e;
          l.lift = Math.sin(p * Math.PI) * 16 * S;
          if (p >= 1) {
            l.x = l.step.tx;
            l.y = l.step.ty;
            l.step = null;
            l.lift = 0;
            l.lastStep = ts;
          }
        }
      });

      const speed = Math.hypot(body.vx, body.vy);
      const isMoving = speed > 4;
      const dirX = isMoving ? body.vx / speed : -Math.sin(body.angle);
      const dirY = isMoving ? body.vy / speed : Math.cos(body.angle);

      const strideDist = 30 * S;
      const strideX = dirX * strideDist;
      const strideY = dirY * strideDist;

      const steppingGrp0 = legs.some((l) => l.cfg.group === 0 && l.step);
      const steppingGrp1 = legs.some((l) => l.cfg.group === 1 && l.step);

      legs.forEach((l) => {
        if (l.isAnchor || l.step) return;

        const restWorld = localToWorld(l.cfg.rest.x, l.cfg.rest.y);
        const err = Math.hypot(l.x - restWorld.x, l.y - restWorld.y);

        const isEmergency = err > 50 * S;
        const canStep = (l.cfg.group === 0 && !steppingGrp1) || (l.cfg.group === 1 && !steppingGrp0);

        if (isEmergency || (err > 32 * S && canStep && (ts - l.lastStep) > 100)) {
          const plantX = restWorld.x + strideX + rnd(-3, 3) * S;
          const plantY = restWorld.y + strideY + rnd(-3, 3) * S;
          startStep(l, plantX, plantY, 140);
          l.lastStep = ts;
        }
      });

      const legPoints = legs.map((l) => {
        const hipWorld = localToWorld(l.cfg.hip.x, l.cfg.hip.y);
        const { knee, foot } = solveLeg(hipWorld.x, hipWorld.y, l.x, l.y, l.cfg.side);
        return { hip: hipWorld, knee, foot, l };
      });

      ctx.beginPath();
      legPoints.forEach(({ hip, knee, foot }) => {
        ctx.moveTo(hip.x, hip.y);
        ctx.lineTo(knee.x, knee.y);
        ctx.lineTo(foot.x, foot.y);
      });
      ctx.strokeStyle = 'rgba(255, 255, 255, 0.12)';
      ctx.lineWidth = 3.0 * S;
      ctx.stroke();

      ctx.strokeStyle = 'rgba(255, 255, 255, 0.95)';
      ctx.lineWidth = 1.2 * S;
      ctx.stroke();

      legPoints.forEach(({ knee, foot, l }) => {
        ctx.fillStyle = '#ffffff';
        ctx.beginPath();
        ctx.arc(knee.x, knee.y, 2.4 * S, 0, Math.PI * 2);
        ctx.fill();

        if (l.isAnchor && l.anchorKind && STYLE[l.anchorKind]) {
          ctx.fillStyle = rgba(STYLE[l.anchorKind].c, 1);
          ctx.beginPath();
          ctx.arc(foot.x, foot.y, 3.6 * S, 0, Math.PI * 2);
          ctx.fill();
        } else {
          ctx.fillStyle = l.step ? '#ffffff' : 'rgba(240, 245, 255, 0.9)';
          const footRadius = (l.step ? 2.6 : 2.0) * S;
          ctx.beginPath();
          ctx.arc(foot.x, foot.y, footRadius, 0, Math.PI * 2);
          ctx.fill();
        }
      });

      ctx.save();
      ctx.translate(body.x, body.y);
      ctx.rotate(body.angle);

      ctx.fillStyle = 'rgba(12, 16, 26, 0.65)';
      ctx.beginPath();
      ctx.ellipse(0, -13 * S, 10 * S, 15 * S, 0, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = 'rgba(255, 255, 255, 0.85)';
      ctx.lineWidth = 1.2 * S;
      ctx.stroke();

      ctx.strokeStyle = 'rgba(255, 255, 255, 0.30)';
      ctx.lineWidth = 0.9 * S;
      ctx.beginPath(); ctx.ellipse(0, -8 * S, 8 * S, 2.5 * S, 0, 0, Math.PI); ctx.stroke();
      ctx.beginPath(); ctx.ellipse(0, -13 * S, 9 * S, 2.8 * S, 0, 0, Math.PI); ctx.stroke();
      ctx.beginPath(); ctx.ellipse(0, -18 * S, 7 * S, 2.5 * S, 0, 0, Math.PI); ctx.stroke();

      ctx.fillStyle = '#ffffff';
      ctx.beginPath();
      ctx.arc(0, -26 * S, 1.6 * S, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = 'rgba(12, 16, 26, 0.70)';
      ctx.beginPath();
      ctx.ellipse(0, 8 * S, 8 * S, 10 * S, 0, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = 1.3 * S;
      ctx.stroke();

      ctx.fillStyle = '#ffffff';
      ctx.beginPath(); ctx.arc(-2.6 * S, 13 * S, 2.0 * S, 0, Math.PI * 2); ctx.fill();
      ctx.beginPath(); ctx.arc(2.6 * S, 13 * S, 2.0 * S, 0, Math.PI * 2); ctx.fill();

      ctx.fillStyle = 'rgba(255, 255, 255, 0.65)';
      ctx.beginPath(); ctx.arc(-4.6 * S, 9 * S, 1.3 * S, 0, Math.PI * 2); ctx.fill();
      ctx.beginPath(); ctx.arc(4.6 * S, 9 * S, 1.3 * S, 0, Math.PI * 2); ctx.fill();

      ctx.strokeStyle = 'rgba(255, 255, 255, 0.9)';
      ctx.lineWidth = 1.1 * S;
      ctx.beginPath();
      ctx.moveTo(-3 * S, 14 * S); ctx.lineTo(-5 * S, 19 * S); ctx.lineTo(-3 * S, 22 * S);
      ctx.moveTo(3 * S, 14 * S);  ctx.lineTo(5 * S, 19 * S);  ctx.lineTo(3 * S, 22 * S);
      ctx.stroke();

      ctx.restore();
    }
    state.raf = requestAnimationFrame(animate);
  }
  animate();

  // ---- Field validation & inspection ---------------------------------------
  const TIME_RE = /^(?:\d+:)?\d{1,2}:\d{2}$/;

  function isValidField(el) {
    if (!el || !el.isConnected) return false;
    const txt = (el.innerText || el.textContent || '').trim();
    if (!txt || txt.length === 0) return false;
    const style = window.getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
    const r = el.getBoundingClientRect();
    if (r.width < 6 || r.height < 5) return false;
    return true;
  }

  function fieldsOf(row) {
    const f = [];

    const titleEl = row.querySelector('a[href*="/track/"] div[dir="auto"]') ||
                    row.querySelector('div[data-encore-id="textTrackTitle"]') ||
                    row.querySelector('a[href*="/track/"]') ||
                    row.querySelector('div[dir="auto"]');
    if (isValidField(titleEl)) f.push({ el: titleEl, kind: 'title' });

    const artistEls = [...row.querySelectorAll('a[href*="/artist/"]')];
    for (const a of artistEls) {
      if (isValidField(a) && !f.some((x) => x.el === a)) {
        f.push({ el: a, kind: 'artist' });
        if (f.filter((x) => x.kind === 'artist').length >= 2) break;
      }
    }

    const timeEl = [...row.querySelectorAll('div,span')].find(
      (e) => !e.children.length && TIME_RE.test((e.textContent || '').trim()) && isValidField(e)
    );
    if (timeEl) f.push({ el: timeEl, kind: 'time' });

    return f;
  }

  function glitch(el) {
    if (!CFG.glitch || getComputedStyle(el).display === 'inline') return;
    const prev = { t: el.style.transform, o: el.style.transformOrigin };
    el.style.transformOrigin = 'left center';
    el.style.transform = `scale(1.03) rotate(${(Math.random() * 1.4 - 0.7).toFixed(2)}deg)`;
    setTimeout(() => { el.style.transform = prev.t; el.style.transformOrigin = prev.o; }, 120);
  }

  // Visit a single track row with deliberate, organic anchor-pull locomotion
  async function visit(row, rec) {
    const items = fieldsOf(row);
    if (!items.length) return;

    for (const o of items) {
      if (state.stopped) break;
      const r = o.el.getBoundingClientRect();
      const anchorX = r.left + r.width * rnd(0.35, 0.65);
      const anchorY = r.top + r.height * 0.5;

      const leadLeg = anchorX < body.x ? legs[0] : legs[4];
      leadLeg.isAnchor = true;
      leadLeg.anchorKind = o.kind;
      startStep(leadLeg, anchorX, anchorY, 140);
      await sleep(130);

      target.x = anchorX - (leadLeg.cfg.rest.x * 0.68 * S);
      target.y = anchorY - (leadLeg.cfg.rest.y * 0.68 * S);
      await sleep(80);

      boxes.push({
        el: o.el,
        x: r.left - 4,
        y: r.top - 2,
        w: r.width + 8,
        h: r.height + 4,
        s: STYLE[o.kind],
        life: 0.9,
      });

      strands.push({
        leg: leadLeg,
        el: o.el,
        x1: leadLeg.x,
        y1: leadLeg.y,
        x2: anchorX,
        y2: anchorY,
        color: STYLE[o.kind].c,
        life: 0.70,
      });

      glitch(o.el);
      await sleep(CFG.visitMs);

      leadLeg.isAnchor = false;
      const nextRest = localToWorld(leadLeg.cfg.rest.x, leadLeg.cfg.rest.y);
      startStep(leadLeg, nextRest.x, nextRest.y, 120);
      await sleep(50);
    }

    // Emit row immediately upon physical visit completion so progress bar and spider position stay 100% in sync!
    if (rec && !emittedIndexes.has(rec.rowRaw)) {
      emittedIndexes.add(rec.rowRaw);
      emit({ type: 'BATCH', tracks: [withIndex(rec)] });
      emit({ type: 'PROGRESS', captured: emittedIndexes.size, expected, distinctIndexes: emittedIndexes.size, maxIndex: Math.max(0, ...tracks.keys()), phase: 'SCAN' });
    }
  }

  // ---- Continuous Single-Pass Crawl Orchestration --------------------------
  emit({ type: 'HELLO', scriptVersion: '2.1.0', selectorsVersion: '2025.1', base: 'aria' });

  if (!(await waitFor(() => rowsNow().length > 0, 30000))) {
    emit({ type: 'ERROR', code: 'NO_ROWS' });
    console.warn('No tracklist rows found after 30s');
    state.done = true;
    window.removeEventListener('resize', fit);
    canvas.remove();
    return;
  }

  scroller = findScroller();
  lastScrollTop = getScrollY();

  const expected = CFG.expected ?? parseExpected();
  const pageTitle = getPlaylistTitle();
  emit({ type: 'META', title: pageTitle, expectedCount: expected, countInferred: expected == null });

  const viewRect = () => (scroller === document.scrollingElement
    ? { top: 0, bottom: innerHeight }
    : scroller.getBoundingClientRect());
  const inView = (row) => {
    const r = row.getBoundingClientRect();
    const mid = r.top + r.height / 2, v = viewRect();
    return mid > v.top + CFG.topInset && mid < v.bottom - CFG.bottomInset;
  };
  const step = () => Math.max(220, scroller.clientHeight * CFG.stepRatio);

  function tweenScroll(dy, ms) {
    return new Promise((res) => {
      const from = scroller.scrollTop, t0 = performance.now();
      let finished = false;
      const end = () => { if (finished) return; finished = true; scroller.scrollTop = from + dy; res(); };
      const tick = (now) => {
        if (finished) return;
        const p = Math.min(1, (now - t0) / ms);
        scroller.scrollTop = from + dy * (p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2);
        if (p < 1 && !state.stopped) requestAnimationFrame(tick); else end();
      };
      requestAnimationFrame(tick);
      setTimeout(end, ms + 150);
    });
  }

  console.log('[spider] scroller:', scroller, '| expected:', expected);

  await smoothRepositionToVisibleRow(300);

  async function crawl() {
    let idle = 0, bottomHits = 0;
    while (!state.stopped && performance.now() - t0 < CFG.maxMs) {
      // 1) Extract mounted rows into local tracks map
      const newlyFound = [];
      for (const row of rowsNow()) {
        const raw = rawIdx(row);
        if (raw == null || tracks.has(raw)) continue;
        const rec = readRow(row, raw);
        if (!rec) continue;
        tracks.set(raw, rec);
        newlyFound.push(rec);
      }
      if (newlyFound.length && base === null) base = Math.min(...tracks.keys()) - 1;

      // 2) Spider walks in-view rows smoothly in order and emits tracks in lockstep on visit
      let visitedNow = 0;
      const todo = rowsNow()
        .map((row) => ({ row, raw: rawIdx(row) }))
        .filter((o) => o.raw != null && tracks.has(o.raw) && !visited.has(o.raw) && inView(o.row))
        .sort((a, b) => a.raw - b.raw);

      for (const { row, raw } of todo) {
        if (state.stopped) break;
        visited.add(raw);
        const rec = tracks.get(raw);
        if (row.isConnected) {
          await visit(row, rec);
          visitedNow++;
        }
      }

      if (expected && emittedIndexes.size >= expected && base !== null) return 'COMPLETE';

      // 3) Advance smoothly down when visible rows are done
      boxes.forEach((x) => { x.rate = 2.5; });
      strands.forEach((x) => { x.rate = 4.0; });
      const before = scroller.scrollTop;
      const atBottom = before + scroller.clientHeight >= scroller.scrollHeight - 6;
      const progressed = newlyFound.length > 0 || visitedNow > 0;

      if (atBottom && !progressed) {
        if (++bottomHits >= 3) return 'BOTTOM';
      } else {
        bottomHits = 0;
      }

      await tweenScroll(step(), CFG.scrollMs);
      const moved = scroller.scrollTop - before;

      if (progressed) idle = 0;
      else if (moved < 2) idle++;
      if (idle >= CFG.maxIdle) return 'STALLED';

      await waitFor(
        () => rowsNow().some((r) => { const k = rawIdx(r); return k != null && !tracks.has(k); }),
        atBottom ? 350 : 700,
      );
    }
    return state.stopped ? 'USER_HALTED' : 'CEILING';
  }

  const reason = await crawl();

  // ---- finish -------------------------------------------------------------
  const result = [...tracks.values()].sort((a, b) => a.rowRaw - b.rowRaw).map(withIndex);
  window.__spider.tracks = result;
  emit({ type: 'FINISHED', reason, captured: result.length, expected });

  boxes.forEach((x) => { x.rate = 2.5; });
  strands.forEach((x) => { x.rate = 4.0; });
  state.done = true;
  await sleep(800);
  cancelAnimationFrame(state.raf);
  window.removeEventListener('resize', fit);

  console.log(`[spider] ${reason}: captured ${result.length}${expected ? ' / ' + expected : ''} in ${((performance.now() - t0) / 1000).toFixed(1)}s`);
  return result;
})();
