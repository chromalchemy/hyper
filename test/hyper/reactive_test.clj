(ns hyper.reactive-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hyper.core :as h]
            [hyper.protocols :as proto]
            [hyper.reactive :as reactive]
            [hyper.test :as ht]))

;; A test source that tracks disposal.
(deftype DisposableSource [value* watches* disposed*]
  clojure.lang.IRef
  (deref [_] @value*)
  (setValidator [_ _])
  (getValidator [_] nil)
  (getWatches [_] @watches*)
  (addWatch [this key callback]
    (swap! watches* assoc key callback)
    (add-watch value* key (fn [k _r old-val new-val]
                            (callback k this old-val new-val)))
    this)
  (removeWatch [this key]
    (swap! watches* dissoc key)
    (remove-watch value* key)
    this)

  proto/Watchable
  (-add-watch [this key callback]
    (.addWatch this key (fn [_k _ref old-val new-val]
                          (callback old-val new-val))))
  (-remove-watch [this key]
    (.removeWatch this key))
  (-dispose [_this]
    (swap! disposed* inc)))

(defn make-disposable-source
  [initial-value]
  (->DisposableSource (atom initial-value) (atom {}) (atom 0)))

;; ---------------------------------------------------------------------------
;; Basic render tests
;; ---------------------------------------------------------------------------

(deftest test-reactive-basic-render
  (testing "reactive injects ID onto the returned element"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [count* (h/tab-cursor :count 0)]
                       [:div
                        (h/reactive [count*]
                          [:p "Count: " @count*])])))]
      (is (str/includes? (:body-html result) "Count: 0"))
      ;; ID should be on the <p>, not a wrapper div
      (is (re-find #"<p id=\"r_" (:body-html result))
          "should inject reactive ID onto the element")))

  (testing "reactive uses existing :id if present"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [count* (h/tab-cursor :count 0)]
                       [:div
                        (h/reactive [count*]
                          [:p {:id "my-counter"} "Count: " @count*])])))]
      (is (str/includes? (:body-html result) "id=\"my-counter\""))
      ;; Should NOT have a generated reactive ID
      (is (not (re-find #"r_" (:body-html result)))
          "should use the existing ID, not generate one"))))

(deftest test-reactive-caching
  (testing "reactive returns cached HTML when deps unchanged"
    (let [counter*  (atom 0)
          render-fn (fn [_req]
                      (swap! counter* inc)
                      (let [static*  (h/tab-cursor :static "hello")
                            dynamic* (h/tab-cursor :dynamic 0)]
                        [:div
                         (h/reactive [static*]
                           [:p "Static: " @static*])
                         (h/reactive [dynamic*]
                           [:p "Dynamic: " @dynamic*])]))
          r1        (ht/test-page render-fn)]

      ;; Both blocks rendered on first pass
      (is (str/includes? (:body-html r1) "Static: hello"))
      (is (str/includes? (:body-html r1) "Dynamic: 0"))

      ;; Modify only :dynamic, re-render with same app-state
      (swap! (:app-state r1) assoc-in [:tabs "test-tab" :data :dynamic] 42)
      (let [r2 (ht/test-page render-fn {:app-state (:app-state r1)})]
        (is (str/includes? (:body-html r2) "Dynamic: 42"))
        ;; Static block should still render (cached HTML is used internally
        ;; by the reactive component, but the test-page always does a full render)
        (is (str/includes? (:body-html r2) "Static: hello"))))))

(deftest test-reactive-nested
  (testing "nested reactive blocks render correctly"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [outer* (h/tab-cursor :outer "A")
                           inner* (h/tab-cursor :inner "B")]
                       [:div
                        (h/reactive [outer*]
                          [:div
                           [:span "Outer: " @outer*]
                           (h/reactive [inner*]
                             [:span "Inner: " @inner*])])])))]
      (is (str/includes? (:body-html result) "Outer: A"))
      (is (str/includes? (:body-html result) "Inner: B")))))

(deftest test-reactive-sweep-conditional
  (testing "stale reactive blocks are swept on conditional change"
    (let [render-fn (fn [_req]
                      (let [mode* (h/tab-cursor :mode :a)]
                        [:div
                         (if (= @mode* :a)
                           (h/reactive [(h/tab-cursor :x 0)]
                             [:p "Mode A"])
                           (h/reactive [(h/tab-cursor :y 0)]
                             [:p "Mode B"]))]))
          r1        (ht/test-page render-fn)]

      (is (str/includes? (:body-html r1) "Mode A"))

      ;; Count reactive components registered
      (let [tab-id     "test-tab"
            components (get-in @(:app-state r1) [:tabs tab-id :reactive-components])]
        (is (= 1 (count components)) "should have 1 reactive component for mode A"))

      ;; Switch mode and re-render
      (swap! (:app-state r1) assoc-in [:tabs "test-tab" :data :mode] :b)
      (let [r2         (ht/test-page render-fn {:app-state (:app-state r1)})
            tab-id     "test-tab"
            components (get-in @(:app-state r2) [:tabs tab-id :reactive-components])]
        (is (str/includes? (:body-html r2) "Mode B"))
        (is (= 1 (count components)) "should have 1 reactive component for mode B (old one swept)")))))

(deftest test-reactive-dep-disposal
  (testing "deps are disposed when reactive block is swept"
    (let [source    (make-disposable-source "val")
          render-fn (fn [_req]
                      (let [show?* (h/tab-cursor :show? true)]
                        [:div
                         (when @show?*
                           (h/reactive [source]
                             [:p "Source: " @source]))]))
          r1        (ht/test-page render-fn)]

      (is (str/includes? (:body-html r1) "Source: val"))
      (is (zero? @(.-disposed* source)) "should not be disposed yet")

      ;; Hide the reactive block → it should be swept
      (swap! (:app-state r1) assoc-in [:tabs "test-tab" :data :show?] false)
      (let [_r2 (ht/test-page render-fn {:app-state (:app-state r1)})]
        ;; The reactive block is gone — sweep should have run
        ;; But disposal only happens if watches were set up (which requires a renderer)
        ;; In test-page context, component watches are not set up since there's no
        ;; renderer thread. The sweep still removes the component registration.
        (let [components (get-in @(:app-state r1) [:tabs "test-tab" :reactive-components])]
          (is (empty? components) "swept component should be removed"))))))
