(ns hyper.utils
  "General-purpose utility functions."
  (:require [clojure.string])
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
