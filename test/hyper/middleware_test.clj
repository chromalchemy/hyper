(ns hyper.middleware-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.context :as context]
            [hyper.core :as h]
            [hyper.effects :as effects]
            [hyper.render :as render]
            [hyper.state :as state]
            [hyper.test :as ht]))

;; ---------------------------------------------------------------------------
;; render/apply-render-middleware
;; ---------------------------------------------------------------------------

(deftest test-apply-render-middleware-empty
  (testing "nil middleware returns the original fn"
    (let [handler identity]
      (is (identical? handler (render/apply-render-middleware handler nil)))))

  (testing "empty seq returns the original fn"
    (let [handler identity]
      (is (identical? handler (render/apply-render-middleware handler []))))))

(deftest test-apply-render-middleware-wraps
  (testing "single middleware wraps the handler"
    (let [handler (fn [_req] [:div "Hello"])
          mw      (fn [h] (fn [req] [:div.wrapper (h req)]))
          wrapped (render/apply-render-middleware handler [mw])]
      (is (= [:div.wrapper [:div "Hello"]] (wrapped {})))))

  (testing "multiple middleware compose in correct order"
    ;; [mw-outer mw-inner] => mw-outer wraps outermost, mw-inner wraps innermost
    (let [handler  (fn [_req] "core")
          mw-outer (fn [h] (fn [req] (str "outer(" (h req) ")")))
          mw-inner (fn [h] (fn [req] (str "inner(" (h req) ")")))
          wrapped  (render/apply-render-middleware handler [mw-outer mw-inner])]
      (is (= "outer(inner(core))" (wrapped {}))))))

(deftest test-apply-render-middleware-short-circuit
  (testing "middleware can short-circuit with Ring response"
    (let [handler  (fn [_req] [:div "Should not reach"])
          guard-mw (fn [_h] (fn [_req] {:status 302 :headers {"Location" "/login"} :body ""}))
          wrapped  (render/apply-render-middleware handler [guard-mw])]
      (is (= {:status 302 :headers {"Location" "/login"} :body ""}
             (wrapped {}))))))

;; ---------------------------------------------------------------------------
;; Render middleware via test-page
;; ---------------------------------------------------------------------------

(deftest test-render-middleware-seeds-cursor
  (testing "render middleware can seed cursor state before handler runs"
    (let [mw     (fn [handler]
                   (fn [req]
                     (reset! (h/session-cursor :user) "alice")
                     (handler req)))
          result (ht/test-page
                   (fn [_req]
                     (let [user* (h/session-cursor :user)]
                       [:div "User: " @user*]))
                   {:render-middleware [mw]})]
      (is (str/includes? (:body-html result) "User: alice"))
      (is (= "alice" (get-in result [:cursors :session :user]))))))

(deftest test-render-middleware-ring-response
  (testing "render middleware returning a Ring response short-circuits rendering"
    (let [guard-mw (fn [_handler]
                     (fn [_req]
                       {:status 302 :headers {"Location" "/login"} :body ""}))
          result   (ht/test-page
                     (fn [_req] [:div "Protected page"])
                     {:render-middleware [guard-mw]})]
      ;; test-page passes through Ring responses as-is
      (is (= 302 (:status result)))
      (is (= "/login" (get-in result [:headers "Location"]))))))

(deftest test-render-middleware-conditional
  (testing "middleware can conditionally pass through or redirect"
    (let [auth-mw (fn [handler]
                    (fn [req]
                      (if (= "admin" (get-in @(h/session-cursor :role) []))
                        (handler req)
                        {:status 403 :body "Forbidden"})))

          ;; Without role — blocked
          r1      (ht/test-page
                    (fn [_req] [:div "Admin Panel"])
                    {:render-middleware [auth-mw]})

          ;; With role — passes through
          r2      (ht/test-page
                    (fn [_req] [:div "Admin Panel"])
                    {:render-middleware [auth-mw]
                     :cursors           {:session {:role "admin"}}})]

      (is (= 403 (:status r1)))
      (is (str/includes? (:body-html r2) "Admin Panel")))))

(deftest test-render-middleware-ordering
  (testing "middleware execute in order — first in vector runs outermost"
    (let [log     (atom [])
          mw-a    (fn [handler]
                    (fn [req]
                      (swap! log conj :a-before)
                      (let [result (handler req)]
                        (swap! log conj :a-after)
                        result)))
          mw-b    (fn [handler]
                    (fn [req]
                      (swap! log conj :b-before)
                      (let [result (handler req)]
                        (swap! log conj :b-after)
                        result)))
          _result (ht/test-page
                    (fn [_req] [:div "test"])
                    {:render-middleware [mw-a mw-b]})]

      ;; A wraps outermost, so A-before runs first, then B-before, then B-after, then A-after
      (is (= [:a-before :b-before :b-after :a-after] @log)))))

(deftest test-render-middleware-has-request-context
  (testing "middleware can read *request* and cursors"
    (let [captured (atom nil)
          mw       (fn [handler]
                     (fn [req]
                       (reset! captured {:session-id (:hyper/session-id req)
                                         :route-name (get-in req [:hyper/route :name])})
                       (handler req)))
          _result  (ht/test-page
                     (fn [_req] [:div "test"])
                     {:render-middleware [mw]
                      :route             {:name        :dashboard :path         "/dashboard"
                                          :path-params {}         :query-params {}}})]

      (is (= "test-session" (:session-id @captured)))
      (is (= :dashboard (:route-name @captured))))))

(deftest test-render-middleware-with-actions
  (testing "middleware works alongside actions — actions still register"
    (let [mw     (fn [handler]
                   (fn [req]
                     (reset! (h/tab-cursor :mw-ran) true)
                     (handler req)))
          result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click (h/action {:as "click"}
                                                        (swap! (h/tab-cursor :count 0) inc))}
                      "Click"])
                   {:render-middleware [mw]})]

      ;; Middleware ran
      (is (true? (get-in result [:cursors :tab :mw-ran])))
      ;; Action still registered
      (is (contains? (:actions result) "click"))
      ;; Action still works
      (let [after (ht/test-action result "click")]
        (is (= 1 (get-in after [:cursors :tab :count])))))))

;; ---------------------------------------------------------------------------
;; Render middleware via create-handler (handler-level)
;; ---------------------------------------------------------------------------

(deftest test-handler-level-render-middleware-stored
  (testing "create-handler stores :render-middleware in app-state"
    (let [mw        [(fn [h] h)]
          app-state (atom (state/init-state))
          _handler  (h/create-handler
                      [["/" {:name :home :get (fn [_] [:div])}]]
                      :app-state app-state
                      :render-middleware mw)]
      (is (= mw (:render-middleware @app-state))))))

;; ---------------------------------------------------------------------------
;; Render middleware — per-route via route data
;; ---------------------------------------------------------------------------

(deftest test-per-route-render-middleware
  (testing "per-route :render-middleware is applied during render-tab"
    (let [log        (atom [])
          route-mw   (fn [handler]
                       (fn [req]
                         (swap! log conj :route-mw)
                         (handler req)))
          app-state  (atom (state/init-state))
          _handler   (h/create-handler
                       [["/" {:name              :home
                              :title             "Home"
                              :get               (fn [_] [:div "Home"])
                              :render-middleware [route-mw]}]]
                       :app-state app-state)

          ;; Set up a tab so we can call render-tab directly
          session-id "test-session"
          tab-id     "test-tab"]
      (state/get-or-create-tab! app-state session-id tab-id)
      (state/set-tab-route! app-state tab-id
                            {:name :home :path "/" :path-params {} :query-params {}})
      (render/register-render-fn! app-state tab-id (fn [_] [:div "Home"]))

      (let [result (render/render-tab app-state session-id tab-id)]
        (is (some? (:body-html result)))
        (is (= [:route-mw] @log))))))

(deftest test-handler-and-route-middleware-merge
  (testing "handler-level and route-level middleware compose correctly"
    (let [log        (atom [])
          handler-mw (fn [handler]
                       (fn [req]
                         (swap! log conj :handler)
                         (handler req)))
          route-mw   (fn [handler]
                       (fn [req]
                         (swap! log conj :route)
                         (handler req)))
          app-state  (atom (state/init-state))
          _handler   (h/create-handler
                       [["/" {:name              :home
                              :title             "Home"
                              :get               (fn [_] [:div "Home"])
                              :render-middleware [route-mw]}]]
                       :app-state app-state
                       :render-middleware [handler-mw])

          session-id "test-session"
          tab-id     "test-tab"]
      (state/get-or-create-tab! app-state session-id tab-id)
      (state/set-tab-route! app-state tab-id
                            {:name :home :path "/" :path-params {} :query-params {}})
      (render/register-render-fn! app-state tab-id (fn [_] [:div "Home"]))

      (render/render-tab app-state session-id tab-id)
      ;; Handler-level runs outermost (first), route-level runs innermost (second)
      (is (= [:handler :route] @log)))))

;; ---------------------------------------------------------------------------
;; *action-name* — bound during action execution
;; ---------------------------------------------------------------------------

(deftest test-action-name-bound-for-named-actions
  (testing "*action-name* is the :as value during action execution"
    (let [captured (atom nil)
          result   (ht/test-page
                     (fn [_req]
                       [:button {:data-on:click
                                 (h/action {:as "save-form"}
                                           (reset! captured context/*action-name*))}
                        "Save"]))]
      (ht/test-action result "save-form")
      (is (= "save-form" @captured)))))

(deftest test-action-name-nil-for-unnamed-actions
  (testing "*action-name* is nil for actions without :as"
    (let [captured (atom :not-set)
          result   (ht/test-page
                     (fn [_req]
                       [:button {:data-on:click
                                 (h/action
                                   (reset! captured context/*action-name*))}
                        "Click"]))]
      (ht/test-action result (first (keys (:actions result))))
      (is (nil? @captured)))))

(deftest test-action-name-accessible-in-utility-functions
  (testing "called functions can read *action-name* from the dynamic var"
    (let [captured (atom nil)]
      (letfn [(audit! []
                (reset! captured (str "audit:" context/*action-name*)))]
        (let [result (ht/test-page
                       (fn [_req]
                         [:button {:data-on:click
                                   (h/action {:as "delete-user"}
                                             (audit!))}
                          "Delete"]))]
          (ht/test-action result "delete-user")
          (is (= "audit:delete-user" @captured)))))))

(deftest test-action-name-nil-outside-action
  (testing "*action-name* is nil outside action context"
    (is (nil? context/*action-name*))))

(deftest test-action-name-isolated-between-actions
  (testing "*action-name* doesn't leak between different actions"
    (let [captured (atom [])
          result   (ht/test-page
                     (fn [_req]
                       [:div
                        [:button {:data-on:click
                                  (h/action {:as "first"}
                                            (swap! captured conj context/*action-name*))}
                         "A"]
                        [:button {:data-on:click
                                  (h/action {:as "second"}
                                            (swap! captured conj context/*action-name*))}
                         "B"]]))]
      (ht/test-action result "first")
      (ht/test-action result "second")
      (is (= ["first" "second"] @captured)))))

;; ---------------------------------------------------------------------------
;; Full workflow: render middleware + action + re-render
;; ---------------------------------------------------------------------------

(deftest test-middleware-full-workflow
  (testing "render → middleware seeds state → action mutates → re-render sees both"
    (let [auth-mw (fn [handler]
                    (fn [req]
                       ;; Middleware seeds user from "cookie"
                      (when-not @(h/session-cursor :user)
                        (reset! (h/session-cursor :user) "cookie-user"))
                      (handler req)))

          page-fn (fn [_req]
                    (let [user*  (h/session-cursor :user)
                          count* (h/tab-cursor :count 0)]
                      [:div
                       [:p "User: " @user*]
                       [:p "Count: " @count*]
                       [:button {:data-on:click (h/action {:as "inc"}
                                                          (swap! (h/tab-cursor :count) inc))}
                        "+1"]]))

          ;; First render — middleware seeds the user
          r1      (ht/test-page page-fn {:render-middleware [auth-mw]})]

      (is (str/includes? (:body-html r1) "User: cookie-user"))
      (is (str/includes? (:body-html r1) "Count: 0"))

      ;; Increment
      (ht/test-action r1 "inc")

      ;; Re-render — middleware runs again, user still there, count incremented
      (let [r2 (ht/test-page page-fn {:app-state         (:app-state r1)
                                      :render-middleware [auth-mw]})]
        (is (str/includes? (:body-html r2) "User: cookie-user"))
        (is (str/includes? (:body-html r2) "Count: 1"))))))

(deftest test-middleware-with-effects
  (testing "actions with effects work when render middleware is present"
    (let [mw     (fn [handler]
                   (fn [req]
                     (reset! (h/tab-cursor :mw-flag) true)
                     (handler req)))
          result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "set-cookie"}
                                         (effects/set-cookie! "token" "abc")
                                         (effects/execute-script! "alert('hi')"))}
                      "Save"])
                   {:render-middleware [mw]})
          after  (ht/test-action result "set-cookie")]

      ;; Middleware effect persisted
      (is (true? (get-in result [:cursors :tab :mw-flag])))
      ;; Action effects captured
      (is (= {"token" {:value "abc" :path "/"}}
             (get-in after [:effects :cookies])))
      (is (= ["alert('hi')"]
             (get-in after [:effects :scripts]))))))
