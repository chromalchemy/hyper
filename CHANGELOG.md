# Changelog

## 0.2.2 — 2026-07-14

### Bug Fixes
- Preserve explicit nil in cursor default init

### Build
- Gate Clojars release on CI

## 0.2.1 — 2026-07-10

### Bug Fixes

- Fix missing request context bindings in teardown and partial renders

## 0.2.0 — 2026-07-10

### Bug Fixes
- Don't drop cursor writes made during flush

### Refactoring

#### Consolidating into hyper.core

*DEPRECATION*: You should no longer require `hyper.protocols` or `hyper.client-params` directly.

Maps the following into `hyper.core` directly:

- `action-name` — wraps `context/*action-name*`
- `route` — wraps `(:hyper/route *request*)`
- `Watchable` — re-export of `hyper.protocols/Watchable`
- `client-param` — re-export of `hyper.client-params/client-param` multimethod

## 0.1.1 — 2026-07-09

### Build
- Add bb release task and drop git-cliff header

### Documentation
- Note pre-1.0 breaking-change policy

### Deps
- Pin squint via maven

## 0.1.0 — 2026-07-09

Initial public release.
