(ns hyper.reconnect-test
  "Tier 0 graceful reconnect: a dropped SSE connection should *detach* a tab
   (stop its renderer/channel) while preserving its state, subviews, watchers,
   and background workers for a grace window.  A reconnect within the window
   re-attaches a fresh renderer; only after the window expires is the tab fully
   torn down."
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.render.queue :as rq]
            [hyper.server :as server]
            [hyper.state :as state]
            [hyper.subview :as subview]
            [hyper.watch :as watch]))

(defn- ctx
  [app-state* session-id tab-id]
  {:hyper/session-id session-id
   :hyper/tab-id     tab-id
   :hyper/app-state  app-state*
   :hyper/router     nil})

(defmacro ^:private rendering
  [app-state* session-id tab-id & body]
  `(binding [context/*request*    (ctx ~app-state* ~session-id ~tab-id)
             context/*action-idx* (atom 0)]
     ~@body))

;; ---------------------------------------------------------------------------
;; Detach preserves state, subviews, and workers
;; ---------------------------------------------------------------------------

(deftest test-detach-preserves-tab-state
  (testing "detach-tab! keeps tab-cursor data, stamps :disconnected-at, drops renderer"
    (let [app-state* (atom (state/init-state))
          session-id "s-detach"
          tab-id     "t_detach"]
      (state/get-or-create-tab! app-state* session-id tab-id)
      ;; A mock renderer so detach has a :stop! to call.
      (let [stopped? (atom false)]
        (swap! app-state* assoc-in [:tabs tab-id :renderer]
               {:render-queue (rq/make-queue)
                :stop!        #(reset! stopped? true)})
        ;; Seed some tab-cursor state.
        (rendering app-state* session-id tab-id
                   (reset! (h/tab-cursor :count 0) 42))
        (is (= 42 (get-in @app-state* [:tabs tab-id :data :count])))

        (server/detach-tab! app-state* tab-id)

        (is (true? @stopped?) "renderer stop! was called on detach")
        (is (contains? (:tabs @app-state*) tab-id) "tab survives detach")
        (is (= 42 (get-in @app-state* [:tabs tab-id :data :count]))
            "tab-cursor state survives detach")
        (is (number? (get-in @app-state* [:tabs tab-id :disconnected-at]))
            ":disconnected-at is stamped")
        (is (nil? (get-in @app-state* [:tabs tab-id :renderer]))
            "renderer is removed on detach")))))

(deftest test-detach-does-not-interrupt-worker
  (testing "a spawn! worker survives a detach (no interrupt within grace)"
    (let [app-state*  (atom (state/init-state))
          session-id  "s-detach-worker"
          tab-id      "t_detach_worker"
          started     (promise)
          interrupted (promise)]
      (state/get-or-create-tab! app-state* session-id tab-id)
      (swap! app-state* assoc-in [:tabs tab-id :renderer]
             {:render-queue (rq/make-queue) :stop! (fn [])})
      (rendering app-state* session-id tab-id
                 (h/spawn! (fn []
                             (deliver started true)
                             (try (Thread/sleep 60000)
                                  (catch InterruptedException _
                                    (deliver interrupted true))))))
      (is (true? (deref started 1000 :timeout)) "worker started")

      (server/detach-tab! app-state* tab-id)

      (is (= :still-running (deref interrupted 300 :still-running))
          "worker is NOT interrupted by a detach")
      (is (= 1 (count (get-in @app-state* [:tabs tab-id :subviews])))
          "worker subview survives detach")
      ;; cleanup
      (subview/teardown-all! app-state* tab-id))))

;; ---------------------------------------------------------------------------
;; Stable triggers: existing watches drive a re-attached renderer
;; ---------------------------------------------------------------------------

(deftest test-watchers-follow-reattached-renderer
  (testing "after detach + re-attach, an existing app-state watcher drives the
            NEW renderer's queue without being re-installed"
    (let [app-state*      (atom (state/init-state))
          session-id      "s-reattach"
          tab-id          "t_reattach"
          trigger-render! (#'server/tab-trigger-render! app-state* tab-id)
          q1              (rq/make-queue)]
      (state/get-or-create-tab! app-state* session-id tab-id)
      ;; Connection 1: install the watcher with the STABLE trigger and a queue.
      (swap! app-state* assoc-in [:tabs tab-id :renderer] {:render-queue q1})
      (watch/setup-watchers! app-state* session-id tab-id trigger-render!)

      ;; A cursor mutation enqueues a full render onto queue 1.
      (rendering app-state* session-id tab-id
                 (reset! (h/tab-cursor :n 0) 1))
      (is (= {:full-render? true :shutdown? false :dirty-ids #{} :scripts []}
             (rq/drain! q1))
          "connection-1 queue received the render")

      ;; Detach (drops renderer/queue), then re-attach a NEW renderer/queue —
      ;; WITHOUT re-installing the watcher.
      (server/detach-tab! app-state* tab-id)
      (let [q2 (rq/make-queue)]
        (swap! app-state* assoc-in [:tabs tab-id :renderer] {:render-queue q2})
        (swap! app-state* update-in [:tabs tab-id] dissoc :disconnected-at)

        (rendering app-state* session-id tab-id
                   (reset! (h/tab-cursor :n 1) 2))
        (is (= {:full-render? true :shutdown? false :dirty-ids #{} :scripts []}
               (rq/drain! q2))
            "connection-2 queue received the render via the same watcher"))
      (watch/remove-watchers! app-state* tab-id))))

;; ---------------------------------------------------------------------------
;; Reaper: tear down only after the grace window elapses
;; ---------------------------------------------------------------------------

(deftest test-reaper-respects-grace-window
  (testing "reap-disconnected-tabs! leaves tabs within grace and reaps expired ones"
    (let [app-state*  (atom (assoc (state/init-state) :disconnect-grace-ms 180000))
          session-id  "s-reap"
          fresh       "t_fresh"
          stale       "t_stale"
          interrupted (promise)]
      ;; Fresh tab: just disconnected.
      (state/get-or-create-tab! app-state* session-id fresh)
      (swap! app-state* assoc-in [:tabs fresh :disconnected-at] (System/currentTimeMillis))

      ;; Stale tab: disconnected well beyond the grace window, with a worker.
      (state/get-or-create-tab! app-state* session-id stale)
      (swap! app-state* assoc-in [:tabs stale :renderer]
             {:render-queue (rq/make-queue) :stop! (fn [])})
      (rendering app-state* session-id stale
                 (h/spawn! (fn []
                             (try (Thread/sleep 60000)
                                  (catch InterruptedException _
                                    (deliver interrupted true))))))
      (Thread/sleep 50)
      (swap! app-state* assoc-in [:tabs stale :disconnected-at]
             (- (System/currentTimeMillis) 200000))

      (server/reap-disconnected-tabs! app-state* (System/currentTimeMillis))

      (is (contains? (:tabs @app-state*) fresh)
          "tab within grace window is preserved")
      (is (not (contains? (:tabs @app-state*) stale))
          "expired tab is fully reaped")
      (is (true? (deref interrupted 1000 :timeout))
          "reaping an expired tab interrupts its worker"))))

;; ---------------------------------------------------------------------------
;; create-handler option
;; ---------------------------------------------------------------------------

(deftest test-disconnect-grace-ms-option
  (testing "defaults to 3 minutes"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home :get (fn [_req] [:div "Home"])}]]
          _handler   (server/create-handler routes app-state*)]
      (is (= 180000 (:disconnect-grace-ms @app-state*)))))

  (testing "honors an explicit :disconnect-grace-ms"
    (let [app-state* (atom (state/init-state))
          routes     [["/" {:name :home :get (fn [_req] [:div "Home"])}]]
          _handler   (server/create-handler routes app-state*
                                            {:disconnect-grace-ms 5000})]
      (is (= 5000 (:disconnect-grace-ms @app-state*))))))
