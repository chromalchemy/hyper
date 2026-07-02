(ns example.middleware
  "Example: Ring middleware + render middleware with h/env and cursors.

   Demonstrates how to structure an app with:
   - **h/env** for read-only infrastructure (DB connections, config)
   - **session cursors** for mutable per-user state (auth)
   - **Ring middleware** for setting up env and hydrating auth from cookies
   - **Render middleware** for per-render access control

   Architecture:
   - Ring middleware (via :middleware on create-handler) populates :hyper/env
     with the database connection and hydrates the session cursor from the
     auth cookie on each HTTP request.  Runs after Hyper's built-in
     cookie/params/session middleware.
   - Render middleware (via :render-middleware on routes) checks the session
     cursor on every render — including SPA navigations that don't trigger
     a new HTTP request.

   Env is for things that don't change during a session — DB connections,
   config maps, feature flags.  Mutable per-user state lives in cursors,
   which re-render immediately when updated and don't depend on HTTP
   request timing.

   Run:  clj -X example.middleware/start!
   Visit http://localhost:4001 — public home page.
   Visit http://localhost:4001/admin — access denied (not logged in).
   Click 'Log in as Alice' → admin access granted.
   Click 'Log in as Bob' → admin access denied (viewer role)."
  (:require [hyper.core :as h]
            [hyper.effects :as effects]))

;; ---------------------------------------------------------------------------
;; Fake database & session store
;; ---------------------------------------------------------------------------

(def db
  "In-memory 'database' — a plain atom.  In a real app this would be a
   connection pool, Datomic connection, XTDB node, etc."
  (atom {:users {"alice" {:name "Alice" :role :admin}
                 "bob"   {:name "Bob" :role :viewer}}}))

(def sessions
  "In-memory session store.  Maps session-token → user-id."
  (atom {}))

;; ---------------------------------------------------------------------------
;; Ring middleware (via :middleware on create-handler)
;;
;; These run inside Hyper's HTTP stack — after cookies, params, and
;; hyper-context are parsed.  Use for read-only infrastructure (:hyper/env)
;; and for hydrating mutable state (cursors) from cookies/headers.
;; ---------------------------------------------------------------------------

(defn wrap-db
  "Inject the database connection into :hyper/env.

   The DB is read-only infrastructure — it doesn't change during a session.
   Every render function and action can access it via `(h/env :db)`."
  [db]
  (fn [handler]
    (fn [req]
      (handler (update req :hyper/env assoc :db db)))))

(defn wrap-auth
  "Hydrate the :user session state from the auth cookie.

   On each HTTP request (page load, action POST, SSE connect), reads the
   session cookie and writes the user into session state.  This keeps the
   in-memory state in sync with the cookie — important when cookies are
   set from a different tab or after a page reload.

   Auth state lives in session state (not env) because it's mutable:
   login/logout actions update it immediately via session cursors, and
   the change triggers a re-render.  The cookie is the durable backing
   store; the session cursor is the reactive in-memory view.

   We write directly to app-state here (not via a cursor) because Ring
   middleware runs outside the render context.  Session cursors in render
   functions and actions will read this value."
  [handler]
  (fn [req]
    (let [token      (get-in req [:cookies "app-session" :value])
          user-id    (when token (get @sessions token))
          db-atom    (get-in req [:hyper/env :db])
          user       (when (and user-id db-atom)
                       (get-in @db-atom [:users user-id]))
          app-state* (:hyper/app-state req)
          session-id (:hyper/session-id req)]
      ;; Write directly to session state in app-state.
      ;; Session cursors in renders/actions read from this path.
      (when (and app-state* session-id)
        (swap! app-state* assoc-in [:sessions session-id :data :user] user))
      (handler req))))

;; ---------------------------------------------------------------------------
;; Render middleware (via :render-middleware on routes)
;;
;; Runs on EVERY render — initial page load AND SSE re-renders.  This is
;; critical: if a user navigates via SPA (h/navigate), there's no new HTTP
;; request, so Ring middleware doesn't run.  But render middleware does,
;; reading the session cursor which was set by the last HTTP request or
;; action.
;; ---------------------------------------------------------------------------

(defn wrap-require-role
  "Render middleware that guards a page based on user role.

   Reads the :user session cursor (set by wrap-auth on HTTP requests, or
   by login/logout actions for immediate reactivity).  If the user doesn't
   have the required role, either redirects or shows an access-denied page.

   Returning a Ring response map `{:status 302 ...}` from render middleware
   works on both initial page loads (normal HTTP redirect) and SSE re-renders
   (Hyper converts it to a client-side `window.location.href` redirect).
   This is how you redirect unauthenticated users to a login page.

   For authenticated users who lack the role, we render an inline error
   page instead of redirecting — they're logged in, just not authorized.

   Usage in route data:
     {:render-middleware [(wrap-require-role :admin)]}"
  [required-role]
  (fn [handler]
    (fn [req]
      (let [user @(h/session-cursor :user)
            role (:role user)]
        (cond
          ;; Authorized — proceed to the page
          (= role required-role)
          (handler req)

          ;; Not logged in — redirect to login
          ;; Works on initial page load (302 redirect) AND on SSE re-renders
          ;; (Hyper converts to client-side window.location.href redirect)
          (nil? user)
          {:status 302 :headers {"Location" "/login"} :body ""}

          ;; Logged in but wrong role — show inline error
          :else
          [:div.page
           [:h1 "⛔ Access Denied"]
           [:p "This page requires the " [:strong (name required-role)] " role."]
           [:p "You are logged in as " [:strong (:name user)]
            " (" (name role) "). "
            [:a (h/navigate :home) "Go home"]]])))))

;; ---------------------------------------------------------------------------
;; Pages
;; ---------------------------------------------------------------------------

(defn home-page [_req]
  (let [user @(h/session-cursor :user)]
    [:div.page
     [:h1 "🏠 Home"]
     (if user
       [:div
        [:p "Welcome, " [:strong (:name user)] "! (role: " (name (:role user)) ")"]
        [:p [:a (h/navigate :admin) "Go to admin panel"]]
        [:button {:data-on:click
                  (h/action {:as "logout"}
                    ;; Delete the cookie — wrap-auth clears the cursor on next HTTP request
                            (effects/delete-cookie! "app-session")
                    ;; Clear the cursor now — re-render sees logged-out state immediately
                            (reset! (h/session-cursor :user) nil)
                    ;; Navigate to login page
                            (effects/navigate! :login))}
         "Log out"]]
       [:div
        [:p "You are not logged in."]
        [:p [:a (h/navigate :login) "Log in"]]])
     [:hr]
     [:p {:style "color: #666;"}
      "This page is public. The admin panel requires the :admin role "
      "and is protected by render middleware."]]))

(defn login-page [_req]
  [:div.page
   [:h1 "🔐 Login"]
   [:p "Pick a user to log in as:"]
   [:div {:style "display: flex; gap: 12px;"}
    [:button {:data-on:click
              (h/action {:as "login-admin"}
                        (let [token (str (random-uuid))
                              user  (get-in @(h/env :db) [:users "alice"])]
                          (swap! sessions assoc token "alice")
                  ;; Set the cookie — durable backing store for auth
                          (effects/set-cookie! "app-session" token {:max-age (* 60 60 24)})
                  ;; Set the cursor — re-render sees the user immediately
                          (reset! (h/session-cursor :user) user)
                  ;; Redirect to home after login
                          (effects/navigate! :home)))}
     "Log in as Alice (admin)"]
    [:button {:data-on:click
              (h/action {:as "login-viewer"}
                        (let [token (str (random-uuid))
                              user  (get-in @(h/env :db) [:users "bob"])]
                          (swap! sessions assoc token "bob")
                          (effects/set-cookie! "app-session" token {:max-age (* 60 60 24)})
                          (reset! (h/session-cursor :user) user)
                          (effects/navigate! :home)))}
     "Log in as Bob (viewer)"]]
   [:br]
   [:p [:a (h/navigate :home) "← Back to home"]]])

(defn admin-page [_req]
  (let [user @(h/session-cursor :user)
        db*  (h/env :db)]
    [:div.page
     [:h1 "🔧 Admin Panel"]
     [:p "Hello, " [:strong (:name user)] "! You have admin access."]
     [:h2 "All users in database"]
     [:ul
      (for [[id {:keys [name role]}] (:users @db*)]
        [:li [:strong name] " (" id ") — " (clojure.core/name role)])]
     [:br]
     [:p [:a (h/navigate :home) "← Back to home"]]]))

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------
;;
;; The admin route uses per-route :render-middleware to enforce the role
;; check.  This runs on EVERY render (initial + SSE), so SPA navigation
;; to /admin is gated too — not just the initial page load.

(def routes
  [["/" {:name  :home
         :title "Home"
         :get   #'home-page}]
   ["/login" {:name  :login
              :title "Login"
              :get   #'login-page}]
   ["/admin" {:name              :admin
              :title             "Admin Panel"
              :get               #'admin-page
              :render-middleware [(wrap-require-role :admin)]}]])

;; ---------------------------------------------------------------------------
;; Server
;; ---------------------------------------------------------------------------
;;
;; The middleware stack (in execution order):
;;
;; 1. Hyper built-ins: cookies → params → keyword-params → brotli → hyper-context
;; 2. :middleware (Ring):
;;    a. wrap-db    — injects {:db db} into :hyper/env (read-only infrastructure)
;;    b. wrap-auth  — reads cookie, hydrates :user session cursor
;; 3. Router dispatch → page-handler / action-handler
;; 4. :render-middleware — runs on every render (initial + SSE)
;;
;; Env (:hyper/env) holds the DB — read-only, doesn't change during a session.
;; Auth lives in a session cursor — mutable, re-renders immediately on change.

(def styles
  [:style
   "* { box-sizing: border-box; }
    body { font-family: system-ui, sans-serif; margin: 0; }
    .page { max-width: 600px; margin: 0 auto; padding: 40px; }
    button { padding: 8px 16px; cursor: pointer; }"])

(defn make-handler []
  (h/create-handler #'routes
                    :head        [styles]
                    :middleware  [(wrap-db db) wrap-auth]))

(defn start! [& _]
  (let [handler (make-handler)
        app     (h/start! handler {:port 4001})]
    (println "Middleware example running on http://localhost:4001")
    app))

(comment
  (def app (start!))
  (h/stop! app))
