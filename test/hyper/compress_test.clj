(ns hyper.compress-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hyper.compress :as gz])
  (:import (java.util.zip Inflater)))

;; ---------------------------------------------------------------------------
;; One-shot compression
;; ---------------------------------------------------------------------------

(deftest test-compress-roundtrip
  (testing "compress and decompress roundtrip with default options"
    (let [input        "Hello, gzip!"
          compressed   (gz/compress input)
          decompressed (gz/decompress compressed)]
      (is (bytes? compressed))
      (is (= input decompressed))))

  (testing "output is a valid gzip member (magic bytes 1f 8b 08)"
    (let [compressed (gz/compress "anything")]
      (is (= [0x1f 0x8b 0x08]
             (mapv #(bit-and (long %) 0xff) (take 3 compressed))))))

  (testing "compressed output is smaller than input for compressible data"
    (let [input      (apply str (repeat 100 "The quick brown fox jumps over the lazy dog. "))
          compressed (gz/compress input)]
      (is (< (alength compressed) (count (.getBytes input "UTF-8"))))))

  (testing "roundtrip with level 9 (max)"
    (let [input      "<html><body><h1>Hello World</h1></body></html>"
          compressed (gz/compress input :level 9)]
      (is (= input (gz/decompress compressed)))))

  (testing "roundtrip with level 1 (fast)"
    (let [input      "<div id=\"hyper-app\"><p>Fast compression</p></div>"
          compressed (gz/compress input :level 1)]
      (is (= input (gz/decompress compressed)))))

  (testing "compresses byte array input"
    (let [input        "byte array input"
          input-bytes  (.getBytes input "UTF-8")
          compressed   (gz/compress input-bytes)
          decompressed (gz/decompress compressed)]
      (is (= input decompressed))))

  (testing "handles empty string"
    (let [compressed   (gz/compress "")
          decompressed (gz/decompress compressed)]
      (is (= "" decompressed))))

  (testing "handles unicode content"
    (let [input        "こんにちは世界 🌍 Ω≈ç√∫"
          compressed   (gz/compress input)
          decompressed (gz/decompress compressed)]
      (is (= input decompressed)))))

;; ---------------------------------------------------------------------------
;; Streaming compression
;; ---------------------------------------------------------------------------

(deftest test-streaming-compression
  (testing "single chunk produces compressed bytes"
    (let [out (gz/byte-array-out-stream)
          g   (gz/compress-out-stream out)]
      (let [compressed (gz/compress-stream out g "Hello, streaming!")]
        (is (bytes? compressed))
        (is (pos? (alength compressed))))
      (gz/close-stream g)))

  (testing "a sync-flushed chunk round-trips WITHOUT closing the stream"
    ;; This is the property Safari needs: a flushed-but-not-final gzip block
    ;; (SYNC_FLUSH) must surface its full payload mid-stream.  A plain Inflater
    ;; recovers every byte even though the gzip trailer was never written.
    (let [out   (gz/byte-array-out-stream)
          g     (gz/compress-out-stream out :window-size 18)
          patch (apply str (repeat 200 "datastar patch fragment "))
          chunk (gz/compress-stream out g patch)]
      (is (= patch (gz/decompress-stream chunk)))
      (gz/close-stream g)))

  (testing "a sync-flushed prefix is recoverable by a plain java Inflater"
    ;; Codec-level proof, independent of hyper's own decoder: strip the 10-byte
    ;; gzip header and inflate the raw deflate body with nowrap=true.
    (let [out   (gz/byte-array-out-stream)
          g     (gz/compress-out-stream out)
          patch (apply str (repeat 50 "abc123-"))
          chunk (gz/compress-stream out g patch)
          body  (java.util.Arrays/copyOfRange chunk 10 (alength chunk))
          inf   (doto (Inflater. true) (.setInput body))
          buf   (byte-array 8192)
          baos  (java.io.ByteArrayOutputStream.)]
      (loop []
        (let [n (.inflate inf buf)]
          (when (pos? n) (.write baos buf 0 n) (recur))))
      (.end inf)
      (is (= patch (.toString baos "UTF-8")))
      (gz/close-stream g)))

  (testing "multiple chunks concatenated decompress to full content after close"
    (let [out      (gz/byte-array-out-stream)
          g        (gz/compress-out-stream out :window-size 18)
          combined (java.io.ByteArrayOutputStream.)
          chunk1   (gz/compress-stream out g "first.")
          _        (.write combined chunk1)
          chunk2   (gz/compress-stream out g "second.")
          _        (.write combined chunk2)
          chunk3   (gz/compress-stream out g "third.")
          _        (.write combined chunk3)]
      ;; Each chunk should produce non-empty output
      (is (pos? (alength chunk1)))
      (is (pos? (alength chunk2)))
      (is (pos? (alength chunk3)))
      ;; Close the stream so the gzip trailer is flushed
      (gz/close-stream g)
      ;; Any final bytes from closing
      (.write combined (.toByteArray out))
      ;; Now decompressing the full stream should yield everything
      (let [full-text (gz/decompress (.toByteArray combined))]
        (is (.contains full-text "first."))
        (is (.contains full-text "second."))
        (is (.contains full-text "third.")))))

  (testing "streaming achieves better compression on repeated content"
    ;; Repeated HTML fragments should compress better with a shared deflate
    ;; window than independently one-shot-compressing each fragment.
    (let [fragment        "<div id=\"hyper-app\" data-hyper-url=\"/\"><h1>Count: 1</h1><button>+1</button></div>"
          out-streaming   (gz/byte-array-out-stream)
          g               (gz/compress-out-stream out-streaming :window-size 18)
          streaming-sizes (mapv (fn [i]
                                  (let [sse (str "event: datastar-patch-elements\ndata: elements "
                                                 (str/replace fragment "1" (str i))
                                                 "\n\n")]
                                    (alength (gz/compress-stream out-streaming g sse))))
                                (range 10))
          oneshot-sizes   (mapv (fn [i]
                                  (let [sse (str "event: datastar-patch-elements\ndata: elements "
                                                 (str/replace fragment "1" (str i))
                                                 "\n\n")]
                                    (alength (gz/compress sse :level 5))))
                                (range 10))]
      ;; Later streaming chunks should be smaller than one-shot because
      ;; the deflate window has seen the repeated structure
      (is (< (apply + (drop 2 streaming-sizes))
             (apply + (drop 2 oneshot-sizes))))
      (gz/close-stream g))))

(deftest test-close-stream
  (testing "close-stream is safe on nil"
    (is (nil? (gz/close-stream nil))))

  (testing "close-stream closes without error"
    (let [out (gz/byte-array-out-stream)
          g   (gz/compress-out-stream out)]
      (gz/close-stream g)
      ;; Calling close again should be safe
      (gz/close-stream g))))

;; ---------------------------------------------------------------------------
;; Accept-Encoding detection
;; ---------------------------------------------------------------------------

(deftest test-accepts-gzip?
  (testing "returns true when accept-encoding includes gzip"
    (is (true? (gz/accepts-gzip? {:headers {"accept-encoding" "gzip, deflate, br"}}))))

  (testing "returns true for gzip-only"
    (is (true? (gz/accepts-gzip? {:headers {"accept-encoding" "gzip"}}))))

  (testing "returns false when gzip is absent"
    (is (false? (gz/accepts-gzip? {:headers {"accept-encoding" "deflate, br"}}))))

  (testing "returns falsy when no accept-encoding header"
    (is (not (gz/accepts-gzip? {:headers {}}))))

  (testing "returns falsy for nil headers"
    (is (not (gz/accepts-gzip? {:headers nil}))))

  (testing "returns falsy for nil request"
    (is (not (gz/accepts-gzip? nil)))))

;; ---------------------------------------------------------------------------
;; Ring middleware
;; ---------------------------------------------------------------------------

(deftest test-wrap-gzip-middleware
  (testing "compresses string body when client accepts gzip"
    (let [handler  (gz/wrap-gzip
                     (fn [_req]
                       {:status  200
                        :headers {"Content-Type" "text/html; charset=utf-8"}
                        :body    "<html><body><h1>Hello</h1></body></html>"}))
          response (handler {:headers {"accept-encoding" "gzip, deflate, br"}})]
      (is (= "gzip" (get-in response [:headers "Content-Encoding"])))
      (is (bytes? (:body response)))
      (is (= "<html><body><h1>Hello</h1></body></html>"
             (gz/decompress (:body response))))))

  (testing "does not compress when client does not accept gzip"
    (let [handler  (gz/wrap-gzip
                     (fn [_req]
                       {:status  200
                        :headers {"Content-Type" "text/html"}
                        :body    "<html>hello</html>"}))
          response (handler {:headers {"accept-encoding" "deflate, br"}})]
      (is (nil? (get-in response [:headers "Content-Encoding"])))
      (is (string? (:body response)))))

  (testing "does not compress when no Accept-Encoding header"
    (let [handler  (gz/wrap-gzip
                     (fn [_req]
                       {:status  200
                        :headers {"Content-Type" "text/html"}
                        :body    "<html>hello</html>"}))
          response (handler {:headers {}})]
      (is (nil? (get-in response [:headers "Content-Encoding"])))
      (is (string? (:body response)))))

  (testing "does not compress non-string bodies"
    (let [handler  (gz/wrap-gzip
                     (fn [_req]
                       {:status  200
                        :headers {"Content-Type" "application/octet-stream"}
                        :body    (.getBytes "binary" "UTF-8")}))
          response (handler {:headers {"accept-encoding" "gzip"}})]
      (is (nil? (get-in response [:headers "Content-Encoding"])))
      (is (bytes? (:body response)))))

  (testing "does not double-compress already-encoded responses"
    (let [handler  (gz/wrap-gzip
                     (fn [_req]
                       {:status  200
                        :headers {"Content-Type"     "text/html"
                                  "Content-Encoding" "br"}
                        :body    "already compressed"}))
          response (handler {:headers {"accept-encoding" "gzip"}})]
      (is (= "br" (get-in response [:headers "Content-Encoding"])))
      (is (= "already compressed" (:body response)))))

  (testing "passes through nil responses"
    (let [handler  (gz/wrap-gzip (fn [_req] nil))
          response (handler {:headers {"accept-encoding" "gzip"}})]
      (is (nil? response)))))
