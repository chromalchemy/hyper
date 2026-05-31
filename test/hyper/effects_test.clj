(ns hyper.effects-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.core :as h]
            [hyper.effects :as effects]
            [hyper.state :as state]
            [hyper.test :as ht]))

;; ---------------------------------------------------------------------------
;; Unit tests — effects namespace internals
;; ---------------------------------------------------------------------------

(deftest test-init-pending
  (testing "init-pending returns an atom with empty cookies, scripts, and session-ops"
    (let [p (effects/init-pending)]
      (is (instance? clojure.lang.Atom p))
      (is (= {} (:cookies @p)))
      (is (= [] (:scripts @p)))
      (is (= [] (:session-ops @p))))))

(deftest test-emit-outside-context-throws
  (testing "set-cookie! throws outside action context"
    (is (thrown-with-msg? Exception #"outside action context"
                          (effects/set-cookie! "foo" "bar"))))

  (testing "delete-cookie! throws outside action context"
    (is (thrown-with-msg? Exception #"outside action context"
                          (effects/delete-cookie! "foo"))))

  (testing "execute-script! throws outside action context"
    (is (thrown-with-msg? Exception #"outside action context"
                          (effects/execute-script! "alert(1)"))))

  (testing "navigate! throws outside action context"
    (is (thrown-with-msg? Exception #"outside action context"
                          (effects/navigate! :home))))

  (testing "assoc-session! throws outside action context"
    (is (thrown-with-msg? Exception #"outside action context"
                          (effects/assoc-session! :uid "x"))))

  (testing "dissoc-session! throws outside action context"
    (is (thrown-with-msg? Exception #"outside action context"
                          (effects/dissoc-session! :uid))))

  (testing "update-session! throws outside action context"
    (is (thrown-with-msg? Exception #"outside action context"
                          (effects/update-session! assoc :uid "x")))))

(deftest test-set-cookie-accumulation
  (testing "set-cookie! accumulates cookies in *pending*"
    (binding [effects/*pending* (effects/init-pending)]
      (effects/set-cookie! "session" "abc123" {:http-only true :max-age 3600})
      (effects/set-cookie! "theme" "dark")
      (let [pending (effects/collect-pending!)]
        (is (= {"session" {:value "abc123" :path "/" :http-only true :max-age 3600}
                "theme"   {:value "dark" :path "/"}}
               (:cookies pending)))))))

(deftest test-delete-cookie-accumulation
  (testing "delete-cookie! sets value to empty string and max-age 0"
    (binding [effects/*pending* (effects/init-pending)]
      (effects/delete-cookie! "session")
      (let [pending (effects/collect-pending!)]
        (is (= {"session" {:value "" :max-age 0 :path "/"}}
               (:cookies pending))))))

  (testing "delete-cookie! with opts merges them"
    (binding [effects/*pending* (effects/init-pending)]
      (effects/delete-cookie! "session" {:path "/app"})
      (let [pending (effects/collect-pending!)]
        (is (= {"session" {:value "" :max-age 0 :path "/app"}}
               (:cookies pending)))))))

(deftest test-execute-script-accumulation
  (testing "execute-script! accumulates scripts in *pending*"
    (binding [effects/*pending* (effects/init-pending)]
      (effects/execute-script! "alert('hello')")
      (effects/execute-script! "console.log('test')")
      (let [pending (effects/collect-pending!)]
        (is (= ["alert('hello')" "console.log('test')"]
               (:scripts pending)))))))

(deftest test-apply-cookies-to-response
  (testing "merges cookies into response"
    (let [response {:status 204}
          pending  {:cookies {"auth" {:value "token123" :http-only true}}
                    :scripts []}]
      (is (= {:status  204
              :cookies {"auth" {:value "token123" :http-only true}}}
             (effects/apply-cookies-to-response response pending)))))

  (testing "no-op when no cookies"
    (let [response {:status 204}
          pending  {:cookies {} :scripts []}]
      (is (= {:status 204}
             (effects/apply-cookies-to-response response pending))))))

(deftest test-assoc-session-accumulation
  (testing "assoc-session! accumulates ops in :session-ops"
    (binding [effects/*pending* (effects/init-pending)]
      (effects/assoc-session! :uid "ryan@example.com")
      (effects/assoc-session! :auth/state "hex123")
      (let [pending (effects/collect-pending!)]
        (is (= [{:op :assoc :k :uid :v "ryan@example.com"}
                {:op :assoc :k :auth/state :v "hex123"}]
               (:session-ops pending)))))))

(deftest test-dissoc-session-accumulation
  (testing "dissoc-session! accumulates ops in :session-ops"
    (binding [effects/*pending* (effects/init-pending)]
      (effects/dissoc-session! :uid)
      (let [pending (effects/collect-pending!)]
        (is (= [{:op :dissoc :k :uid}]
               (:session-ops pending)))))))

(deftest test-update-session-accumulation
  (testing "update-session! captures fn and args"
    (binding [effects/*pending* (effects/init-pending)]
      (effects/update-session! assoc :uid "ryan@example.com" :role :admin)
      (let [pending (effects/collect-pending!)
            op      (first (:session-ops pending))]
        (is (= :update (:op op)))
        (is (= assoc (:f op)))
        (is (= [:uid "ryan@example.com" :role :admin] (:args op)))))))

(deftest test-session-ops-mixed-order
  (testing "ops accumulate in emission order; later writes win on the same key"
    (binding [effects/*pending* (effects/init-pending)]
      (effects/assoc-session! :uid "first@x.com")
      (effects/assoc-session! :uid "second@x.com")
      (effects/dissoc-session! :other)
      (let [pending (effects/collect-pending!)]
        (is (= [{:op :assoc :k :uid :v "first@x.com"}
                {:op :assoc :k :uid :v "second@x.com"}
                {:op :dissoc :k :other}]
               (:session-ops pending)))))))

(deftest test-apply-session-to-response
  (testing "applies assoc op to base session"
    (let [response {:status 204}
          base     {:existing :keep}
          pending  {:session-ops [{:op :assoc :k :uid :v "ryan@example.com"}]}]
      (is (= {:status  204
              :session {:existing :keep :uid "ryan@example.com"}}
             (effects/apply-session-to-response response base pending)))))

  (testing "applies dissoc op"
    (let [response {:status 204}
          base     {:uid "x" :other :y}
          pending  {:session-ops [{:op :dissoc :k :uid}]}]
      (is (= {:status  204
              :session {:other :y}}
             (effects/apply-session-to-response response base pending)))))

  (testing "applies update op with args"
    (let [response {:status 204}
          base     {:existing :keep}
          pending  {:session-ops [{:op     :update
                                   :f      merge
                                   :args   [{:uid "x" :role :admin}]}]}]
      (is (= {:status  204
              :session {:existing :keep :uid "x" :role :admin}}
             (effects/apply-session-to-response response base pending)))))

  (testing "no-op when no session ops — does NOT add :session to response"
    (let [response {:status 204}
          pending  {:session-ops []}]
      (is (= {:status 204}
             (effects/apply-session-to-response response {:x 1} pending)))
      (is (not (contains? (effects/apply-session-to-response response nil pending)
                          :session)))))

  (testing "handles nil base-session (treats as empty map)"
    (let [response {:status 204}
          pending  {:session-ops [{:op :assoc :k :uid :v "x"}]}]
      (is (= {:status 204 :session {:uid "x"}}
             (effects/apply-session-to-response response nil pending)))))

  (testing "later writes win on same key (left-to-right reduction)"
    (let [response {:status 204}
          base     {}
          pending  {:session-ops [{:op :assoc :k :uid :v "first"}
                                  {:op :assoc :k :uid :v "second"}]}]
      (is (= {:status 204 :session {:uid "second"}}
             (effects/apply-session-to-response response base pending))))))

(deftest test-format-execute-script-event
  (testing "formats as Datastar patch-elements SSE event with self-removing script"
    (let [event (effects/format-execute-script-event "alert('hi')")]
      (is (str/includes? event "event: datastar-patch-elements"))
      (is (str/includes? event "data: mode append"))
      (is (str/includes? event "data: selector body"))
      (is (str/includes? event "data: elements <script"))
      (is (str/includes? event "alert('hi')")))))

(deftest test-format-pending-scripts
  (testing "formats all scripts as SSE events"
    (let [pending {:cookies {} :scripts ["alert(1)" "alert(2)"]}
          result  (effects/format-pending-scripts pending)]
      (is (string? result))
      (is (str/includes? result "alert(1)"))
      (is (str/includes? result "alert(2)"))))

  (testing "returns nil when no scripts"
    (is (nil? (effects/format-pending-scripts {:cookies {} :scripts []})))))

;; ---------------------------------------------------------------------------
;; Integration tests — effects via test-page + test-action
;; ---------------------------------------------------------------------------

(deftest test-action-returns-effects
  (testing "test-action result includes :effects key"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click (h/action {:as "noop"})}
                      "Nothing"]))
          after  (ht/test-action result "noop")]
      (is (contains? after :effects))
      (is (= {} (:cookies (:effects after))))
      (is (= [] (:scripts (:effects after))))
      (is (= [] (:session-ops (:effects after)))))))

(deftest test-set-cookie-from-action
  (testing "set-cookie! in action appears in test-action :effects"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "login"}
                                         (effects/set-cookie! "auth" "jwt-token-123"
                                                              {:http-only true
                                                               :max-age   86400}))}
                      "Login"]))
          after  (ht/test-action result "login")]
      (is (= {"auth" {:value     "jwt-token-123"
                      :path      "/"
                      :http-only true
                      :max-age   86400}}
             (get-in after [:effects :cookies]))))))

(deftest test-delete-cookie-from-action
  (testing "delete-cookie! in action appears in test-action :effects"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "logout"}
                                         (effects/delete-cookie! "auth"))}
                      "Logout"]))
          after  (ht/test-action result "logout")]
      (is (= {"auth" {:value "" :max-age 0 :path "/"}}
             (get-in after [:effects :cookies]))))))

(deftest test-execute-script-from-action
  (testing "execute-script! in action appears in test-action :effects"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "focus"}
                                         (effects/execute-script! "document.getElementById('q').focus()"))}
                      "Focus"]))
          after  (ht/test-action result "focus")]
      (is (= ["document.getElementById('q').focus()"]
             (get-in after [:effects :scripts]))))))

(deftest test-navigate-from-action
  (testing "navigate! in action updates route state and queues pushState script"
    (let [home-page (fn [_req]
                      [:div
                       [:button {:data-on:click
                                 (h/action {:as "go-about"}
                                           (effects/navigate! :about))}
                        "Go About"]])
          routes    [["/" {:name  :home
                           :title "Home"
                           :get   home-page}]
                     ["/about" {:name  :about
                                :title "About"
                                :get   (fn [_req] [:div "About page"])}]]
          ;; create-handler sets up the router in app-state
          app-state (atom (state/init-state))
          _handler  (h/create-handler routes :app-state app-state)
          ;; Render the home page — pass app-state so the router is available
          result    (ht/test-page home-page {:app-state app-state})
          after     (ht/test-action result "go-about")]
      ;; Route state should be updated server-side
      (is (= :about (get-in after [:cursors :route :name])))
      (is (= "/about" (get-in after [:cursors :route :path])))
      ;; Should have a pushState script queued
      (is (seq (get-in after [:effects :scripts])))
      (is (some #(str/includes? % "pushState") (get-in after [:effects :scripts]))))))

(deftest test-multiple-effects-in-one-action
  (testing "multiple effects accumulate together"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "multi"}
                                         (effects/set-cookie! "token" "abc" {:http-only true})
                                         (effects/execute-script! "showNotification()")
                                         (effects/execute-script! "scrollToTop()"))}
                      "Multi"]))
          after  (ht/test-action result "multi")]
      (is (= {"token" {:value "abc" :path "/" :http-only true}}
             (get-in after [:effects :cookies])))
      (is (= ["showNotification()" "scrollToTop()"]
             (get-in after [:effects :scripts]))))))

(deftest test-effects-with-cursor-mutations
  (testing "effects and cursor mutations coexist"
    (let [result (ht/test-page
                   (fn [_req]
                     (let [_status* (h/tab-cursor :status "idle")]
                       [:button {:data-on:click
                                 (h/action {:as "save"}
                                           (reset! (h/tab-cursor :status) "saved")
                                           (effects/set-cookie! "last-save" "2024-01-01")
                                           (effects/execute-script! "flashSuccess()"))}
                        "Save"])))
          after  (ht/test-action result "save")]
      ;; Cursor mutation works
      (is (= "saved" (get-in after [:cursors :tab :status])))
      ;; Effects also captured
      (is (= {"last-save" {:value "2024-01-01" :path "/"}}
             (get-in after [:effects :cookies])))
      (is (= ["flashSuccess()"]
             (get-in after [:effects :scripts]))))))

(deftest test-effects-isolated-between-actions
  (testing "effects from one test-action don't leak to the next"
    (let [result (ht/test-page
                   (fn [_req]
                     [:div
                      [:button {:data-on:click
                                (h/action {:as "with-effect"}
                                          (effects/execute-script! "doSomething()"))}
                       "A"]
                      [:button {:data-on:click
                                (h/action {:as "no-effect"})}
                       "B"]]))
          after1 (ht/test-action result "with-effect")
          after2 (ht/test-action result "no-effect")]
      (is (= ["doSomething()"] (get-in after1 [:effects :scripts])))
      (is (= [] (get-in after2 [:effects :scripts]))))))

(deftest test-assoc-session-from-action
  (testing "assoc-session! in action appears in test-action :effects :session-ops"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "login"}
                                         (effects/assoc-session! :uid "ryan@example.com")
                                         (effects/assoc-session! :auth/state "hex123"))}
                      "Login"]))
          after  (ht/test-action result "login")]
      (is (= [{:op :assoc :k :uid :v "ryan@example.com"}
              {:op :assoc :k :auth/state :v "hex123"}]
             (get-in after [:effects :session-ops]))))))

(deftest test-dissoc-session-from-action
  (testing "dissoc-session! in action appears in test-action :effects :session-ops"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "logout"}
                                         (effects/dissoc-session! :uid))}
                      "Logout"]))
          after  (ht/test-action result "logout")]
      (is (= [{:op :dissoc :k :uid}]
             (get-in after [:effects :session-ops]))))))

(deftest test-update-session-from-action
  (testing "update-session! in action appears in test-action :effects :session-ops"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "bulk-login"}
                                         (effects/update-session! merge
                                                                  {:uid "x@y.com"
                                                                   :role :admin}))}
                      "Bulk Login"]))
          after  (ht/test-action result "bulk-login")
          op     (first (get-in after [:effects :session-ops]))]
      (is (= :update (:op op)))
      (is (= merge (:f op)))
      (is (= [{:uid "x@y.com" :role :admin}] (:args op))))))

(deftest test-session-and-cookie-coexist-in-one-action
  (testing "session writes and cookies accumulate independently in one action"
    (let [result (ht/test-page
                   (fn [_req]
                     [:button {:data-on:click
                               (h/action {:as "full-login"}
                                         (effects/assoc-session! :uid "ryan@example.com")
                                         (effects/set-cookie! "theme" "dark"))}
                      "Full Login"]))
          after  (ht/test-action result "full-login")]
      (is (= [{:op :assoc :k :uid :v "ryan@example.com"}]
             (get-in after [:effects :session-ops])))
      (is (= {"theme" {:value "dark" :path "/"}}
             (get-in after [:effects :cookies]))))))
