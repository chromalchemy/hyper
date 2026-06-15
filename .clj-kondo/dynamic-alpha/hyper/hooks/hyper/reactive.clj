(ns hooks.hyper.reactive
  "clj-kondo hook for the hyper.core/reactive macro.

   Validates the call shape — `(reactive [deps] & body)`: a deps *vector* and a
   non-empty body — and rewrites the call into an analyzable `let` so
   clj-kondo's normal linting applies: the dep symbols resolve and count as
   used, and the body is linted in place with the surrounding lexical scope
   (so a nested `reactive` and any closed-over locals analyze normally)."
  (:require [clj-kondo.hooks-api :as api]))

(defn- finding!
  [node level message type]
  (api/reg-finding!
   (assoc (meta node)
          :level level
          :message message
          :type type)))

(defn reactive
  "Validate + rewrite (reactive [deps] & body)."
  [{:keys [node]}]
  (let [[_ deps & body] (:children node)]
    (when-not (and deps (api/vector-node? deps))
      (finding! (or deps node) :error
                "h/reactive requires a deps vector as its first argument (e.g. [clock*])"
                :hyper.reactive/invalid-deps))
    (when (empty? body)
      (finding! node :error
                "h/reactive requires a body"
                :hyper.reactive/missing-body))
    (let [new-node (api/list-node
                    (list*
                     (api/token-node 'let)
                     (api/vector-node
                      [(api/token-node '_deps) (or deps (api/vector-node []))])
                     body))]
      {:node new-node})))
