(ns ^:no-doc hyper.component.bundle
  "Assembly and serving support for the client-components JS bundle.

   Components registered in hyper.component/registry* are assembled into a
   single ES module — squint core import, the hyper component runtime
   (resources/hyper/component-runtime.js), then each compiled component —
   served at /hyper/components.js (see hyper.server) and injected into the
   page head with a content-hashed URL (see hyper.render), which makes the
   response immutable-cacheable and lets REPL redefinition hot-swap the
   bundle over SSE."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hyper.component :as component]))

(def default-squint-core-url
  "CDN URL for squint's core.js runtime, version-matched to the compiler
   dependency in deps.edn.  Override via the :squint-core-url option on
   create-handler to self-host."
  "https://cdn.jsdelivr.net/npm/squint-cljs@0.9.184/src/squint/core.js")

(defn- runtime-js []
  (slurp (io/resource "hyper/component-runtime.js")))

(defn- sha256-hex
  [^String s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) (take 8 d)))))

(defn- import-lines
  "Build the deduplicated ES module import statements for all component
   :requires across the registry.  Aliases are munged the same way Squint
   munges them in compiled output (e.g. chart-lib -> chart_lib), so the
   import identifier matches the compiled references.

   Throws when an alias maps to more than one URL — the bundle is a single
   module scope (this is also checked per-component at registration; this
   guard catches stale-registry edge cases)."
  [registry]
  (let [pairs (->> (vals registry)
                   (mapcat :requires)
                   (map (juxt :alias :url))
                   distinct
                   (sort-by (comp str first)))]
    (doseq [[alias urls] (group-by first pairs)
            :when        (> (count urls) 1)]
      (throw (ex-info (str "Alias " alias " maps to multiple URLs across components: "
                           (pr-str (mapv second urls)))
                      {:alias alias :urls (mapv second urls)})))
    (apply str
           (map (fn [[alias url]]
                  (str "import * as " (munge (name alias)) " from '" url "';\n"))
                pairs))))

(defn- assemble-bundle
  [registry squint-core-url]
  (let [js (str "import * as $sc from '" (or squint-core-url default-squint-core-url) "';\n"
                (import-lines registry)
                (runtime-js)
                "\n"
                (->> (sort-by key registry)
                     (map (comp :js val))
                     (str/join "\n")))]
    {:js js :hash (sha256-hex js)}))

(defonce ^:private bundle-cache* (atom nil))

(defn bundle
  "Assemble (or return cached) the components JS bundle.

   Returns {:js \"...\" :hash \"...\"} or nil when no components are
   registered.  The bundle is a single ES module: squint core import,
   the hyper component runtime, then each registered component sorted
   by name.  Cached against the registry snapshot + core URL."
  ([] (bundle nil))
  ([{:keys [squint-core-url]}]
   (let [registry @component/registry*]
     (when (seq registry)
       (let [cache-key [registry squint-core-url]
             cached    @bundle-cache*]
         (if (= cache-key (:key cached))
           (:bundle cached)
           (let [b (assemble-bundle registry squint-core-url)]
             (reset! bundle-cache* {:key cache-key :bundle b})
             b)))))))

(defn head-script-tag
  "Hiccup script tag for the components bundle, or nil when no components
   are registered.  The content hash is carried in the query string so the
   endpoint can serve immutable cache headers and a registry change rotates
   the URL (which, combined with head-element fingerprint diffing, makes
   REPL redefinition hot-swap the bundle over SSE)."
  [base-path opts]
  (when-let [{:keys [hash]} (bundle opts)]
    [:script {:type "module"
              :src  (str base-path "/hyper/components.js?v=" hash)}]))
