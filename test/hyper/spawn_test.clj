(ns hyper.spawn-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.render :as render]
            [hyper.server :as server]
            [hyper.state :as state]
            [hyper.subview :as subview]))

(defn- ctx
  "A minimal *request* map for `tab-id` in `app-state*`."
  [app-state* session-id tab-id]
  {:hyper/session-id session-id
   :hyper/tab-id     tab-id
   :hyper/app-state  app-state*
   :hyper/router     nil})

(defmacro ^:private rendering
  "Evaluate body as if inside a render of `tab-id` (so *action-idx* keys
   spawn! by call order, matching production)."
  [app-state* session-id tab-id & body]
  `(binding [context/*request*    (ctx ~app-state* ~session-id ~tab-id)
             context/*action-idx* (atom 0)]
     ~@body))

(deftest test-worker-runs-off-render-guard
  (testing "a worker writes state even when spawned under an ACTIVE :error
            guard — the virtual thread does not inherit the render guard"
    (let [app-state* (atom (state/init-state))
          session-id "s-spawn-guard"
          tab-id     "t_spawn_guard"
          wrote      (promise)]
      (state/get-or-create-tab! app-state* session-id tab-id)
      ;; Bind an active :error guard on the spawning thread. We call
      ;; spawn-worker! directly (rather than h/spawn!, whose own guard-effect!
      ;; would throw under :error) to isolate the worker-thread invariant.
      (binding [context/*render-guard* (doto (context/make-guard :error)
                                         (swap! assoc :mode :active))]
        (subview/spawn-worker!
          app-state* tab-id "s_guard" session-id
          (fn []
           ;; tab-cursor resolves via the rebound *request*; the write would
           ;; throw if the guard were active on this thread.
            (reset! (h/tab-cursor :v 0) 42)
            (deliver wrote true))))
      (is (true? (deref wrote 1000 :timeout)) "worker ran to completion")
      (is (= 42 (get-in @app-state* [:tabs tab-id :data :v]))
          "cursor write from the worker landed (worker ran guard-free)")
      (subview/teardown-all! app-state* tab-id))))

(deftest test-spawn-registers-mount-scoped-worker
  (testing "h/spawn! registers a mount-scoped subview holding the worker thread"
    (let [app-state* (atom (state/init-state))
          session-id "s-spawn-reg"
          tab-id     "t_spawn_reg"
          started    (promise)]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (rendering app-state* session-id tab-id
                 (h/spawn! (fn []
                             (deliver started true)
                             (try (Thread/sleep 60000)
                                  (catch InterruptedException _ nil)))))
      (is (true? (deref started 1000 :timeout)) "worker started")
      (let [subs (get-in @app-state* [:tabs tab-id :subviews])]
        (is (= 1 (count subs)) "exactly one worker subview")
        (let [sv (first (vals subs))]
          (is (= :mount (:scope sv)) "worker is mount-scoped")
          (is (instance? Thread (:resource sv)) "the thread is stored as :resource")
          (is (ifn? (:unmount sv)) "an :unmount is installed")))
      (subview/teardown-mount-scoped! app-state* tab-id))))

(deftest test-unmount-interrupts-worker
  (testing "tearing down mount-scoped subviews interrupts the worker thread"
    (let [app-state*  (atom (state/init-state))
          session-id  "s-spawn-int"
          tab-id      "t_spawn_int"
          started     (promise)
          interrupted (promise)]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (rendering app-state* session-id tab-id
                 (h/spawn! (fn []
                             (deliver started true)
                             (try (Thread/sleep 60000)
                                  (catch InterruptedException _
                                    (deliver interrupted true))))))
      (is (true? (deref started 1000 :timeout)) "worker started")
      (subview/teardown-mount-scoped! app-state* tab-id)
      (is (true? (deref interrupted 1000 :timeout))
          "worker interrupted on mount-scoped teardown")
      (is (empty? (get-in @app-state* [:tabs tab-id :subviews]))
          "worker subview removed"))))

(deftest test-spawn-idempotent-across-renders
  (testing "a form-1 body re-invoking spawn! spawns exactly one worker"
    (let [app-state* (atom (state/init-state))
          session-id "s-spawn-idem"
          tab-id     "t_spawn_idem"
          spawns     (atom 0)
          render!    (fn []
                       (rendering app-state* session-id tab-id
                                  (h/spawn!
                                    (fn []
                                      (swap! spawns inc)
                                      (try (Thread/sleep 60000)
                                           (catch InterruptedException _ nil))))))]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (render!) (render!) (render!)
      (Thread/sleep 50)
      (is (= 1 (count (get-in @app-state* [:tabs tab-id :subviews])))
          "one worker subview across three renders")
      (is (= 1 @spawns) "worker fn invoked exactly once")
      (subview/teardown-mount-scoped! app-state* tab-id))))

(deftest test-navigation-interrupts-worker
  (testing "navigating away (page-view remount) interrupts the worker"
    (let [interrupted (promise)
          page-a      (fn [_req]
                        (h/spawn! (fn []
                                    (try (Thread/sleep 60000)
                                         (catch InterruptedException _
                                           (deliver interrupted true)))))
                        [:div "A"])
          page-b      (fn [_req] [:div "B"])
          rts         [["/a" {:name :a :get page-a}]
                       ["/b" {:name :b :get page-b}]]
          app-state*  (atom (assoc (state/init-state)
                                   :routes rts :global-watches []))]
      (state/get-or-create-tab! app-state* "s" "t")
      (state/set-tab-route! app-state* "t"
                            {:name :a :path "/a" :path-params {} :query-params {}})
      (render/register-render-fn! app-state* "t" page-a)
      (render/render-tab app-state* "s" "t")
      (is (= 1 (count (get-in @app-state* [:tabs "t" :subviews])))
          "worker registered when /a renders")

      ;; Navigate to /b — different handler identity => page-view remount.
      (state/set-tab-route! app-state* "t"
                            {:name :b :path "/b" :path-params {} :query-params {}})
      (render/register-render-fn! app-state* "t" page-b)
      (render/render-tab app-state* "s" "t")
      (is (true? (deref interrupted 1000 :timeout))
          "worker interrupted on navigation")
      (is (empty? (get-in @app-state* [:tabs "t" :subviews]))
          "worker subview torn down on navigation"))))

(deftest test-disconnect-interrupts-worker
  (testing "tab disconnect (cleanup-tab!) interrupts the worker"
    (let [app-state*  (atom (state/init-state))
          session-id  "s-spawn-dc"
          tab-id      "t_spawn_dc"
          started     (promise)
          interrupted (promise)]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (rendering app-state* session-id tab-id
                 (h/spawn! (fn []
                             (deliver started true)
                             (try (Thread/sleep 60000)
                                  (catch InterruptedException _
                                    (deliver interrupted true))))))
      (is (true? (deref started 1000 :timeout)) "worker started")
      (server/cleanup-tab! app-state* tab-id)
      (is (true? (deref interrupted 1000 :timeout))
          "worker interrupted on disconnect")
      (is (not (contains? (:tabs @app-state*) tab-id)) "tab removed"))))
