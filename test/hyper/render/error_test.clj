(ns hyper.render.error-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyper.render.error :as render.error]))

(deftest test-minimal
  (testing "Returns hiccup with a generic message"
    (let [e      (ex-info "secret leak" {:password "p@ssw0rd"})
          result (render.error/minimal e {})
          s      (str result)]
      (is (vector? result))
      (is (re-find #"Something went wrong" s))
      ;; Production-safety: must not leak message OR ex-data
      (is (not (re-find #"secret leak" s)))
      (is (not (re-find #"p@ssw0rd" s)))))

  (testing "Tolerates nil error / nil req"
    (is (vector? (render.error/minimal nil nil)))))

(deftest test-explain
  (testing "Renders class, message, ex-data, and stack trace"
    (let [e      (try
                   (throw (ex-info "boom" {:why :kaboom}))
                   (catch Throwable t t))
          result (render.error/explain e {})
          s      (str result)]
      (is (vector? result))
      (is (re-find #"Render Error" s))
      (is (re-find #"ExceptionInfo" s))
      (is (re-find #"boom" s))
      (is (re-find #":why" s))
      (is (re-find #":kaboom" s))
      ;; Stack-trace frames look like "at fully.Qualified.name("
      (is (re-find #"at .+\(" s))))

  (testing "Walks the cause chain"
    (let [root (try (throw (ex-info "root cause" {})) (catch Throwable t t))
          wrap (ex-info "wrapper" {} root)
          s    (str (render.error/explain wrap {}))]
      (is (re-find #"wrapper" s))
      (is (re-find #"root cause" s))
      (is (re-find #"Caused by chain" s)))))
