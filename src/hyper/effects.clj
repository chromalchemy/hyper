(ns hyper.effects
  "Side-effects for hyper actions.

   Provides escape hatches for actions that need to do more than mutate
   cursors/signals.  Most action logic should use cursors (the UI is a
   pure function of state), but some operations genuinely require
   side-effects that can't be expressed as state:

   - `navigate!`        — client-side route change (pushState) from an action
   - `set-cookie!`      — set an HTTP cookie on the action response
   - `delete-cookie!`   — remove an HTTP cookie
   - `execute-script!`  — run arbitrary JS on the client via SSE
   - `assoc-session!`   — assoc a key into the Ring session map
   - `dissoc-session!`  — dissoc a key from the Ring session map
   - `update-session!`  — apply (f session & args) to the Ring session map

   These are intentionally few.  If you're reaching for execute-script!
   to update UI, consider whether a cursor would be more appropriate.

   Effects are accumulated during action execution in a dynamic var and
   processed by the action handler after the action completes.  Cookies
   and session writes are applied to the HTTP response; scripts are sent
   via SSE.  Session writes require the host app to wrap the
   `/hyper/actions` route with `ring.middleware.session/wrap-session`
   (or equivalent) so the response's `:session` key is persisted into
   the cookie / session store of choice.

   Example:
     (require '[hyper.effects :as effects])

     ;; Navigate after saving
     (h/action
       (save-post! data)
       (effects/navigate! :post-detail {:id 123}))

     ;; Set a cookie
     (h/action
       (when (authenticate! user pass)
         (effects/set-cookie! \"auth\" jwt {:http-only true :max-age 86400})
         (effects/navigate! :dashboard)))

     ;; Run client-side JS (use sparingly)
     (h/action
       (effects/execute-script! \"document.getElementById('search').focus()\"))"
  (:require [hyper.context :as context]
            [hyper.render :as render]
            [hyper.routes :as routes]
            [hyper.state :as state]
            [hyper.utils :as utils]
            [reitit.core :as reitit]))

;; ---------------------------------------------------------------------------
;; Pending effects accumulator
;; ---------------------------------------------------------------------------

(def ^:no-doc ^:dynamic *pending*
  "Accumulator for effects emitted during action execution.

   Bound to (atom {:cookies {} :scripts [] :session-ops []}) by the
   action handler.  nil outside action execution.

   :session-ops is a vector of operation maps applied left-to-right
   over the base Ring session at flush time.  Each op has shape
   {:op :assoc  :k k :v v} | {:op :dissoc :k k} | {:op :update :f f :args args}."
  nil)

(defn- require-pending!
  "Ensure we're inside an action context with effects support.
   Returns the pending atom."
  [caller]
  (or *pending*
      (throw (ex-info (str caller " called outside action context. "
                           "Effects can only be emitted during action execution.")
                      {:caller caller}))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn navigate!
  "Navigate to a named route from within an action.

   Performs the server-side route transition (updates render-fn and route
   state, which triggers a re-render via SSE) and queues a pushState
   call to update the browser URL bar.

   route-name: Keyword name of the route
   params: Optional map of path parameters
   query-params: Optional map of query parameters

   Example:
     (h/action
       (let [post (save-post! data)]
         (effects/navigate! :post-detail {:id (:id post)})))

     (h/action
       (effects/navigate! :search {} {:q \"clojure\"}))"
  ([route-name]
   (navigate! route-name nil nil))
  ([route-name params]
   (navigate! route-name params nil))
  ([route-name params query-params]
   (let [pending*   (require-pending! "navigate!")
         req        context/*request*
         app-state* (:hyper/app-state req)
         tab-id     (:hyper/tab-id req)
         router     (or (:hyper/router req) (get @app-state* :router))]
     (when-not router
       (throw (ex-info "No router available for navigate!" {:route-name route-name})))
     (when-let [path (:path (reitit/match-by-name router route-name params))]
       (let [href        (state/build-url path query-params)
             route-index (routes/live-route-index app-state*)
             render-fn   (routes/find-render-fn route-index route-name)
             title-spec  (routes/find-route-title route-index route-name)
             title       (routes/resolve-title title-spec req)]
         ;; Server-side state transition
         (when render-fn
           (render/register-render-fn! app-state* tab-id render-fn))
         (state/set-tab-route! app-state* tab-id
                               {:name         route-name
                                :path         path
                                :path-params  (or params {})
                                :query-params (or query-params {})})
         ;; Queue client-side URL update
         (let [escaped-title (or (utils/escape-js-string title) "")
               escaped-href  (utils/escape-js-string href)]
           (swap! pending* update :scripts conj
                  (str "window.history.pushState("
                       "{title:'" escaped-title "'},"
                       "'',"
                       "'" escaped-href "');"
                       (when title
                         (str "document.title='" escaped-title "'"))))))))))

(defn set-cookie!
  "Set an HTTP cookie from within an action.

   The cookie will be added to the action's HTTP response via Set-Cookie
   headers.  This is the only way to set cookies from hyper — the SSE
   channel cannot carry Set-Cookie headers.

   name: Cookie name string
   value: Cookie value string
   opts: Optional map of cookie options:
     :path      — Cookie path (default \"/\")
     :max-age   — Max age in seconds
     :http-only — Boolean, prevent JS access (default false)
     :secure    — Boolean, HTTPS only (default false)
     :same-site — :strict, :lax, or :none

   Example:
     (h/action
       (when-let [token (authenticate! user pass)]
         (effects/set-cookie! \"auth\" token {:http-only true
                                             :secure true
                                             :max-age (* 60 60 24 7)})))"
  ([name value]
   (set-cookie! name value {}))
  ([name value opts]
   (let [pending* (require-pending! "set-cookie!")]
     (swap! pending* assoc-in [:cookies name]
            (merge {:value value :path "/"} opts))
     nil)))

(defn delete-cookie!
  "Delete an HTTP cookie from within an action.

   Sets the cookie with an empty value and max-age 0, which instructs
   the browser to remove it.

   name: Cookie name string
   opts: Optional map — typically :path must match the original cookie's path

   Example:
     (h/action
       (effects/delete-cookie! \"auth\")
       (effects/navigate! :login))"
  ([name]
   (delete-cookie! name {}))
  ([name opts]
   (let [pending* (require-pending! "delete-cookie!")]
     (swap! pending* assoc-in [:cookies name]
            (merge {:value "" :max-age 0 :path "/"} opts))
     nil)))

(defn execute-script!
  "Execute JavaScript on the client from within an action.

   The script is sent via SSE as a Datastar execute-script event.
   Use sparingly — most UI updates are better expressed as cursor
   mutations that the render function responds to.

   Legitimate uses: focusing an element, scrolling to a position,
   triggering a file download, clipboard operations.

   js: JavaScript string to execute in the browser

   Example:
     (h/action
       (effects/execute-script! \"document.getElementById('search').focus()\"))

     (h/action
       (effects/execute-script! \"window.scrollTo(0, 0)\"))"
  [js]
  (let [pending* (require-pending! "execute-script!")]
    (swap! pending* update :scripts conj js)
    nil))

(defn assoc-session!
  "Assoc a key-value pair into the Ring session map from within an action.

   The change is applied to the base session captured at action entry
   and the resulting map is set as `:session` on the action's HTTP
   response.  The host app must wrap the action route with
   `ring.middleware.session/wrap-session` (or equivalent) for the
   change to be persisted.

   Multiple session operations within one action accumulate and apply
   in order at flush time, so later writes win on the same key.

   k: Session map key (typically a keyword)
   v: Value to assoc

   Example:
     (h/action
       (when-let [user (authenticate! email)]
         (effects/assoc-session! :uid (:email user))
         (effects/navigate! :dashboard)))"
  [k v]
  (let [pending* (require-pending! "assoc-session!")]
    (swap! pending* update :session-ops conj {:op :assoc :k k :v v})
    nil))

(defn dissoc-session!
  "Dissoc a key from the Ring session map from within an action.

   k: Session map key

   Example:
     (h/action
       (effects/dissoc-session! :uid)
       (effects/navigate! :home))"
  [k]
  (let [pending* (require-pending! "dissoc-session!")]
    (swap! pending* update :session-ops conj {:op :dissoc :k k})
    nil))

(defn update-session!
  "Apply f to the Ring session map: (apply f current-session args).

   Use when assoc/dissoc are insufficient — e.g. bulk merging, removing
   multiple keys at once, or composing with existing values.

   Example:
     ;; Merge several keys at once
     (h/action
       (effects/update-session! merge {:uid email :role :admin}))

     ;; Replace the entire session
     (h/action
       (effects/update-session! (constantly {:uid email})))"
  [f & args]
  (let [pending* (require-pending! "update-session!")]
    (swap! pending* update :session-ops conj
           {:op :update :f f :args (vec args)})
    nil))

;; ---------------------------------------------------------------------------
;; Internal — used by action-handler in server.clj
;; ---------------------------------------------------------------------------

(defn ^:no-doc init-pending
  "Create a fresh pending effects atom.  Called by action-handler before
   binding *pending*."
  []
  (atom {:cookies {} :scripts [] :session-ops []}))

(defn ^:no-doc collect-pending!
  "Drain and return the accumulated effects.  Returns a map with
   :cookies, :scripts, and :session-ops.  Called by action-handler
   after action execution."
  []
  (when *pending*
    @*pending*))

(defn ^:no-doc apply-cookies-to-response
  "Merge pending cookies into an HTTP response map.
   Returns the response with :cookies added/merged."
  [response pending-effects]
  (let [cookies (:cookies pending-effects)]
    (if (seq cookies)
      (update response :cookies merge cookies)
      response)))

(defn- apply-session-op
  "Apply one session op against a session map."
  [sess {:keys [op k v f args]}]
  (case op
    :assoc  (assoc sess k v)
    :dissoc (dissoc sess k)
    :update (apply f sess args)))

(defn apply-session-to-response
  "Apply pending session operations to an HTTP response map.

   Reduces all accumulated :session-ops over `base-session` (defaulting
   to {} when nil) and assocs the result as `:session` on the response.

   No-op when no session ops were emitted, so an action that didn't
   touch the session leaves the Ring response's `:session` key absent
   — letting `ring.middleware.session` preserve the existing session
   cookie unchanged."
  [response base-session pending-effects]
  (let [ops (:session-ops pending-effects)]
    (if (seq ops)
      (assoc response :session
             (reduce apply-session-op (or base-session {}) ops))
      response)))

(defn ^:no-doc format-execute-script-event
  "Format a JavaScript string as a Datastar patch-elements SSE event
   that appends a self-removing <script> tag to the body.

   Datastar doesn't have a dedicated execute-script event type.  Instead,
   script execution is done by patching a <script> element into the DOM
   using the 'append' mode and 'body' selector.  The script removes itself
   after execution via a data-effect attribute."
  [js]
  (str "event: datastar-patch-elements\n"
       "data: mode append\n"
       "data: selector body\n"
       "data: elements <script data-effect=\"el.remove()\">" js "</script>\n\n"))

(defn ^:no-doc format-pending-scripts
  "Format all pending scripts as SSE events.  Returns a single string
   of concatenated SSE events, or nil if there are no scripts."
  [pending-effects]
  (let [scripts (:scripts pending-effects)]
    (when (seq scripts)
      (apply str (map format-execute-script-event scripts)))))
