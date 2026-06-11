(ns hyper.component.hiccup
  "Macro-time hiccup compilation for client components.

   Literal hiccup in defc render segments is compiled into ($h ...)
   descriptor calls at macro-expansion time, so the client runtime needs no
   parsing heuristics for the common path: tag shorthand, attrs-map
   detection, and element-vs-fragment disambiguation are all resolved here,
   on the JVM, where they are unit-testable.  Dynamically constructed hiccup
   (vectors built at runtime) falls back to the runtime array interpreter.

   The runtime counterpart lives in resources/hyper/component-runtime.js:
   `$h` consumes the descriptors emitted here, and `$parseTag` implements
   the same tag-shorthand contract as `parse-tag` for the dynamic fallback
   path.  The two must stay in agreement — the kitchen-sink conformance
   e2e test (hyper.component-e2e-test) pins the shared contract."
  (:require [clojure.string :as str]))

(defn parse-tag
  "Parse a hiccup tag keyword into [tag id classes].
   :div         -> [\"div\" nil nil]
   :div#m       -> [\"div\" \"m\" nil]
   :div.a.b     -> [\"div\" nil \"a b\"]
   :svg.chart#c -> [\"svg\" \"c\" \"chart\"]
   :.box        -> [\"div\" nil \"box\"]

   JS twin: `$parseTag` in component-runtime.js (runtime fallback path)."
  [tag-kw]
  (let [s       (name tag-kw)
        parts   (str/split s #"(?=[#.])")
        tag     (let [t (first parts)]
                  (if (or (str/blank? t) (str/starts-with? t "#") (str/starts-with? t "."))
                    "div"
                    t))
        parts   (if (= tag (first parts)) (rest parts) parts)
        id      (some #(when (str/starts-with? % "#") (subs % 1)) parts)
        classes (->> parts
                     (filter #(str/starts-with? % "."))
                     (map #(subs % 1))
                     seq)]
    [tag id (when classes (str/join " " classes))]))

(def ^:private value-position-forms
  "Control forms whose value positions are recursed into by compile-hiccup.
   Anything else is left untouched and handled by the runtime fallback."
  '#{if when when-not when-let if-let when-some if-some do let for cond})

(declare compile-hiccup)

(defn- hiccup-vector? [form]
  (and (vector? form)
       (keyword? (first form))))

(defn- compile-element
  "Compile a literal hiccup vector into an ($h ...) call form.
   The attrs position is decided syntactically: a map literal in second
   position is attrs; anything else is a child."
  [[tag-kw & body]]
  (let [[tag id classes] (parse-tag tag-kw)
        [attrs children] (if (map? (first body))
                           [(first body) (rest body)]
                           [nil body])]
    (list '$h tag id classes attrs
          (mapv compile-hiccup children))))

(defn- compile-expr
  "Recurse into the value positions of known control forms so hiccup inside
   (if ...), (for ...), (let ...) etc. still compiles.  For `let`/`do`/`when`
   style bodies only the final (value) form is compiled — earlier forms are
   side effects.  `cond` compiles result positions, never tests.  Unknown
   expressions pass through untouched (runtime fallback)."
  [form]
  (let [[head & args] form]
    (case head
      (if if-let if-some)
      (list* head (first args) (map compile-hiccup (rest args)))

      (when when-not when-let when-some do let for)
      ;; Compile only the last (value-position) form.
      (let [body     (vec args)
            last-idx (dec (count body))]
        (list* head (concat (subvec body 0 last-idx)
                            [(compile-hiccup (nth body last-idx))])))

      cond
      (list* head (map-indexed (fn [i x] (if (odd? i) (compile-hiccup x) x)) args))

      ;; Unknown expression — leave for the runtime fallback.
      form)))

(defn compile-hiccup
  "Compile literal hiccup in a render form into ($h tag id classes attrs
   [children]) descriptor calls.  See compile-element / compile-expr for
   the rules.  Public for testing."
  [form]
  (cond
    (hiccup-vector? form)
    (compile-element form)

    (and (seq? form) (contains? value-position-forms (first form)))
    (compile-expr form)

    :else form))
