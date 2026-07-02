// hyper WebKit/Safari SSE workaround.
//
// WebKit/Safari's fetch + ReadableStream delivery holds back the trailing bytes
// of a large SSE write until the next read wakeup, so a large isolated patch
// renders one write behind and an idle connection appears frozen.  Native
// EventSource does not have this problem.  This shim overrides window.fetch and,
// for the GET render stream only, backs it with an EventSource whose events are
// re-serialized into the byte stream datastar's reader consumes.  Everything
// else (POST actions, other fetches) passes straight through untouched.
//
// Injected into <head> (before the datastar script) only for WebKit/Safari user
// agents; other browsers never receive it.
//
// Robustness:
//   - Fail-open: if EventSource can't be constructed, or errors before ever
//     delivering data, the REAL fetch response is pumped into the same stream,
//     so the app keeps working (un-shimmed behaviour) instead of breaking.
//   - Reconnect: an EventSource drop *after* data errors the stream so
//     datastar's own retry re-opens a fresh connection (keeping datastar's
//     connection-status signals accurate).
//   - Teardown: datastar may abort its request via the AbortSignal without
//     cancelling our stream, so both the stream's cancel and the request signal
//     drive a single teardown that closes the EventSource and cancels any
//     fail-open reader.  This keeps neither an EventSource nor a fetch reader
//     leaking across reconnects and navigations.
(function () {
  try {
    if (!window.fetch || !window.EventSource || !window.ReadableStream) return;

    var realFetch = window.fetch.bind(window);

    // hyper's SSE event types over the render stream.  Named events must be
    // enumerated because EventSource has no wildcard listener.
    var EVENTS = ["datastar-patch-elements", "datastar-patch-signals", "connected"];

    function isEventsGet(input, init) {
      var url = typeof input === "string" ? input : (input && input.url) || "";
      var method = ((init && init.method) || (input && input.method) || "GET").toUpperCase();
      return method === "GET" && url.indexOf("/hyper/events") >= 0;
    }

    function eventSourceResponse(input, init, url) {
      var enc = new TextEncoder();
      var es = null;          // the backing EventSource, once constructed
      var pumpReader = null;  // the fail-open fetch reader, once pumping
      var done = false;       // torn down: no further enqueue / connections

      // Close whatever is backing the stream.  Idempotent; safe to call from the
      // stream's cancel, the request's abort signal, or an internal error.
      function teardown() {
        if (done) return;
        done = true;
        try { if (es) es.close(); } catch (e) {}
        try { if (pumpReader) pumpReader.cancel(); } catch (e) {}
      }

      // datastar aborts its render request through the AbortSignal, which does
      // not invoke a manually-constructed stream's cancel, so tear down here too.
      var signal = init && init.signal;
      if (signal) {
        if (signal.aborted) done = true;
        else try { signal.addEventListener("abort", teardown); } catch (e) {}
      }

      // Fail-open: stream the REAL fetch response into datastar's reader, so the
      // app keeps working (the un-shimmed path) if the EventSource path can't be
      // used.  Read failures are surfaced so datastar's own retry kicks in.
      function pumpRealFetch(ctrl) {
        realFetch(input, init).then(function (resp) {
          if (done) { try { resp && resp.body && resp.body.cancel(); } catch (e) {} return; }
          if (!resp || !resp.body) { done = true; try { ctrl.close(); } catch (e) {} return; }
          pumpReader = resp.body.getReader();
          (function pump() {
            pumpReader.read().then(function (r) {
              if (done) return;
              if (r.done) { done = true; try { ctrl.close(); } catch (e) {} return; }
              try { ctrl.enqueue(r.value); } catch (e) {}
              pump();
            }, function () {
              if (done) return;
              done = true;
              try { ctrl.error(new Error("hyper sse fetch read error")); } catch (e) {}
            });
          })();
        }, function () {
          if (done) return;
          done = true;
          try { ctrl.error(new Error("hyper sse fetch failed")); } catch (e) {}
        });
      }

      var stream = new ReadableStream({
        start: function (ctrl) {
          var gotData = false;
          var errored = false;   // es.onerror handled once

          function forward(ev) {
            if (done) return;
            // Reconstruct SSE wire bytes for the datastar reader.  EventSource
            // strips one space after "data:" and joins multiple data lines with
            // "\n"; reverse both faithfully.
            try {
              var out = "event: " + ev.type + "\n";
              if (ev.lastEventId) out += "id: " + ev.lastEventId + "\n";
              var data = ev.data == null ? "" : String(ev.data);
              var lines = data.split("\n");
              for (var i = 0; i < lines.length; i++) out += "data: " + lines[i] + "\n";
              out += "\n";
              ctrl.enqueue(enc.encode(out));
              gotData = true;
            } catch (e) {}
          }

          if (done) return;   // aborted before we even started
          try {
            es = new EventSource(url);
          } catch (e) {
            pumpRealFetch(ctrl);   // fail open: EventSource unavailable
            return;
          }
          for (var i = 0; i < EVENTS.length; i++) es.addEventListener(EVENTS[i], forward);
          es.onmessage = forward;
          es.onerror = function () {
            if (done || errored) return;
            errored = true;
            try { es.close(); } catch (e) {}
            if (gotData) {
              // Connected, then dropped: error the stream so datastar's own
              // retry re-opens the connection (a fresh EventSource).
              done = true;
              try { ctrl.error(new Error("hyper sse disconnected")); } catch (e) {}
            } else {
              // Never delivered anything here: fail open to the real fetch.
              pumpRealFetch(ctrl);
            }
          };
        },
        cancel: teardown
      });

      return Promise.resolve(new Response(stream, {
        status: 200,
        headers: { "Content-Type": "text/event-stream" }
      }));
    }

    window.fetch = function (input, init) {
      try {
        if (isEventsGet(input, init)) {
          return eventSourceResponse(input, init, typeof input === "string" ? input : input.url);
        }
      } catch (e) { /* fall through to the real fetch */ }
      return realFetch(input, init);
    };
  } catch (e) {
    // Fail open: leave the real fetch in place.
  }
})();
