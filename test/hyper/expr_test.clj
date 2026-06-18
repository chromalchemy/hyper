(ns hyper.expr-test
  "Unit tests for hyper.expr — the Clojure → Datastar expression transpiler."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.expr :refer [->expr]]
            [hyper.signal :as signal]))

(defn- local-sig [nm default]
  (signal/->LocalSignal (str "_" nm) (str "_" nm) default))

(defn- sig [js-name]
  (signal/->Signal js-name js-name [(keyword js-name)] (atom {}) "t1" nil))

;; ---------------------------------------------------------------------------
;; Raw Datastar style (no locals)
;; ---------------------------------------------------------------------------

(deftest test-raw-datastar-style
  (testing "set! on $signals"
    (is (= "$count = 0" (->expr (set! $count 0)))))

  (testing "kebab-case signal names are preserved"
    (is (= "$record-id = 5" (->expr (set! $record-id 5)))))

  (testing "namespaced signals"
    (is (= "$person.name = \"alice\"" (->expr (set! $person.name "alice")))))

  (testing "actions"
    (is (= "@post(\"/update\")" (->expr (@post "/update"))))
    (is (= "@get(\"/poke\")" (->expr (@get "/poke")))))

  (testing "multiple statements join with ;"
    (is (= "$a = 1; @get(\"/x\")" (->expr (set! $a 1) (@get "/x")))))

  (testing "evt and el pass through as client-side symbols"
    (is (= "(evt.key) === (\"Enter\")" (->expr (= evt.key "Enter"))))
    (is (str/includes? (->expr (.focus el)) "el.focus()"))))

;; ---------------------------------------------------------------------------
;; Sandbox-safe operators (no squint_core in output, ever)
;; ---------------------------------------------------------------------------

(deftest test-sandbox-safe-output
  (testing "boolean and equality forms compile to bare JS operators"
    (is (= "(($a) === (1)) && (($b) || (evt.shiftKey))"
           (->expr (and (= $a 1) (or $b evt.shiftKey)))))
    (is (= "(!(($name) === (\"\")))" (->expr (not= $name ""))))
    (is (= "'' + (\"n=\") + ($n)" (->expr (str "n=" $n)))))

  (testing "when compiles to a ternary"
    (let [out (->expr (when $open (@post "/x")))]
      (is (str/includes? out "?"))
      (is (str/includes? out "@post"))))

  (testing "no squint_core references in any output"
    (doseq [out [(->expr (and $a $b $c))
                 (->expr (or $a $b))
                 (->expr (not $a))
                 (->expr (= $a $b $c))
                 (->expr (not= $a $b))
                 (->expr (str $a "x" $b))
                 (->expr (when $a (set! $b 1) (set! $c 2)))
                 (->expr (if $a 1 2))
                 (->expr (println "dbg" $a))
                 (->expr (+ $a 1))
                 (->expr (< $a 5))]]
      (is (not (str/includes? out "squint_core")) out))))

;; ---------------------------------------------------------------------------
;; Boundary inference — signals as atoms
;; ---------------------------------------------------------------------------

(deftest test-signal-atom-vocabulary
  (testing "swap! with not — the toggle idiom"
    (let [open?* (local-sig "open" false)]
      (is (= "$_open = (!($_open))" (->expr (swap! open?* not))))))

  (testing "swap! with inc and with extra args"
    (let [n* (local-sig "n" 0)]
      (is (= "$_n = ($_n + 1)" (->expr (swap! n* inc))))
      (is (= "$_n = ($_n + 5)" (->expr (swap! n* + 5))))))

  (testing "reset! from a client-side value"
    (let [query* (sig "searchQuery")]
      (is (= "$searchQuery = evt.target.value"
             (->expr (reset! query* evt.target.value))))))

  (testing "deref reads as a signal reference"
    (let [name* (sig "userName")]
      (is (= "(!(($userName) === (\"\")))" (->expr (not= @name* ""))))
      (is (= "$userName" (->expr @name*)))))

  (testing "the full guard + assign + action composition"
    (let [query* (sig "q")]
      (is (= (str "(((evt.key) === (\"Enter\")) ? "
                  "(($q = evt.target.value), (@post(\"/search\"))) : (null))")
             (->expr (when (= evt.key "Enter")
                       (reset! query* evt.target.value)
                       (@post "/search")))))))

  (testing "reset!/swap! on a non-signal throws at runtime"
    (let [x 5]
      (is (thrown-with-msg? Exception #"must be a signal"
                            (->expr (reset! x 1)))))))

;; ---------------------------------------------------------------------------
;; Boundary inference — locals and Clojure data
;; ---------------------------------------------------------------------------

(deftest test-value-splicing
  (testing "locals splice as JS literals"
    (let [factor 2
          label  "a'b"]
      (is (= "$scaled = ($raw * 2)" (->expr (set! $scaled (* $raw factor)))))
      (is (= "$label = 'a\\'b'" (->expr (set! $label label)))
          "strings are escaped JS literals")))

  (testing "keyword-call forms evaluate as Clojure"
    (let [person {:id 1234 :name "alice"}]
      (is (= "$person-id = 1234" (->expr (set! $person-id (:id person)))))
      (is (= "$person-name = 'alice'" (->expr (set! $person-name (:name person)))))))

  (testing "explicit unquote still works as the escape hatch"
    (let [v 42]
      (is (= "$x = 43" (->expr (set! $x ~(inc v)))))))

  (testing "collections splice as JS literals"
    (let [xs [1 2 3]]
      (is (= "$items = [1, 2, 3]" (->expr (set! $items xs)))))))

;; ---------------------------------------------------------------------------
;; Compile-once semantics
;; ---------------------------------------------------------------------------

(deftest test-macro-time-compilation
  (testing "no splices: expansion is a constant string"
    (is (string? (macroexpand-1 '(hyper.expr/->expr (set! $count 0))))))

  (testing "with splices: expansion is a substitute call over a compiled template"
    ;; macroexpand-1 has no &env locals, so use the explicit unquote form
    ;; to exercise the splice path.
    (let [expansion (macroexpand-1 '(hyper.expr/->expr (set! $x ~(compute))))]
      (is (seq? expansion))
      (is (= 'hyper.expr/substitute (first expansion)))
      (is (string? (second expansion)) "template compiled at expansion, not per call"))))

;; ---------------------------------------------------------------------------
;; Deref of static signal vars (e.g. the connection signals)
;; ---------------------------------------------------------------------------

(deftest test-deref-signal-var
  (testing "deref of a top-level var holding a signal splices to a $ref"
    (is (= "(!($_hyperConnected))"
           (->expr (not @signal/connected?*))))
    (is (= "($_hyperConnection) === (\"reconnecting\")"
           (->expr (= @signal/connection* :reconnecting)))))

  (testing "keyword and string tokens compile identically"
    (is (= (->expr (= @signal/connection* :open))
           (->expr (= @signal/connection* "open")))))

  (testing "deref of a non-signal var is left to the client (no splice)"
    (is (str/includes? (->expr (not @some-undefined-thing))
                       "squint_core.deref"))))
