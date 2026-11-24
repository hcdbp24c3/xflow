package com.alchemistaloha.xflow.core.client

/**
 * The JavaScript that computes the `x-client-transaction-id` header inside a
 * page of x.com. Copied verbatim from the Flutter app
 * (transaction_id_service.dart); do not reformat.
 */
object TransactionIdScript {
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
