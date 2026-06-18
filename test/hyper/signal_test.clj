(ns hyper.signal-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [dev.onionpancakes.chassis.core :as c]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.signal :as signal]
            [hyper.state :as state]))

;; ---------------------------------------------------------------------------
;; Name conversion
;; ---------------------------------------------------------------------------

(deftest signal-js-name-test
  (testing "keyword → camelCase"
    (is (= "name" (signal/signal-js-name :name)))
    (is (= "userName" (signal/signal-js-name :user-name)))
    (is (= "myLongSignalName" (signal/signal-js-name :my-long-signal-name))))

  (testing "vector path → dot-notation camelCase"
    (is (= "user.name" (signal/signal-js-name [:user :name])))
    (is (= "userProfile.firstName" (signal/signal-js-name [:user-profile :first-name])))
    (is (= "a.b.c" (signal/signal-js-name [:a :b :c])))))

(deftest signal-html-name-test
  (testing "keyword → kebab-case (Datastar auto-converts)"
    (is (= "name" (signal/signal-html-name :name)))
    (is (= "user-name" (signal/signal-html-name :user-name))))

  (testing "vector path → dot-notation kebab-case"
    (is (= "user.name" (signal/signal-html-name [:user :name])))
    (is (= "user-profile.first-name" (signal/signal-html-name [:user-profile :first-name])))))

;; ---------------------------------------------------------------------------
;; Value encoding
;; ---------------------------------------------------------------------------

(deftest clj->js-literal-test
  (testing "strings wrapped in single quotes"
    (is (= "'hello'" (signal/clj->js-literal "hello")))
    (is (= "''" (signal/clj->js-literal ""))))

  (testing "strings with special characters are escaped"
    (is (= "'it\\'s'" (signal/clj->js-literal "it's")))
    (is (= "'line1\\nline2'" (signal/clj->js-literal "line1\nline2"))))

  (testing "numbers are bare"
    (is (= "42" (signal/clj->js-literal 42)))
    (is (= "3.14" (signal/clj->js-literal 3.14))))

  (testing "booleans"
    (is (= "true" (signal/clj->js-literal true)))
    (is (= "false" (signal/clj->js-literal false))))

  (testing "nil → null"
    (is (= "null" (signal/clj->js-literal nil))))

  (testing "keywords → single-quoted name"
    (is (= "'dark'" (signal/clj->js-literal :dark))))

  (testing "maps → JS object literal"
    (is (= "{name: 'John', age: 30}"
           (signal/clj->js-literal {:name "John" :age 30}))))

  (testing "vectors → JS array literal"
    (is (= "[1, 2, 3]" (signal/clj->js-literal [1 2 3])))))

;; ---------------------------------------------------------------------------
;; Signal type — render context
;; ---------------------------------------------------------------------------

(deftest signal-deref-render-context-test
  (testing "deref returns Datastar expression in render context (no *signals* bound)"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_1"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :user-name "default")]
          (is (= "$userName" @sig))))))

  (testing "vector path deref returns dot-notation expression"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_2"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal [:user :name] "")]
          (is (= "$user.name" @sig)))))))

;; ---------------------------------------------------------------------------
;; Signal type — action context
;; ---------------------------------------------------------------------------

(deftest signal-deref-action-context-test
  (testing "deref returns live value from *signals* in action context"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_3"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*signals*          {:user-name "Alice"}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :user-name "default")]
          (is (= "Alice" @sig))))))

  (testing "deref returns default when signal missing from *signals*"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_4"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*signals*          {:other "value"}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :user-name "default")]
          (is (= "default" @sig))))))

  (testing "deref reads nested signal from *signals*"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_5"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*signals*          {:user {:name "Bob"}}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal [:user :name] "")]
          (is (= "Bob" @sig)))))))

;; ---------------------------------------------------------------------------
;; Signal mutation
;; ---------------------------------------------------------------------------

(deftest signal-reset!-test
  (testing "reset! updates tab state"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_6"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :count 0)]
          (is (= 0 (get-in @app-state* [:tabs tab-id :signals :count])))
          (reset! sig 42)
          (is (= 42 (get-in @app-state* [:tabs tab-id :signals :count])))))))

  (testing "reset! to nil is preserved across re-renders (not overwritten by default)"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_6b"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      ;; First render — creates the signal with default "hello"
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :greeting "hello")]
          (is (= "hello" (get-in @app-state* [:tabs tab-id :signals :greeting])))
          ;; Simulate an action that sets the signal to nil
          (reset! sig nil)
          (is (nil? (get-in @app-state* [:tabs tab-id :signals :greeting])))))
      ;; Second render — create-signal should NOT overwrite nil with default
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (h/signal :greeting "hello")
        (is (nil? (get-in @app-state* [:tabs tab-id :signals :greeting]))
            "nil value should be preserved, not reset to default")))))

(deftest signal-swap!-test
  (testing "swap! in action context uses live signal value"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_7"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*signals*          {:count 10}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :count 0)]
          (swap! sig inc)
          (is (= 11 (get-in @app-state* [:tabs tab-id :signals :count])))))))

  (testing "swap! outside action context uses server-side value"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_8"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig (h/signal :count 5)]
          (swap! sig + 10)
          (is (= 15 (get-in @app-state* [:tabs tab-id :signals :count]))))))))

;; ---------------------------------------------------------------------------
;; Local signal
;; ---------------------------------------------------------------------------

(deftest local-signal-deref-test
  (testing "deref in render context returns Datastar expression"
    (binding [context/*declared-signals* (atom [])]
      (let [sig (h/local-signal :open false)]
        (is (= "$_open" @sig)))))

  (testing "deref in action context throws"
    (binding [context/*declared-signals* (atom [])
              context/*signals*          {}]
      (let [sig (h/local-signal :open false)]
        (is (thrown-with-msg? Exception #"Cannot deref local signal"
                              @sig))))))

(deftest local-signal-toString-test
  (testing "toString returns underscore-prefixed JS name"
    (binding [context/*declared-signals* (atom [])]
      (let [sig (h/local-signal :show-menu false)]
        (is (= "_showMenu" (str sig)))))))

;; ---------------------------------------------------------------------------
;; Signal declaration accumulator
;; ---------------------------------------------------------------------------

(deftest declared-signals-accumulation-test
  (testing "signal adds declaration to *declared-signals* during render"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_9"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (h/signal :name "")
        (h/local-signal :open false)
        (let [declared @context/*declared-signals*]
          (is (= 2 (count declared)))
          (is (= {:path :name :html-name "name" :default-val "" :local? false}
                 (first declared)))
          (is (= {:path :open :html-name "_open" :default-val false :local? true}
                 (second declared))))))))

;; ---------------------------------------------------------------------------
;; HTML signal attributes
;; ---------------------------------------------------------------------------

(deftest format-signal-attrs-test
  (testing "produces data-signals:NAME__ifmissing attributes"
    (let [attrs (signal/format-signal-attrs
                  [{:html-name "name" :default-val "" :local? false}
                   {:html-name "_open" :default-val false :local? true}])]
      (is (= "''" (get attrs (keyword "data-signals:name__ifmissing"))))
      (is (= "false" (get attrs (keyword "data-signals:_open__ifmissing"))))))

  (testing "returns nil for empty declarations"
    (is (nil? (signal/format-signal-attrs [])))
    (is (nil? (signal/format-signal-attrs nil)))))

;; ---------------------------------------------------------------------------
;; Chassis protocol — {:data-bind signal*} works directly
;; ---------------------------------------------------------------------------

(deftest chassis-attribute-value-test
  (testing "signal renders as attribute value via Chassis protocol"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_10"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig  (h/signal :user-name "")
              html (c/html [:input {:data-bind sig}])]
          (is (clojure.string/includes? html "data-bind=\"userName\""))))))

  (testing "local signal renders as attribute value via Chassis protocol"
    (binding [context/*declared-signals* (atom [])]
      (let [sig  (h/local-signal :show-menu false)
            html (c/html [:input {:data-bind sig}])]
        (is (clojure.string/includes? html "data-bind=\"_showMenu\"")))))

  (testing "signal deref in data-text attribute renders expression"
    (let [app-state* (atom (state/init-state))
          tab-id     "tab_11"]
      (state/get-or-create-tab! app-state* "ses_1" tab-id)
      (binding [context/*request*          {:hyper/session-id "ses_1"
                                            :hyper/tab-id     tab-id
                                            :hyper/app-state  app-state*}
                context/*declared-signals* (atom [])]
        (let [sig  (h/signal :count 0)
              html (c/html [:span {:data-text @sig}])]
          (is (clojure.string/includes? html "data-text=\"$count\"")))))))

;; ---------------------------------------------------------------------------
;; SSE patch-signals event
;; ---------------------------------------------------------------------------

(deftest format-patch-signals-event-test
  (testing "formats flat signals as JSON patch event"
    (let [event (signal/format-patch-signals-event {:count 42})]
      (is (clojure.string/starts-with? event "event: datastar-patch-signals\n"))
      (is (clojure.string/includes? event "data: signals {\"count\":42}"))))

  (testing "formats string signal values as JSON"
    (let [event (signal/format-patch-signals-event {:name "Alice"})]
      (is (clojure.string/includes? event "data: signals {\"name\":\"Alice\"}"))))

  (testing "formats kebab-case keys as camelCase in JSON output"
    (let [event (signal/format-patch-signals-event {:user-name "Jane"})]
      (is (clojure.string/includes? event "\"userName\":\"Jane\"")))))

;; ---------------------------------------------------------------------------
;; changed-signals
;; ---------------------------------------------------------------------------

(deftest changed-signals-test
  (testing "returns changed signals"
    (is (= {:name "Bob"}
           (signal/changed-signals {:name "Alice" :count 0}
                                   {:name "Bob" :count 0}))))

  (testing "returns new signals"
    (is (= {:email "a@b.com"}
           (signal/changed-signals {:name "Alice"}
                                   {:name "Alice" :email "a@b.com"}))))

  (testing "returns empty map when nothing changed"
    (is (= {}
           (signal/changed-signals {:name "Alice"} {:name "Alice"}))))

  (testing "returns nil for removed signals"
    (is (= {:old-key nil}
           (signal/changed-signals {:name "Alice" :old-key "x"}
                                   {:name "Alice"})))))

;; ---------------------------------------------------------------------------
;; drop-ifmissing-covered-patches (issue #44)
;; ---------------------------------------------------------------------------

(deftest drop-ifmissing-covered-patches-test
  (let [declared [{:path :ids :html-name "ids" :default-val [] :local? false}]]
    (testing "first-load patch equal to declared default is suppressed"
      ;; This is the issue-#44 scenario: a checkbox-array signal declared as
      ;; [] would otherwise emit a redundant {\"ids\":[]} patch that clobbers
      ;; Datastar's DOM-materialized array.  sent-signals is nil on first load.
      (is (= {}
             (signal/drop-ifmissing-covered-patches {:ids []} declared nil))))

    (testing "patch differing from declared default is kept"
      (is (= {:ids ["a"]}
             (signal/drop-ifmissing-covered-patches {:ids ["a"]} declared nil))))

    (testing "patch equal to default but already sent is kept (reset-to-default)"
      ;; The client already has :ids (it was sent before, here as [\"a\"]), so
      ;; __ifmissing won't reset it — the patch back to the default [] must be
      ;; delivered explicitly.
      (is (= {:ids []}
             (signal/drop-ifmissing-covered-patches {:ids []} declared {:ids ["a"]}))))

    (testing "undeclared signals are always kept"
      (is (= {:other 0}
             (signal/drop-ifmissing-covered-patches {:other 0} declared nil))))

    (testing "nested-path patch equal to declared default is suppressed and pruned"
      (let [declared [{:path        [:form :ids] :html-name "form.ids"
                       :default-val []           :local?    false}]]
        (is (= {}
               (signal/drop-ifmissing-covered-patches
                 {:form {:ids []}} declared nil)))))

    (testing "nested-path drop preserves sibling leaves under the same branch"
      (let [declared [{:path        [:form :ids] :html-name "form.ids"
                       :default-val []           :local?    false}
                      {:path        [:form :name] :html-name "form.name"
                       :default-val ""            :local?    false}]]
        ;; :ids matches its default and is dropped; :name differs and stays,
        ;; so the :form branch survives with only :name.
        (is (= {:form {:name "x"}}
               (signal/drop-ifmissing-covered-patches
                 {:form {:ids [] :name "x"}} declared nil)))))

    (testing "nested-path patch already sent is kept"
      (let [declared [{:path        [:form :ids] :html-name "form.ids"
                       :default-val []           :local?    false}]]
        (is (= {:form {:ids []}}
               (signal/drop-ifmissing-covered-patches
                 {:form {:ids []}} declared {:form {:ids ["a"]}})))))

    (testing "mixed patch — suppresses default-matching, keeps the rest"
      (let [declared [{:path :ids :html-name "ids" :default-val [] :local? false}
                      {:path :count :html-name "count" :default-val 0 :local? false}]]
        (is (= {:count 5}
               (signal/drop-ifmissing-covered-patches
                 {:ids [] :count 5} declared nil)))))

    (testing "single-element vector path is treated as top-level"
      (let [declared [{:path [:ids] :html-name "ids" :default-val [] :local? false}]]
        (is (= {}
               (signal/drop-ifmissing-covered-patches {:ids []} declared nil)))))

    (testing "empty/nil patches pass through unchanged"
      (is (= {} (signal/drop-ifmissing-covered-patches {} declared nil)))
      (is (nil? (signal/drop-ifmissing-covered-patches nil declared nil))))))

;; ---------------------------------------------------------------------------
;; Connection status signals (static, client-only)
;; ---------------------------------------------------------------------------

(deftest connection-render-deref-test
  (testing "in render context, connection signals deref to their $-expression"
    (is (= "$_hyperConnected" @signal/connected?*))
    (is (= "$_hyperConnection" @signal/connection*))))

(deftest connection-action-deref-throws-test
  (testing "in action context, connection signals throw (client-only)"
    (binding [context/*signals* {}]
      (is (thrown? clojure.lang.ExceptionInfo @signal/connected?*))
      (is (thrown? clojure.lang.ExceptionInfo @signal/connection*)))))

(deftest connection-states-test
  (testing "the documented token set"
    (is (= #{:connecting :open :reconnecting :error :closed}
           signal/connection-states))))

(deftest connection-attrs-test
  (testing "connection-attrs declares both signals and the lifecycle handler"
    (let [attrs (signal/connection-attrs)]
      (is (= "'connecting'"
             (get attrs (keyword "data-signals:_hyper-connection__ifmissing"))))
      (is (= "true"
             (get attrs (keyword "data-signals:_hyper-connected__ifmissing"))))
      (let [js (get attrs (keyword "data-on:datastar-fetch"))]
        ;; isolates the SSE connection from action POSTs
        (is (clojure.string/includes? js "evt.detail.el === el"))
        ;; healthy stream
        (is (clojure.string/includes?
              js "evt.detail.type === 'started' ? ($_hyperConnection = 'open', $_hyperConnected = true)"))
        ;; transient drop
        (is (clojure.string/includes?
              js "'retrying' ? ($_hyperConnection = 'reconnecting', $_hyperConnected = false)"))
        ;; terminal failure
        (is (clojure.string/includes?
              js "'retries-failed' ? ($_hyperConnection = 'error', $_hyperConnected = false)"))))))
