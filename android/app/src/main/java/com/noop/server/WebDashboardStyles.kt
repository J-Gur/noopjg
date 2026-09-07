package com.noop.server

/**
 * Embedded stylesheet shared by every page in [WebDashboardPages] — no external stylesheet, since this
 * is served from an on-device HTTP server with no other assets to host.
 *
 * Values are pulled straight from the app's own design tokens (dark theme) so these pages read as NOOP
 * screens rather than generic HTML: colors from `DarkTokens` (`com.noop.ui.PaletteTokens`), spacing/radii
 * from `Metrics`, type sizes/weights from `NoopType` (both in `com.noop.ui.Theme`). The tile/hero-ring
 * gradients approximate `LiquidRender.kt`'s tint -> tint.darker(28%) top-to-bottom fill plus a soft
 * top-left white sheen — the real Liquid system is an animated fluid sim (`LiquidSim.kt`), far beyond
 * what a static served page can reproduce, so this is a deliberate, disclosed approximation of its
 * *look*, not its physics.
 */
internal fun styles(): String = """
    :root {
      --bg: #121518;             /* surfaceBase */
      --surface: #25292C;        /* surfaceRaised */
      --surface-2: #1C1F26;      /* surfaceOverlay — a touch darker, used for gradient bottoms */
      --hairline: #21304A;       /* hairline */
      --text: #F4F6F8;           /* textPrimary */
      --text2: #C8CFD8;          /* textSecondary */
      --text3: #8A94A4;          /* textTertiary */
      --accent: #69DDB8;         /* accent (mint) */
      --accent-hover: #54E6BD;   /* accentHover */
      --accent-muted: #163329;   /* accentMuted */
      --charge: #03E095;         /* chargeColor — Recovery's color world */
      --charge-deep: #0B9D62;    /* chargeDeep — gradient bottom for charge-tinted surfaces */
      --effort: #4090E0;         /* effortColor — Strain's color world */
      --effort-deep: #2A6FB0;    /* effortDeep */
      --critical: #E0662F;       /* statusCritical */
      --sleep-light: #4A90E2; --sleep-deep: #2F6FCB; --sleep-rem: #6FA8E8; --sleep-awake: #8A94A4;
      /* The five recovery-ramp stops (recovery000..100) — the exact colors the native RecoveryRing sweeps. */
      --rec-0: #E0463C; --rec-30: #E8743C; --rec-55: #F9DF4A; --rec-78: #8FD86A; --rec-100: #03E095;
      --radius: 18px;            /* Metrics.cardRadius */
      --radius-sm: 12px;         /* Metrics.cornerSm */
      --pad: 16px;               /* Metrics.cardPadding */
      --gap: 12px;               /* Metrics.gap */
      --section-gap: 28px;       /* Metrics.sectionGap */
      --screen-pad: 24px;        /* Metrics.screenPadding */
      --nav-height: 64px;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0; padding: var(--screen-pad) var(--screen-pad) calc(var(--nav-height) + var(--screen-pad));
      background: var(--bg); color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", Helvetica, Arial, sans-serif;
    }
    .screen { max-width: 480px; margin: 0 auto; animation: fadeIn 0.5s ease-out both; }
    .header { display:flex; align-items:center; justify-content:space-between; margin-bottom: var(--section-gap); }
    h1 { font-size: 28px; font-weight: 700; margin: 0; letter-spacing: -0.01em; } /* NoopType.title1 */
    .navlink { color: var(--accent); text-decoration: none; font-weight: 600; font-size: 15px; transition: color 0.15s ease; }
    .navlink:hover, .navlink:focus-visible { color: var(--accent-hover); }
    .navlink.back { display: inline-block; margin-top: var(--gap); }

    /* NoopType.overline: 11 / Bold / +1.4 tracking / caps — the app's section-header style. */
    .section-label {
      font-size: 11px; font-weight: 700; letter-spacing: 1.4px; text-transform: uppercase;
      color: var(--text3); margin: 0 2px 8px;
    }

    .card {
      background: linear-gradient(180deg, var(--surface) 0%, var(--surface-2) 100%);
      border: 1px solid var(--hairline);
      border-radius: var(--radius); padding: var(--pad); margin-bottom: var(--section-gap);
      position: relative; overflow: hidden;
    }
    /* The Liquid sheen: a soft white highlight near the top-left, same trick LiquidRender.kt uses for
       its "soft top-left highlight (radial)" on the hero vessel. */
    .card::before, .tile::before, .prominent-card::before {
      content: ""; position: absolute; inset: 0; pointer-events: none; border-radius: inherit;
      background: radial-gradient(ellipse 90% 60% at 22% 0%, rgba(255,255,255,0.07), transparent 55%);
    }

    .stat-row {
      display:flex; justify-content:space-between; align-items:baseline;
      padding: 8px 0; border-bottom: 1px solid var(--hairline); font-size: 15px; position: relative;
    }
    .stat-row:last-child { border-bottom: none; }
    .stat-row span { color: var(--text2); }
    .stat-row strong { color: var(--text); font-weight: 600; font-variant-numeric: tabular-nums; }

    .tile-grid { display:grid; grid-template-columns: 1fr 1fr; gap: var(--gap); margin-bottom: var(--section-gap); }
    .tile {
      background: linear-gradient(165deg, var(--surface) 0%, var(--surface-2) 100%);
      border: 1px solid var(--hairline); border-radius: var(--radius);
      padding: var(--pad); min-height: 108px; display:flex; flex-direction:column; justify-content:center;
      position: relative; overflow: hidden;
    }
    .tile .value { font-size: 32px; font-weight: 700; font-variant-numeric: tabular-nums; color: var(--text); position: relative; }
    .tile .label { font-size: 12px; color: var(--text3); margin-top: 4px; position: relative; }
    .tile-charge { background: linear-gradient(165deg, rgba(3,224,149,0.20) 0%, rgba(11,157,98,0.10) 100%); border-color: rgba(3,224,149,0.28); }
    .tile-charge .value { color: var(--charge); }
    .tile-effort { background: linear-gradient(165deg, rgba(64,144,224,0.20) 0%, rgba(42,111,176,0.10) 100%); border-color: rgba(64,144,224,0.28); }
    .tile-effort .value { color: var(--effort); }
    .vital-tile .sparkline { display: block; margin-top: 6px; width: 100%; height: 28px; position: relative; }

    /* Steps / Active Calories: a bigger, standalone card above the regular Vitals grid — see
       WebDashboardPages.prominentCard. No progress bar: no daily step/calorie goal exists in NOOP. */
    .prominent-grid { display:grid; grid-template-columns: 1fr 1fr; gap: var(--gap); margin-bottom: var(--section-gap); }
    .prominent-card {
      background: linear-gradient(165deg, var(--surface) 0%, var(--surface-2) 100%);
      border: 1px solid var(--hairline); border-radius: var(--radius);
      padding: 18px; position: relative; overflow: hidden;
    }
    .prominent-label {
      font-size: 11px; font-weight: 700; letter-spacing: 1.2px; text-transform: uppercase;
      color: var(--text3); margin-bottom: 8px; position: relative;
    }
    .prominent-value { font-size: 36px; font-weight: 700; font-variant-numeric: tabular-nums; color: var(--text); position: relative; }
    .prominent-unit { font-size: 14px; font-weight: 600; color: var(--text3); }
    .prominent-card .sparkline { display: block; margin-top: 10px; width: 100%; height: 36px; position: relative; }

    .button-grid { display:grid; grid-template-columns: 1fr 1fr; gap: var(--gap); margin-bottom: var(--gap); }
    .btn {
      text-decoration: none; text-align: center; border-radius: var(--radius); font-weight: 600;
      transition: transform 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
    }
    .btn-start {
      display:flex; align-items:center; justify-content:center; min-height: 64px; padding: 12px;
      background: var(--accent-muted); color: var(--accent); border: 1px solid var(--accent-muted);
      font-size: 15px;
    }
    .btn-start:hover, .btn-start:focus-visible { box-shadow: 0 0 0 3px rgba(105,221,184,0.25); }
    .btn-start:active { background: var(--accent); color: var(--bg); transform: scale(0.97); }
    .btn-stop {
      display:block; width:100%; padding: 16px; margin-top: 4px;
      background: var(--critical); color: var(--text); font-size: 16px; font-weight: 700;
    }
    .btn-stop:hover, .btn-stop:focus-visible { box-shadow: 0 0 0 3px rgba(224,102,47,0.30); }
    .btn-stop:active { transform: scale(0.98); }

    .status-card {
      display:flex; align-items:center; gap: 8px; background: var(--surface);
      border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: 12px var(--pad);
      font-size: 14px; color: var(--text2); margin-bottom: var(--section-gap);
    }
    .status-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--text3); flex-shrink: 0; }
    .status-card.active { color: var(--charge); }
    .status-card.active .status-dot { background: var(--charge); box-shadow: 0 0 8px var(--charge); }

    .workout-row { padding: 10px 0; border-bottom: 1px solid var(--hairline); }
    .workout-row:last-child { border-bottom: none; }
    .workout-row .sport { font-weight: 600; color: var(--text); }
    .workout-row .meta { font-size: 13px; color: var(--text3); margin-top: 2px; }
    .empty { color: var(--text3); font-size: 14px; margin: 0; }

    /* Recovery/Rest hero ring (SVG) — see WebDashboardPages.recoveryRingSvg. */
    .ring-section { display:flex; flex-direction:column; align-items:center; margin-bottom: var(--section-gap); }
    .ring-wrap { position: relative; }
    .ring-wrap svg { display: block; }
    .ring-value { animation: ringPop 0.6s cubic-bezier(.34,1.56,.64,1) both; animation-delay: 0.1s; transform-origin: center; }
    .ring-center {
      position: absolute; inset: 0; display:flex; flex-direction:column; align-items:center; justify-content:center;
    }
    .ring-value-text { font-size: 48px; font-weight: 700; color: var(--text); font-variant-numeric: tabular-nums; line-height: 1; }
    .ring-pct { font-size: 22px; color: var(--text3); font-weight: 600; }
    .ring-state {
      font-size: 12px; font-weight: 700; letter-spacing: 1.2px; text-transform: uppercase;
      color: var(--text3); margin-top: 4px;
    }
    .ring-caption { color: var(--text2); font-size: 14px; margin-top: 14px; text-align: center; }

    /* Sleep stage bar — real per-segment hypnogram when available, proportional fallback otherwise. */
    .stage-bar { display:flex; height: 22px; border-radius: 8px; overflow:hidden; background: var(--surface-2); }
    .stage-seg { height:100%; }
    .stage-awake { background: var(--sleep-awake); }
    .stage-light { background: var(--sleep-light); }
    .stage-deep  { background: var(--sleep-deep); }
    .stage-rem   { background: var(--sleep-rem); }
    .stage-legend { display:flex; gap: 14px; margin-top: 10px; flex-wrap: wrap; }
    .stage-legend-item { display:flex; align-items:center; gap: 6px; font-size: 12px; color: var(--text3); }
    .stage-legend-dot { width: 8px; height: 8px; border-radius: 50%; }

    /* Recovery Drivers ("what shaped it") rows. */
    .driver-row { display:flex; justify-content:space-between; align-items:center; padding: 10px 0; border-bottom: 1px solid var(--hairline); }
    .driver-row:last-child { border-bottom: none; }
    .driver-label { font-size: 14px; color: var(--text); font-weight: 500; }
    .driver-detail { font-size: 12px; color: var(--text3); margin-top: 2px; }
    .driver-chip {
      font-size: 13px; font-weight: 700; padding: 3px 10px; border-radius: 50px; font-variant-numeric: tabular-nums;
      flex-shrink: 0; margin-left: 12px;
    }
    .driver-chip.positive { background: rgba(3,224,149,0.16); color: var(--charge); }
    .driver-chip.negative { background: rgba(224,102,47,0.16); color: var(--critical); }
    .driver-chip.neutral { background: rgba(138,148,164,0.16); color: var(--text3); }

    /* Fitness Age card. */
    .fitness-age-hero { display:flex; align-items:baseline; gap: 10px; }
    .fitness-age-value { font-size: 40px; font-weight: 700; color: var(--text); font-variant-numeric: tabular-nums; }
    .fitness-age-unit { font-size: 15px; color: var(--text3); }
    .fitness-age-delta { font-size: 14px; font-weight: 600; margin-top: 6px; }
    .fitness-age-delta.positive { color: var(--charge); }
    .fitness-age-delta.negative { color: var(--critical); }
    .fitness-age-caption { font-size: 12px; color: var(--text3); margin-top: 8px; }

    /* Bottom tab bar. */
    .tab-bar {
      position: fixed; left: 0; right: 0; bottom: 0; height: var(--nav-height);
      display:flex; background: rgba(28,31,38,0.92); backdrop-filter: blur(12px);
      border-top: 1px solid var(--hairline);
    }
    .tab-item {
      flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap: 3px;
      text-decoration:none; color: var(--text3); font-size: 11px; font-weight: 600;
      transition: color 0.15s ease;
    }
    .tab-item .tab-dot { width: 5px; height: 5px; border-radius: 50%; background: transparent; margin-bottom: 1px; }
    .tab-item.active { color: var(--accent); }
    .tab-item.active .tab-dot { background: var(--accent); }
    .tab-item:hover { color: var(--text2); }

    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(8px); }
      to   { opacity: 1; transform: translateY(0); }
    }
    @keyframes ringPop {
      from { opacity: 0; transform: scale(0.82); }
      to   { opacity: 1; transform: scale(1); }
    }
""".trimIndent()
