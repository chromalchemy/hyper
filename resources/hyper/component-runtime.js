// hyper.component client runtime.
// Included verbatim in the assembled /hyper/components.js ES module, after
// the squint core import (`$sc`) and before the registered components.
//
// Responsibilities:
// - $define(name, spec): register a spec and define a shell custom element
//   class that delegates to the registry at call time (the indirection that
//   makes REPL hot-swap possible — customElements.define is once-only).
// - Attribute change gate: string equality, with microtask-batched updates
//   so a morph touching several attributes produces one render.
// - Attr parsing: JSON for collection/number/boolean-shaped values, raw
//   strings otherwise; parsed values cached per raw string.
// - Tiny hiccup->DOM interpreter for render output (squint compiles hiccup
//   literals to plain JS arrays with string tags).
// - Style inheritance: document stylesheets are cloned into each shadow
//   root so global CSS (e.g. Tailwind) styles component internals.
// - ctx.emit: bubbling, composed CustomEvents across the boundary.

// The spec registry is page-global (not module-scoped): a hot-swapped bundle
// is a *new* module instance, and its $define must see — and update — the
// same registry that live element instances (defined by an earlier module)
// read their specs from.
window.hyper = window.hyper || {};
window.hyper.components = window.hyper.components || { registry: new Map() };
const $registry = window.hyper.components.registry;

function $parseAttr(raw) {
  if (raw === null || raw === undefined) return null;
  const t = raw.trim();
  if (t === '') return raw;
  const c = t[0];
  if (c === '{' || c === '[' || c === '"' || c === '-' || (c >= '0' && c <= '9') ||
      t === 'true' || t === 'false' || t === 'null') {
    try { return JSON.parse(t); } catch (_e) { return raw; }
  }
  return raw;
}

function $parseTag(spec) {
  let tag = 'div';
  let id = null;
  const classes = [];
  for (const part of spec.split(/(?=[#.])/)) {
    if (part[0] === '#') id = part.slice(1);
    else if (part[0] === '.') classes.push(part.slice(1));
    else if (part) tag = part;
  }
  return { tag, id, classes };
}

function $setAttrs(el, attrs) {
  for (const k in attrs) {
    const v = attrs[k];
    if (v === null || v === undefined || v === false) continue;
    if (k === 'on' && typeof v === 'object') {
      for (const ev in v) el.addEventListener(ev, v[ev]);
    } else if (k === 'class') {
      // setAttribute, not el.className — className is read-only on SVG elements.
      const existing = el.getAttribute('class');
      el.setAttribute('class', existing ? existing + ' ' + v : String(v));
    } else if (k === 'style' && typeof v === 'object') {
      Object.assign(el.style, v);
    } else if (v === true) {
      el.setAttribute(k, '');
    } else {
      el.setAttribute(k, String(v));
    }
  }
}

const $SVG_NS = 'http://www.w3.org/2000/svg';

// Descriptor constructor for hiccup compiled at macro-expansion time by
// hyper.component/compile-hiccup. Tag/id/class parsing and attrs detection
// happened on the JVM — this path has no heuristics.
function $h(tag, id, cls, attrs, children) {
  return { $hd: 1, tag, id, cls, attrs, children };
}

function $appendHiccup(parent, h, ns) {
  if (h === null || h === undefined || h === false) return;
  if (h.$hd === 1) {
    // Compiled descriptor — the fast, unambiguous path.
    const childNs = h.tag === 'svg' ? $SVG_NS
                  : h.tag === 'foreignObject' ? null
                  : ns;
    const el = childNs
      ? document.createElementNS(childNs, h.tag)
      : document.createElement(h.tag);
    if (h.id) el.id = h.id;
    if (h.cls) el.setAttribute('class', h.cls);
    if (h.attrs) $setAttrs(el, h.attrs);
    for (const child of h.children) $appendHiccup(el, child, childNs);
    parent.appendChild(el);
    return;
  }
  if (typeof h === 'string') {
    parent.appendChild(document.createTextNode(h));
    return;
  }
  if (typeof h === 'number' || typeof h === 'boolean') {
    parent.appendChild(document.createTextNode(String(h)));
    return;
  }
  if (Array.isArray(h)) {
    if (typeof h[0] === 'string') {
      // element: ["div.gauge" {attrs}? & children]
      const { tag, id, classes } = $parseTag(h[0]);
      // SVG elements require the SVG namespace; children inherit it,
      // and <foreignObject> switches its subtree back to HTML.
      const childNs = tag === 'svg' ? $SVG_NS
                    : tag === 'foreignObject' ? null
                    : ns;
      const el = childNs
        ? document.createElementNS(childNs, tag)
        : document.createElement(tag);
      if (id) el.id = id;
      if (classes.length) el.setAttribute('class', classes.join(' '));
      let i = 1;
      if (h.length > 1 && h[1] !== null && typeof h[1] === 'object' &&
          !Array.isArray(h[1]) && !(h[1] instanceof Node) &&
          !(typeof h[1][Symbol.iterator] === 'function')) {
        $setAttrs(el, h[1]);
        i = 2;
      }
      for (; i < h.length; i++) $appendHiccup(el, h[i], childNs);
      parent.appendChild(el);
      return;
    }
    for (const child of h) $appendHiccup(parent, child, ns);
    return;
  }
  if (h instanceof Node) {
    parent.appendChild(h);
    return;
  }
  if (typeof h[Symbol.iterator] === 'function') {
    // lazy seqs / iterables from squint's for, map, etc.
    for (const child of h) $appendHiccup(parent, child, ns);
  }
}

function $adoptStyles(shadowRoot) {
  // Clone document stylesheets into the shadow root so global CSS reaches
  // component internals. Link clones hit the browser cache (no refetch).
  for (const node of document.querySelectorAll(
    'head link[rel="stylesheet"], head style')) {
    shadowRoot.appendChild(node.cloneNode(true));
  }
}

function $define(name, spec) {
  const existed = $registry.has(name);
  $registry.set(name, spec);
  if (!customElements.get(name)) {
    customElements.define(name, class extends HTMLElement {
      // Static — locked to the attrs present at first definition. A hot
      // reload that changes the attribute list requires a page refresh.
      static observedAttributes = (spec.attrs || []);

      constructor() {
        super();
        this.attachShadow({ mode: 'open' });
        this._cache = {};       // attr name -> {raw, parsed}
        this._scheduled = false;
        this._mounted = false;
        this._lastProps = null;
        // Stable per-instance context: carries emit and doubles as the
        // instance state slot for lifecycle code (e.g. (set! (.-chart ctx) ...)).
        const host = this;
        this._ctx = {
          emit: (evName, detail) => host.dispatchEvent(
            new CustomEvent(evName, { detail, bubbles: true, composed: true })),
        };
      }

      connectedCallback() {
        if (!this._root) {
          $adoptStyles(this.shadowRoot);
          this._root = document.createElement('div');
          this._root.setAttribute('data-hyper-root', '');
          this.shadowRoot.appendChild(this._root);
        }
        this._schedule();
      }

      disconnectedCallback() {
        // Debounce: morphs and DOM moves disconnect+reconnect synchronously.
        // Only a node still disconnected at microtask time is really gone.
        queueMicrotask(() => {
          if (!this.isConnected && this._mounted) {
            const s = $registry.get(name);
            if (s && s.unmount) s.unmount(this._lastProps, this._ctx, this._root);
            this._mounted = false;
          }
        });
      }

      attributeChangedCallback(_name, oldV, newV) {
        if (oldV === newV) return;   // the change gate
        this._schedule();
      }

      _schedule() {
        if (this._scheduled || !this.isConnected) return;
        this._scheduled = true;
        queueMicrotask(() => {
          this._scheduled = false;
          if (this.isConnected) this._flush();
        });
      }

      _props() {
        const s = $registry.get(name) || {};
        const props = {};
        for (const a of (s.attrs || [])) {
          const raw = this.getAttribute(a);
          const c = this._cache[a];
          if (c && c.raw === raw) {
            props[a] = c.parsed;
          } else {
            const parsed = $parseAttr(raw);
            this._cache[a] = { raw, parsed };
            props[a] = parsed;
          }
        }
        return props;
      }

      _renderInto(s, props) {
        if (!s.render) return;
        this._root.replaceChildren();
        $appendHiccup(this._root, s.render(props, this._ctx));
      }

      _flush() {
        const s = $registry.get(name);
        if (!s || !this._root) return;
        const oldProps = this._lastProps;
        const props = this._props();
        this._lastProps = props;
        if (!this._mounted) {
          // First flush (or remount after a real disconnect): render the
          // scaffold once, then hand the root to mount.
          this._renderInto(s, props);
          if (s.mount) s.mount(props, this._ctx, this._root);
          this._mounted = true;
        } else if (s.update) {
          // Seamless mode: update owns data changes — the scaffold DOM is
          // never re-rendered, so chart instances and transitions survive.
          s.update(props, this._ctx, this._root, oldProps);
        } else {
          // Declarative mode: re-render on every real change.
          this._renderInto(s, props);
        }
      }

      _reinit() {
        // Hot reload: tear down and remount against the new spec.
        const s = $registry.get(name);
        if (this._mounted && s && s.unmount) {
          s.unmount(this._lastProps, this._ctx, this._root);
        }
        this._mounted = false;
        this._cache = {};
        this._lastProps = null;
        if (this.isConnected) this._flush();
      }
    });
  } else if (existed) {
    // Hot reload: spec already swapped in the registry — reinit live
    // instances (unmount -> remount for lifecycle components, fresh
    // render for declarative ones).
    for (const el of document.querySelectorAll(name)) {
      if (el._reinit) el._reinit();
    }
  }
}

// Debug access from the console.
window.hyper.components.define = $define;
