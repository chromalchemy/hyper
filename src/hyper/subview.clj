(ns hyper.subview
  "Unified per-tab sub-region lifecycle registry.

   A *subview* is a managed region of a page that owns a slice of lifecycle:
   it subscribes to a set of Watchable `:deps`, optionally renders (the
   reactive case), and is swept when it disappears from the view tree.  This
   is the single mechanism the higher-level subsystems route through:

   - `hyper.reactive` (a render-bearing subview, `:on-change :partial`) —
     re-renders only itself when its deps change.
   - (future) `h/watch!` (a deps-only subview, `:on-change :full`) and
     `h/async` (a `:mount`/`:unmount`-bearing subview) plug into the same
     registry.

   State layout (per tab):
     [:tabs id :subviews        sid] -> the subview spec (see register-subview!)
     [:tabs id :subview-watches sid] -> {watch-key dep} bookkeeping for teardown

   Deps are reference-counted via `hyper.watch` so a source shared across
   subviews/tabs is only disposed when the last consumer releases it.  A
   subview's `:on-change` decides what a dep change triggers: `:partial`
   re-renders just this subview (a targeted Datastar fragment), `:full`
   triggers a full page re-render."
  (:require [clojure.set]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.context :as context]
            [hyper.protocols :as proto]
            [hyper.watch :as watch]
            [taoensso.telemere :as t]))

;; ---------------------------------------------------------------------------
;; Registry access
;; ---------------------------------------------------------------------------

(defn get-subview
  "Retrieve a subview's spec from app-state."
  [app-state* tab-id sid]
  (get-in @app-state* [:tabs tab-id :subviews sid]))

(defn- inject-id
  "Inject a subview ID onto hiccup.  If the element already has an :id, uses
   it as the html-id and leaves the hiccup unchanged.  Otherwise, adds the
   subview-id as the :id.  Returns [html-id hiccup]."
  [hiccup sid]
  (let [[tag & rest] hiccup
        has-attrs?   (map? (first rest))
        attrs        (if has-attrs? (first rest) {})
        children     (if has-attrs? (next rest) rest)]
    (if-let [existing-id (:id attrs)]
      [(str existing-id) hiccup]
      [sid (into [tag (assoc attrs :id sid)] children)])))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn register-subview!
  "Record/refresh a subview during a render, marking it live for this render
   cycle so it survives the post-render sweep.  Idempotent.

   `spec` keys (all optional unless noted):
   - :deps        vector of Watchable sources to subscribe (ref-counted)
   - :on-change   :partial (default) | :full — what a dep change triggers
   - :scope       :render (default) | :mount — sweep lifetime (see below)
   - :render-fn   thunk returning hiccup (render-bearing subviews)
   - :cached-html last rendered HTML string (for partial re-render)
   - :html-id     the element id targeted by partial fragments
   - :dep-vals    snapshot of (mapv deref deps) at last render
   - :mount       (fn [] -> resource) run once when the subview first appears
   - :unmount     (fn [resource] ...) run when the subview is swept/torn down
   - :resource    value returned by :mount, threaded into :unmount

   `:scope` decides the sweep lifetime: `:render` subviews (reactive regions)
   are re-registered every full render and swept when absent; `:mount`
   subviews (h/watch!, async workers — registered once in form-2 setup / a
   form-3 :mount) survive per-render sweeps and are torn down only on
   page-view remount (navigation) or tab disconnect.

   Returns the stored spec."
  [app-state* tab-id sid spec]
  (when-let [acc context/*registered-subview-ids*]
    (swap! acc conj sid))
  (let [spec (-> spec
                 (update :on-change #(or % :partial))
                 (update :scope #(or % :render)))]
    (swap! app-state* assoc-in [:tabs tab-id :subviews sid] spec)
    spec))

(defn render-reactive!
  "Render a render-bearing subview inline during a full page render.

   Always re-executes `render-fn` because the body may close over parent data
   (cursor values, fn args, watched snapshots) that changed but is not tracked
   in `deps`.  Injects the subview id onto the returned element (or uses an
   existing :id), caches the serialized HTML for later partial re-renders, and
   registers the subview as live.  Returns the hiccup.

   `:on-change` defaults to `:partial` — a dep change re-renders only this
   region."
  [app-state* tab-id sid deps render-fn]
  (let [body             (render-fn)
        [html-id hiccup] (inject-id body sid)
        html             (c/html hiccup)]
    (register-subview! app-state* tab-id sid
                       {:render-fn   render-fn
                        :deps        deps
                        :dep-vals    (mapv deref deps)
                        :cached-html html
                        :html-id     html-id
                        :on-change   :partial})
    hiccup))

;; ---------------------------------------------------------------------------
;; Partial render (called from the renderer thread for targeted updates)
;; ---------------------------------------------------------------------------

(defn partial-render
  "Re-render a single render-bearing subview and return the HTML string for a
   targeted Datastar fragment.  Updates the cache.  Returns nil when the
   subview is no longer registered or has no render-fn."
  [app-state* tab-id sid]
  (when-let [{:keys [render-fn deps] :as spec} (get-subview app-state* tab-id sid)]
    (when render-fn
      (let [tab-state  (get-in @app-state* [:tabs tab-id])
            session-id (:session-id tab-state)
            req        {:hyper/session-id session-id
                        :hyper/tab-id     tab-id
                        :hyper/app-state  app-state*
                        :hyper/router     (:router @app-state*)}]
        (push-thread-bindings (context/partial-render-bindings req))
        (try
          (let [body             (render-fn)
                [html-id hiccup] (inject-id body sid)
                html             (c/html hiccup)]
            (swap! app-state* assoc-in [:tabs tab-id :subviews sid]
                   (assoc spec
                          :dep-vals    (mapv deref deps)
                          :cached-html html
                          :html-id     html-id))
            html)
          (finally
            (pop-thread-bindings)))))))

;; ---------------------------------------------------------------------------
;; Dep watches (reference-counted)
;; ---------------------------------------------------------------------------

(defn setup-subview-watches!
  "Set up watches on a subview's deps.  When any dep changes, the callback
   fires according to `on-change`: `:full` invokes `trigger-render!` (a full
   page re-render), anything else (`:partial`) invokes `(enqueue-partial! sid)`.
   Uses reference counting via retain/release on each dep."
  [app-state* tab-id sid deps on-change trigger-render! enqueue-partial!]
  (doseq [dep deps]
    (let [watch-key (keyword (str "subview-" sid "-" (System/identityHashCode dep)))]
      (watch/retain-source! app-state* dep)
      (proto/-add-watch dep watch-key
                        (fn [_old _new]
                          (if (= on-change :full)
                            (when trigger-render! (trigger-render!))
                            (when enqueue-partial! (enqueue-partial! sid)))))
      (swap! app-state* update-in [:tabs tab-id :subview-watches sid]
             (fnil assoc {}) watch-key dep))))

(defn teardown-subview-watches!
  "Remove watches and release dep refcounts for a subview."
  [app-state* tab-id sid]
  (let [watches (get-in @app-state* [:tabs tab-id :subview-watches sid])]
    (doseq [[watch-key dep] watches]
      (proto/-remove-watch dep watch-key)
      (watch/release-source! app-state* dep))
    (swap! app-state* update-in [:tabs tab-id :subview-watches] dissoc sid)))

(defn setup-new-watches!
  "Set up dep watches for subviews that don't already have them.  Called after
   each full render to wire up new/changed subviews.

   `trigger-render!` is a zero-arg fn (full re-render) for `:full` subviews;
   `enqueue-partial!` is a one-arg fn (component-id) for `:partial` subviews.
   Either may be nil when that change policy is not in use."
  [app-state* tab-id trigger-render! enqueue-partial!]
  (let [subviews    (get-in @app-state* [:tabs tab-id :subviews])
        watched-ids (set (keys (get-in @app-state* [:tabs tab-id :subview-watches])))]
    (doseq [[sid {:keys [deps on-change]}] subviews
            :when                          (and (seq deps)
                                                (not (contains? watched-ids sid)))]
      (setup-subview-watches! app-state* tab-id sid deps (or on-change :partial)
                              trigger-render! enqueue-partial!))))

;; ---------------------------------------------------------------------------
;; Sweep / teardown
;; ---------------------------------------------------------------------------

(defn- run-unmount!
  "Run a subview's :unmount on its :resource (if any).  The seam for
   resource-owning subviews (form-3 regions, async workers)."
  [spec]
  (when-let [u (:unmount spec)]
    (u (:resource spec))))

(defn sweep-stale!
  "Remove *render-scoped* subviews that were not re-registered during the last
   full render: tear down their watches, release dep refcounts, and run any
   :unmount.  Mount-scoped subviews (h/watch!, async) are immune — they are
   torn down by `teardown-mount-scoped!` (on remount) or `teardown-all!` (on
   disconnect)."
  [app-state* tab-id live-ids]
  (let [subviews   (get-in @app-state* [:tabs tab-id :subviews])
        render-ids (set (keep (fn [[sid sv]]
                                (when (= :render (:scope sv)) sid))
                              subviews))
        stale      (clojure.set/difference render-ids live-ids)]
    (doseq [sid stale]
      (teardown-subview-watches! app-state* tab-id sid)
      (run-unmount! (get-subview app-state* tab-id sid))
      (swap! app-state* update-in [:tabs tab-id :subviews] dissoc sid))))

(defn teardown-mount-scoped!
  "Tear down all *mount-scoped* subviews for a tab (h/watch!, async workers).
   Called when the page-view remounts (navigation / handler redefinition) — the
   new mount re-registers whatever it still needs."
  [app-state* tab-id]
  (doseq [[sid sv] (get-in @app-state* [:tabs tab-id :subviews])
          :when    (= :mount (:scope sv))]
    (teardown-subview-watches! app-state* tab-id sid)
    (run-unmount! sv)
    (swap! app-state* update-in [:tabs tab-id :subviews] dissoc sid)))

(defn teardown-all!
  "Remove all subviews for a tab.  Called on disconnect."
  [app-state* tab-id]
  (doseq [sid (keys (get-in @app-state* [:tabs tab-id :subviews]))]
    (teardown-subview-watches! app-state* tab-id sid)
    (run-unmount! (get-subview app-state* tab-id sid)))
  (swap! app-state* update-in [:tabs tab-id] dissoc :subviews))

;; ---------------------------------------------------------------------------
;; Single-subview wiring + introspection
;; ---------------------------------------------------------------------------

(defn register-watch!
  "Register an external `source` as a full-render subview keyed by source
   identity (dedup), returning the subview id.  This is the shared primitive
   behind every kind of external-source watch:

   - `:mount` scope (default) — user `h/watch!` and route-level `:watches`;
     torn down on page-view remount (navigation) / disconnect.
   - `:tab` scope — framework watches (routes/head Vars, the component
     registry); set up once at SSE connect, torn down only on disconnect.

   A source watched via several paths collapses to a single subview that
   triggers a full re-render on change and is wired by `setup-new-watches!`."
  ([app-state* tab-id source]
   (register-watch! app-state* tab-id source :mount))
  ([app-state* tab-id source scope]
   (let [sid (str "w_" tab-id "_" (System/identityHashCode source))]
     (register-subview! app-state* tab-id sid
                        {:deps [source] :on-change :full :scope scope})
     sid)))

(defn wire-subview!
  "Wire a single subview's dep watches immediately, if it has deps and is not
   already wired (dedup against :subview-watches).  Used by h/watch! to attach
   the watch the moment it is registered when a renderer is already present;
   otherwise `setup-new-watches!` wires it on the next full render."
  [app-state* tab-id sid trigger-render! enqueue-partial!]
  (when-let [{:keys [deps on-change]} (get-subview app-state* tab-id sid)]
    (when (and (seq deps)
               (not (contains? (get-in @app-state* [:tabs tab-id :subview-watches]) sid)))
      (setup-subview-watches! app-state* tab-id sid deps (or on-change :partial)
                              trigger-render! enqueue-partial!))))

;; ---------------------------------------------------------------------------
;; Worker subviews (h/spawn!)
;; ---------------------------------------------------------------------------

(defn spawn-worker!
  "Register (idempotently) a mount-scoped background-worker subview keyed by
   `sid`.  On first registration spawns a virtual thread that runs
   `worker-fn` with `*request*` rebound to this tab's context, stores the
   `Thread` as the subview `:resource`, and installs an `:unmount` that
   interrupts it.

   The worker runs on a fresh virtual thread, which starts with its own
   thread-locals, so:
   - `*request*` is rebound explicitly (like the `action` macro) so cursor
     reads/writes resolve to this tab;
   - the render guard sees a clean thread, so the worker may write cursors
     freely (the whole point of a worker);
   - cursor writes hit the live app-state directly.

   Mount-scoped: it survives the per-render sweep and is torn down only on
   page-view remount (navigation / handler redefinition) or tab disconnect,
   at which point `:unmount` interrupts the thread.

   Idempotent: a form-1 render body re-invokes `h/spawn!` on every render, so
   only the first occurrence (when `sid` is absent) actually spawns; later
   renders are a no-op."
  [app-state* tab-id sid session-id router worker-fn]
  (when-let [acc context/*registered-subview-ids*]
    (swap! acc conj sid))
  (when-not (get-subview app-state* tab-id sid)
    (let [env    (get-in @app-state* [:tabs tab-id :env])
          req    {:hyper/session-id session-id
                  :hyper/tab-id     tab-id
                  :hyper/app-state  app-state*
                  :hyper/router     router
                  :hyper/env        env}
          thread (Thread/startVirtualThread
                   (fn []
                     (binding [context/*request* req]
                       (try
                         (worker-fn)
                         (catch InterruptedException _ nil)
                         (catch Throwable e
                           (t/error! {:id :hyper.error/spawn-worker} e))))))]
      (register-subview! app-state* tab-id sid
                         {:scope    :mount
                          :resource thread
                          :unmount  (fn [^Thread t] (when t (.interrupt t)))})
      sid)))

;; ---------------------------------------------------------------------------
;; Async render-time data loading (h/async)
;; ---------------------------------------------------------------------------
;;
;; `h/async` is a render-time data loader = a `spawn!`-style worker + a backing
;; status *cell* (a plain atom) + a `reactive` region that depends on the cell.
;;
;; Coordination state lives at [:tabs id :async component-id]:
;;   {:cell <atom {:status ... :result ... :error ...}>
;;    :dep-vals <snapshot of user deps>
;;    :thread   <the in-flight fetch VirtualThread, or nil>}
;;
;; The watched re-render paths are :data/:route/:signals/:global (see
;; hyper.watch/setup-watchers!); the `:async` store lives outside them, so
;; writing it during a render is invisible to the re-render trigger — like the
;; :subviews store.  The cell itself is watched (a dep of the region subview),
;; so a worker writing it drives a targeted partial re-render.

(defn- start-async-fetch!
  "Spawn a `*request*`-rebound virtual thread that runs `fetch-fn` and writes
   the outcome into `cell` ({:status :ready :result v} on success;
   {:status :error :error e :result <prior>} on failure — prior result is kept
   so a UI can show stale data alongside an error).  An interrupt (supersede /
   teardown) leaves the cell untouched: the new fetch or teardown owns it.
   Records the thread in the async store so a later refetch/teardown can
   interrupt it."
  [app-state* tab-id session-id router store-path cell fetch-fn]
  (let [env    (get-in @app-state* [:tabs tab-id :env])
        req    {:hyper/session-id session-id
                :hyper/tab-id     tab-id
                :hyper/app-state  app-state*
                :hyper/router     router
                :hyper/env        env}
        thread (Thread/startVirtualThread
                 (fn []
                   (binding [context/*request* req]
                     (try
                       (let [v (fetch-fn)]
                         (reset! cell {:status :ready :result v}))
                       (catch InterruptedException _ nil)
                       (catch Throwable e
                         (swap! cell (fn [s] {:status :error :error e :result (:result s)}))
                         (t/error! {:id :hyper.error/async-fetch} e))))))]
    (swap! app-state* assoc-in (conj (vec store-path) :thread) thread)))

(defn- coordinate-async!
  "Ensure the async coordination store at `store-path` is current for this
   render and return the status `cell`.

   - First sighting: create the cell ({:status :loading}), snapshot deps, and
     spawn the fetch.
   - User deps changed since last render: stale-while-revalidate — mark the
     cell {:status :reloading :result <prior>}, interrupt the in-flight fetch,
     re-snapshot deps, and spawn a fresh fetch.
   - Otherwise: reuse the existing cell (no refetch)."
  [app-state* tab-id session-id router store-path user-deps fetch-fn]
  (let [existing (get-in @app-state* store-path)
        cur-vals (mapv deref user-deps)]
    (cond
      (nil? existing)
      (let [cell (atom {:status :loading})]
        (swap! app-state* assoc-in store-path {:cell cell :dep-vals cur-vals :thread nil})
        (start-async-fetch! app-state* tab-id session-id router store-path cell fetch-fn)
        cell)

      (not= cur-vals (:dep-vals existing))
      (let [cell (:cell existing)]
        (swap! cell (fn [s] {:status :reloading :result (:result s)}))
        (when-let [^Thread old (:thread existing)] (.interrupt old))
        (swap! app-state* assoc-in store-path (assoc existing :dep-vals cur-vals))
        (start-async-fetch! app-state* tab-id session-id router store-path cell fetch-fn)
        cell)

      :else
      (:cell existing))))

(defn render-async!
  "Render an `h/async` region during a full page render.

   Coordinates the backing fetch (see `coordinate-async!`), renders the body
   via `(render-fn @cell)` where `@cell` is the status map
   `{:status :loading|:reloading|:ready|:error :result … :error …}`, injects
   the component id for targeted partial fragments, caches the HTML, and
   registers a render-scoped, partial-on-change subview whose deps are
   `[cell & user-deps]`:

   - a write to `cell` (the worker finishing) re-renders only this region;
   - a change to a user dep re-runs the body, whose coordination step detects
     the change and refetches (stale-while-revalidate).

   `:unmount` (run when the region is swept — it disappeared from the view
   tree — or on disconnect) interrupts any in-flight fetch and drops the
   coordination store.  The render body must return a single rooted hiccup
   element (as with `reactive`), so the id can be injected.  Returns the
   hiccup."
  [app-state* tab-id session-id router component-id user-deps fetch-fn render-fn]
  (let [store-path       [:tabs tab-id :async component-id]
        cell             (coordinate-async! app-state* tab-id session-id router
                                            store-path user-deps fetch-fn)
        deps             (into [cell] user-deps)
        body             (render-fn @cell)
        [html-id hiccup] (inject-id body component-id)
        html             (c/html hiccup)
        ;; The stored render-fn re-coordinates (so a partial re-render from a
        ;; user-dep change still refetches) then renders the current status.
        thunk            (fn []
                           (let [cell (coordinate-async! app-state* tab-id session-id router
                                                         store-path user-deps fetch-fn)]
                             (render-fn @cell)))]
    (register-subview! app-state* tab-id component-id
                       {:render-fn   thunk
                        :deps        deps
                        :dep-vals    (mapv deref deps)
                        :cached-html html
                        :html-id     html-id
                        :on-change   :partial
                        :scope       :render
                        :unmount     (fn [_]
                                       (let [{:keys [^Thread thread]} (get-in @app-state* store-path)]
                                         (when thread (.interrupt thread)))
                                       (swap! app-state* update-in [:tabs tab-id :async] dissoc component-id))})
    hiccup))

(defn watched-sources
  "The distinct external sources watched via mount-scoped, full-render
   subviews (i.e. h/watch!).  Used by `hyper.test` to report a render's
   watches."
  [app-state* tab-id]
  (->> (get-in @app-state* [:tabs tab-id :subviews])
       vals
       (filter #(and (= :mount (:scope %)) (= :full (:on-change %))))
       (mapcat :deps)
       distinct
       vec))
