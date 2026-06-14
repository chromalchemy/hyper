(ns hyper.context
  "Request context and dynamic vars for hyper applications.

   Lives in a low-level namespace so that both render.clj and core.clj
   can reference these vars without circular dependencies.")

;; Dynamic var to hold current request context
(def ^:dynamic *request* nil)

;; Per-render action counter. Bound to (atom 0) before each render so that
;; deterministic render functions produce the same action IDs every time,
;; enabling effective brotli streaming compression.
(def ^:dynamic *action-idx* nil)

;; Datastar signal values parsed from the @post() request body during
;; action execution.  Bound to a keyword-keyed map by the action handler
;; so that Signal/deref can return the live client-side value.  nil
;; outside of an action context (i.e. during render).
(def ^:dynamic *signals* nil)

;; Accumulator for signal declarations emitted during a render pass.
;; Bound to (atom []) before each render so that (h/signal ...) and
;; (h/local-signal ...) can register themselves for HTML injection.
;; Each entry is a map with :html-name, :default-val, and :local? keys.
(def ^:dynamic *declared-signals* nil)

;; Accumulator for action IDs registered during a render pass.
;; Bound to (atom #{}) before each render so that register-action!
;; can track which action IDs are live.  After render, stale actions
;; (present in the previous cycle but absent from this one) are removed
;; in a single atomic swap — no cleanup-before-render gap needed.
(def ^:dynamic *registered-action-ids* nil)

;; State overlay for cursor read/write indirection.
;;
;; When non-nil, a map with:
;;   :state*  — shadow atom serving cursor reads / read-your-writes
;;   :ops*    — atom [] of ordered mutations (not values):
;;                {:kind :update :path p :f g}
;;                {:kind :reset  :path p :value v}
;;                {:kind :cas    :path p :old o :new n}
;;   :owner   — the Thread that created the overlay (see ownership below)
;;
;; Bound during render (read consistency) and inside `batch` (atomicity).
;; Cursors use the overlay when it's bound AND owned by the current thread;
;; otherwise they hit app-state* directly.  At the boundary the op-log is
;; replayed onto the live atom via `flush-overlay!` — replaying operations
;; (not absolute values) lets a swap!/update-style write compose with a
;; concurrent same-path write instead of clobbering it; reset! still
;; overwrites by design.  nil during action execution (without batch).
;;
;; Thread ownership: `future`, `send-off`, fibers, etc. convey dynamic
;; bindings, so background work spawned from a render/batch would inherit
;; an overlay whose boundary may already have flushed — silently routing
;; its writes into dead state.  The :owner stamp guards against this; see
;; `current-overlay`.
(def ^:dynamic *state-overlay* nil)

(defn current-overlay
  "Return the bound state overlay, but only when the current thread created
   it.  An overlay seen on any other thread is a conveyed binding (future /
   send-off / fiber spawned inside a render or batch) — treat it as absent
   so cursor reads/writes hit the live app-state directly.

   Future extension: a sanctioned `bound-fn`-style opt-in could let work
   on another thread deliberately participate in the owner's overlay by
   adopting its :owner; until then, ownership is strictly single-thread."
  []
  (when-let [overlay *state-overlay*]
    (when (identical? (:owner overlay) (Thread/currentThread))
      overlay)))

(defn apply-ops
  "Replay an ordered op-log against a state value, returning the new state.
   :update applies its fn to the current value (composing with concurrent
   writes), :reset overwrites the path, :cas writes only when the value
   still equals the expected old.  Pure — runs inside flush-overlay!'s
   swap!, so op fns must be side-effect free, as with clojure.core/swap!."
  [state ops]
  (reduce (fn [s {:keys [kind path] :as op}]
            (case kind
              :update (update-in s path (:f op))
              :reset  (assoc-in s path (:value op))
              :cas    (if (= (get-in s path) (:old op))
                        (assoc-in s path (:new op))
                        s)))
          state
          ops))

(defn flush-overlay!
  "Replay the current overlay's op-log onto the live atom in a single swap!.
   No-op when nothing was recorded, or the calling thread doesn't own the
   overlay."
  [app-state*]
  (when-let [{:keys [ops*]} (current-overlay)]
    (let [ops @ops*]
      (when (seq ops)
        (swap! app-state* apply-ops ops)))))

;; The :as name of the currently executing action, or nil when outside
;; an action context or when the action was not given an :as name.
;; Bound by the action handler in server.clj so that utility functions
;; called from within actions can identify which action is running
;; without the caller having to pass the name explicitly.
;;
;; Example:
;;   (h/action {:as "delete-user"}
;;     (audit! context/*action-name*)  ;; => "delete-user"
;;     (delete-user! id))
(def ^:dynamic *action-name* nil)

;; Accumulator for reactive component IDs registered during a render pass.
;; Bound to (atom #{}) before each full render so that the reactive macro
;; can track which components are live.  After render, stale components
;; (present in the previous cycle but absent from this one) are swept —
;; their watches are removed and deps released.
(def ^:dynamic *registered-reactive-ids* nil)

(defn render-bindings
  "Build the thread-binding map for a full render context.
   Includes a state overlay snapshot so cursor reads/writes are isolated.
   Returns a map suitable for `push-thread-bindings`."
  [req app-state*]
  {#'*request*                 req
   #'*action-idx*              (atom 0)
   #'*declared-signals*        (atom [])
   #'*registered-action-ids*   (atom #{})
   #'*registered-reactive-ids* (atom #{})
   #'*state-overlay*           {:state* (atom @app-state*)
                                :ops*   (atom [])
                                :owner  (Thread/currentThread)}})

(defn partial-render-bindings
  "Build the thread-binding map for a partial (reactive component) render.
   No state overlay — reads/writes go directly to the live atom."
  [req]
  {#'*request*                 req
   #'*action-idx*              (atom 0)
   #'*declared-signals*        (atom [])
   #'*registered-action-ids*   (atom #{})
   #'*registered-reactive-ids* (atom #{})
   #'*state-overlay*           nil})

(defn require-context!
  "Extract and validate the request context from *request*.
   Throws if called outside a request context or if required keys are missing.
   Returns a map with :session-id, :tab-id, :app-state*, and :router."
  [caller-name]
  (when-not *request*
    (throw (ex-info (str caller-name " called outside request context") {})))
  (let [session-id (:hyper/session-id *request*)
        tab-id     (:hyper/tab-id *request*)
        app-state* (:hyper/app-state *request*)]
    (when-not app-state*
      (throw (ex-info "No app-state in request" {:request *request*})))
    {:session-id session-id
     :tab-id     tab-id
     :app-state* app-state*
     :router     (:hyper/router *request*)}))
