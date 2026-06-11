# Hyper

[![CI](https://github.com/dynamic-alpha/hyper/actions/workflows/ci.yaml/badge.svg)](https://github.com/dynamic-alpha/hyper/actions/workflows/ci.yaml)

A reactive server-rendered web framework for Clojure built on
[Datastar](https://data-star.dev/) and
[Reitit](https://github.com/metosin/reitit).

Hyper renders your pages as hiccup on the server using
[Chassis](https://github.com/onionpancakes/chassis), then keeps them alive
over SSE — when state changes, the server re-renders and patches the DOM
automatically. No client-side framework, no JSON APIs, no JavaScript to write.

```clojure
(require '[hyper.core :as h])

(defn home-page [req]
  (let [count* (h/tab-cursor :count 0)]
    [:div
     [:h1 "Count: " @count*]
     [:button {:data-on:click (h/action (swap! count* inc))}
      "Increment"]]))

(def routes
  [["/" {:name :home
         :title "Home"
         :get #'home-page}]])

(def handler (h/create-handler #'routes))
(def app (h/start! handler {:port 3000}))
```

## Origin & Inspiration

Hyper wouldn't exist without the generosity of the Clojure community. We're
grateful to the people whose work and ideas made this possible:

- [Anders Murphy](https://andersmurphy.com)'s essay
  [Realtime Collaborative Web Apps Without ClojureScript](https://andersmurphy.com/2025/04/07/clojure-realtime-collaborative-web-apps-without-clojurescript.html)
  laid the groundwork — demonstrating that server-rendered Clojure + Datastar +
  SSE is a viable architecture for reactive web apps.
- [David Yang](https://github.com/davidyang) and
  [David Nolen](https://github.com/swannodette) at
  [Lightweight Labs](https://lightweightlabs.com), whose talk
  [From Tomorrow Back to Yesterday](https://www.youtube.com/watch?v=8W6Lr1hRgXo&t=2s)
  at Clojure/conj 2025 shaped our thinking on server-driven UI and the
  direction of web development in Clojure.

## Project Status

Hyper is in active alpha development and used in internal projects at Dynamic
Alpha. The API is evolving rapidly — expect bugs and breakage until a 1.0
release.

We're building in the open to share with the Clojure community. Feedback and
contributions are welcome.

## Installation

We eventually intend to publish to Clojars, however while we are rapidly
evolving the project we recommend to install via a :git/url instead. Make sure
to grab the latest SHA.

```clojure
{dynamic-alpha/hyper {:git/url "https://github.com/dynamic-alpha/hyper"
                      :git/sha "..."}}
```

## Requirements

Hyper uses [virtual threads](https://openjdk.org/jeps/444) for its per-tab
rendering loop — each connected browser tab gets its own lightweight virtual
thread that blocks on a semaphore until state changes trigger a re-render. This
means you need **JDK 21 or later**.

Virtual threads were finalized in JDK 21 (JEP 444) and are available without
any flags. On JDK 19 or 20 they are a preview feature and require the
`--enable-preview` flag, but we recommend just using JDK 21+.

## Cursors

Cursors are the primary way to read and write state in Hyper. They behave just
like atoms — use `deref`, `reset!`, `swap!`, and `add-watch` as you would with a
normal atom.

Each cursor type scopes state differently:

```clojure
(h/global-cursor :theme "light")       ;; shared across everything
(h/session-cursor :user)               ;; scoped to browser session
(h/tab-cursor :count 0)                ;; scoped to a single tab
(h/path-cursor :page 1)               ;; backed by URL query params
```

The first argument is a key — either a keyword for flat access, or a vector for
nested access. `global-cursor`, `session-cursor`, and `tab-cursor` all support
this:

```clojure
(h/tab-cursor :count 0)               ;; flat — state[:count]
(h/tab-cursor [:form :email] "")       ;; nested — state[:form][:email]
(h/session-cursor [:user :name])       ;; nested — session[:user][:name]
```

The optional second argument sets a default value when the key is nil.

| Cursor | Shared across tabs? | Shared across sessions? | Survives page reload? |
|---|---|---|---|
| `global-cursor` | ✅ | ✅ | ✅ (global, in-memory) |
| `session-cursor` | ✅ | No | ✅  (session length) |
| `tab-cursor` | No | No | No (in-memory) |
| `path-cursor` | No | No | ✅ (URL query params) |

Mutating any cursor triggers a re-render for every tab that depends on that
scope — global changes re-render all tabs, session changes re-render tabs in
that session, and so on.

Renders are throttled at `~60fps` (`16 ms` intervals). Multiple cursor mutations
within the same frame window are batched into a single render. Mutations
spread across different frame windows produce one render per window.
For example: a sequence of mutations over 48 ms will result in roughly 3 renders,
each reflecting the latest state at that moment.

## Actions

Actions are server-side functions triggered by user interactions. The `action`
macro captures the current session and tab context at render time, registers a
handler on the server, and returns a Datastar expression string that can be
bound to any event attribute.

```clojure
(defn counter [req]
  (let [count* (h/tab-cursor :count 0)]
    [:div
     [:p "Count: " @count*]
     [:button {:data-on:click (h/action (swap! count* inc))} "+1"]
     [:button {:data-on:click (h/action (swap! count* dec))} "-1"]]))
```

When the button is clicked, Datastar POSTs to the server, Hyper executes the
action body, the cursor mutation triggers the watcher, and the tab re-renders
over SSE — all in one round trip with no page reload.

Actions have full access to the request context, so you can use any cursor type
inside them:

```clojure
;; Toggle a global theme that affects all tabs and sessions
[:button {:data-on:click (h/action
                           (let [theme* (h/global-cursor :theme "light")]
                             (swap! theme* #(if (= % "light") "dark" "light"))))}
 "Toggle theme"]

;; Update session state shared across tabs
[:button {:data-on:click (h/action
                           (reset! (h/session-cursor :user) {:name "Alice"}))}
 "Log in"]
```

Actions are scoped to the tab that rendered them and are cleaned up automatically
when the tab disconnects. The body can contain arbitrary Clojure — call
functions, hit databases, update multiple cursors — whatever happens, the
resulting state changes trigger re-renders for the appropriate tabs.

### Action identity

During action execution, `context/*action-name*` is bound to the `:as` value of
the currently running action (or `nil` if the action was not given an `:as`
name). This lets utility functions identify which action is running without the
caller having to pass the name explicitly:

```clojure
(require '[hyper.context :as context])

(defn audit! []
  (log/info "Action executed" {:action context/*action-name*
                                :user   @(h/session-cursor :user)}))

(h/action {:as "delete-user"}
  (audit!)          ;; logs {:action "delete-user", :user ...}
  (delete-user! id))
```

### Client params

Actions can capture client-side DOM values and transmit them to the server using special `$` symbols:

| Symbol | Captures | Use case |
|---|---|---|
| `$value` | `evt.target.value` | Input/select/textarea value |
| `$checked` | `evt.target.checked` | Checkbox/radio boolean state |
| `$key` | `evt.key` | Keyboard event key name |
| `$detail` | `evt.detail` | CustomEvent payload (e.g. from web components) |
| `$form-data` | All named form fields | Form submission as a map |

Map-shaped params (`$form-data`, `$detail`) arrive with keyword keys —
`{:name "Alice"}`, not `{"name" "Alice"}`.

Example usage:

```clojure
;; Capture input value on change
[:input {:data-on:change (h/action (reset! (h/tab-cursor :query) $value))}]

;; React to specific keys
[:input {:data-on:keydown
         (h/action (when (= $key "Enter")
                     (search! $value)))}]

;; Checkbox toggle
[:input {:type "checkbox"
         :data-on:change (h/action (reset! (h/tab-cursor :dark?) $checked))}]

;; Full form submission
[:form {:data-on:submit__prevent (h/action (save-user! $form-data))}
 [:input {:name "email"}]
 [:input {:name "password" :type "password"}]
 [:button "Save"]]
```

When `$` symbols appear in the action body, the macro automatically generates a `fetch()` call instead of `@post()`, sending the extracted values as a JSON body. On the server, the action function receives these values bound to the corresponding `$` symbols.

Additional symbols can be defined by extending the `hyper.client-params/client-param` multimethod.

For example, if you were handling mouse events, you might want to create support for tracking
the x and y offset.

```clojure
(defmethod hyper.client-params/client-param '$mouse-offset
  [_]
  {:js "{x:evt.offsetX, y:evt.offsetY}"
   :key "mouseOffset"})
```

The :js key is the JavaScript that executes in the client browser to collect
the data, and the :key is key used in the JSON payload for the data, sent from
the browser to the hyper application.

With this in place, your `h/action` code can reference symbol `$mouse-offset`, which will be
an EDN map with keys :x and :y.

### Client-side guards

Pass a `:when` option to `action` to inject a client-side Datastar expression.
The guard runs before the action fires, letting you filter events at the browser level without a server round-trip.

```clojure
;; Only POST when Enter is pressed — no network traffic on other keystrokes
[:input {:type "text"
         :data-on:keydown (h/action {:when "evt.key === 'Enter'"}
                           (reset! (h/tab-cursor :value) $value))}]
```

## Signals

Signals are client-side reactive variables backed by
[Datastar's signal system](https://data-star.dev/guide/reactive_signals). They
let you keep ephemeral UI state in the browser — input values, toggle states,
form drafts — without a server round-trip on every change, while still being
readable and writable from server-side actions.

```clojure
(defn user-form [req]
  (let [name*    (h/signal :user-name "")
        enabled* (h/local-signal :enabled false)]
    [:div
     [:input {:data-bind name*}]
     [:label
      [:input {:type "checkbox" :data-bind enabled*}]
      "Enable"]
     [:p {:data-show @enabled*} "Name: " [:span {:data-text @name*}]]
     [:button {:data-on:click (h/action
                                (create-user! {:name @name*})
                                (reset! name* ""))}
      "Submit"]]))
```

### `signal`

`(h/signal path)` or `(h/signal path default-value)` creates a Datastar signal
that syncs between the browser and server.

Path can be a keyword or a vector of keywords for nested signals:

```clojure
(h/signal :name "")               ;; → $name
(h/signal :user-name "")          ;; → $userName  (Datastar camelCase conversion)
(h/signal [:user :name] "")       ;; → $user.name (Datastar dot-notation nesting)
```

#### Dereferencing signals

`@signal*` behaves differently depending on the context:

**During render**, `@signal*` returns the Datastar expression string (e.g.
`"$userName"`). This is the `$`-prefixed signal name that Datastar evaluates
client-side — suitable for use in `data-text`, `data-show`, and any other
Datastar attribute that expects an expression:

```clojure
[:span {:data-text @name*}]                 ;; → data-text="$userName"
[:div {:data-show @enabled*} "Visible"]     ;; → data-show="$enabled"
```

Because `@signal*` is just a string in render context, you can use it to build
Datastar expressions with normal string operations:

```clojure
[:span {:data-text (str @name* ".toUpperCase()")}]   ;; → data-text="$userName.toUpperCase()"
[:div {:data-show (str @name* " !== ''")} "Has name"] ;; → data-show="$userName !== ''"
```

The signal itself **without deref** renders as the raw signal name (no `$`
prefix), which is the correct format for `data-bind`:

```clojure
[:input {:data-bind name*}]                 ;; → data-bind="userName"
```

**During action execution**, `@signal*` returns the live value sent by
Datastar in the `@post()` request body. Datastar automatically sends all
non-underscore signals with every backend request, so your action code can
read the current client-side value:

```clojure
[:button {:data-on:click (h/action
                           (println "Current name:" @name*)
                           (save-to-db! @name*))}
 "Save"]
```

`reset!` and `swap!` update the signal value on the server, which triggers a
`datastar-patch-signals` SSE event to push the new value to the client:

```clojure
(h/action
  (reset! name* "")              ;; clear the input
  (swap! counter* inc))          ;; increment a counter
```

### `local-signal`

`(h/local-signal path default-value)` creates a local Datastar signal
(underscore-prefixed). Local signals are **client-only** — Datastar does not
send them to the server.

#### Dereferencing local signals

Local signals follow the same deref pattern as regular signals:

**During render**, `@local*` returns the Datastar expression string (e.g.
`"$_open"`) — suitable for `data-show`, `data-text`, and building expressions.
Without deref, the signal renders as the raw name (e.g. `"_open"`) for
`data-bind`.

**During action execution**, `@local*` **throws** — local signals are
underscore-prefixed, and Datastar does not include them in requests to the
server. `reset!` and `swap!` are also not supported.

Use local signals for ephemeral UI state that doesn't need server processing —
dropdown visibility, accordion state, modal toggles:

```clojure
(let [open?* (h/local-signal :open false)]
  [:div
   ;; Build a toggle expression using deref
   [:button {:data-on:click (str @open?* " = !" @open?*)} "Toggle"]
   ;; data-show needs the expression form (@)
   [:div {:data-show @open?*} "Collapsible content"]])
```

### How signals work

Under the hood, `h/signal` does three things:

1. **Declares** the signal in the rendered HTML via a
   `data-signals:NAME__ifmissing` attribute on the wrapper div, so Datastar
   creates the signal on page load without overwriting it on re-renders.

2. **Tracks** the signal value in server-side tab state, so `reset!`/`swap!`
   in actions can push updates to the client via `datastar-patch-signals` SSE
   events.

3. **Reads** signal values from the `@post()` request body during action
   execution, so `@signal*` returns the live client-side value.

All actions use Datastar's `@post()` under the hood, so signal values are
always available — even in actions that also use client params like `$value`,
`$key`, etc.

## Effects

Effects are escape hatches for actions that need to do more than mutate cursors
or signals. Most action logic should use cursors — the UI is a pure function of
state — but some operations genuinely require side-effects that can't be
expressed as state: navigating to a different route, setting a cookie, or running
a snippet of JavaScript on the client.

```clojure
(require '[hyper.effects :as effects])
```

Hyper provides four effect functions, all of which may only be called inside an
action body:

| Effect | What it does |
|---|---|
| `navigate!` | Client-side route change (pushState) + server-side state transition |
| `set-cookie!` | Set an HTTP cookie on the action response |
| `delete-cookie!` | Remove an HTTP cookie |
| `execute-script!` | Run arbitrary JS on the client via SSE |

Effects are accumulated during action execution and processed after the action
completes. Cookies are applied to the HTTP response; scripts are delivered to the
client via SSE.

### `navigate!`

`navigate!` performs a server-side route transition and pushes a `pushState` call
to update the browser URL — all from within an action body. The target page
re-renders over SSE without a page reload.

```clojure
(h/action
  (let [post (save-post! data)]
    (effects/navigate! :post-detail {:id (:id post)})))

;; With query params
(h/action
  (effects/navigate! :search {} {:q "clojure"}))
```

The signature mirrors `h/navigate`: `(navigate! route-name)`,
`(navigate! route-name params)`, or
`(navigate! route-name params query-params)`.

### `set-cookie!` / `delete-cookie!`

Set or remove HTTP cookies from within an action. Cookies are added to the
action's HTTP response via `Set-Cookie` headers — this is the only way to set
cookies from Hyper, since the SSE channel cannot carry `Set-Cookie` headers.

```clojure
;; Set a cookie
(h/action
  (when-let [token (authenticate! user pass)]
    (effects/set-cookie! "auth" token {:http-only true
                                       :secure    true
                                       :max-age   (* 60 60 24 7)})))

;; Delete a cookie
(h/action
  (effects/delete-cookie! "auth")
  (effects/navigate! :login))
```

`set-cookie!` accepts a name, value, and optional opts map:

| Option | Description | Default |
|---|---|---|
| `:path` | Cookie path | `"/"` |
| `:max-age` | Max age in seconds | — |
| `:http-only` | Prevent JS access | `false` |
| `:secure` | HTTPS only | `false` |
| `:same-site` | `:strict`, `:lax`, or `:none` | — |

`delete-cookie!` sets the cookie with an empty value and `max-age 0`. Pass
`:path` if the original cookie was set on a non-default path.

### `execute-script!`

Run arbitrary JavaScript on the client. The script is sent via SSE as a Datastar
fragment that appends a self-removing `<script>` tag to the body.

```clojure
(h/action
  (effects/execute-script! "document.getElementById('search').focus()"))

(h/action
  (effects/execute-script! "window.scrollTo({top: 0, behavior: 'smooth'})"))
```

Use sparingly — most UI updates are better expressed as cursor mutations that
the render function responds to. Legitimate uses include focusing an element,
scrolling to a position, triggering a file download, or clipboard operations.

### Composing multiple effects

A single action can emit any combination of effects. They all accumulate and are
applied together after the action completes:

```clojure
(h/action
  (effects/set-cookie! "token" "abc" {:http-only true})
  (effects/execute-script! "showNotification('Saved!')")
  (reset! (h/tab-cursor :status) "saved"))
```

Cursor mutations and effects coexist naturally — cursors trigger re-renders as
usual while effects are processed separately.

### Calling effects outside an action

All effect functions throw if called outside an action context. This is
intentional — effects are tied to the HTTP request/response cycle and have no
meaning outside of it.

## Render Middleware

Render middleware lets you wrap every page render with cross-cutting logic —
authentication, authorization, request-scoped bindings, or anything else that
should run before (or around) the render function. Middleware follows the same
`(fn [handler] (fn [req] ...))` shape as Ring middleware.

```clojure
(defn wrap-auth [handler]
  (fn [req]
    (if-let [user (get-in req [:cookies "auth-token" :value])]
      (do
        (reset! (h/session-cursor :user) (validate-token user))
        (handler req))
      {:status 302 :headers {"Location" "/login"} :body ""})))
```

Middleware runs inside the render context — `context/*request*` is bound, cursors
work, and the full Ring request (cookies, headers, query-params) is available on
initial page loads. On SSE re-renders, a minimal synthetic request is passed
instead, but cursors and app-state are always available.

Returning a Ring response map from middleware (e.g. `{:status 302 ...}`)
short-circuits the render. On initial HTTP requests this produces a normal
redirect. On SSE re-renders, Hyper converts 3xx redirects to a client-side
`window.location.href` redirect automatically.

### Handler-level middleware

Pass `:render-middleware` to `create-handler` to apply middleware to every page:

```clojure
(def handler
  (h/create-handler #'routes
    :render-middleware [wrap-auth wrap-request-logging]))
```

### Per-route middleware

Declare `:render-middleware` on individual routes for page-specific logic:

```clojure
(def routes
  [["/"      {:name :home
              :get  #'home-page}]
   ["/admin" {:name              :admin
              :get               #'admin-page
              :render-middleware [wrap-require-admin]}]])
```

### Merging order

When both handler-level and route-level middleware are present, handler-level
wraps outermost (runs first) and route-level wraps innermost (closer to the
render function):

```
wrap-auth → wrap-request-logging → wrap-require-admin → admin-page
(handler-level)                    (route-level)
```

This matches Ring convention — earlier in the vector = outermost wrapper.

### Common patterns

**Auth rehydration from cookie** — read a long-lived token on every render and
restore session state:

```clojure
(defn wrap-rehydrate-session [handler]
  (fn [req]
    (when-let [token (get-in req [:cookies "remember-me" :value])]
      (when-not @(h/session-cursor :user)
        (when-let [user (validate-jwt token)]
          (reset! (h/session-cursor :user) user))))
    (handler req)))
```

**Auth guard** — redirect unauthenticated users:

```clojure
(defn wrap-require-auth [handler]
  (fn [req]
    (if @(h/session-cursor :user)
      (handler req)
      {:status 302 :headers {"Location" "/login"} :body ""})))
```

**Request-scoped bindings** — bind dynamic vars for the duration of the render:

```clojure
(defn wrap-db-conn [handler]
  (fn [req]
    (with-open [conn (db/get-connection)]
      (binding [*db* conn]
        (handler req)))))
```

### Why no action middleware?

Actions are arbitrary Clojure code — you can call any function inside them.
Rather than a middleware abstraction, compose functions directly:

```clojure
(h/action {:as "delete-user"}
  (require-admin!)
  (audit! "delete-user")
  (delete-user! id))
```

This is explicit, composable, and easy to reason about. Render middleware earns
its complexity because render functions have a constrained signature
(`req → hiccup`) and you often need to intercept *before* the render runs.
Actions have no such constraint.

## Ring Middleware

Hyper supports standard Ring middleware via the `:middleware` option on
`create-handler`. This middleware runs **inside** Hyper's HTTP stack — after
cookies, params, and session context are parsed — so your middleware sees
`:cookies`, `:params`, `:hyper/session-id`, and `:hyper/tab-id` on the request.

```clojure
(defn wrap-request-logging [handler]
  (fn [req]
    (println "Request from session:" (:hyper/session-id req))
    (handler req)))

(def handler
  (h/create-handler #'routes
    :middleware [wrap-request-logging]))
```

Ring middleware runs on every real HTTP request — initial page loads, action
POSTs, navigation, and SSE connections. It does **not** run on SSE re-renders
(those have no HTTP request). For logic that must run on every render, use
[render middleware](#render-middleware) instead.

Middleware can also be applied outside `create-handler` by wrapping the returned
handler directly, but external middleware will not have access to Hyper's parsed
cookies, params, or session/tab IDs.

The execution order for `:middleware` is:

```
Request arrives
  → Hyper built-ins: cookies → params → keyword-params → brotli → hyper-context
  → Your :middleware (first in vector = outermost, runs first)
  → Router dispatch (page-handler, action-handler, etc.)
```

### When to use Ring middleware vs render middleware

| | Ring middleware (`:middleware`) | Render middleware (`:render-middleware`) |
|---|---|---|
| **Runs on** | HTTP requests (page load, action POST, SSE connect) | Every render (initial + SSE re-renders) |
| **Has access to** | Full Ring request (cookies, headers, body) | Synthetic request (cursors, app-state, `:hyper/env`) |
| **Use for** | `:hyper/env` setup (DB, config), request logging | Page guards, layout wrappers, permission checks |
| **Covers SPA navigation?** | No (no HTTP request on `h/navigate`) | Yes (re-render always fires) |

In practice, the two compose naturally: Ring middleware populates `:hyper/env`
with infrastructure (DB connections, config), and render middleware reads it
alongside cursors to make per-render decisions. See the
[Environment](#environment) section below.

## Environment

`:hyper/env` is a reserved request key for read-only application infrastructure
that Hyper automatically propagates across SSE re-renders and action handlers.

Env is for things that don't change during a session — database connections,
config maps, feature flags, API clients. For mutable per-user state (auth,
preferences, etc.), use [cursors](#cursors) instead.

### The problem

Hyper has three request contexts:

1. **Initial page load** — a real Ring request with cookies, headers, etc.
2. **SSE re-renders** — no HTTP request; a synthetic request is built from
   app-state.
3. **Action execution** — the action macro binds its own minimal request map.

Ring middleware can enrich the request on initial page load, but that context is
lost on SSE re-renders and inside action bodies. `:hyper/env` solves this —
Hyper captures it from every HTTP request, stashes it per-tab, and propagates it
everywhere.

### Setting up env with Ring middleware

Use `:middleware` on `create-handler` so your middleware sees parsed cookies:

```clojure
(defn wrap-db [db]
  (fn [handler]
    (fn [req]
      (handler (update req :hyper/env assoc :db db)))))

(defn wrap-config [config]
  (fn [handler]
    (fn [req]
      (handler (update req :hyper/env assoc :config config)))))

(def handler
  (h/create-handler #'routes
    :middleware [(wrap-db my-db) (wrap-config my-config)]))
```

Each middleware layer enriches `:hyper/env` on the request. Hyper stashes the
final value per-tab on every HTTP request (page load, action POST, navigation,
SSE connect), fully replacing the previous value.

### Reading env

Use `h/env` to read the environment from anywhere — render functions, action
bodies, render middleware:

```clojure
(h/env)            ;; → {:db #<Pool> :config {:feature-x true}}
(h/env :db)        ;; → #<Pool>
(h/env :config)    ;; → {:feature-x true}
(h/env :missing :default)  ;; → :default
```

In a render function:

```clojure
(defn dashboard [req]
  (let [db (h/env :db)]
    [:div
     [:h1 "Dashboard"]
     [:p "Users: " (count (db/list-users db))]]))
```

In an action:

```clojure
[:button {:data-on:click
          (h/action
            (let [db (h/env :db)]
              (db/insert! db {:event "clicked"})))}
 "Save"]
```

### How propagation works

| Entry point | What happens |
|---|---|
| **Page load** (GET) | Ring middleware sets `:hyper/env` → Hyper stashes per-tab → render fn sees it |
| **SSE re-render** | Hyper seeds the synthetic request with the stashed env → render fn sees it |
| **Action POST** | Ring middleware refreshes `:hyper/env` → Hyper re-stashes (full replace) → action body sees it |
| **SPA navigation** | No HTTP request → render uses stashed env from last capture |

### Env vs cursors

Env and cursors serve different purposes:

| | Env (`:hyper/env`) | Cursors |
|---|---|---|
| **Set by** | Ring middleware | Render functions, actions |
| **Changes** | Rarely (app startup, deploy) | Frequently (user interaction) |
| **Examples** | DB connection, config, API clients | Auth state, form data, UI state |
| **Triggers re-render?** | No | Yes |

For auth, use a `session-cursor` — it re-renders immediately when updated, works
across all tabs in a session, and doesn't depend on HTTP request timing. Use env
for the database connection the auth middleware *reads from*.

### Effects and middleware

Effects (`set-cookie!`, `delete-cookie!`, `navigate!`) compose naturally with
middleware. A typical auth flow:

**Login** — set the cookie, update the cursor, redirect:

```clojure
(h/action {:as "login"}
  (let [token (authenticate! email password)
        user  (find-user-by-token token)]
    ;; Cookie — durable backing store, Ring middleware reads it on next request
    (effects/set-cookie! "auth" token {:http-only true :max-age (* 60 60 24 7)})
    ;; Cursor — immediate reactivity, re-render sees the user right away
    (reset! (h/session-cursor :user) user)
    ;; Navigate — redirect to the home page after login
    (effects/navigate! :home)))
```

**Logout** — delete the cookie, clear the cursor, redirect:

```clojure
(h/action {:as "logout"}
  (effects/delete-cookie! "auth")
  (reset! (h/session-cursor :user) nil)
  (effects/navigate! :login))
```

**Ring middleware** — hydrate the cursor from the cookie on each HTTP request.
This keeps the cursor in sync when the user returns after a page reload or
when a cookie is set from a different tab:

```clojure
(defn wrap-auth [handler]
  (fn [req]
    (let [token      (get-in req [:cookies "auth" :value])
          user       (when token (find-user-by-token token))
          app-state* (:hyper/app-state req)
          session-id (:hyper/session-id req)]
      (when (and app-state* session-id)
        (swap! app-state* assoc-in [:sessions session-id :data :user] user))
      (handler req))))
```

**Render middleware** — redirect unauthenticated users. Returning a Ring
response map (`{:status 302 ...}`) from render middleware works on both initial
page loads (normal HTTP redirect) and SSE re-renders (Hyper converts 3xx
redirects to a client-side `window.location.href` redirect):

```clojure
(defn wrap-require-auth [handler]
  (fn [req]
    (if @(h/session-cursor :user)
      (handler req)
      {:status 302 :headers {"Location" "/login"} :body ""})))
```

The cookie and cursor serve complementary roles: the cookie is the durable
backing store (survives page reloads, read by Ring middleware), while the cursor
is the reactive in-memory view (triggers re-renders immediately, read by render
functions and render middleware).

## Navigation

Hyper uses [Reitit](https://github.com/metosin/reitit) for routing. Routes are
plain vectors with `:name`, `:get`, and optional metadata like `:title`:

```clojure
(def routes
  [["/"           {:name :home
                   :title "Home"
                   :get #'home-page}]
   ["/about"      {:name :about
                   :title "About"
                   :get #'about-page}]
   ["/user/:id"   {:name :user
                   :title (fn [req] (str "User " (get-in req [:hyper/route :path-params :id])))
                   :get #'user-page}]])
```

Use `navigate` to create SPA links. It returns attributes for an `<a>` tag —
click navigates via Datastar + pushState, right-click / cmd-click opens in a new
tab via the `:href`:

```clojure
[:a (h/navigate :home) "Home"]
[:a (h/navigate :user {:id "42"}) "View User"]
[:a (h/navigate :search {} {:q "clojure"}) "Search"]
```

The `:title` metadata is included in the browser history entry so that
back/forward navigation shows meaningful titles. Titles can be static strings,
functions of the request, or deref-able values like cursors.

Pass routes as a Var (`#'routes`) to `create-handler` for live-reloading during
development — route changes are picked up on the next request without restarting
the server and any connected tabs will automatically re-render.

### `:hyper/route`

Every request passed to your render function includes `:hyper/route` — a map
with the current route's name, path, and parameters:

```clojure
{:name         :user
 :path         "/user/42"
 :path-params  {:id "42"}
 :query-params {:tab "posts"}}
```

This works identically on the initial page load and on every SSE re-render after
SPA navigation, so it's safe to use anywhere — including shared components like
navbars and breadcrumbs:

```clojure
(defn navbar [req]
  (let [current (get-in req [:hyper/route :name])]
    [:nav
     [:a (merge (h/navigate :home)
                (when (= :home current) {:class "active"}))
      "Home"]
     [:a (merge (h/navigate :about)
                (when (= :about current) {:class "active"}))
      "About"]]))

(defn home-page [req]
  [:div
   (navbar req)
   [:h1 "Home"]])
```

You can also read it from `context/*request*` inside actions or anywhere within
the request context — the value is always consistent with the tab's current
route.

### Ring response passthrough

If a route handler returns a Ring response map (a map with `:status`) instead of
hiccup, Hyper passes it through as-is without wrapping it in HTML. This gives
you an escape hatch for redirects, error responses, or anything else that
doesn't fit the render-and-stream model:

```clojure
(defn admin-page [req]
  (if-not (admin? req)
    {:status 302 :headers {"Location" "/login"} :body ""}
    [:div "Secret admin stuff"]))
```

This works for any status code or response shape — 301/302 redirects, 403
forbidden, JSON responses, etc.

### Custom 404 page

When no route matches, Hyper renders a built-in 404 page through the normal
render pipeline — full HTML with HTTP 404 on initial loads, and pushed over SSE
when client-side navigation hits a dead URL. Override it with `:not-found`, a
`(fn [req] -> hiccup)`:

```clojure
(defn not-found-page [req]
  [:div
   [:h1 "Page not found"]
   [:p "No page at " [:code (:uri req)]]
   [:a (h/navigate :home) "Go home"]])

(def handler
  (h/create-handler #'routes
    :not-found #'not-found-page))
```

Pass a Var to pick up REPL redefinitions without restarting. The default lives
at `hyper.render.error/not-found`. Pass `:not-found nil` to disable the feature
and fall back to Reitit's plain-text 404.

## Suppress hyper wrapping certain endpoints

You can suppress hyper wrapping an endpoint altogether by marking it as `:hyper/disabled?`

```clojure
(def routes
  [["/"           {:name :home
                   :title "Home"
                   :get #'home-page}]
   ["/api/info"   {:name :api-info
                   :hyper/disabled? true ;; disable hyper wrapping this endpoint
                   :get #'about-page}]])
```

## Watches

Under the hood, Hyper maintains a persistent SSE connection per tab. When state
changes, the server re-renders your page function, diffs nothing — it sends the
full HTML as a [Datastar](https://data-star.dev/) fragment, and Datastar
morphs the DOM. Cursors changing state trigger this automatically, but for
external sources you need to tell Hyper what to watch.

### `watch!`

Call `watch!` from your render function to observe any external source. When it
changes, Hyper re-renders and pushes an update to the client:

```clojure
(def db-results* (atom []))

(defn dashboard [req]
  (h/watch! db-results*)
  [:div
   [:h1 "Results"]
   [:ul (for [r @db-results*]
          [:li (:name r)])]])
```

`watch!` is idempotent — safe to call on every render. Watches are automatically
cleaned up when the tab disconnects.

### The `Watchable` protocol

By default, `watch!` works with anything that implements `clojure.lang.IRef`
(atoms, refs, agents, vars). For custom external sources, extend
`hyper.protocols/Watchable`:

```clojure
(require '[hyper.protocols :as proto])

(extend-protocol proto/Watchable
  my.db/QueryResult
  (-add-watch [this key callback]
    ;; callback is (fn [old-val new-val])
    ;; Set up your change listener, call callback when data changes
    )
  (-remove-watch [this key]
    ;; Tear down the listener
    )
  (-dispose [this]
    ;; Release resources (close connections, stop polling, etc.)
    ;; Called when the last tab watching this source navigates away
    ;; or disconnects. No-op for sources that hold no external resources.
    (.close this)))
```

`-dispose` is reference-counted — if multiple tabs watch the same source, it's
only called when the **last** tab releases it. For built-in `IRef` types (atoms,
refs, vars), `-dispose` is a no-op.

### Route-level `:watches`

For sources that are tied to a specific page, declare them directly on the route
with `:watches`. Hyper sets them up when a tab navigates to that route and tears
them down when it navigates away:

```clojure
(def live-orders* (atom []))

(def routes
  [["/" {:name    :dashboard
         :title   "Dashboard"
         :get     #'dashboard-page
         :watches [live-orders*]}]])
```

When the `:get` handler is a Var (e.g. `#'dashboard-page`), it's automatically
added to the route's watches. This means redefining the function at the REPL
triggers an instant live reload for all connected tabs — no page refresh needed.

### Global `:watches`

For sources that should trigger a re-render on **every** page, pass `:watches`
to `create-handler`. These are added to all page routes automatically — useful
for things like a top-level config atom or feature-flags that affect every view:

```clojure
(def feature-flags* (atom {:new-ui? false}))

(def handler
  (h/create-handler
    #'routes
    :watches [feature-flags*]))
```

Global watches are combined with any per-route `:watches` — global sources come
first, then route-specific ones.

## Reactive components

By default, any state change triggers a full page re-render. For pages with
expensive render functions or frequently-changing data, `reactive` lets you
mark a sub-region of the page for independent re-rendering — when its deps
change, only that component re-renders and a targeted Datastar fragment is sent.
The rest of the page is untouched.

```clojure
(defn dashboard [req]
  (let [clock*  (h/global-cursor :clock)
        stats*  (h/tab-cursor :stats)]
    [:div
     [:h1 "Dashboard"]
     [:div.stats (render-expensive-stats @stats*)]
     (h/reactive [clock*]
       [:p "Last updated: " @clock*])]))
```

In this example, when `clock*` changes, only the `[:p ...]` re-renders. The
expensive stats section is not re-executed.

### How it works

`reactive` takes a vector of deps (any `Watchable` source — atoms, cursors,
etc.) and a body. It:

1. Injects a stable ID onto the returned element (or uses an existing `:id` if
   present)
2. Registers watches on the deps
3. **On dep change**: re-renders only this component and sends a targeted
   Datastar fragment — no full page re-render
4. **On full page re-render**: always re-executes the body (since the
   component may close over parent data that changed) and caches the result

```clojure
;; The <p> IS the reactive element — no extra div
(h/reactive [clock*]
  [:p.timestamp "Time: " @clock*])

;; Use your own ID — reactive will use it for fragment targeting
(h/reactive [clock*]
  [:p {:id "my-clock"} "Time: " @clock*])
```

### Nesting

Reactive blocks can be nested. Each block caches independently:

```clojure
(h/reactive [data*]
  [:div
   [:h2 "Data: " @data*]
   (h/reactive [clock*]
     [:span "Updated: " @clock*])])
```

When only `clock*` changes, just the inner `[:span ...]` re-renders via a
targeted Datastar fragment — the outer block and the rest of the page are
untouched. When a full page re-render occurs, both blocks re-execute normally.

### Cleanup

Reactive components are automatically cleaned up:

- **On navigation**: stale components are swept (watches removed, deps released)
- **On tab disconnect**: all components are torn down
- **On conditional change**: if a re-render produces different reactive blocks
  (e.g. an `if` branch changes), the old blocks are swept and new ones registered

Deps are reference-counted — if multiple components or tabs watch the same
source, it's only disposed when the last consumer releases it.

### Extracting components

Since `reactive` captures lexical scope, you can extract reactive components
into functions:

```clojure
(defn live-clock [clock*]
  (h/reactive [clock*]
    [:p "Time: " @clock*]))

(defn dashboard [req]
  (let [clock* (h/global-cursor :clock)]
    [:div
     [:h1 "Dashboard"]
     (live-clock clock*)]))
```

## Batched cursor updates

When an action updates multiple cursors, the renderer could snapshot between
writes and show an intermediate state the developer never intended to expose.
`batch` groups cursor writes so they land in a single atomic `swap!` — the
renderer only ever sees the final result.

```clojure
(h/action
  (h/batch
    (reset! (h/tab-cursor :data) (fetch-data!))
    (reset! (h/tab-cursor :loading?) false)))
```

Without `batch`, the renderer might snapshot after `:data` is set but before
`:loading?` is cleared — producing a frame with both the data *and* a spinner.

**Progress bar pattern** — leave the intermediate write *outside* `batch` so the
renderer picks it up, and batch only the final pair:

```clojure
(h/action
  (reset! (h/tab-cursor :loading?) true)     ;; immediate — shows spinner
  (let [data (fetch-data!)]
    (h/batch                                  ;; atomic — one render
      (reset! (h/tab-cursor :data) data)
      (reset! (h/tab-cursor :loading?) false))))
```

Inside a batch, cursor reads see all accumulated writes (read-your-writes), so
intermediate logic that depends on earlier mutations works naturally. Side
effects (I/O, HTTP, DB) are fine — only cursor writes are deferred.

Nested `batch` calls are transparent: the inner batch executes within the
outer overlay and the outermost boundary handles the flush.

## Assets and `<head>` injection

Hyper doesn’t ship with an asset pipeline (Tailwind, Vite, etc.), but it *does*
provide a couple small hooks so apps can easily:

- serve precompiled static assets (CSS/JS/images)
- inject tags into the HTML `<head>` (stylesheets, scripts, meta tags)

### Static assets

Enable static serving when you create your handler:

```clojure
(def handler
  (h/create-handler
    #'routes
    :static-resources "public"))
```

Put files under `resources/public/` and they’ll be available by URL:

- `resources/public/app.css` → `GET /app.css`
- `resources/public/favicon.ico` → `GET /favicon.ico`

For filesystem-based serving (useful in dev):

```clojure
(def handler
  (h/create-handler
    #'routes
    :static-dir "public"))
```

You can also pass multiple directories (first match wins):

```clojure
(def handler
  (h/create-handler
    #'routes
    :static-dir ["public" "target/public"]))
```

### Injecting into `<head>`

Pass `:head` as either hiccup, or a function `(fn [req] ...)` that returns hiccup.
When `:head` is a function, it is re-evaluated on every SSE render cycle and the
full `<head>` is pushed to the client. This means dynamic stylesheets, meta tags,
and the `<title>` are all kept in sync reactively.

```clojure
(def handler
  (h/create-handler
    #'routes
    :static-resources "public"
    :head [[:link {:rel "stylesheet" :href "/app.css"}]
           [:script {:defer true :src "/app.js"}] ]))
```

Pass `:head` as a Var (`#'my-head`) to enable live-reloading — when you
redefine it at the REPL, all connected tabs automatically update their `<head>`.

This is typically how you’d include your compiled Tailwind stylesheet.

## Reverse proxy: subfolder deployments

If your app is served under a subfolder (e.g. `/my-app`) by a reverse proxy
such as nginx or Caddy, pass `:base-path` to `create-handler`. Hyper will
mount its internal endpoints (`/hyper/events`, `/hyper/actions`,
`/hyper/navigate`) under that prefix and generate all client-side URLs
accordingly — no manual path editing required.

```clojure
(def handler
  (h/create-handler
    #'routes
    :base-path "/my-app"))
```

The value must start with `"/"` and have no trailing slash. With the above
example, Hyper mounts its endpoints at:

- `GET  /my-app/hyper/events`   — SSE stream
- `POST /my-app/hyper/actions`  — action handler
- `POST /my-app/hyper/navigate` — SPA back/forward navigation

Your own application routes (e.g. `"/"`, `"/about"`) are unaffected — prefix
those in your reverse proxy config as you normally would.

A minimal nginx snippet for the above:

```nginx
location /my-app/ {
  proxy_pass http://localhost:3000/;
  proxy_http_version 1.1;
  proxy_set_header Connection "";
  proxy_buffering off;
}
```

## SSE connection behavior

By default, Hyper keeps the SSE connection open even when the browser tab is
hidden (e.g. the user switches to another tab). This ensures state updates are
delivered immediately without waiting for the tab to become visible again.

If you'd prefer Datastar's default behavior — closing the connection when the
tab is hidden and reopening it when visible — pass `:open-when-hidden? false`:

```clojure
(def handler
  (h/create-handler
    #'routes
    :open-when-hidden? false))
```

Hyper's built-in `/hyper/events` endpoint automatically sends SSE-friendly
headers, including `Cache-Control: no-cache, no-transform` and
`X-Accel-Buffering: no`, to improve compatibility with reverse proxies.

## Brotli compression

Hyper uses [brotli4j](https://github.com/hyperxpro/Brotli4j) to compress both
initial page responses and streaming SSE updates.

## clj-kondo

Hyper ships with clj-kondo config. Import it with:

```bash
clj-kondo --copy-configs --dependencies --lint "$(clojure -Spath)"
```

## Testing

The `hyper.test` namespace provides `test-page` and `test-action` for testing
page handlers in isolation — no server, no browser, no SSE. Render a page,
inspect the output and effects, simulate user interactions, and re-render to
verify state changes.

```clojure
(require '[hyper.test :as ht])
(require '[hyper.core :as h])
```

### `test-page`

`test-page` renders a page handler and returns a map describing everything that
happened:

```clojure
(defn counter-page [req]
  (let [count* (h/tab-cursor :count 0)]
    [:div
     [:h1 "Count: " @count*]
     [:button {:data-on:click (h/action {:as "increment"}
                                (swap! count* inc))}
      "+1"]
     [:button {:data-on:click (h/action {:as "decrement"}
                                (swap! count* dec))}
      "-1"]]))

(ht/test-page counter-page)
;; => {:body      [:div [:h1 "Count: " 0] [:button {...} "+1"] ...]
;;     :body-html "<div><h1>Count: 0</h1>..."
;;     :actions   {"increment" {:fn #fn}, "decrement" {:fn #fn}}
;;     :cursors   {:global {}, :session {}, :tab {:count 0}, :route {...}}
;;     :signals   {:count {:html-name "count" :default-val 0 :local? false}}
;;     :watches   [#<Atom@...>]
;;     :app-state #<Atom@...>}
```

Pass options to customize the test context:

```clojure
;; Seed cursor state so the handler sees pre-existing values
(ht/test-page my-page {:cursors {:tab     {:count 10}
                                 :session {:user "alice"}
                                 :global  {:theme "dark"}}})

;; Simulate a specific route
(ht/test-page my-page {:route {:name         :user
                               :path         "/user/42"
                               :path-params  {:id "42"}
                               :query-params {}}})

;; Apply render middleware (same shape as create-handler :render-middleware)
(ht/test-page my-page {:render-middleware [wrap-auth]})
```

Seeded `:cursors` values take precedence over defaults — if your handler calls
`(h/tab-cursor :count 0)` but you seed `{:tab {:count 10}}`, the cursor will
read `10`.

### Naming actions with `:as`

The `action` macro accepts an `:as` option that gives the action a
human-readable name. `test-page` uses this as the key in the `:actions` map,
making it easy to find and invoke specific actions in tests:

```clojure
;; In your page handler
(h/action {:as "save-form"} (save! $form-data))

;; In your test
(get-in result [:actions "save-form" :fn])
```

Without `:as`, actions are keyed by their auto-generated action ID. `:as` can
be combined with `:when`:

```clojure
(h/action {:as "search" :when "evt.key === 'Enter'"} (search! $value))
```

### `test-action`

`test-action` executes an action from a `test-page` result and returns a
snapshot of cursor state and any effects accumulated during execution:

```clojure
(let [result (ht/test-page counter-page)]
  (ht/test-action result "increment"))
;; => {:cursors   {:global {}, :session {}, :tab {:count 1}, :route {...}}
;;     :effects   {:cookies {}, :scripts []}
;;     :app-state #<Atom@...>}
```

The `:effects` map contains `:cookies` (a map of cookie-name → cookie opts) and
`:scripts` (a vector of JS strings). Effects are collected but **not applied** —
this lets tests assert on what effects *would* happen without actually setting
cookies or sending SSE events:

```clojure
(let [result (ht/test-page my-page)
      after  (ht/test-action result "login")]
  (is (= "jwt-token" (get-in after [:effects :cookies "auth" :value])))
  (is (seq (get-in after [:effects :scripts]))))
```

Pass client params to simulate `$value`, `$checked`, `$key`, or `$form-data`:

```clojure
(ht/test-action result "search" {:value "clojure"})
```

### Full workflow

Chain `test-page` → `test-action` → `test-page` to verify the full
render–interact–re-render cycle. Thread `:app-state` to preserve state across
calls:

```clojure
(deftest test-counter-page
  (let [;; Initial render
        r1 (ht/test-page counter-page)]
    (is (str/includes? (:body-html r1) "Count: 0"))

    ;; Simulate two clicks
    (ht/test-action r1 "increment")
    (ht/test-action r1 "increment")

    ;; Re-render with the same state
    (let [r2 (ht/test-page counter-page {:app-state (:app-state r1)})]
      (is (str/includes? (:body-html r2) "Count: 2"))
      (is (= 2 (get-in r2 [:cursors :tab :count])))

      ;; Decrement
      (ht/test-action r2 "decrement")

      (let [r3 (ht/test-page counter-page {:app-state (:app-state r2)})]
        (is (str/includes? (:body-html r3) "Count: 1"))))))
```

## Developing

Tests are run with [Kaocha](https://github.com/lambdaisland/kaocha) via the
`:test` alias. There are two test suites: `:unit` for fast in-process tests and
`:e2e` for browser-based end-to-end tests.

```bash
# Run unit tests only
clojure -M:test --focus :unit

# Run E2E browser tests only
clojure -M:test --focus :e2e

# Run all tests
clojure -M:test
```

### Unit tests

Unit tests live in `test/hyper/` and cover cursors, actions, navigation, routing,
rendering, state management, effects, render middleware, and brotli compression.
They run in-process with no server or browser — just bind `*request*` and
exercise the API directly.

### E2E tests

End-to-end tests use [Playwright](https://playwright.dev/) via the
[wally](https://github.com/pfeodrippe/wally) library to drive a real headless
Chromium browser against a running Hyper server. They're tagged with `^:e2e`
metadata so Kaocha can filter them.

The E2E suite covers:

- **Cursor isolation** — multiple browser contexts (separate sessions) and
  multiple tabs within a session verify that global, session, tab, and URL
  cursors propagate to exactly the right scope
- **Title live reload** — redefining the routes Var updates `document.title`
  via SSE without a page refresh
- **Head live reload** — redefining the `:head` Var hot-swaps `<head>` content
  via SSE
- **Content live reload** — redefining the routes Var with new inline handler
  functions hot-swaps the page content via SSE
- **Effects** — `navigate!`, `set-cookie!`, `delete-cookie!`, and
  `execute-script!` are exercised end-to-end, including combined effects in a
  single action

## Contributing

PRs and ideas welcome! Please follow the [angular commit
guidelines](https://github.com/angular/angular/blob/main/contributing-docs/commit-message-guidelines.md)
with your messages. All subject lines should be less than 80 characters long and
avoid needless language.

eg:

``` bash
fix: header template rendering w/ nil data
```

