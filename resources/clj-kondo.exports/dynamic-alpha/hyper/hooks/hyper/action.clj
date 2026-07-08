(ns hooks.hyper.action
  "clj-kondo hook for the hyper.core/action macro.

   Its sole job is to steer users off the deprecated `:when` guard: when an
   action's leading options map carries `:when`, it registers a warning
   pointing at the `h/expr` composition instead.  The call node is returned
   unchanged, so clj-kondo's normal analysis (and the client-param
   `:unresolved-symbol` excludes in config.edn) still applies to the body."
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private opt-keys #{:when :as :upload :key})

(defn- opts-node
  "The leading options map of an `action` call — a map node whose keys include
   at least one recognized option — or nil."
  [args]
  (let [m (first args)]
    (when (and m (api/map-node? m))
      (let [ks (->> (partition 2 (:children m))
                    (map first)
                    (filter api/keyword-node?)
                    (map api/sexpr)
                    set)]
        (when (seq (filter opt-keys ks))
          m)))))

(defn- when-key-node
  "The `:when` keyword key node inside the options map, or nil."
  [opts]
  (some (fn [[k _v]]
          (when (and (api/keyword-node? k) (= :when (api/sexpr k)))
            k))
        (partition 2 (:children opts))))

(defn action
  "Warn on the deprecated `action :when` guard; leave the node otherwise
   untouched for normal analysis."
  [{:keys [node]}]
  (let [[_ & args] (:children node)]
    (when-let [opts (opts-node args)]
      (when-let [k (when-key-node opts)]
        (api/reg-finding!
          (assoc (meta k)
                 :level   :warning
                 :message (str "action :when is deprecated — gate the action with h/expr instead: "
                               "(h/expr (when <cond> (h/action ...))). "
                               "Client-param symbols like $key/$value work inside h/expr.")
                 :type    :hyper.action/deprecated-when)))))
  {:node node})
