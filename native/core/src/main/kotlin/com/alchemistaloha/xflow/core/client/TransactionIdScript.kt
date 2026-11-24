package com.alchemistaloha.xflow.core.client

/**
 * JavaScript run inside a page of x.com for the `x-client-transaction-id`
 * header.
 *
 * [embeddedGeneratorScript] is the generator of the Flutter app, copied
 * verbatim (transaction_id_service.dart); do not reformat. It no longer
 * produces an id: it looks for the loading frames under the class `.r-0`
 * and reads fixed key bytes, and X changed both. It is kept for reference
 * and is not run any more; [moduleGeneratorScript] replaced it.
 */
object TransactionIdScript {
    /** The import of the generator script, as a JavaScript regular expression literal. */
    const val MODULE_IMPORT_JS_REGEX: String =
        """/import\(\s*[`'"]\.\/([A-Za-z0-9_-]+\.js)[`'"]\s*\)\.then\(\s*\(?\s*(\w+)\s*\)?\s*=>\s*\2\.default\(\)\s*\)/g"""

    /**
     * Finds X's own generator in the loaded page and keeps it as the promise
     * `window.__xflowTxidModule`.
     *
     * The x.com client loads the generator as a separate script with
     * `import("./<name>.js").then(e => e.default())`, from the script that
     * sets the `x-client-transaction-id` header. This looks through the
     * scripts of the page for that import, loads the same script (the
     * browser gives back the instance the page already uses) and checks
     * that it returns an id. Nothing about the algorithm is assumed, so a
     * new build of the generator keeps working.
     */
    const val moduleGeneratorScript: String = """(function () {
  if (window.__xflowTxidModule) return true;
  window.__xflowTxidModule = (async function () {
    var seen = {}, urls = [];
    function add(u) {
      if (typeof u !== 'string' || seen[u]) return;
      if (!/^https:\/\/[a-z0-9.-]*(twimg\.com|x\.com)\/.*\.js(\?|${'$'})/.test(u)) return;
      seen[u] = 1;
      urls.push(u);
    }
    performance.getEntriesByType('resource').forEach(function (e) { add(e.name); });
    document.querySelectorAll('script[src],link[href]').forEach(function (n) { add(n.src || n.href); });
    var header = 'x-client-transaction-id';
    var tried = 0;
    for (var i = 0; i < urls.length; i++) {
      var text;
      try {
        text = await (await fetch(urls[i], { cache: 'force-cache' })).text();
      } catch (e) { continue; }
      var at = text.indexOf(header);
      if (at < 0) continue;
      var re = $MODULE_IMPORT_JS_REGEX;
      var candidates = [], m;
      while ((m = re.exec(text))) {
        candidates.push({ name: m[1], distance: Math.abs(m.index - at) });
      }
      candidates.sort(function (a, b) { return a.distance - b.distance; });
      for (var c = 0; c < candidates.length && c < 3; c++) {
        tried++;
        try {
          var mod = await import(new URL('./' + candidates[c].name, urls[i]).href);
          var fn = await mod.default();
          if (typeof fn !== 'function') continue;
          var probe = await fn('/i/api/graphql/probe/Probe', 'GET');
          if (typeof probe === 'string' && /^[A-Za-z0-9+\/]{60,}${'$'}/.test(probe)) {
            return { fn: fn, scripts: urls.length };
          }
        } catch (e) {}
      }
    }
    throw new Error('generator module not found (scripts=' + urls.length + ' candidates=' + tried + ')');
  })();
  window.__xflowTxidModule.catch(function () {});
  return true;
})();"""

    const val embeddedGeneratorScript: String = """(() => {
  if (window.__xflowGenerateTransactionId) return;

  // Source-aligned port of fa0311/twitter-tid-deobf-fork output/additional2.js.
  const W = () => {
    let Sc;
    let zc;
    let Yc = [];

    const lc = (n) =>
      btoa(Array.from(n).map((n) => String.fromCharCode(n)).join('')).replace(
        /=/g,
        ''
      );

    const Jc = (n, t) => (n && n.getAttribute(t)) || '';

    const hc = (n) => (typeof n === 'string' ? new TextEncoder().encode(n) : n);

    const wc = (n) => crypto.subtle.digest('sha-256', hc(n));

    const pc = (n) => (n < 16 ? '0' : '') + n.toString(16);

    const Fc = (n) =>
      Array.from(n).map((n) => (n.parentElement?.removeChild(n), n));

    const Ac = (n, t, r) => (t ? n ^ r[0] : n);

    const Pc = () => {
      const n = Jc(document.querySelectorAll('[name^=tw]')[0], 'content');
      if (!n) return null;
      return new Uint8Array(atob(n).split('').map((n) => n.charCodeAt(0)));
    };

    const Uc = (n, t) =>
      (Sc =
        Sc ||
        Jc(Fc(document.querySelectorAll(n))[t[5] % 4]?.childNodes?.[0]?.childNodes?.[1], 'd')
          .substring(9)
          .split('C')
          .map((n) =>
            n
              .replace(/[^\d]+/g, ' ')
              .trim()
              .split(' ')
              .map(Number)
          ));

    const Mc = (n, t, r, u) => {
      const c = (n * (r - t)) / 255 + t;
      return u ? Math.floor(c) : c.toFixed(2);
    };

    const yc = (n) => ({
      color: [
        '#' + pc(n[0]) + pc(n[1]) + pc(n[2]),
        '#' + pc(n[3]) + pc(n[4]) + pc(n[5]),
      ],
      transform: ['rotate(0deg)', 'rotate(' + Mc(n[6], 60, 360, true) + 'deg)'],
      easing:
        'cubic-bezier(' +
        Array.from(n.slice(7))
          .map((n, t) => Mc(n, t % 2 ? -1 : 0, 1, false))
          .join() +
        ')',
    });

    const ensureProbe = () => {
      let probe = document.getElementById('__xflow_txid_probe');
      if (!probe) {
        probe = document.createElement('div');
        probe.id = '__xflow_txid_probe';
        probe.style.position = 'absolute';
        probe.style.left = '-9999px';
        probe.style.top = '-9999px';
        probe.style.width = '1px';
        probe.style.height = '1px';
        probe.style.pointerEvents = 'none';
        document.body.appendChild(probe);
      }
      return probe;
    };

    const kn = ensureProbe();
    const On = () => kn.remove();

    const Ic = (n, t, r) => {
      if (!n.animate) return;
      const u = n.animate(yc(t), 4096);
      u.pause();
      u.currentTime = Math.round(r / 10) * 10;
    };

    const Ec = (n) => {
      if (!zc) {
        const an = Uc('.r-0', n);
        if (!an || !an[n[33] % 16]) return null;

        new Promise(() => {
          try {
            const r = new RTCPeerConnection();
            const c = Math.random().toString(36);
            r.createDataChannel(c);
            r.createOffer()
              .then((W) => {
                try {
                  const t = W.sdp || c;
                  Yc = Array.from(hc([t[n[5] % 8] || '4', t[n[8] % 8] || '0']));
                  r.close();
                } catch (_) {}
              })
              .catch(() => 0);
          } catch (_) {}
        }).catch(() => 0);

        Ic(
          kn,
          an[n[33] % 16],
          (n[28] % 16) * (n[28] % 16) * (n[37] % 16)
        );
        const Gn = getComputedStyle(kn);
        zc = Array.from(
          ('' + Gn.color + Gn.transform).matchAll(/([\d.-]+)/g)
        )
          .map((n) => Number(Number(n[0]).toFixed(2)).toString(16))
          .join('')
          .replace(/[.-]/g, '');
        On();
      }
      return zc;
    };

    return async (n, t) => {
      const o = Math.floor((Date.now() - 1682924400000) / 1e3);
      const e = new Uint8Array(new Uint32Array([o]).buffer);
      const f = Pc();
      if (!f) return null;
      const i = Ec(f);
      if (!i) return null;

      return lc(
        new Uint8Array(
          [Math.random() * 256]
            .concat(
              Array.from(f),
              Array.from(e),
              Array.from(
                new Uint8Array(await wc([t, n, o].join('!') + 'obfiowerehiring' + i))
              )
                .slice(0, 16)
                .concat(Yc),
              [3]
            )
            .map(Ac)
        )
      );
    };
  };

  window.__xflowGenerateTransactionId = W();
})();
"""
}
