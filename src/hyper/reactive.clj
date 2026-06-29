(ns hyper.reactive
  "Backwards-compatible shims for the reactive-component API.

   Reactive components are now just one kind of *subview* — a render-bearing,
   `:on-change :partial` region — managed by `hyper.subview`, the unified
   per-tab sub-region lifecycle registry.  This namespace preserves the
   original public function names and arities so existing call sites
   (`hyper.core/reactive`, `hyper.server`, `hyper.test`) keep working while
   delegating all behaviour to `hyper.subview`.

   New code should prefer `hyper.subview` directly."
  (:require [hyper.subview :as subview]))

(defn get-component
  "Retrieve a reactive component's registration from app-state."
  [app-state* tab-id component-id]
  (subview/get-subview app-state* tab-id component-id))

(defn render-component
  "Render a reactive component during a full page render.  Delegates to
   `subview/render-reactive!` (a render-bearing, partial-on-change subview).
   The 5-arity treats `component-id` as the fallback id (root `:id` still
   wins); the 6-arity passes a `key` (path-scoped identity) that takes
   precedence."
  ([app-state* tab-id component-id deps render-fn]
   (subview/render-reactive! app-state* tab-id nil component-id deps render-fn))
  ([app-state* tab-id key fallback-id deps render-fn]
   (subview/render-reactive! app-state* tab-id key fallback-id deps render-fn)))

(defn partial-render
  "Re-render a single reactive component and return the targeted-fragment HTML."
  [app-state* tab-id component-id]
  (subview/partial-render app-state* tab-id component-id))

(defn setup-component-watches!
  "Set up watches on a reactive component's deps (partial re-render on change)."
  [app-state* tab-id component-id deps enqueue-partial!]
  (subview/setup-subview-watches! app-state* tab-id component-id deps
                                  :partial nil enqueue-partial!))

(defn teardown-component-watches!
  "Remove watches and release refcounts for a reactive component's deps."
  [app-state* tab-id component-id]
  (subview/teardown-subview-watches! app-state* tab-id component-id))

(defn sweep-stale-components!
  "Remove reactive components not re-registered during the last full render."
  [app-state* tab-id live-component-ids]
  (subview/sweep-stale! app-state* tab-id live-component-ids))

(defn teardown-all-components!
  "Remove all reactive components for a tab.  Called on disconnect."
  [app-state* tab-id]
  (subview/teardown-all! app-state* tab-id))

(defn setup-new-component-watches!
  "Set up watches for reactive components that don't already have them.
   Reactive components are partial-on-change, so only `enqueue-partial!` is
   needed (no full-render trigger)."
  [app-state* tab-id enqueue-partial!]
  (subview/setup-new-watches! app-state* tab-id nil enqueue-partial!))
