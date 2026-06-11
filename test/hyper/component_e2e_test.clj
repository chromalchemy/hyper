(ns ^:e2e hyper.component-e2e-test
  "End-to-end browser tests for client-side web components (hyper.component).

   Verifies the full pipeline: squint compilation on the JVM, bundle serving,
   head injection, shadow-DOM rendering, the attribute change gate, and the
   event boundary (CustomEvent -> data-on -> h/action via $detail).

   Run with: clojure -M:test --focus :e2e"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hyper.component :as hc]
            [hyper.core :as h]
            [hyper.e2e-test :as e2e]
            [hyper.state :as state]
            [wally.main :as w]))

;; ---------------------------------------------------------------------------
;; Test component + routes
;; ---------------------------------------------------------------------------

(hc/defc e2e-gauge
  [{:keys [value max label]}]
  (event ::selected [_e]
    (emit "gauge-selected" {:label label :value value}))
  (render
    [:div#gauge-root {:on {:click ::selected}}
     [:span#gauge-label label]
     [:span#gauge-value (str value)]
     [:span#gauge-pct (str (js/Math.round (* 100 (/ value max))))]
     [:span#render-stamp (str (js/Date.now))]]))

;; Seamless-mode component: counts its own lifecycle calls so tests can
;; assert mount-once / update-per-change / nothing-on-unrelated-renders.
;; ctx persists across remounts, so mount counts survive hot swaps.
(hc/defc e2e-lifecycle
  [{:keys [data]}]
  (render
    [:div#lc-root
     [:span#lc-mounts "0"]
     [:span#lc-updates "0"]
     [:span#lc-data ""]
     ;; SVG scaffold — regression coverage for namespace-correct creation
     ;; (createElement('svg') yields a dead HTMLUnknownElement).
     [:svg#lc-svg {:width 10 :height 10}
      [:rect {:width 5 :height 5}]]])
  (mount [root]
    (set! (.-mounts ctx) (inc (or (.-mounts ctx) 0)))
    (set! (.-updates ctx) 0)
    (set! (.-textContent (.querySelector root "#lc-mounts")) (str (.-mounts ctx)))
    (set! (.-textContent (.querySelector root "#lc-data")) (str data)))
  (update [root _old]
    (set! (.-updates ctx) (inc (.-updates ctx)))
    (set! (.-textContent (.querySelector root "#lc-updates")) (str (.-updates ctx)))
    (set! (.-textContent (.querySelector root "#lc-data")) (str data)))
  (unmount [_root]
    (set! js/window.__lcUnmounts (inc (or js/window.__lcUnmounts 0)))))

;; Signal-linked component: reads :linked like any attribute; writes it back
;; by emitting an event named after the attribute. The whole loop is
;; client-side — Datastar assigns the signal, data-attr rewrites the
;; attribute, and the change gate re-renders.
(hc/defc sig-reflector
  [{:keys [linked]}]
  (event ::bump [_e]
    (emit "linked" (str linked "+")))
  (render
    [:div#reflect-root
     [:span#reflect-val (str linked)]
     [:button#reflect-btn {:on {:click ::bump}}]]))

(defn- components-get [_]
  (let [temp*     (h/tab-cursor :temp 10)
        selected* (h/tab-cursor :selected nil)
        noise*    (h/tab-cursor :noise 0)
        lc-data*  (h/tab-cursor :lc-data 5)
        hov*      (h/signal :hov "init")
        saved*    (h/tab-cursor :saved-hov "")]
    [:div
     (e2e-gauge {:value @temp*
                 :max   40
                 :label "Gauge"
                 :data-on:gauge-selected
                 (h/action {:as "gauge-selected"}
                           (reset! (h/tab-cursor :selected) $detail))})
     (e2e-lifecycle {:data @lc-data*})
     [:button#lc-inc {:data-on:click (h/action (swap! (h/tab-cursor :lc-data) + 3))} "+3"]
     ;; Signal bind: component + data-text span + server action all share hov*
     (sig-reflector {:linked hov*})
     [:span#hov-text {:data-text @hov*}]
     [:button#save-hov {:data-on:click (h/action {:as "save-hov"}
                                                 (reset! (h/tab-cursor :saved-hov) @hov*))}
      "save"]
     [:span#saved-hov (str @saved*)]
     [:button#hotter {:data-on:click (h/action (swap! (h/tab-cursor :temp) + 5))} "+5"]
     [:button#noise {:data-on:click (h/action (swap! (h/tab-cursor :noise) inc))} "noise"]
     [:span#noise-count (str @noise*)]
     [:span#selected-value (str (get @selected* :value "none"))]
     [:span#selected-label (str (get @selected* :label "none"))]]))

(def ^:private routes
  [["/components" {:name :components
                   :title "Components"
                   :get  #'components-get}]])

;; ---------------------------------------------------------------------------
;; Server lifecycle (own port — independent of hyper.e2e-test's server)
;; ---------------------------------------------------------------------------

(def ^:private test-port 13021)
(def ^:private base-url (str "http://localhost:" test-port))
(def ^:private test-server (atom nil))

(use-fixtures :once
  (fn [f]
    (let [app-state (atom (state/init-state))
          handler   (h/create-handler #'routes :app-state app-state)]
      (reset! test-server (h/start! handler {:port test-port}))
      (try
        (f)
        (finally
          (h/stop! @test-server)
          (reset! test-server nil))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest ^:e2e component-pipeline-test
  (let [browser-info (e2e/launch-browser)
        ctx          (e2e/new-context browser-info)
        page         (e2e/new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url "/components"))
        (e2e/wait-for-sse)

        (testing "component renders client-side into shadow DOM from server attributes"
          ;; Playwright selectors pierce open shadow roots.
          (e2e/wait-for-text "#gauge-value" "10")
          (e2e/wait-for-text "#gauge-label" "Gauge")
          (e2e/wait-for-text "#gauge-pct" "25"))

        (testing "server cursor change -> attribute morph -> component re-render"
          (w/click "#hotter")
          (e2e/wait-for-text "#gauge-value" "15")
          (e2e/wait-for-text "#gauge-pct" "38"))

        (testing "change gate: unrelated server re-render does not re-render the component"
          (let [stamp-before (w/text-content "#render-stamp")]
            (w/click "#noise")
            (e2e/wait-for-text "#noise-count" "1")
            ;; The page re-rendered (noise-count moved), but the gauge's
            ;; attributes were unchanged — its render timestamp must be frozen.
            (is (= stamp-before (w/text-content "#render-stamp")))))

        (testing "event boundary: emit -> CustomEvent -> data-on action -> $detail"
          (w/click "#gauge-root")
          (e2e/wait-for-text "#selected-value" "15")
          (e2e/wait-for-text "#selected-label" "Gauge")))
      (finally
        (e2e/close-browser! browser-info)))))

(deftest ^:e2e component-lifecycle-test
  (let [browser-info (e2e/launch-browser)
        ctx          (e2e/new-context browser-info)
        page         (e2e/new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url "/components"))
        (e2e/wait-for-sse)

        (testing "mount runs once with initial data; update not called"
          (is (e2e/wait-for-text "#lc-mounts" "1"))
          (is (e2e/wait-for-text "#lc-data" "5"))
          (is (= "0" (w/text-content "#lc-updates"))))

        (testing "svg scaffold elements are namespace-correct"
          (is (true? (e2e/eval-js
                       "document.querySelector('e2e-lifecycle').shadowRoot
                          .querySelector('#lc-svg') instanceof SVGSVGElement")))
          (is (true? (e2e/eval-js
                       "document.querySelector('e2e-lifecycle').shadowRoot
                          .querySelector('#lc-svg rect') instanceof SVGRectElement"))))

        (testing "server data change calls update — not mount, no re-render"
          (w/click "#lc-inc")
          (is (e2e/wait-for-text "#lc-updates" "1"))
          (is (e2e/wait-for-text "#lc-data" "8"))
          (is (= "1" (w/text-content "#lc-mounts"))))

        (testing "unrelated server re-render (full page morph) touches nothing"
          (w/click "#noise")
          (is (e2e/wait-for-text "#noise-count" "1"))
          (is (= "1" (w/text-content "#lc-updates")))
          (is (= "1" (w/text-content "#lc-mounts"))))

        (testing "hot swap unmounts and remounts; instance ctx survives"
          (is (zero? (e2e/eval-js "window.__lcUnmounts || 0")))
          ;; Touch the component's JS to rotate the bundle hash and trigger
          ;; the hot-swap path over SSE.
          (swap! hc/registry* update-in ["e2e-lifecycle" :js] str "\n// v2")
          (is (e2e/wait-for-text "#lc-mounts" "2"))
          (is (= 1 (e2e/eval-js "window.__lcUnmounts || 0")))
          ;; Scaffold re-rendered fresh on remount
          (is (= "0" (w/text-content "#lc-updates")))))
      (finally
        (e2e/close-browser! browser-info)))))

(deftest ^:e2e component-signal-bind-test
  (let [browser-info (e2e/launch-browser)
        ctx          (e2e/new-context browser-info)
        page         (e2e/new-page ctx)]
    (try
      (w/with-page page
        (w/navigate (str base-url "/components"))
        (e2e/wait-for-sse)

        (testing "signal-linked attr seeds from the signal default"
          (is (e2e/wait-for-text "#reflect-val" "init")))

        (testing "component emit writes the signal; the page reacts client-side"
          (w/click "#reflect-btn")
          ;; Component re-renders from its own write, round-tripped through
          ;; Datastar: emit -> $hov assignment -> data-attr -> change gate.
          (is (e2e/wait-for-text "#reflect-val" "init+"))
          ;; A plain data-text subscriber to the same signal also updates —
          ;; it's the shared page signal, not component-local state.
          (is (e2e/wait-for-text "#hov-text" "init+")))

        (testing "loop composes: second click appends again"
          (w/click "#reflect-btn")
          (is (e2e/wait-for-text "#reflect-val" "init++"))
          (is (e2e/wait-for-text "#hov-text" "init++")))

        (testing "server actions see the live signal value"
          (w/click "#save-hov")
          (is (e2e/wait-for-text "#saved-hov" "init++"))))
      (finally
        (e2e/close-browser! browser-info)))))

(deftest ^:e2e component-hot-swap-test
  (let [browser-info (e2e/launch-browser)
        ctx          (e2e/new-context browser-info)
        page         (e2e/new-page ctx)
        original     (get @hc/registry* "e2e-gauge")]
    (try
      (w/with-page page
        (w/navigate (str base-url "/components"))
        (e2e/wait-for-sse)
        (is (e2e/wait-for-text "#gauge-label" "Gauge"))

        (testing "re-registering a component hot-swaps live instances over SSE"
          (hc/register-component! "e2e-gauge"
            {:attrs  [:value :max :label]
             :render "
(fn [{:keys [value max label]} _ctx]
  [:div#gauge-root
   [:span#gauge-label (str \"v2:\" label)]
   [:span#gauge-value (str value)]])"})
          (is (e2e/wait-for-text "#gauge-label" "v2:Gauge"))))
      (finally
        ;; Restore the original component for other tests in this namespace.
        (swap! hc/registry* assoc "e2e-gauge" original)
        (e2e/close-browser! browser-info)))))
