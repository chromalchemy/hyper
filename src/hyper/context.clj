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

;; Snapshot of @app-state* taken at the start of a render cycle, wrapped
;; in a volatile for single-thread mutation.  During render, Cursor/deref
;; reads from this snapshot instead of the live atom, guaranteeing a
;; consistent point-in-time view immune to concurrent action mutations.
;; Cursor writes (reset!, swap!) update both the live atom AND this
;; snapshot so that default-value initialization and intra-render writes
;; are visible to subsequent reads within the same render pass.
;; nil during action execution so that actions always see/mutate live state.
(def ^:dynamic *state-snapshot* nil)

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
