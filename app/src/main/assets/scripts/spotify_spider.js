/*
 * Spotify procedural rappelling spider crawler & high-velocity queue pipeline
 *
 * Features:
 * - Brute-Force Shotgun Scroll Engine: scrollLastRowIntoView(), scrollTop mutation, event dispatching
 * - Rappelling Silk Strand: Spider hangs from top ceiling on a shimmering neon silk thread
 * - High-Velocity Extraction: Fast background DOM scraping (~3-5s for 330 tracks)
 * - Recommendation Boundary Filter: Excludes "Recommended / Fans also like" sections (never captures track 331)
 * - Correct Canvas Teardown Order: 60fps animation active during 600ms opacity fade-out AFTER FINISHED emission
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
    expected: null,        // null = read from page header
    resumeFromIndex: 0,    // starting row index on resume
    stepRatio: 0.75,       // scroll step = 75% of container height
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
  let base = null;

  // DOM helpers
  const rowsNow = () => [...document.querySelectorAll('[data-testid="tracklist-row"]')];

  const rawIdx = (row, fallbackIdx = 0) => {
    const n = parseInt(row.closest('[aria-rowindex]')?.getAttribute('aria-rowindex'), 10);
    if (Number.isFinite(n) && n > 0) return n;
    const all = rowsNow();
    const pos = all.indexOf(row);
    return pos >= 0 ? (pos + 1) : (fallbackIdx + 1);
  };

  function findScroller() {
    const candidates = [
      document.querySelector('[data-overlayscrollbars-viewport]'),
      document.querySelector('.Root__main-view [data-overlayscrollbars-viewport]'),
      document.querySelector('.os-viewport'),
      document.querySelector('.Root__main-view'),
      document.querySelector('main'),
      document.querySelector('[role="main"]'),
      rowsNow()[0]?.parentElement?.parentElement,
      rowsNow()[0]?.parentElement,
      document.scrollingElement,
      document.documentElement,
      document.body
    ].filter(Boolean);

    for (const el of candidates) {
      if (el.scrollHeight > el.clientHeight + 4) {
        const prev = el.scrollTop;
        el.scrollTop = prev + 1;
        if (el.scrollTop !== prev) {
          el.scrollTop = prev;
          return el;
        }
        el.scrollTop = prev;
      }
    }

    return document.querySelector('[data-overlayscrollbars-viewport]') ||
           document.querySelector('.Root__main-view [data-overlayscrollbars-viewport]') ||
           document.querySelector('main') ||
           document.scrollingElement;
  }

  let scroller = findScroller();
  const getScrollY = () => {
    if (!scroller || scroller === document.scrollingElement) return window.scrollY;
    return scroller.scrollTop;
  };

  function triggerEvents(el) {
    if (!el) return;
    try {
      el.dispatchEvent(new Event('scroll', { bubbles: true, cancelable: true }));
      el.dispatchEvent(new UIEvent('scroll', { bubbles: true, cancelable: true }));
    } catch (_) {}
  }

  function scrollLastRowIntoView() {
    const rows = rowsNow();
    if (rows.length > 0) {
      const lastRow = rows[rows.length - 1];
      try {
        lastRow.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
      } catch (_) {
        try { lastRow.scrollIntoView(false); } catch (_) {}
      }
    }
  }

  // Brute-force "shotgun" scroll fallback chain with scrollIntoView and event dispatching
  function performScroll(dy) {
    if (!scroller || !scroller.isConnected) scroller = findScroller();
    const before = scroller ? scroller.scrollTop : 0;

    // 1. Scroll last row into view so Spotify's React IntersectionObserver wakes up
    scrollLastRowIntoView();

    // 2. Adjust scroller.scrollTop
    if (scroller) {
      scroller.scrollTop = before + dy;
      triggerEvents(scroller);

      if (Math.abs(scroller.scrollTop - before) < 2) {
        try { scroller.scrollTo({ top: before + dy, behavior: 'instant' }); } catch (_) {}
        triggerEvents(scroller);
      }

      if (Math.abs(scroller.scrollTop - before) < 2 && scroller.firstElementChild) {
        try { scroller.firstElementChild.scrollTop += dy; } catch (_) {}
        triggerEvents(scroller.firstElementChild);
      }
    }

    // 3. Fallback window scroll
    if (!scroller || Math.abs((scroller ? scroller.scrollTop : 0) - before) < 2) {
      try { window.scrollBy(0, dy); } catch (_) {}
      triggerEvents(window);
      triggerEvents(document);
    }

    return scroller ? (scroller.scrollTop - before) : dy;
  }

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
    let thumbnailUrl = img ? (img.getAttribute('src') || img.src) : null;

    // Upgrade Spotify thumbnail resolution from 64x64 (00004851) to 640x640 HD (0000b273)
    if (thumbnailUrl) {
      thumbnailUrl = thumbnailUrl
        .replace('00004851', '0000b273')
        .replace('00001e02', '0000b273');
    }

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

  // ---- Canvas setup (capped DPR for optimal 60fps & delayed opacity fade-in)
  const canvas = document.createElement('canvas');
  canvas.id = '__spider_canvas';
  Object.assign(canvas.style, {
    position: 'fixed',
    top: 0,
    left: 0,
    zIndex: 999999,
    pointerEvents: 'none',
    opacity: '0',
    transition: 'opacity 0.6s ease'
  });
  document.body.appendChild(canvas);
  setTimeout(() => { canvas.style.opacity = '1'; }, 50);

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

  // ---- Spider Anatomy & Rappelling Arachnid Rig ----------------------------
  const S = Math.min(1.25, Math.max(0.9, innerWidth / 1300));
  const L1 = 78 * S;  // Femur length
  const L2 = 96 * S;  // Tibia length
  const REACH = L1 + L2; // ~174 * S

  const LEG_CONFIGS = [
    // LEFT LEGS (side = -1)
    { id: 0, side: -1, group: 0, hip: { x: -9, y: 12 },  rest: { x: -125, y: 55 } },
    { id: 1, side: -1, group: 1, hip: { x: -12, y: 4 },  rest: { x: -155, y: 18 } },
    { id: 2, side: -1, group: 0, hip: { x: -12, y: -4 }, rest: { x: -150, y: -18 } },
    { id: 3, side: -1, group: 1, hip: { x: -9, y: -12 }, rest: { x: -115, y: -55 } },

    // RIGHT LEGS (side = 1)
    { id: 4, side: 1,  group: 1, hip: { x: 9, y: 12 },   rest: { x: 125, y: 55 } },
    { id: 5, side: 1,  group: 0, hip: { x: 12, y: 4 },   rest: { x: 155, y: 18 } },
    { id: 6, side: 1,  group: 1, hip: { x: 12, y: -4 },  rest: { x: 150, y: -18 } },
    { id: 7, side: 1,  group: 0, hip: { x: 10, y: -12 }, rest: { x: 115, y: -55 } },
  ];

  const body = { x: innerWidth * 0.45, y: 220, vx: 0, vy: 0, angle: 0 };

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
    };
  });

  const boxes = [];
  const strands = [];

  const RANDOM_COLORS = [
    { c: [0, 240, 255],   fill: 0.22 }, // Electric Cyan
    { c: [255, 42, 133],  fill: 0.25 }, // Neon Magenta
    { c: [0, 230, 118],   fill: 0.22 }, // Lime Green
    { c: [179, 71, 255],  fill: 0.22 }  // Electric Violet
  ];

  const TIME_RE = /^(?:\d+:)?\d{1,2}:\d{2}$/;
  function isValidField(el) {
    if (!el || !el.isConnected) return false;
    const txt = (el.innerText || el.textContent || '').trim();
    if (!txt || txt.length === 0) return false;
    const r = el.getBoundingClientRect();
    return r.width >= 6 && r.height >= 5;
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
    return f;
  }

  // Pure GPU Canvas Scan Highlight
  function flashNeonBox(row) {
    const fields = fieldsOf(row);
    if (!fields.length) return;
    const targetField = fields[Math.floor(Math.random() * fields.length)];
    const r = targetField.el.getBoundingClientRect();
    if (r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight) {
      const colorObj = RANDOM_COLORS[Math.floor(Math.random() * RANDOM_COLORS.length)];
      boxes.push({
        el: targetField.el,
        x: r.left - 4,
        y: r.top - 2,
        w: r.width + 8,
        h: r.height + 4,
        s: colorObj,
        life: 0.85
      });

      strands.push({
        x1: body.x,
        y1: body.y + 10 * S,
        x2: r.left + r.width * 0.5,
        y2: r.top + r.height * 0.5,
        color: colorObj.c,
        life: 0.65
      });
    }
  }

  // ---- 60 FPS Animation Loop (Rappelling Silk Thread & Pendulum Dangle) ----
  let lastTs = performance.now();

  function animate(ts) {
    ts = ts || performance.now();
    const dt = Math.min(0.05, Math.max(0.001, (ts - (lastTs || ts)) / 1000 || 0.016));
    lastTs = ts;

    if (state.done && !boxes.length && !strands.length) return;
    ctx.clearRect(0, 0, innerWidth, innerHeight);

    // Gentle pendulum dangle sway for rappelling spider
    body.x = (innerWidth * 0.45) + Math.sin(ts * 0.0028) * 20 * S;
    body.y = 220 + Math.cos(ts * 0.0018) * 10 * S;
    body.angle = Math.sin(ts * 0.0028) * 0.12;

    // 1. Shimmering neon silk thread hanging from ceiling to spider
    ctx.beginPath();
    ctx.moveTo(body.x, 0);
    ctx.lineTo(body.x, body.y - 20 * S);
    ctx.strokeStyle = 'rgba(0, 240, 255, 0.65)';
    ctx.lineWidth = 1.8 * S;
    ctx.stroke();

    // Faint outer glow on silk thread
    ctx.beginPath();
    ctx.moveTo(body.x, 0);
    ctx.lineTo(body.x, body.y - 20 * S);
    ctx.strokeStyle = 'rgba(0, 240, 255, 0.20)';
    ctx.lineWidth = 4.5 * S;
    ctx.stroke();

    // 2. Highlight scan boxes
    for (let i = boxes.length - 1; i >= 0; i--) {
      const b = boxes[i];
      b.life -= dt * 2.2;
      if (b.life <= 0) { boxes.splice(i, 1); continue; }

      let bx = b.x, by = b.y, bw = b.w, bh = b.h;
      if (b.el && b.el.isConnected) {
        const fresh = b.el.getBoundingClientRect();
        bx = fresh.left - 4;
        by = fresh.top - 2;
        bw = fresh.width + 8;
        bh = fresh.height + 4;
      }

      if (by + bh < -10 || by > innerHeight + 10) continue;

      const a = Math.min(1, b.life);
      ctx.fillStyle = `rgba(${b.s.c[0]},${b.s.c[1]},${b.s.c[2]},${a * b.s.fill})`;
      ctx.fillRect(bx, by, bw, bh);
      ctx.strokeStyle = `rgba(${b.s.c[0]},${b.s.c[1]},${b.s.c[2]},${a * 0.95})`;
      ctx.lineWidth = 1.4;
      ctx.strokeRect(bx, by, bw, bh);
    }

    // 3. Neon laser scan threads
    for (let i = strands.length - 1; i >= 0; i--) {
      const s = strands[i];
      s.life -= dt * 3.2;
      if (s.life <= 0) { strands.splice(i, 1); continue; }

      const c = s.color || [0, 240, 255];
      ctx.strokeStyle = `rgba(${c[0]}, ${c[1]}, ${c[2]}, ${s.life * 0.85})`;
      ctx.lineWidth = 1.4;
      ctx.beginPath();
      ctx.moveTo(body.x, body.y + 10 * S);
      ctx.lineTo(s.x2, s.y2);
      ctx.stroke();
    }

    if (!state.done) {
      // Calculate leg positions
      const legPoints = legs.map((l) => {
        const hipWorld = localToWorld(l.cfg.hip.x, l.cfg.hip.y);
        const restWorld = localToWorld(l.cfg.rest.x, l.cfg.rest.y);
        const { knee, foot } = solveLeg(hipWorld.x, hipWorld.y, restWorld.x, restWorld.y, l.cfg.side);
        return { hip: hipWorld, knee, foot };
      });

      // Pass 1: Faint ambient white bloom
      ctx.beginPath();
      legPoints.forEach(({ hip, knee, foot }) => {
        ctx.moveTo(hip.x, hip.y);
        ctx.lineTo(knee.x, knee.y);
        ctx.lineTo(foot.x, foot.y);
      });
      ctx.strokeStyle = 'rgba(255, 255, 255, 0.12)';
      ctx.lineWidth = 3.0 * S;
      ctx.stroke();

      // Pass 2: Crisp thin white wireframe segment
      ctx.strokeStyle = 'rgba(255, 255, 255, 0.95)';
      ctx.lineWidth = 1.2 * S;
      ctx.stroke();

      legPoints.forEach(({ knee, foot }) => {
        ctx.fillStyle = '#ffffff';
        ctx.beginPath();
        ctx.arc(knee.x, knee.y, 2.4 * S, 0, Math.PI * 2);
        ctx.fill();

        ctx.fillStyle = 'rgba(240, 245, 255, 0.9)';
        ctx.beginPath();
        ctx.arc(foot.x, foot.y, 2.2 * S, 0, Math.PI * 2);
        ctx.fill();
      });

      // --- Body ---
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

  const expected = CFG.expected ?? parseExpected();
  const pageTitle = getPlaylistTitle();
  emit({ type: 'META', title: pageTitle, expectedCount: expected, countInferred: expected == null });

  console.log('[spider] scroller:', scroller, '| expected:', expected);

  // Fast-Forward Scroll Offset with Incremental Micro-Jump Hydration (PRD §10)
  if (CFG.resumeFromIndex > 0) {
    const targetScrollY = CFG.resumeFromIndex * 56;
    console.log('[spider] Incremental hydration resolver fast-forwarding to resumeFromIndex:', CFG.resumeFromIndex, '| Target Y:', targetScrollY);

    while ((scroller ? scroller.scrollTop : 0) < targetScrollY - 200 && !state.stopped) {
      const current = scroller ? scroller.scrollTop : 0;
      const stepJump = Math.min(600, targetScrollY - current);
      performScroll(stepJump);
      await sleep(250); // Await React virtual DOM hydration at each micro-jump
    }

    performScroll(targetScrollY - (scroller ? scroller.scrollTop : 0));
    await sleep(350); // Await full mounting of target row bounds
    await waitFor(() => rowsNow().length > 0, 15000);
  }

  // ---- High-Velocity Ingestion Loop (3-5s for 330 tracks) ------------------
  async function crawl() {
    let idle = 0, bottomHits = 0;
    while (!state.stopped && performance.now() - t0 < CFG.maxMs) {
      // 1) Extract all currently mounted DOM rows immediately at max speed (excluding recommended rows)
      const newlyFound = [];
      const currentRows = rowsNow();

      for (const row of currentRows) {
        // Boundary Guard: Stop immediately if expected count is reached (PRD §2.3)
        if (expected && tracks.size >= expected) break;

        const raw = rawIdx(row);
        if (raw == null || tracks.has(raw)) continue;
        const rec = readRow(row, raw);
        if (!rec) continue;

        tracks.set(raw, rec);
        newlyFound.push(rec);

        // Flash random neon scan box on newly parsed track
        flashNeonBox(row);
      }

      if (newlyFound.length && base === null) {
        const minRaw = Math.min(...tracks.keys());
        base = minRaw > 0 ? minRaw - 1 : 0;
      }

      // 2) Emit BATCH & PROGRESS immediately
      if (newlyFound.length) {
        const withIdx = newlyFound.map(withIndex);
        const unEmitted = withIdx.filter(t => !CFG.resumeFromIndex || (t.rowIndex != null && t.rowIndex > CFG.resumeFromIndex));
        if (unEmitted.length) {
          emit({ type: 'BATCH', tracks: unEmitted });
          emit({ type: 'PROGRESS', captured: tracks.size, expected, distinctIndexes: tracks.size, maxIndex: Math.max(0, ...tracks.keys()), phase: 'SCAN' });
        }
      }

      if (expected && tracks.size >= expected && tracks.size > 0 && base !== null) return 'COMPLETE';

      // 3) Rapid Auto-Scroll Step against virtual scroll container (brute-force shotgun fallback chain)
      const currentY = scroller ? scroller.scrollTop : 0;
      const scrollHeight = scroller ? scroller.scrollHeight : document.documentElement.scrollHeight;
      const clientHeight = scroller ? scroller.clientHeight : window.innerHeight;

      const isAtBottom = currentY > 50 && (currentY + clientHeight >= scrollHeight - 12);
      const isFullyCaptured = expected && tracks.size >= expected;
      const progressed = newlyFound.length > 0;

      if (isAtBottom && !progressed && isFullyCaptured) {
        if (++bottomHits >= 3) return 'BOTTOM';
      } else if (isAtBottom && !progressed) {
        // Nudge scroll up slightly and down to trigger React virtualization re-mount
        performScroll(-150);
        await sleep(250);
        performScroll(150);
        await sleep(250);
        bottomHits++;
      } else {
        bottomHits = 0;
      }

      // Perform scroll step using brute-force shotgun fallback chain
      const scrollAmount = Math.round(clientHeight * 0.75);
      const deltaMoved = performScroll(scrollAmount);

      if (progressed) idle = 0;
      else if (Math.abs(deltaMoved) < 2) idle++;
      if (idle >= CFG.maxIdle) return 'STALLED';

      // Brief 200ms wait for React virtual DOM hydration
      await sleep(200);
    }
    return state.stopped ? 'USER_HALTED' : 'CEILING';
  }

  let reason = await crawl();
  if (reason === 'COMPLETE' && tracks.size === 0) {
    reason = 'STALLED';
  }

  // ---- finish & graceful opacity fade-out -----------------------------------
  const result = [...tracks.values()].sort((a, b) => a.rowRaw - b.rowRaw).map(withIndex);
  window.__spider.tracks = result;
  emit({ type: 'FINISHED', reason, captured: result.length, expected });

  boxes.forEach((x) => { x.rate = 3.5; });
  strands.forEach((x) => { x.rate = 5.0; });

  // Correct Teardown Sequence: Keep canvas active during fade-out, set state.done = true AFTER 600ms sleep
  canvas.style.opacity = '0';
  await sleep(600);
  state.done = true;
  cancelAnimationFrame(state.raf);
  window.removeEventListener('resize', fit);
  canvas.remove();

  console.log(`[spider] ${reason}: captured ${result.length}${expected ? ' / ' + expected : ''} in ${((performance.now() - t0) / 1000).toFixed(1)}s`);
  return result;
})();
