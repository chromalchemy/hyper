(ns hyper.compress
  "Gzip compression for HTTP responses and SSE streams.

   Provides both one-shot compression (for page responses, via middleware)
   and streaming compression (for SSE connections where the deflate window is
   maintained across chunks for better ratios).

   Why gzip and not brotli?  hyper streams its SSE patches compressed, flushing
   after every patch so the client sees it immediately.  A brotli flush emits a
   block that is only surfaced by an *eager* decoder; WebKit/Safari's decoder is
   not eager, so a flushed-but-not-final brotli block stays buffered until the
   *next* write arrives — patches render one event behind.  Gzip's SYNC_FLUSH
   (the `00 00 FF FF` marker) is universally surfaced mid-stream by every
   browser, so each patch paints as soon as it is written.  Gzip is also pure
   `java.util.zip` (JDK), so the framework carries no JNI native dependency."
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.util.zip CRC32 Deflater GZIPInputStream GZIPOutputStream Inflater)))

;; ---------------------------------------------------------------------------
;; One-shot compression
;; ---------------------------------------------------------------------------
;;
;; GZIPOutputStream always deflates at the default level (6) and offers no hook
;; to change it, so the one-shot path frames gzip by hand around a max-level
;; Deflater.  This is the only place we want maximum compression — initial page
;; responses are not latency-sensitive like streaming SSE.

(def ^:private gzip-header
  "Fixed 10-byte gzip member header: magic, deflate method, no flags, zero
   mtime/xfl, OS unknown."
  (byte-array [(unchecked-byte 0x1f) (unchecked-byte 0x8b) 0x08 0 0 0 0 0 0 0]))

(defn- le-bytes
  "Encode the low 32 bits of `n` as a little-endian 4-byte array (gzip trailer
   fields are little-endian)."
  ^bytes [^long n]
  (byte-array [(unchecked-byte n)
               (unchecked-byte (bit-shift-right n 8))
               (unchecked-byte (bit-shift-right n 16))
               (unchecked-byte (bit-shift-right n 24))]))

(defn compress
  "One-shot gzip-compress a string or byte array.  Returns a byte array.
   Suitable for full page responses where max compression is preferred.

   level: 0-9 (default 9, Deflater/BEST_COMPRESSION)."
  ^bytes [data & {:keys [level] :or {level Deflater/BEST_COMPRESSION}}]
  (let [input    (if (string? data)
                   (.getBytes ^String data "UTF-8")
                   ^bytes data)
        deflater (doto (Deflater. (int level) true) ;; nowrap → raw deflate
                   (.setInput input)
                   (.finish))
        baos     (ByteArrayOutputStream.)
        buf      (byte-array 8192)]
    (.write baos ^bytes gzip-header)
    (while (not (.finished deflater))
      (let [n (.deflate deflater buf)]
        (when (pos? n) (.write baos buf 0 n))))
    (.end deflater)
    (let [crc (doto (CRC32.) (.update input))]
      (.write baos (le-bytes (.getValue crc)))      ;; CRC-32 of uncompressed
      (.write baos (le-bytes (alength input))))     ;; ISIZE mod 2^32
    (.toByteArray baos)))

;; ---------------------------------------------------------------------------
;; Streaming compression (for SSE)
;; ---------------------------------------------------------------------------

(defn byte-array-out-stream
  "Create a ByteArrayOutputStream for use with compress-out-stream."
  ^ByteArrayOutputStream []
  (ByteArrayOutputStream.))

(defn compress-out-stream
  "Create a sync-flushing GZIPOutputStream wrapping a ByteArrayOutputStream.
   Used for streaming compression where the deflate window is maintained across
   multiple chunks (e.g. SSE events).  `syncFlush` is enabled so each `.flush`
   emits a SYNC_FLUSH boundary the browser can decode immediately.

   Accepts (and ignores) keyword opts for call-site compatibility; gzip has no
   tunable window size."
  ^GZIPOutputStream [^ByteArrayOutputStream out-stream & _opts]
  (GZIPOutputStream. out-stream true))

(defn compress-stream
  "Write a string chunk into a streaming gzip compressor, flush (SYNC_FLUSH),
   and return the compressed bytes.  Resets the underlying ByteArrayOutputStream
   so the next call only returns newly compressed data.

   The GZIPOutputStream maintains its deflate window across calls, giving better
   compression ratios for repeated SSE fragments."
  ^bytes [^ByteArrayOutputStream out ^GZIPOutputStream gz ^String chunk]
  (doto gz
    (.write (.getBytes chunk "UTF-8"))
    (.flush))
  (let [result (.toByteArray out)]
    (.reset out)
    result))

(defn close-stream
  "Close the gzip output stream (writes the gzip trailer).  Safe to call on nil."
  [^GZIPOutputStream gz]
  (when gz
    (try (.close gz) (catch Exception _))))

;; ---------------------------------------------------------------------------
;; Decompression (used by tests; the browser decompresses in production)
;; ---------------------------------------------------------------------------

(defn decompress
  "Decompress a complete gzip-compressed byte array back to a UTF-8 string."
  [^bytes data]
  (with-open [in  (GZIPInputStream. (ByteArrayInputStream. data))
              out (ByteArrayOutputStream.)]
    (let [buf (byte-array 8192)]
      (loop [n (.read in buf)]
        (when (pos? n)
          (.write out buf 0 n)
          (recur (.read in buf)))))
    (.toString out "UTF-8")))

(defn decompress-stream
  "Decompress gzip data that may be an incomplete stream (e.g. SYNC_FLUSH'd
   streaming chunks with no gzip trailer).  Strips the 10-byte gzip header and
   inflates the raw deflate body with a plain Inflater, which surfaces every
   byte up to the flush boundary."
  [^bytes data]
  (let [body (java.util.Arrays/copyOfRange data 10 (alength data))
        inf  (doto (Inflater. true) (.setInput body))
        out  (ByteArrayOutputStream.)
        buf  (byte-array 8192)]
    (loop []
      (let [n (.inflate inf buf)]
        (when (pos? n)
          (.write out buf 0 n)
          (recur))))
    (.end inf)
    (.toString out "UTF-8")))

;; ---------------------------------------------------------------------------
;; Ring middleware
;; ---------------------------------------------------------------------------

(defn accepts-gzip?
  "Check if the request's Accept-Encoding header includes gzip.  Every browser
   advertises gzip (over HTTP and HTTPS alike), so SSE compression is available
   even on plain-HTTP dev connections."
  [req]
  (when-let [accept (get-in req [:headers "accept-encoding"])]
    (some? (re-find #"\bgzip\b" accept))))

(defn wrap-gzip
  "Ring middleware that compresses response bodies with gzip.
   Only compresses when:
   - Client sends Accept-Encoding: gzip
   - Response body is a string (typical for HTML/JSON)
   - Response is not already encoded

   Uses level 9 (max) for one-shot responses since they're not
   latency-sensitive like streaming SSE."
  [handler]
  (fn [req]
    (let [response (handler req)]
      (if (and response
               (accepts-gzip? req)
               (string? (:body response))
               (not (get-in response [:headers "Content-Encoding"])))
        (-> response
            (update :body #(compress % :level Deflater/BEST_COMPRESSION))
            (assoc-in [:headers "Content-Encoding"] "gzip"))
        response))))
