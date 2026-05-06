(ns hyper.reactive
  "Reactive component infrastructure for hyper.

   Reactive components are sub-regions of a page that can re-render
   independently when their deps change, without triggering a full page
   re-render.  Each component:
   - Wraps its output in a div with a stable ID
   - Watches its deps (any Watchable source) for changes
   - Caches its last render output; returns cached HTML during full
     renders when deps haven't changed
   - Sends a targeted Datastar fragment on partial re-render

   Deps are reference-counted via the watch system — shared deps across
   multiple components or tabs are only disposed when the last consumer
   releases them."
  (:require [clojure.set]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.context :as context]
            [hyper.protocols :as proto]))

;; ---------------------------------------------------------------------------
;; Component registration & cache
;; ---------------------------------------------------------------------------

(defn get-component
  "Retrieve a reactive component's registration from app-state."
  [app-state* tab-id component-id]
  (get-in @app-state* [:tabs tab-id :reactive-components component-id]))

(defn- cache-valid?
  "Check if the cache is still valid: same dep sources and same values."
  [deps cached-deps cached-dep-vals]
  (and (some? cached-dep-vals)
       (= (count deps) (count cached-deps))
       (every? true? (map identical? deps cached-deps))
       (= (mapv deref deps) cached-dep-vals)))

(defn- inject-id
  "Inject a reactive component ID onto hiccup.  If the element already has
   an :id, uses it as the html-id and leaves the hiccup unchanged.
   Otherwise, adds the component-id as the :id.
   Returns [html-id hiccup]."
  [hiccup component-id]
  (let [[tag & rest] hiccup
        has-attrs? (map? (first rest))
        attrs      (if has-attrs? (first rest) {})
        children   (if has-attrs? (next rest) rest)]
    (if-let [existing-id (:id attrs)]
      [(str existing-id) hiccup]
      [component-id (into [tag (assoc attrs :id component-id)] children)])))

(defn render-component
  "Render a reactive component, using cache when deps are unchanged.

   During a full page render, this is called inline from the reactive macro.
   Injects the component ID onto the returned hiccup element (or uses an
   existing :id if present).  No wrapper div is added.

   If deps haven't changed since last render, returns the cached HTML
   without re-executing render-fn.  If deps changed, re-executes render-fn,
   caches the result, and returns fresh hiccup."
  [app-state* tab-id component-id deps render-fn]
  ;; Track this component as live in the current render
  (when-let [acc context/*registered-reactive-ids*]
    (swap! acc conj component-id))
  (let [existing    (get-component app-state* tab-id component-id)
        cached-deps (:deps existing)
        dep-vals    (:dep-vals existing)
        cached-html (:cached-html existing)]
    (if (and cached-html (cache-valid? deps cached-deps dep-vals))
      ;; Cache hit — return cached HTML without re-executing
      (c/raw cached-html)
      ;; Cache miss — render fresh
      (let [body              (render-fn)
            [html-id hiccup]  (inject-id body component-id)
            html              (c/html hiccup)
            new-vals          (mapv deref deps)]
        (swap! app-state* assoc-in [:tabs tab-id :reactive-components component-id]
               {:render-fn  render-fn
                :deps       deps
                :dep-vals   new-vals
                :cached-html html
                :html-id     html-id})
        hiccup))))

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
      (push-thread-bindings {#'context/*request*               req
                             #'context/*action-idx*            (atom 0)
                             #'context/*declared-signals*      (atom [])
                             #'context/*registered-action-ids* (atom #{})
                             #'context/*registered-reactive-ids* (atom #{})
                             #'context/*state-overlay*        nil})
      (try
        (let [body              (render-fn)
              [html-id hiccup]  (inject-id body component-id)
              html              (c/html hiccup)
              new-vals          (mapv deref deps)]
          (swap! app-state* assoc-in [:tabs tab-id :reactive-components component-id]
                 {:render-fn    render-fn
                  :deps         deps
                  :dep-vals     new-vals
                  :cached-html  html
                  :html-id      html-id})
          html)
        (finally
          (pop-thread-bindings))))))

;; ---------------------------------------------------------------------------
;; Watch setup for reactive deps
;; ---------------------------------------------------------------------------

(defn setup-component-watches!
  "Set up watches on a reactive component's deps.  When any dep changes,
   adds the component ID to the pending-partials set and fires the semaphore
   (without marking a full render as needed).
   Uses reference counting via retain/release on each dep.

   trigger-partial! is a zero-arg fn that fires the renderer semaphore
   without setting the full-render-needed flag."
  [app-state* tab-id component-id deps trigger-partial! pending-partials*]
  (doseq [dep deps]
    (let [watch-key (keyword (str "reactive-" component-id "-" (System/identityHashCode dep)))]
      ;; Retain refcount
      (swap! app-state* update-in [:source-refcounts dep] (fnil inc 0))
      ;; Add the watch
      (proto/-add-watch dep watch-key
                        (fn [_old _new]
                          (swap! pending-partials* conj component-id)
                          (trigger-partial!)))
      ;; Track the watch key for cleanup
      (swap! app-state* update-in [:tabs tab-id :reactive-watches component-id]
             (fnil assoc {}) watch-key dep))))

(defn teardown-component-watches!
  "Remove watches and release refcounts for a reactive component's deps."
  [app-state* tab-id component-id]
  (let [watches (get-in @app-state* [:tabs tab-id :reactive-watches component-id])]
    (doseq [[watch-key dep] watches]
      (proto/-remove-watch dep watch-key)
      ;; Release refcount — dispose if last consumer
      (let [new-state (swap! app-state* (fn [state]
                                          (let [n (dec (get-in state [:source-refcounts dep] 1))]
                                            (if (pos? n)
                                              (assoc-in state [:source-refcounts dep] n)
                                              (update state :source-refcounts dissoc dep)))))]
        (when-not (contains? (:source-refcounts new-state) dep)
          (proto/-dispose dep))))
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

   trigger-partial! fires the renderer semaphore without setting
   full-render-needed — so the renderer knows it can attempt a partial."
  [app-state* tab-id trigger-partial! pending-partials*]
  (let [components    (get-in @app-state* [:tabs tab-id :reactive-components])
        watched-ids   (set (keys (get-in @app-state* [:tabs tab-id :reactive-watches])))]
    (doseq [[component-id {:keys [deps]}] components
            :when (not (contains? watched-ids component-id))]
      (setup-component-watches! app-state* tab-id component-id deps
                                trigger-partial! pending-partials*))))
