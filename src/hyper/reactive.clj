(ns hyper.reactive
  "Reactive component infrastructure for hyper.

   Reactive components are sub-regions of a page that can re-render
   independently when their deps change, without triggering a full page
   re-render.  Each component:
   - Wraps its output in a div with a stable ID
   - Watches its deps (any Watchable source) for changes
   - Caches its last render output for use by partial re-renders
   - Sends a targeted Datastar fragment on partial re-render

   During full page renders, reactive components always re-execute their
   body because they may close over parent data (cursor values, function
   arguments, watched source snapshots) that changed but isn't tracked
   in deps.  The real performance win is the partial render path — when
   only a dep changes, the rest of the page is untouched.

   Deps are reference-counted via the watch system — shared deps across
   multiple components or tabs are only disposed when the last consumer
   releases them."
  (:require [clojure.set]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.context :as context]
            [hyper.protocols :as proto]
            [hyper.watch :as watch]))

;; ---------------------------------------------------------------------------
;; Component registration & cache
;; ---------------------------------------------------------------------------

(defn get-component
  "Retrieve a reactive component's registration from app-state."
  [app-state* tab-id component-id]
  (get-in @app-state* [:tabs tab-id :reactive-components component-id]))

(defn- inject-id
  "Inject a reactive component ID onto hiccup.  If the element already has
   an :id, uses it as the html-id and leaves the hiccup unchanged.
   Otherwise, adds the component-id as the :id.
   Returns [html-id hiccup]."
  [hiccup component-id]
  (let [[tag & rest] hiccup
        has-attrs?   (map? (first rest))
        attrs        (if has-attrs? (first rest) {})
        children     (if has-attrs? (next rest) rest)]
    (if-let [existing-id (:id attrs)]
      [(str existing-id) hiccup]
      [component-id (into [tag (assoc attrs :id component-id)] children)])))

(defn render-component
  "Render a reactive component during a full page render.

   Called inline from the reactive macro.  Always re-executes render-fn
   because the component body may close over parent data (cursor values,
   function arguments, watched source snapshots) that changed since the
   last render — and those values are not tracked as deps.

   The rendered HTML is cached so that `partial-render` (triggered when
   a dep changes between full renders) can send a targeted Datastar
   fragment without a full page re-render.  This partial render path is
   the real performance win of reactive components.

   Injects the component ID onto the returned hiccup element (or uses
   an existing :id if present).  No wrapper div is added."
  [app-state* tab-id component-id deps render-fn]
  ;; Track this component as live in the current render
  (when-let [acc context/*registered-reactive-ids*]
    (swap! acc conj component-id))
  (let [body             (render-fn)
        [html-id hiccup] (inject-id body component-id)
        html             (c/html hiccup)
        new-vals         (mapv deref deps)]
    (swap! app-state* assoc-in [:tabs tab-id :reactive-components component-id]
           {:render-fn   render-fn
            :deps        deps
            :dep-vals    new-vals
            :cached-html html
            :html-id     html-id})
    hiccup))

;; ---------------------------------------------------------------------------
;; Partial render (called from renderer thread for targeted updates)
;; ---------------------------------------------------------------------------

(defn partial-render
  "Re-render a single reactive component and return the HTML string
   for a targeted Datastar fragment.  Updates the cache.
   Returns nil if the component is no longer registered."
  [app-state* tab-id component-id]
  (when-let [{:keys [render-fn deps]} (get-component app-state* tab-id component-id)]
    (let [;; Bind request context for the partial render
          tab-state  (get-in @app-state* [:tabs tab-id])
          session-id (:session-id tab-state)
          req        {:hyper/session-id session-id
                      :hyper/tab-id     tab-id
                      :hyper/app-state  app-state*
                      :hyper/router     (:router @app-state*)}]
      (push-thread-bindings (context/partial-render-bindings req))
      (try
        (let [body             (render-fn)
              [html-id hiccup] (inject-id body component-id)
              html             (c/html hiccup)
              new-vals         (mapv deref deps)]
          (swap! app-state* assoc-in [:tabs tab-id :reactive-components component-id]
                 {:render-fn   render-fn
                  :deps        deps
                  :dep-vals    new-vals
                  :cached-html html
                  :html-id     html-id})
          html)
        (finally
          (pop-thread-bindings))))))

;; ---------------------------------------------------------------------------
;; Watch setup for reactive deps
;; ---------------------------------------------------------------------------

(defn setup-component-watches!
  "Set up watches on a reactive component's deps.  When any dep changes,
   enqueues a partial render for the component via the provided callback.
   Uses reference counting via retain/release on each dep.

   enqueue-partial! is a single-arg fn that takes a component-id and
   enqueues it for partial rendering on the renderer's queue."
  [app-state* tab-id component-id deps enqueue-partial!]
  (doseq [dep deps]
    (let [watch-key (keyword (str "reactive-" component-id "-" (System/identityHashCode dep)))]
      (watch/retain-source! app-state* dep)
      (proto/-add-watch dep watch-key
                        (fn [_old _new]
                          (enqueue-partial! component-id)))
      (swap! app-state* update-in [:tabs tab-id :reactive-watches component-id]
             (fnil assoc {}) watch-key dep))))

(defn teardown-component-watches!
  "Remove watches and release refcounts for a reactive component's deps."
  [app-state* tab-id component-id]
  (let [watches (get-in @app-state* [:tabs tab-id :reactive-watches component-id])]
    (doseq [[watch-key dep] watches]
      (proto/-remove-watch dep watch-key)
      (watch/release-source! app-state* dep))
    (swap! app-state* update-in [:tabs tab-id :reactive-watches] dissoc component-id)))

;; ---------------------------------------------------------------------------
;; Sweep stale components
;; ---------------------------------------------------------------------------

(defn sweep-stale-components!
  "Remove reactive components that were not re-registered during the last
   full render.  Tears down their watches and releases dep refcounts."
  [app-state* tab-id live-component-ids]
  (let [all-ids (set (keys (get-in @app-state* [:tabs tab-id :reactive-components])))
        stale   (clojure.set/difference all-ids live-component-ids)]
    (doseq [component-id stale]
      (teardown-component-watches! app-state* tab-id component-id)
      (swap! app-state* update-in [:tabs tab-id :reactive-components] dissoc component-id))))

(defn teardown-all-components!
  "Remove all reactive components for a tab.  Called on disconnect."
  [app-state* tab-id]
  (let [all-ids (keys (get-in @app-state* [:tabs tab-id :reactive-components]))]
    (doseq [component-id all-ids]
      (teardown-component-watches! app-state* tab-id component-id))
    (swap! app-state* update-in [:tabs tab-id] dissoc :reactive-components)))

(defn setup-new-component-watches!
  "Set up watches for reactive components that don't already have them.
   Called after each full render to wire up new/changed components.

   enqueue-partial! is a single-arg fn that takes a component-id and
   enqueues it for partial rendering on the renderer's queue."
  [app-state* tab-id enqueue-partial!]
  (let [components  (get-in @app-state* [:tabs tab-id :reactive-components])
        watched-ids (set (keys (get-in @app-state* [:tabs tab-id :reactive-watches])))]
    (doseq [[component-id {:keys [deps]}] components
            :when                         (not (contains? watched-ids component-id))]
      (setup-component-watches! app-state* tab-id component-id deps
                                enqueue-partial!))))
