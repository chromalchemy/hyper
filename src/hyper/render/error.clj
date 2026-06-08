(ns hyper.render.error
  "Built-in error renderers for use as the `:render-error` option on
   `hyper.core/create-handler`.

   An error renderer is a function `(fn [error req] -> hiccup)` that produces
   the markup shown in place of a view whose render-fn threw.  The exception
   is always logged via telemere — the renderer is purely a UI concern.

   Two renderers are provided:

   - `minimal` (default): generic, production-safe message with no exception
     details.  Use in production.
   - `explain`: developer-friendly message with the exception class, message,
     `ex-data`, and the full stack trace including the cause chain.  Use in
     development.

   This namespace also provides `not-found`, the default `:not-found`
   renderer — a `(fn [req] -> hiccup)` shown when no route matches a request.

   You can also supply your own renderer.  Pass a Var to pick up REPL
   redefinitions without restarting the server:

       (h/create-handler routes
                         :render-error  my.app.errors/branded-error)

       (h/create-handler routes
                         :render-error  #'my.app.errors/branded-error)"
  (:require [clojure.pprint]
            [clojure.string :as string]))

;; ---------------------------------------------------------------------------
;; Shared styling
;; ---------------------------------------------------------------------------

(def ^:private container-style
  "padding: 20px; font-family: sans-serif; background: #fee;
   border: 1px solid #fcc; border-radius: 4px; margin: 20px;")

(def ^:private heading-style
  "color: #c00; margin-top: 0;")

(def ^:private pre-style
  "background: #fff; padding: 10px; border-radius: 4px; overflow: auto;
   white-space: pre-wrap; word-break: break-word; font-size: 0.85em;")

(def ^:private not-found-container-style
  "padding: 40px 20px; font-family: sans-serif; text-align: center;
   color: #333;")

(def ^:private not-found-heading-style
  "font-size: 4em; margin: 0 0 8px; color: #666; font-weight: 700;")

;; ---------------------------------------------------------------------------
;; minimal
;; ---------------------------------------------------------------------------

(defn minimal
  "Production-safe error renderer.

   Renders a generic apology without leaking any exception details.  This is
   the default `:render-error` for `create-handler`."
  [_error _req]
  [:div {:style container-style}
   [:h2 {:style heading-style} "Something went wrong"]
   [:p "An error occurred while rendering this view."]])

;; ---------------------------------------------------------------------------
;; explain
;; ---------------------------------------------------------------------------

(defn- stack-trace-string
  "Build the standard Throwable.printStackTrace() output for an exception,
   including its full cause chain, as a string."
  [^Throwable e]
  (let [sw (java.io.StringWriter.)
        pw (java.io.PrintWriter. sw)]
    (.printStackTrace e pw)
    (.flush pw)
    (str sw)))

(defn- pp-str
  "Pretty-print a value to a string, falling back to (str v) on failure."
  [v]
  (try
    (with-out-str (clojure.pprint/pprint v))
    (catch Throwable _
      (str v))))

(defn- causes
  "Walk a Throwable's cause chain into a vector of exceptions, root first → root cause last."
  [^Throwable e]
  (loop [e e, acc []]
    (if e
      (recur (.getCause e) (conj acc e))
      acc)))

(defn explain
  "Developer-friendly error renderer.

   Renders the exception class, message, any `ex-data`, and the full stack
   trace (including cause chain).  Intended for development environments —
   do not enable in production.

   Configure via:

       (h/create-handler routes :render-error hyper.render.error/explain)"
  [^Throwable error _req]
  (let [chain   (causes error)
        primary (first chain)]
    [:div {:style container-style}
     [:h2 {:style heading-style} "Render Error"]
     [:p [:strong (.getName (class primary))] ": "
      (or (ex-message primary) "(no message)")]
     (when-let [data (ex-data primary)]
       [:div
        [:p {:style "margin-bottom: 4px;"} [:strong "ex-data:"]]
        [:pre {:style pre-style} (pp-str data)]])
     (when (> (count chain) 1)
       [:p {:style "margin-bottom: 4px;"}
        [:strong "Caused by chain (" (count chain) " exceptions):"]])
     [:pre {:style pre-style}
      (string/trimr (stack-trace-string error))]]))

;; ---------------------------------------------------------------------------
;; not-found
;; ---------------------------------------------------------------------------

(defn not-found
  "Default `:not-found` renderer for `hyper.core/create-handler`.

   A not-found renderer is a function `(fn [req] -> hiccup)` shown when no route
   matches; it can read the attempted `:uri` from the request.  This default is
   generic and production-safe — supply your own (a Var to pick up REPL
   redefinitions) to brand it:

       (h/create-handler routes :not-found #'my.app.errors/not-found-page)"
  [_req]
  [:div {:style not-found-container-style}
   [:h1 {:style not-found-heading-style} "404"]
   [:p "The page you're looking for doesn't exist."]])
