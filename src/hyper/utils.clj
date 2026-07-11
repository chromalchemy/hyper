(ns ^:no-doc hyper.utils
  "General-purpose utility functions."
  (:require [clojure.string]
            [taoensso.telemere :as t])
  (:import (java.net URLDecoder URLEncoder)))

(defn escape-js-string
  "Escape a string for safe embedding in a single-quoted JavaScript string literal
   inside a <script> block. Handles backslashes, quotes, newlines, line/paragraph
   separators, and </script> injection."
  [s]
  (when s
    (-> s
        (clojure.string/replace "\\" "\\\\")
        (clojure.string/replace "'" "\\'")
        (clojure.string/replace "\"" "\\\"")
        (clojure.string/replace "\n" "\\n")
        (clojure.string/replace "\r" "\\r")
        (clojure.string/replace "\u2028" "\\u2028")
        (clojure.string/replace "\u2029" "\\u2029")
        ;; Prevent </script> from closing the script block in HTML parser
        (clojure.string/replace "</" "<\\/"))))

(defn parse-query-string
  "Parse a query string into a keyword-keyed map with URL-decoded values.
   Returns nil if query-string is nil."
  [query-string]
  (when query-string
    (into {}
          (map (fn [pair]
                 (let [[k v] (clojure.string/split pair #"=" 2)]
                   [(keyword (URLDecoder/decode k "UTF-8"))
                    (URLDecoder/decode (or v "") "UTF-8")])))
          (clojure.string/split query-string #"&"))))

(defn build-url
  "Build a URL string from a path and query params map.
   Omits query params with nil values.
   Returns path if no query params remain."
  [path query-params]
  (let [non-nil-query-params (into {}
                                   (remove (comp nil? val))
                                   query-params)]
    (if (empty? non-nil-query-params)
      path
      (let [query-string (->> non-nil-query-params
                              (map (fn [[k v]]
                                     (str (name k) "=" (URLEncoder/encode (str v) "UTF-8"))))
                              (clojure.string/join "&"))]
        (str path "?" query-string)))))

;; ---------------------------------------------------------------------------
;; Warn-on-access map
;; ---------------------------------------------------------------------------

(deftype WarnOnAccessMap [inner]
  ;; Core lookup — keyword access (:foo req), (get req :foo), etc.
  ;; Any key not present in the inner map returns nil and logs a warning.
  clojure.lang.ILookup
  (valAt [_ k]
    (if (contains? inner k)
      (get inner k)
      (do (t/log! {:level :warn
                   :id    :hyper.warn/http-key-in-render
                   :msg   (str "Render function accessed " k " which is only "
                               "available on initial HTTP page loads, not SSE "
                               "re-renders. Use middleware to read this value "
                               "and store it in a cursor.")})
          nil)))
  (valAt [_ k not-found]
    (if (contains? inner k)
      (get inner k)
      (do (t/log! {:level :warn
                   :id    :hyper.warn/http-key-in-render
                   :msg   (str "Render function accessed " k " which is only "
                               "available on initial HTTP page loads, not SSE "
                               "re-renders. Use middleware to read this value "
                               "and store it in a cursor.")})
          not-found)))

  ;; Map as function — (req :foo)
  clojure.lang.IFn
  (invoke [this k] (.valAt this k))
  (invoke [this k not-found] (.valAt this k not-found))

  ;; Associative — assoc, containsKey, entryAt
  clojure.lang.Associative
  (containsKey [_ k] (contains? inner k))
  (entryAt [_ k] (find inner k))
  (assoc [_ k v] (WarnOnAccessMap. (assoc inner k v)))

  ;; IPersistentMap — without (dissoc)
  clojure.lang.IPersistentMap
  (without [_ k] (WarnOnAccessMap. (dissoc inner k)))
  (assocEx [_ k v]
    (if (contains? inner k)
      (throw (RuntimeException. (str "Key already present: " k)))
      (WarnOnAccessMap. (assoc inner k v))))

  ;; Seqable + Counted + Iterable — for seq, count, doseq
  clojure.lang.Seqable
  (seq [_] (seq inner))

  clojure.lang.Counted
  (count [_] (count inner))

  clojure.lang.IPersistentCollection
  (cons [_ o] (WarnOnAccessMap. (conj inner o)))
  (empty [_] (WarnOnAccessMap. (empty inner)))
  (equiv [_ o] (= inner o))

  java.lang.Iterable
  (iterator [_] (.iterator ^java.lang.Iterable inner)))

(defmethod print-method WarnOnAccessMap [^WarnOnAccessMap m ^java.io.Writer w]
  (print-method (.inner m) w))

(defn warn-on-access-map
  "Wrap a map so that accessing any key not present in the map returns
   nil and logs a warning.  Used for SSE re-render requests where HTTP
   context is unavailable — the warning alerts developers to use
   middleware + cursors for data that must survive re-renders."
  [m]
  (WarnOnAccessMap. m))
