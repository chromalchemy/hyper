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
;;   :state*  — atom holding the shadow state (cursor reads/writes go here)
;;   :paths*  — atom #{} of full-paths that were written (for selective flush)
;;
;; Bound during render (snapshot for read consistency) and inside the
;; `batch` macro (deferred writes for atomicity).  Cursors check this var
;; first: if bound, reads/writes go through the overlay; otherwise they
;; hit app-state* directly.
;;
;; At the boundary end (render complete / batch body returns), written
;; paths are flushed to app-state* in a single swap! via `flush-overlay!`.
;; nil during action execution (without batch) so that actions see/mutate
;; live state and each cursor write fires the watcher immediately.
(def ^:dynamic *state-overlay* nil)

(defn flush-overlay!
  "Flush written paths from the current overlay to the live atom.
   Only touches paths that were actually written during the overlay's
   lifetime, preserving concurrent changes to other paths.
   No-op when no paths were written."
  [app-state*]
  (when-let [{:keys [state* paths*]} *state-overlay*]
    (let [shadow @state*
          paths  @paths*]
      (when (seq paths)
        (swap! app-state* (fn [live]
                            (reduce (fn [s p]
                                      (assoc-in s p (get-in shadow p)))
                                    live
                                    paths)))))))

;; Accumulator for reactive component IDs registered during a render pass.
;; Bound to (atom #{}) before each full render so that the reactive macro
;; can track which components are live.  After render, stale components
;; (present in the previous cycle but absent from this one) are swept —
;; their watches are removed and deps released.
(def ^:dynamic *registered-reactive-ids* nil)

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
