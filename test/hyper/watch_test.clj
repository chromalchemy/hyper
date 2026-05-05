(ns hyper.watch-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.protocols :as proto]
            [hyper.render :as render]
            [hyper.server :as server]
            [hyper.state :as state]
            [hyper.watch :as watch]))

;; A test source that tracks watches and disposal for assertions.
(deftype DisposableSource [watches* disposed*]
  proto/Watchable
  (-add-watch [_this key callback]
    (swap! watches* assoc key callback))
  (-remove-watch [_this key]
    (swap! watches* dissoc key))
  (-dispose [_this]
    (swap! disposed* inc)))

(deftest test-watchers
  (testing "Watchers trigger callback on state change"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-4"
          tab-id          "test_tab_4"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          render-fn       (fn [_req]
                            [:div "Count: " (get-in @app-state* [:tabs tab-id :data :count])])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      (watch/setup-watchers! app-state* session-id tab-id trigger-render!)

      ;; Change tab state
      (swap! app-state* assoc-in [:tabs tab-id :data :count] 1)
      (Thread/sleep 50)

      (is (>= @trigger-count 1))

      ;; Change session state
      (swap! app-state* assoc-in [:sessions session-id :data :user] "Alice")
      (Thread/sleep 50)

      (is (>= @trigger-count 2))

      ;; Change global state
      (swap! app-state* assoc-in [:global :theme] "dark")
      (Thread/sleep 50)

      (is (>= @trigger-count 3))

      ;; Clean up watchers
      (watch/remove-watchers! app-state* tab-id))))

(deftest test-cleanup
  (testing "Cleanup removes all tab resources"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-5"
          tab-id          "test_tab_5"
          stopped?        (atom false)
          trigger-render! (fn [])
          render-fn       (fn [_req] [:div "test"])]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (render/register-render-fn! app-state* tab-id render-fn)

      ;; Store a mock renderer handle with a stop! fn
      (swap! app-state* assoc-in [:tabs tab-id :renderer]
             {:trigger-render! trigger-render!
              :stop!           #(reset! stopped? true)})

      (watch/setup-watchers! app-state* session-id tab-id trigger-render!)

      (is (some? (render/get-render-fn app-state* tab-id)))
      (is (contains? (:tabs @app-state*) tab-id))

      ;; Cleanup
      (server/cleanup-tab! app-state* tab-id)

      (is (not (contains? (:tabs @app-state*) tab-id)))
      (is @stopped? "Renderer stop! should have been called"))))

(deftest test-external-watch
  (testing "watch-source! triggers callback when source changes"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-ext"
          tab-id          "test_tab_ext"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          external-atom   (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      (watch/watch-source! app-state* tab-id trigger-render! external-atom)

      ;; Mutate the external atom
      (swap! external-atom inc)
      (Thread/sleep 50)

      (is (= 1 @trigger-count) "trigger-render! should have been called")

      ;; Clean up
      (watch/remove-external-watches! app-state* tab-id))))

(deftest test-pending-watches-stash
  (testing "watch! with no trigger-render! stashes source under :pending-watches"
    (let [app-state*    (atom (state/init-state))
          session-id    "test-session-pending"
          tab-id        "test_tab_pending"
          external-atom (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; No renderer set up — trigger-render! will be nil
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      ;; Should be stashed under :pending-watches
      (let [pending (get-in @app-state* [:tabs tab-id :pending-watches])]
        (is (some? pending) "pending-watches should exist")
        (is (= 1 (count pending)) "should have exactly one pending watch")
        (is (= external-atom (first (vals pending)))
            "pending watch value should be the external atom"))

      ;; Should NOT have a real watch on the atom yet
      (is (empty? (.getWatches external-atom))
          "external atom should have no real watches yet"))))

(deftest test-pending-watches-stash-idempotent
  (testing "watch! stashing the same source twice is idempotent"
    (let [app-state*    (atom (state/init-state))
          session-id    "test-session-idem"
          tab-id        "test_tab_idem"
          external-atom (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom)
        (h/watch! external-atom))

      (let [pending (get-in @app-state* [:tabs tab-id :pending-watches])]
        (is (= 1 (count pending))
            "duplicate watch! calls should not create multiple entries")))))

(deftest test-promote-pending-watches
  (testing "promote-pending-watches! creates real watches and clears pending"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-promote"
          tab-id          "test_tab_promote"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          external-atom   (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Stash via watch! with no renderer
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      ;; Verify stashed
      (is (= 1 (count (get-in @app-state* [:tabs tab-id :pending-watches]))))

      ;; Promote
      (watch/promote-pending-watches! app-state* tab-id trigger-render!)

      ;; :pending-watches should be cleared
      (is (nil? (get-in @app-state* [:tabs tab-id :pending-watches]))
          "pending-watches should be cleared after promotion")

      ;; Real watch should exist on the atom
      (is (= 1 (count (.getWatches external-atom)))
          "external atom should have a real watch after promotion")

      ;; Watch should be tracked under :watches in tab state
      (is (= 1 (count (get-in @app-state* [:tabs tab-id :watches])))
          "promoted watch should be tracked under :watches")

      ;; Mutating the atom should trigger render
      (swap! external-atom inc)
      (Thread/sleep 50)
      (is (= 1 @trigger-count) "trigger-render! should fire when source changes")

      ;; Clean up
      (watch/remove-external-watches! app-state* tab-id))))

(deftest test-watch-with-trigger-render-still-works
  (testing "watch! with trigger-render! present still creates real watches directly"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-direct"
          tab-id          "test_tab_direct"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          external-atom   (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Set up a renderer so trigger-render! is available
      (swap! app-state* assoc-in [:tabs tab-id :renderer]
             {:trigger-render! trigger-render!})

      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      ;; Should NOT use pending-watches path
      (is (nil? (get-in @app-state* [:tabs tab-id :pending-watches]))
          "should not stash when trigger-render! is present")

      ;; Real watch should exist directly
      (is (= 1 (count (.getWatches external-atom)))
          "real watch should be on the atom")

      ;; Mutating the atom should trigger render
      (swap! external-atom inc)
      (Thread/sleep 50)
      (is (= 1 @trigger-count))

      ;; Calling watch! again with the same source is idempotent
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      (is (= 1 (count (.getWatches external-atom)))
          "duplicate watch! should not create additional watches")

      ;; Clean up
      (watch/remove-external-watches! app-state* tab-id))))

(deftest test-navigation-clears-user-watches
  (testing "user h/watch! watches are removed when navigating to a new route"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-nav"
          tab-id          "test_tab_nav"
          trigger-count   (atom 0)
          trigger-render! #(swap! trigger-count inc)
          external-atom   (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Set initial route
      (state/set-tab-route! app-state* tab-id
                            {:name :page-a :path "/a" :path-params {} :query-params {}})

      ;; Set up watchers (this is the app-state watcher that detects route changes)
      (watch/setup-watchers! app-state* session-id tab-id trigger-render!)

      ;; Register an external watch (simulating h/watch! on page A)
      (watch/watch-source! app-state* tab-id trigger-render! external-atom)

      ;; Verify external watch is active
      (is (= 1 (count (get-in @app-state* [:tabs tab-id :watches])))
          "should have one user watch")
      (is (= 1 (count (.getWatches external-atom)))
          "external atom should have a watch")

      ;; Mutating the external atom should trigger render
      (reset! trigger-count 0)
      (swap! external-atom inc)
      (Thread/sleep 50)
      (is (>= @trigger-count 1) "watch should trigger before navigation")

      ;; Navigate to a different route
      (reset! trigger-count 0)
      (state/set-tab-route! app-state* tab-id
                            {:name :page-b :path "/b" :path-params {} :query-params {}})
      (Thread/sleep 50)

      ;; User watches should be cleaned up
      (is (empty? (get-in @app-state* [:tabs tab-id :watches]))
          "user watches should be removed after navigation")
      (is (empty? (.getWatches external-atom))
          "external atom watch should be removed after navigation")

      ;; Mutating the external atom should NOT trigger render anymore
      (reset! trigger-count 0)
      (swap! external-atom inc)
      (Thread/sleep 50)
      (is (zero? @trigger-count)
          "watch should not trigger after navigating away")

      ;; Clean up
      (watch/remove-watchers! app-state* tab-id))))

(deftest test-cleanup-clears-pending-watches
  (testing "cleanup-tab! clears pending-watches along with the whole tab"
    (let [app-state*    (atom (state/init-state))
          session-id    "test-session-cleanup-pw"
          tab-id        "test_tab_cleanup_pw"
          external-atom (atom 0)]

      (state/get-or-create-tab! app-state* session-id tab-id)

      ;; Stash a pending watch
      (binding [context/*request* {:hyper/session-id session-id
                                   :hyper/tab-id     tab-id
                                   :hyper/app-state  app-state*}]
        (h/watch! external-atom))

      (is (some? (get-in @app-state* [:tabs tab-id :pending-watches])))

      ;; Cleanup the tab
      (server/cleanup-tab! app-state* tab-id)

      ;; Tab should be completely gone
      (is (not (contains? (:tabs @app-state*) tab-id))))))

;; ---------------------------------------------------------------------------
;; Disposal tests
;; ---------------------------------------------------------------------------

(deftest test-dispose-called-on-watch-removal
  (testing "dispose is called when removing watches for a tab"
    (let [app-state*      (atom (state/init-state))
          tab-id          "test_tab_dispose"
          trigger-render! (fn [])
          disposed*       (atom 0)
          source          (->DisposableSource (atom {}) disposed*)]

      (state/get-or-create-tab! app-state* "sess" tab-id)

      (watch/watch-source! app-state* tab-id trigger-render! source)
      (is (zero? @disposed*) "should not be disposed yet")

      (watch/remove-external-watches! app-state* tab-id)
      (is (= 1 @disposed*) "should be disposed after watch removal"))))

(deftest test-dispose-called-on-navigation
  (testing "dispose is called when navigating to a new route"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-dispose-nav"
          tab-id          "test_tab_dispose_nav"
          trigger-render! (fn [])
          disposed*       (atom 0)
          source          (->DisposableSource (atom {}) disposed*)]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (state/set-tab-route! app-state* tab-id
                            {:name :page-a :path "/a" :path-params {} :query-params {}})
      (watch/setup-watchers! app-state* session-id tab-id trigger-render!)

      (watch/watch-source! app-state* tab-id trigger-render! source)
      (is (zero? @disposed*))

      ;; Navigate away
      (state/set-tab-route! app-state* tab-id
                            {:name :page-b :path "/b" :path-params {} :query-params {}})
      (Thread/sleep 50)

      (is (= 1 @disposed*) "should be disposed after navigation")

      (watch/remove-watchers! app-state* tab-id))))

(deftest test-dispose-called-on-tab-disconnect
  (testing "dispose is called when tab disconnects"
    (let [app-state*      (atom (state/init-state))
          session-id      "test-session-dispose-dc"
          tab-id          "test_tab_dispose_dc"
          trigger-render! (fn [])
          disposed*       (atom 0)
          source          (->DisposableSource (atom {}) disposed*)]

      (state/get-or-create-tab! app-state* session-id tab-id)
      (watch/watch-source! app-state* tab-id trigger-render! source)
      (is (zero? @disposed*))

      (server/cleanup-tab! app-state* tab-id)
      (is (= 1 @disposed*) "should be disposed on tab disconnect"))))

(deftest test-dispose-refcounted-across-tabs
  (testing "shared source is only disposed when the last tab releases it"
    (let [app-state*       (atom (state/init-state))
          session-id       "test-session-refcount"
          tab-id-a         "test_tab_rc_a"
          tab-id-b         "test_tab_rc_b"
          trigger-render!  (fn [])
          disposed*        (atom 0)
          shared-source    (->DisposableSource (atom {}) disposed*)]

      (state/get-or-create-tab! app-state* session-id tab-id-a)
      (state/get-or-create-tab! app-state* session-id tab-id-b)

      ;; Both tabs watch the same source
      (watch/watch-source! app-state* tab-id-a trigger-render! shared-source)
      (watch/watch-source! app-state* tab-id-b trigger-render! shared-source)
      (is (zero? @disposed*))

      ;; First tab removes watches — source should NOT be disposed
      (watch/remove-external-watches! app-state* tab-id-a)
      (is (zero? @disposed*)
          "should not dispose while second tab still watches")

      ;; Second tab removes watches — source should now be disposed
      (watch/remove-external-watches! app-state* tab-id-b)
      (is (= 1 @disposed*)
          "should dispose after last tab releases"))))

(deftest test-dispose-not-called-for-plain-atoms
  (testing "plain atoms (IRef) are not affected by dispose (no-op)"
    (let [app-state*      (atom (state/init-state))
          tab-id          "test_tab_atom_dispose"
          trigger-render! (fn [])
          external-atom   (atom 0)]

      (state/get-or-create-tab! app-state* "sess" tab-id)
      (watch/watch-source! app-state* tab-id trigger-render! external-atom)

      ;; Should not throw — IRef -dispose is a no-op
      (watch/remove-external-watches! app-state* tab-id)

      ;; Atom is still usable
      (is (= 0 @external-atom)))))
