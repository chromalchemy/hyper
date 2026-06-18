(ns hyper.context-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]))

(deftest require-context-test
  (testing "throws when called outside request context"
    (is (thrown-with-msg? Exception #"global-cursor called outside request context"
                          (context/require-context! "global-cursor"))))

  (testing "throws when app-state is missing"
    (binding [context/*request* {:hyper/session-id "s1"
                                 :hyper/tab-id     "t1"}]
      (is (thrown-with-msg? Exception #"No app-state"
                            (context/require-context! "test")))))

  (testing "returns context map with all keys"
    (let [app-state* (atom {})]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*
                                   :hyper/router     :mock-router}]
        (let [ctx (context/require-context! "test")]
          (is (= "s1" (:session-id ctx)))
          (is (= "t1" (:tab-id ctx)))
          (is (= app-state* (:app-state* ctx)))
          (is (= :mock-router (:router ctx)))))))

  (testing "router is nil when not in request"
    (let [app-state* (atom {})]
      (binding [context/*request* {:hyper/session-id "s1"
                                   :hyper/tab-id     "t1"
                                   :hyper/app-state  app-state*}]
        (is (nil? (:router (context/require-context! "test"))))))))

(deftest current-overlay-test
  (testing "returns nil when no overlay is bound"
    (is (nil? (context/current-overlay))))

  (testing "returns the overlay on the thread that owns it"
    (let [overlay {:state* (atom {})
                   :ops*   (atom [])
                   :owner  (Thread/currentThread)}]
      (binding [context/*state-overlay* overlay]
        (is (identical? overlay (context/current-overlay))))))

  (testing "returns nil on a thread that merely inherited the binding"
    (let [overlay {:state* (atom {})
                   :ops*   (atom [])
                   :owner  (Thread/currentThread)}]
      (binding [context/*state-overlay* overlay]
        ;; future conveys dynamic bindings, but the overlay is not ours
        (is (nil? (deref (future (context/current-overlay)) 2000 ::timeout))))))

  (testing "render-bindings stamps the creating thread as owner"
    (let [app-state* (atom {})
          bindings   (context/render-bindings {} app-state*)
          overlay    (get bindings #'context/*state-overlay*)]
      (is (identical? (Thread/currentThread) (:owner overlay))))))

(deftest flush-overlay-ownership-test
  (testing "flush replays the op-log on the owner thread only"
    (let [app-state* (atom {:x 0})
          overlay    {:state* (atom {:x 1})
                      :ops*   (atom [{:kind :reset :path [:x] :value 1}])
                      :owner  (Thread/currentThread)}]
      (binding [context/*state-overlay* overlay]
        ;; Non-owner thread flush is a no-op
        (deref (future (context/flush-overlay! app-state*)) 2000 ::timeout)
        (is (= 0 (:x @app-state*)) "foreign-thread flush should be a no-op")
        ;; Owner flush replays the recorded op
        (context/flush-overlay! app-state*)
        (is (= 1 (:x @app-state*)))))))

(deftest apply-ops-test
  (testing ":update composes its fn with the current value at the path"
    (is (= {:m {:rows [:a :b] :status :ok}}
           (context/apply-ops {:m {:rows [:a :b]}}
                              [{:kind :update :path [:m] :f #(assoc % :status :ok)}]))))

  (testing ":reset overwrites the exact path (overwrite-this-value semantics)"
    (is (= {:m {:status :new}}
           (context/apply-ops {:m {:rows [:a]}}
                              [{:kind :reset :path [:m] :value {:status :new}}]))))

  (testing ":cas writes only when the live value still equals the expected old"
    (is (= {:x 2} (context/apply-ops {:x 1} [{:kind :cas :path [:x] :old 1 :new 2}]))
        "matching old → applies")
    (is (= {:x 9} (context/apply-ops {:x 9} [{:kind :cas :path [:x] :old 1 :new 2}]))
        "diverged old → no-op (yields to the concurrent value)"))

  (testing "ops are replayed in recorded order"
    (is (= {:x 3}
           (context/apply-ops {:x 0}
                              [{:kind :reset :path [:x] :value 1}
                               {:kind :update :path [:x] :f inc}
                               {:kind :update :path [:x] :f inc}])))))
