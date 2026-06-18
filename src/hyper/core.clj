(ns hyper.core
  "Public API for the hyper web framework.

   Provides:
   - global-cursor, session-cursor, tab-cursor, and path-cursor for state management
   - action macro for handling user interactions
   - navigate function for SPA navigation
   - watch! for observing external state sources
   - create-handler for building ring handlers"
  (:require [clojure.string :as str]
            [hyper.actions :as actions]
            [hyper.client-params :as client-params]
            [hyper.component :as component]
            [hyper.context :as context :refer [*request* *action-idx*]]
            [hyper.expr]
            [hyper.lifecycle :as lifecycle]
            [hyper.reactive :as reactive]
            [hyper.render :as render]
            [hyper.routes :as routes]
            [hyper.server :as server]
            [hyper.signal :as signal]
            [hyper.state :as state]
            [hyper.subview :as subview]
            [hyper.utils :as utils]
            [reitit.core :as reitit]))

(defn global-cursor
  "Create a cursor to global state at the given path.
   Global state is shared across all sessions and tabs — a change to global
   state triggers a re-render for every connected tab.

   Path can be a keyword or vector.
   If default-value is provided and the path is nil, initializes with default-value.

   Example:
     (global-cursor :theme)
     (global-cursor [:config :feature-flags])
     (global-cursor :user-count 0)"
  ([path]
   (let [{:keys [app-state*]} (context/require-context! "global-cursor")]
     (state/global-cursor app-state* path)))
  ([path default-value]
   (let [{:keys [app-state*]} (context/require-context! "global-cursor")]
     (state/global-cursor app-state* path default-value))))

(defn session-cursor
  "Create a cursor to session state at the given path.
   Path can be a keyword or vector.
   If default-value is provided and the path is nil, initializes with default-value.

   Example:
     (session-cursor :user)
     (session-cursor [:user :name])
     (session-cursor :counter 0)"
  ([path]
   (let [{:keys [session-id app-state*]} (context/require-context! "session-cursor")]
     (state/session-cursor app-state* session-id path)))
  ([path default-value]
   (let [{:keys [session-id app-state*]} (context/require-context! "session-cursor")]
     (state/session-cursor app-state* session-id path default-value))))

(defn tab-cursor
  "Create a cursor to tab state at the given path.
   Path can be a keyword or vector.
   If default-value is provided and the path is nil, initializes with default-value.

   Example:
     (tab-cursor :count)
     (tab-cursor [:todos :list])
     (tab-cursor :count 0)"
  ([path]
   (let [{:keys [tab-id app-state*]} (context/require-context! "tab-cursor")]
     (state/tab-cursor app-state* tab-id path)))
  ([path default-value]
   (let [{:keys [tab-id app-state*]} (context/require-context! "tab-cursor")]
     (state/tab-cursor app-state* tab-id path default-value))))

(defn path-cursor
  "Create a cursor backed by URL query parameters.
   Reading returns the current value of the query param from the tab's route state.
   Writing updates the query param, which triggers a re-render and a replaceState
   to update the browser URL bar.

   Path can be a keyword or vector of keywords for the query param key(s).
   If default-value is provided and the query param is nil, initializes with default-value.

   Example:
     (path-cursor :count 0)     ;; URL: /?count=0
     (path-cursor :search \"\")   ;; URL: /?search=hello"
  ([path]
   (let [{:keys [tab-id app-state*]} (context/require-context! "path-cursor")]
     (state/create-cursor app-state* [:tabs tab-id :route :query-params] path)))
  ([path default-value]
   (let [{:keys [tab-id app-state*]} (context/require-context! "path-cursor")
         cursor                      (state/create-cursor
                                       app-state*
                                       [:tabs tab-id :route :query-params]
                                       path)]
     ;; cas so the default init yields to a concurrent write (see state/Cursor).
     (compare-and-set! cursor nil default-value)
     cursor)))

(defn watch!
  "Watch an external source for changes, triggering a re-render of the current
   tab when it changes. Source must satisfy the hyper.protocols/Watchable protocol
   (extended by default for atoms, refs, vars, and any IRef).

   Idempotent — safe to call on every render with the same source.
   Watches are automatically cleaned up when the tab disconnects.

   Example:
     ;; Watch a database query result atom
     (defn my-page [req]
       (watch! db-results)
       [:div [:p \"Count: \" (count @db-results)]])

     ;; Watch any Watchable source
     (watch! my-event-stream)"
  [source]
  (context/guard-effect! :watch "h/watch!")
  (let [{:keys [tab-id app-state*]} (context/require-context! "watch!")
        ;; A user watch is a mount-scoped, full-render subview: registered once
        ;; per mount (form-2 setup) or idempotently each render (form-1 body),
        ;; keyed by source identity for dedup.  Torn down on navigation
        ;; (page-view remount) and tab disconnect — never by the per-render sweep.
        sid                         (subview/register-watch! app-state* tab-id source)
        trigger-render!             (get-in @app-state* [:tabs tab-id :renderer :trigger-render!])]
    ;; Wire immediately when an SSE renderer already exists; otherwise the
    ;; first full render's setup-new-watches! wires it (initial HTTP render).
    (when trigger-render!
      (subview/wire-subview! app-state* tab-id sid trigger-render! nil))
    nil))

(defn spawn!
  "Spawn a background virtual-thread worker bound to the current view's
   lifecycle.  `worker-fn` is a zero-arg fn run once on a fresh virtual
   thread; the framework owns the thread handle and **interrupts it on
   unmount** (navigation away, route-handler redefinition, or tab
   disconnect).  Returns nil; a worker is fire-and-forget and communicates by
   writing state (cursors), which drives the usual declarative re-render.

   The worker runs on its own thread with `*request*` rebound, so cursor writes
   land and the same `tab-cursor`/`session-cursor`/`global-cursor`/`env` calls
   work inside it as in a handler.

   Mount-scoped and keyed by call order (like `reactive`), so a form-1 body
   that calls `spawn!` on every render still spawns exactly one worker.
   Prefer calling it from a **form-2 setup closure** (runs once per mount),
   where its intent — \"start this worker when the view mounts\" — is clearest:

     (defn ticker-page [req]
       (let [now* (h/tab-cursor :now)]
         (h/spawn!                           ;; setup — runs once per mount
           (fn []
             (loop []
               (reset! now* (System/currentTimeMillis))
               (Thread/sleep 1000)
               (recur))))                    ;; interrupted on unmount
         (fn [req] [:p \"Now: \" @now*])))     ;; render — pure

   For background work, prefer modeling results as state the worker writes;
   reach for `spawn!` only when you genuinely need a long-lived loop or
   blocking consumer tied to the view.  For one-shot data loading with a
   placeholder, prefer `async`."
  [worker-fn]
  (context/guard-effect! :spawn "h/spawn!")
  (let [{:keys [session-id tab-id app-state* router]} (context/require-context! "spawn!")
        idx                                           (if *action-idx* (swap! *action-idx* inc) 0)
        sid                                           (str "s_" tab-id "_" idx)]
    (subview/spawn-worker! app-state* tab-id sid session-id router worker-fn)
    nil))

(defn env
  "Get the request environment, or a specific key from it.

   Ring middleware can set `:hyper/env` on the request to provide context
   that persists across SSE re-renders and action handlers.  Hyper
   automatically stashes the env per-tab on each HTTP request (page load,
   action POST, navigation) and propagates it to every subsequent render
   and action execution.

   Use Ring middleware for I/O and request-dependent context (database
   connections, authenticated user, feature flags read from headers/cookies).
   Use render middleware to guard renders based on env (e.g. permission checks).

   Example:
     ;; Ring middleware sets :hyper/env
     (defn wrap-app-env [handler db]
       (fn [req]
         (handler (assoc req :hyper/env {:db db}))))

     ;; Read in a render function
     (defn my-page [req]
       (let [db (h/env :db)]
         [:div \"Connected to: \" (str db)]))

     ;; Read in an action
     [:button {:data-on:click (h/action
                                (let [db (h/env :db)]
                                  (db/insert! db ...)))}
      \"Save\"]"
  ([]
   (:hyper/env context/*request*))
  ([key]
   (get (:hyper/env context/*request*) key))
  ([key default]
   (get (:hyper/env context/*request*) key default)))

;; ---------------------------------------------------------------------------
;; Batched cursor updates
;; ---------------------------------------------------------------------------

(defmacro batch
  "Execute body with all cursor writes batched into a single atomic update.

   During the body, cursor reads see the accumulated writes (read-your-writes).
   After the body completes, all mutations are flushed to app-state* in a
   single swap!, so the renderer only sees the final state — never an
   intermediate one.

   Side effects (I/O, HTTP calls, DB queries) inside batch work normally —
   only cursor writes are deferred.

   Nested batches are transparent — the inner batch executes within the
   existing overlay and the outermost boundary handles the flush.

   A batch inside background work (future, send-off, fiber) spawned from a
   render or outer batch ignores the conveyed overlay and creates its own,
   flushing to the live app-state when its body completes.

   Example:
     ;; Without batch, the renderer might snapshot between cursor updates
     ;; and show a partial state (e.g. new data with loading still true).
     (h/action
       (h/batch
         (reset! (h/tab-cursor :data) (fetch-data!))
         (reset! (h/tab-cursor :loading?) false)))

     ;; Progress bar: use batch only for the atomic pair, leave the
     ;; intermediate :loading state unbatched so the renderer picks it up.
     (h/action
       (reset! (h/tab-cursor :loading?) true)     ;; immediate — shows spinner
       (let [data (fetch-data!)]
         (h/batch                                  ;; atomic — one render
           (reset! (h/tab-cursor :data) data)
           (reset! (h/tab-cursor :loading?) false))))"
  [& body]
  `(if (context/current-overlay)
     ;; Already inside an overlay owned by this thread — just execute;
     ;; the outer boundary flushes.  A conveyed overlay from another
     ;; thread does not count (see context/current-overlay).
     (do ~@body)
     ;; Fresh overlay — snapshot current state, execute, flush.
     (let [{app-state*# :app-state*} (context/require-context! "batch")
           overlay#                  {:state* (atom @app-state*#)
                                      :ops*   (atom [])
                                      :owner  (Thread/currentThread)}]
       (binding [context/*state-overlay* overlay#]
         (let [result# (do ~@body)]
           (context/flush-overlay! app-state*#)
           result#)))))

;; ---------------------------------------------------------------------------
;; Reactive components
;; ---------------------------------------------------------------------------

(defmacro reactive
  "Create a reactive component that re-renders independently when its deps change.

   deps is a vector of Watchable sources (atoms, cursors, or any type extending
   hyper.protocols/Watchable).  The body is a hiccup expression that will be
   wrapped in a div with a stable ID.

   When any dep changes, only this component re-renders and a targeted Datastar
   fragment is sent — the rest of the page is untouched.  During full page
   re-renders, the body is always re-executed (since it may close over parent
   data not tracked in deps) and the result is cached for future partial renders.

   Supports nesting — inner reactive blocks re-execute independently during
   partial renders triggered by their own deps.

   Usage:
     (let [clock* (h/global-cursor :clock)]
       (reactive [clock*]
         [:p \"The time is: \" @clock*]))

     ;; Multiple deps
     (let [x* (h/tab-cursor :x 0)
           y* (h/tab-cursor :y 0)]
       (reactive [x* y*]
         [:p \"Position: \" @x* \", \" @y*]))"
  [deps & body]
  `(let [{tab-id# :tab-id app-state*# :app-state*} (context/require-context! "reactive")
         idx#                                      (if context/*action-idx* (swap! context/*action-idx* inc) 0)
         component-id#                             (str "r_" tab-id# "_" idx#)
         deps#                                     ~deps
         render-fn#                                (fn [] ~@body)]
     (reactive/render-component app-state*# tab-id# component-id# deps# render-fn#)))

(defmacro async
  "Render-time data loading with a placeholder.  Spawns the `fetch` on a
   background virtual thread, renders a placeholder immediately, and re-renders
   *just this region* when the value lands — no manual loading-state plumbing.

   Shape (a sibling of `reactive`):

     (h/async [deps] fetch-expr binding & render-body)

   - `deps`        a vector of Watchable sources (like `reactive`).  `[]` means
                   fetch once per mount; otherwise a change to a dep refetches
                   (stale-while-revalidate — see `:reloading` below).
   - `fetch-expr`  a single expression evaluated on a background worker thread
                   while the placeholder renders (wrap multiple forms in `do`).
                   Blocking I/O is fine (it is a virtual thread).
   - `binding`     a destructuring form bound to the status map.
   - `render-body` hiccup rendered from the status; must return a single rooted
                   element (as with `reactive`) so the region id can be injected.

   The status map is `{:status :result :error}` where `:status` is one of:
   - `:loading`   — first load in flight; `:result` is nil.
   - `:ready`     — `:result` holds the fetched value (a nil result is
                    `{:status :ready :result nil}`).
   - `:error`     — `:error` holds the throwable; `:result` keeps the prior
                    value (if any) so you can show stale data with an error.
   - `:reloading` — a dep changed and a refetch is in flight; `:result` still
                    holds the previous value (stale-while-revalidate).

   Example:

     (defn rows-page [req]
       (let [user-id* (h/path-cursor :user 0)]
         (h/async [user-id*]
           (db/fetch-rows @user-id*)
           {:keys [status result error]}
           (case status
             :ready     (render-rows result)
             :error     [:p \"Failed: \" (ex-message error)]
             :reloading [:div.stale (render-rows result)]
             [:p \"Loading…\"]))))

   `async` is a *declaration*: rendering it registers a region and starts the
   fetch on a worker thread, so it belongs in the render body like `reactive`.
   The region is torn down (and any in-flight fetch interrupted) when it
   disappears from the view tree, on navigation, or on tab disconnect.

   Prefer `async` for leaf / region-local data that wants its own loading state
   co-located with its render.  For page-level data you want before first paint
   or shared across regions, load it in a form-2 setup closure into a cursor."
  [deps fetch-expr binding & render-body]
  `(let [{tab-id#     :tab-id
          app-state*# :app-state*
          session-id# :session-id
          router#     :router}    (context/require-context! "async")
         idx#                     (if context/*action-idx* (swap! context/*action-idx* inc) 0)
         component-id#            (str "async_" tab-id# "_" idx#)]
     (subview/render-async! app-state*# tab-id# session-id# router# component-id#
                            ~deps
                            (fn [] ~fetch-expr)
                            (fn [~binding] ~@render-body))))

;; ---------------------------------------------------------------------------
;; View lifecycle (form-3)
;; ---------------------------------------------------------------------------

(defn view
  "Declare a form-3 view that owns an external resource needing explicit
   teardown (a connection, a file handle, a subscription that is not a
   Watchable, etc.).

   A page handler returns a `view` instead of hiccup when it must allocate
   something at mount and release it at unmount.  The framework threads the
   resource immutably through the lifecycle — there is no mutable per-view
   slot, because the server always re-renders declaratively.

   Spec keys:
   - :render  (required) `(fn [resource req] -> hiccup)`.  `resource` is the
              value returned by `:mount` (nil when there is no `:mount`).
              Called on every render; must be pure.
   - :mount   (optional) `(fn [] -> resource)`.  Runs once when the view
              mounts; its return value is the resource.
   - :unmount (optional) `(fn [resource] -> any)`.  Runs once when the view
              unmounts (navigation away, handler redefinition, or tab
              disconnect).

   The view (re)mounts when the page first renders or the route handler
   identity changes; a superseded view is unmounted first.

   Prefer the simpler rungs of the ladder when you can: a pure `(fn [req] ->
   hiccup)` (form-1) when the view owns nothing, or a setup closure
   `(fn [req] (h/watch! …) (fn [req] hiccup))` (form-2) when it owns only
   framework-managed subscriptions.  Reach for `view` only for a genuine
   external resource — frequent need for it is a sign a resource should be
   modeled as Watchable or owned by the system layer (:hyper/env) instead.

   Example:
     (defn report-page [req]
       (h/view
         {:mount   (fn []          (db/open-cursor (h/env :db) :reports))
          :render  (fn [cursor req] [:ul (for [r (db/take! cursor 50)]
                                           [:li (:title r)])])
          :unmount (fn [cursor]     (db/close-cursor cursor))}))"
  [spec]
  (lifecycle/view spec))

;; ---------------------------------------------------------------------------
;; Signals
;; ---------------------------------------------------------------------------

(defn signal
  "Create a Datastar signal — a reactive client-side variable that syncs
   between the browser and server.

   During render, `@signal*` returns the Datastar expression string (e.g.
   `\"$userName\"`), suitable for use in `data-text`, `data-show`, etc.
   During action execution, `@signal*` returns the live value sent by
   Datastar in the `@post()` request body.

   `reset!` and `swap!` update the signal value on the server, which
   triggers a `datastar-patch-signals` SSE event to push the new value
   to the client.

   Path can be a keyword or a vector of keywords:
     (signal :name)                ;; → $name
     (signal :user-name \"default\") ;; → $userName
     (signal [:user :name] \"\")     ;; → $user.name

   Example:
     (let [name* (signal :name \"\")]
       [:div
        [:input (bind name*)]
        [:p {:data-text @name*} \"\"]])"
  ([path]
   (signal path nil))
  ([path default-value]
   (let [{:keys [tab-id app-state*]} (context/require-context! "signal")]
     (signal/create-signal app-state* tab-id path default-value))))

(defn local-signal
  "Create a local Datastar signal (underscore-prefixed).  Local signals
   are client-only: Datastar does not send them to the server, so
   `reset!` and `swap!` are not supported and `deref` in an action throws.

   During render, `@local*` returns the Datastar expression string
   (e.g. `\"$_open\"`), suitable for `data-show`, `data-text`, etc.
   The signal itself (without deref) can be used as a `data-bind` value.

   Path can be a keyword or a vector of keywords:
     (local-signal :open? false)   ;; → $_open
     (local-signal :show-menu false)  ;; → $_showMenu

   Example:
     (let [open?* (local-signal :open false)]
       [:div
        [:button {:data-on:click (str @open?* \" = !\" @open?*)} \"Toggle\"]
        [:div {:data-show @open?*} \"Content\"]])"
  ([path]
   (local-signal path nil))
  ([path default-value]
   (signal/create-local-signal path default-value)))

;; ---------------------------------------------------------------------------
;; Connection status signals (client-only, maintained by Datastar)
;; ---------------------------------------------------------------------------

(def connected?*
  "Static client-only boolean signal — true while the SSE connection is
   healthy, false while disconnected/reconnecting.  Maintained entirely
   client-side from Datastar's connection lifecycle (the server cannot report
   on a connection that is down).

   Deref in render/`expr` yields its Datastar expression; deref in an action
   throws (connection state is not server-readable).

     [:div {:data-show (h/expr (not @h/connected?*))} \"Reconnecting…\"]"
  signal/connected?*)

(def connection*
  "Static client-only signal holding the SSE connection status as a keyword
   token from `connection-states` (`:connecting`, `:open`, `:reconnecting`,
   `:error`, `:closed`).  Use it for richer connection UX; compare against
   keyword tokens (they compile to the wire string):

     [:span {:data-show (h/expr (= @h/connection* :reconnecting))} \"Reconnecting…\"]
     [:span {:data-show (h/expr (= @h/connection* :error))}        \"Connection lost\"]"
  signal/connection*)

(def connection-states
  "The set of keyword tokens `connection*` may hold."
  signal/connection-states)

(defn reconnect
  "Return a Datastar expression that re-opens this tab's SSE connection, for
   binding to an event attribute — e.g. a \"Retry\" button shown when
   `connection*` is `:error`:

     [:button {:data-on:click (h/reconnect)} \"Retry\"]

   This is a *soft* reconnect: it re-attaches to the still-living tab (within
   the disconnect grace window), so cursor state, signals, and workers are
   preserved — unlike a full page reload, which starts a fresh tab.  Useful for
   the connection states Datastar does not auto-retry (`:error` / `:closed`).

   Reuses the exact same `@get` the page booted with (endpoint, base-path, and
   `openWhenHidden`), so the two can't drift.  Must be called in render
   context."
  []
  (let [{:keys [tab-id app-state*]} (context/require-context! "reconnect")
        base-path                   (get @app-state* :base-path "")
        owh?                        (get @app-state* :open-when-hidden? true)]
    (server/sse-connect-expr base-path tab-id owh?)))

;; ---------------------------------------------------------------------------
;; Client param support for actions
;; ---------------------------------------------------------------------------

(defn- find-client-params
  "Walk the action body forms and return the subset of the defined client-params
   whose symbols appear in the body.
   
   Returns a map of symbols (that appear in the body) to their definition."
  [body]
  (let [all-syms (set (filter symbol? (tree-seq coll? seq body)))]
    (->> (client-params/defined-client-params)
         (filter all-syms)
         (reduce (fn [m sym]
                   (assoc m sym (client-params/client-param sym)))
                 {}))))

(defmacro action
  "Create a server action expression for use in Datastar event attributes.
   Returns a Datastar expression string that can be bound to any event.

   The action is registered with the current session/tab context
   and can access state via cursors. Action IDs are deterministic
   (derived from a per-render counter + tab-id) so that re-renders
   produce identical HTML, enabling effective gzip streaming compression.

   Supports client-side special forms that transmit DOM values to the server:
   - $value     — the value of the input/select/textarea that fired the event
   - $checked   — the checked state of a checkbox/radio (boolean)
   - $key       — the key name for keyboard events (e.g. \"Enter\", \"Escape\")
   - $form-data — all named fields in the enclosing form as a map

   Example:
     [:button {:data-on:click (action (swap! (tab-cursor :count) inc))}
      \"Increment\"]

     ;; Capture input value
     [:input {:data-on:change (action (reset! (tab-cursor :query) $value))}]

     ;; Keyboard shortcut
     [:input {:data-on:keydown (action (when (= $key \"Enter\")
                                 (search!)))}]

     ;; ... with client side check
     [:input {:data-on:keydown (action {:when \"evt.key === 'Enter'\"}
                                 (search!))}]

     ;; Named action for testing — :as gives the action a human-readable name
     ;; that hyper.test/test-page uses as the key in its :actions map
     [:button {:data-on:click (action {:as \"increment\"}
                                (swap! (tab-cursor :count) inc))}
      \"Increment\"]

     ;; Checkbox
     [:input {:type \"checkbox\"
              :data-on:change (action (reset! (tab-cursor :dark?) $checked))}]

     ;; Form submission
     [:form {:data-on:submit__prevent (action (save-user! $form-data))}
      [:input {:name \"email\"}]
      [:button \"Save\"]]
      
     Applications may define additional client parameters by extending
     the hyper.client-params/client-param multi-method."
  [& args]
  (let [[maybe-opts & body] args
        opts-map?           (and (map? maybe-opts)
                                 (some #{:when :as} (keys maybe-opts)))
        [js as-name body]   (if opts-map?
                              (let [guard (:when maybe-opts)
                                    ;; String literals are validated here; any
                                    ;; other form (e.g. (expr ...)) is deferred
                                    ;; to runtime evaluation — sanitize-guard
                                    ;; validates the result.
                                    js    (cond
                                            (string? guard) (when-not (str/blank? guard) guard)
                                            (some? guard)   guard)]
                                [js (:as maybe-opts) body])
                              [nil nil args])
        used-params         (find-client-params body)
        param-syms          (keys used-params)
        cp-sym              (gensym "client-params")]
    `(let [{session-id# :session-id
            tab-id#     :tab-id
            app-state*# :app-state*
            router#     :router}    (context/require-context! "action")
           action-fn#               (fn [~cp-sym]
                                      (let [~@(mapcat (fn [sym]
                                                        (let [k (keyword (:key (client-params/client-param sym)))]
                                                          [sym (list `get cp-sym k)]))
                                                      param-syms)]
                                        (binding [context/*request* {:hyper/session-id session-id#
                                                                     :hyper/tab-id     tab-id#
                                                                     :hyper/app-state  app-state*#
                                                                     :hyper/router     router#
                                                                     :hyper/env        (get-in @app-state*# [:tabs tab-id# :env])}]
                                          ~@body)))
           idx#                     (if context/*action-idx* (swap! context/*action-idx* inc) (hash action-fn#))
           action-id#               (str "a_" tab-id# "_" idx#)
           _#                       (actions/register-action! app-state*# session-id# tab-id# action-fn# action-id#
                                                              ~(when as-name {:as as-name}))
           base-path#               (get @app-state*# :base-path "")]
       (actions/build-action-expr action-id# '~used-params (actions/sanitize-guard ~js) base-path#))))

;; ---------------------------------------------------------------------------
;; Client-side expressions and components (re-exports)
;; ---------------------------------------------------------------------------

(defmacro expr
  "Compile Clojure forms into a Datastar expression string for use in
   data-* attributes, action :when guards, etc.

   Signals use atom vocabulary — the same (reset! sig v) that means a
   server round-trip inside `action` compiles to an instant client-side
   assignment here:

     (let [open?* (local-signal :open false)]
       [:button {:data-on:click (expr (swap! open?* not))} \"Toggle\"]
       [:div {:data-show (expr @open?*)} \"...\"])

     [:input {:data-on:keydown
              (expr (when (= evt.key \"Enter\") (@post \"/search\")))}]

   Locals splice automatically; evt/el/$signals/JS interop pass through
   to the client.  Canonical documentation: hyper.expr/->expr."
  [& forms]
  `(hyper.expr/->expr ~@forms))

(defmacro defc
  "Define a client-side web component, authored in a CLJS dialect (Squint)
   and compiled to JavaScript on the JVM at macro-expansion time.

     (defc temp-gauge
       [{:keys [value max label]}]
       (event ::selected [_e]
         (emit \"gauge-selected\" {:value value :label label}))
       (render
         [:div {:on {:click ::selected}} label \": \" value]))

   Also defines a server-side render function of the same name, so pages
   call components like ordinary hiccup functions.  Canonical
   documentation: hyper.component/defc."
  {:style/indent        1
   :style.cljfmt/indent [[:block 1] [:inner 1]]}
  [& args]
  `(component/defc ~@args))

(defn navigate
  "Create a navigation link using reitit named routes.
   Returns a map with :href for standard links and :data-on:click__prevent for SPA navigation.

   On click, registers an action that:
   1. Looks up the target route's handler
   2. Updates the tab's render fn and route state
   3. Triggers a re-render via SSE
   4. Pushes the new URL via pushState (with title in history state)

   The :href ensures right-click → open in new tab works.
   The title from the target route's :title metadata is resolved eagerly and
   included in the pushState call so browser history entries have meaningful titles.

   route-name: Keyword name of the route
   params: Optional map of path parameters
   query-params: Optional map of query parameters

   Example:
     [:a (navigate :home) \"Go Home\"]
     [:a (navigate :user-profile {:id \"123\"}) \"View User\"]
     [:a (navigate :search {} {:q \"clojure\"}) \"Search\"]"
  ([route-name]
   (navigate route-name nil nil))
  ([route-name params]
   (navigate route-name params nil))
  ([route-name params query-params]
   (let [router     (:hyper/router *request*)
         app-state* (:hyper/app-state *request*)
         session-id (:hyper/session-id *request*)
         tab-id     (:hyper/tab-id *request*)]
     (when-let [path (:path (reitit/match-by-name router route-name params))]
       (let [href          (state/build-url path query-params)
             base-path     (get @app-state* :base-path "")
             ;; Use live-routes to always get the latest route metadata
             route-index   (routes/live-route-index app-state*)
             ;; Resolve title eagerly for the pushState call
             title-spec    (routes/find-route-title route-index route-name)
             title         (routes/resolve-title title-spec *request*)
             ;; Register an action that performs the navigation server-side
             nav-fn        (fn [_client-params]
                             (let [route-idx (routes/live-route-index app-state*)
                                   render-fn (routes/find-render-fn route-idx route-name)]
                               (when render-fn
                                 (render/register-render-fn! app-state* tab-id render-fn))
                        ;; Setting the route triggers the route watcher,
                        ;; which handles re-rendering via SSE
                               (state/set-tab-route! app-state* tab-id
                                                     {:name         route-name
                                                      :path         path
                                                      :path-params  (or params {})
                                                      :query-params (or query-params {})})))
             nav-idx       (if *action-idx* (swap! *action-idx* inc) (hash nav-fn))
             action-id     (actions/register-action! app-state* session-id tab-id nav-fn
                                                     (str "a_" tab-id "_" nav-idx))
             escaped-title (or (utils/escape-js-string title) "")
             escaped-href  (utils/escape-js-string href)]
         {:href href
          :data-on:click__prevent
          (str "@post('" base-path "/hyper/actions?action-id=" action-id "');"
               " window.history.pushState({title: '" escaped-title "'}, '', '" escaped-href "');"
               (when title
                 (str " document.title = '" escaped-title "'")))})))))

(defn create-handler
  "Create a Ring handler for a hyper application.

   routes: Vector of reitit routes, or a Var holding routes for live reloading.
           When a Var is provided, route changes are picked up on the next request
           without restarting the server — ideal for REPL-driven development.

   Options (keyword arguments):
   - :app-state         — Atom for application state (default: fresh atom)
   - :datastar-script   - Override of the default datastar script tag (as Hiccup) or nil to suppress
   - :squint-core-url   — Override of the squint core.js URL used by the client
                          components bundle (default: version-matched jsDelivr CDN).
                          Point at a self-hosted copy for offline/air-gapped deploys.
   - :head              — Hiccup nodes appended to the HTML <head>, or (fn [req] ...) -> hiccup
   - :base-path         — URL path prefix for reverse-proxy deployments where the app is served
                          under a subfolder (e.g. \"/my-app\"). When set, all internal hyper
                          endpoints (/hyper/events, /hyper/actions, /hyper/navigate) are mounted
                          and referenced under this prefix. Must start with \"/\" and have no
                          trailing slash.
   - :static-resources  — Classpath resource root(s) to serve as static assets
   - :static-dir        — Filesystem directory (or directories) to serve as static assets
   - :watches           — Vector of Watchable sources added to every page route.
                          Useful for top-level atoms that should trigger a re-render
                          on any page (e.g. a global config or feature-flags atom).
   - :hiccup-transform  — (fn [hiccup] hiccup) applied to body and head hiccup before
                          Chassis serialization. Useful for expanding component systems
                          (e.g. lambdaisland/ornament defstyled components) into plain
                          keyword-first hiccup vectors that Chassis can serialize.
   - :middleware        — Vector of Ring middleware fns applied inside the HTTP stack.
                          Each is (fn [handler] (fn [req] ...)).  Runs after Hyper's
                          built-in cookie, params, and session middleware, so your
                          middleware sees parsed :cookies, :params, :hyper/session-id,
                          and :hyper/tab-id.  Use this for auth, :hyper/env setup, and
                          other request-level concerns.  Middleware can also be applied
                          outside create-handler, but will not have access to parsed
                          cookies/params.
   - :render-middleware — Vector of middleware fns applied to every page render.
                          Each is (fn [handler] (fn [req] ...)), identical to Ring
                          middleware.  Applied on both initial page loads and SSE
                          re-renders.  Per-route :render-middleware wraps inside these.
   - :render-error      — Function `(fn [error req] -> hiccup)` rendered when a
                          view's render-fn throws.  May be a Var to pick up
                          redefinitions without restarting the server.  Defaults
                          to `hyper.render.error/minimal` (generic, production-
                          safe).  Use `hyper.render.error/explain` in development
                          to render the message, ex-data, and full stack trace.
   - :not-found         — Function `(fn [req] -> hiccup)` rendered when no route
                          matches, served as a full page with HTTP 404 (and over
                          SSE for client-side navigation).  May be a Var to pick
                          up redefinitions.  Defaults to
                          `hyper.render.error/not-found`; pass `nil` to disable
                          and fall back to reitit's plain-text 404.

   The request key :hyper/env is reserved for application-provided context.
   Ring middleware that sets :hyper/env on the request will have it automatically
   stashed per-tab and propagated to every SSE re-render and action handler.
   See `env` for details.

   Example:
     (def routes
       [[\"/\" {:name :home
               :get (fn [req] [:div [:h1 \"Home\"]])}]
        [\"/about\" {:name :about
                    :get (fn [req] [:div [:h1 \"About\"]])}]
        [\"/api/info\" {:name :api-info
                       :hyper/disabled? true
                       :get (fn [req] .. a json api endpoint ..)]])

     ;; Static routes
     (def handler (create-handler routes))

     ;; Live-reloading routes (pass the Var)
     (def handler (create-handler #'routes))

     ;; Inject a stylesheet (e.g. Tailwind output)
     (def handler
       (create-handler routes
                       :static-resources \"public\"
                       :head [[:link {:rel \"stylesheet\" :href \"/app.css\"}]]))

     (def app (start! handler {:port 3000}))
     ;; Later...
     (stop! app)"
  [routes & {:keys [app-state head static-resources static-dir watches
                    datastar-script base-path middleware render-middleware
                    render-error hiccup-transform squint-core-url render-guard]
             :or   {app-state       (atom (state/init-state))
                    datastar-script server/default-datastar-script}
             :as   opts}]
  (server/create-handler routes app-state
                         (cond-> {:head              head
                                  :datastar-script   datastar-script
                                  :static-resources  static-resources
                                  :static-dir        static-dir
                                  :watches           watches
                                  :base-path         base-path
                                  :middleware        middleware
                                  :render-middleware render-middleware
                                  :hiccup-transform  hiccup-transform
                                  :squint-core-url   squint-core-url}
                           ;; Only forward when supplied so server defaults apply.
                           render-error (assoc :render-error render-error)
                           render-guard (assoc :render-guard render-guard)
                           ;; `contains?` so an explicit `:not-found nil` (disable) is honored.
                           (contains? opts :not-found) (assoc :not-found (:not-found opts)))))

(defn start!
  "Start the hyper application server.

   handler: Ring handler created with create-handler
   options:
   - :port - Port to run server on (default: 3000)

   Returns a stop function. Call (stop! app) to shut down the server
   and clean up all tab resources (watchers, SSE channels, actions).

   Example:
     (def handler (create-handler routes))
     (def app (start! handler {:port 3000}))
     ;; Later...
     (stop! app)"
  [handler {:keys [port] :or {port 3000}}]
  (server/start! handler {:port port}))

(defn stop!
  "Stop the hyper application server and clean up all resources.

   app: Stop function returned from start!"
  [app]
  (server/stop! app))
