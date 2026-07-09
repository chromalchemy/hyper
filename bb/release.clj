(ns release
  "Cut a release: stamp CHANGELOG.md from Conventional Commits, commit, and tag.

  Usage:
    bb release <patch|minor|major>   ; bump the latest vX.Y.Z tag
    bb release X.Y.Z                 ; release an explicit version
    bb release ... --retag           ; reuse an existing tag (re-stamp + move it)
    bb release ... --push            ; also push the branch and tag

  The generated section is inserted beneath the hand-written CHANGELOG.md
  preamble. Pushing a vX.Y.Z tag is what triggers the Clojars deploy, so this
  task stops at the tag by default and prints the push command."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(def ^:private changelog "CHANGELOG.md")

(def ^:private usage
  "Usage: bb release <patch|minor|major|X.Y.Z> [--retag] [--push]")

(defn- fail [msg]
  (binding [*out* *err*] (println "release:" msg))
  (System/exit 1))

(defn- run
  "Run a command, returning {:exit :out :err} with trimmed streams."
  [& args]
  (let [{:keys [exit out err]} @(p/process (vec args) {:out :string :err :string})]
    {:exit exit :out (str/trim (or out "")) :err (str/trim (or err ""))}))

(defn- git
  "Run a git command, returning trimmed stdout. Aborts on failure."
  [& args]
  (let [{:keys [exit out err]} (apply run "git" args)]
    (when-not (zero? exit)
      (fail (format "`git %s` failed:\n%s" (str/join " " args) err)))
    out))

;; --- version resolution -----------------------------------------------------

(defn- parse-version [s]
  (when-let [[_ a b c] (re-matches #"v?(\d+)\.(\d+)\.(\d+)" (str/trim (str s)))]
    [(parse-long a) (parse-long b) (parse-long c)]))

(defn- fmt-version [[a b c]]
  (format "%d.%d.%d" a b c))

(defn- latest-version
  "Highest vMAJOR.MINOR.PATCH git tag as [major minor patch], or [0 0 0]."
  []
  (or (->> (str/split-lines (git "tag" "--list" "v[0-9]*.[0-9]*.[0-9]*"))
           (keep parse-version)
           sort
           last)
      [0 0 0]))

(defn- bump [[a b c] level]
  (case level
    "major" [(inc a) 0 0]
    "minor" [a (inc b) 0]
    "patch" [a b (inc c)]))

(defn- resolve-version [spec]
  (cond
    (contains? #{"major" "minor" "patch"} spec) (fmt-version (bump (latest-version) spec))
    (parse-version spec)                        (fmt-version (parse-version spec))
    :else (fail (str "invalid version spec: " (pr-str spec) "\n" usage))))

;; --- guards -----------------------------------------------------------------

(defn- ensure-git-cliff! []
  (when-not (zero? (:exit (run "git-cliff" "--version")))
    (fail "git-cliff not found on PATH — run this inside `nix-shell`.")))

(defn- ensure-clean-tree! []
  (when-not (str/blank? (git "status" "--porcelain" "--untracked-files=no"))
    (fail "working tree has uncommitted changes — commit or stash them first.")))

(defn- tag-exists? [tag]
  (zero? (:exit (run "git" "rev-parse" "--verify" "--quiet" (str "refs/tags/" tag)))))

(defn- remote-tag-exists? [tag]
  (not (str/blank? (:out (run "git" "ls-remote" "--tags" "origin" (str "refs/tags/" tag))))))

(defn- ensure-no-section! [content version]
  (when (re-find (re-pattern (str "(?m)^## " (str/replace version "." "\\.") "(?:\\s|$)")) content)
    (fail (str "CHANGELOG.md already has a section for " version
               " — remove it before re-stamping."))))

;; --- changelog --------------------------------------------------------------

(defn- generate-section
  "Render the CHANGELOG section for `version` from the unreleased commits."
  [version]
  (let [{:keys [exit out err]} (run "git-cliff" "--tag" (str "v" version) "--unreleased")]
    (when-not (zero? exit)
      (fail (str "git-cliff failed:\n" err)))
    ;; Collapse the runs of blank lines git-cliff leaves between groups.
    (-> out str/trim (str/replace #"\n{3,}" "\n\n"))))

(defn- insert-section
  "Insert `section` beneath the preamble, above the first existing `## ` heading."
  [content section]
  (let [lines (str/split-lines content)
        idx   (->> lines (keep-indexed (fn [i l] (when (str/starts-with? l "## ") i))) first)]
    (if idx
      (str (str/trimr (str/join "\n" (take idx lines)))
           "\n\n" section "\n\n"
           (str/join "\n" (drop idx lines)) "\n")
      (str (str/trimr content) "\n\n" section "\n"))))

;; --- entry point ------------------------------------------------------------

(defn -main [args]
  (let [args       (vec args)
        flags      (set (filter #(str/starts-with? % "--") args))
        positional (remove #(str/starts-with? % "--") args)
        spec       (first positional)
        retag?     (contains? flags "--retag")
        push?      (contains? flags "--push")]
    (when-not spec
      (println usage)
      (System/exit 2))
    (ensure-git-cliff!)
    (ensure-clean-tree!)
    (let [version  (resolve-version spec)
          tag      (str "v" version)
          existed? (tag-exists? tag)]
      (when (and existed? (not retag?))
        (fail (str "tag " tag " already exists — pass --retag to re-stamp and move it.")))
      (println (str "Releasing " tag (when (and existed? retag?) " (retag)")))
      ;; Retag: drop the local tag so those commits count as unreleased again.
      (when (and existed? retag?)
        (git "tag" "-d" tag)
        (println (str "  deleted local tag " tag)))
      (let [content (slurp changelog)]
        (ensure-no-section! content version)
        (let [section (generate-section version)]
          (when (str/blank? (str/replace section #"(?m)^##.*$" ""))
            (fail "no unreleased commits — nothing to release."))
          (spit changelog (insert-section content section))
          (println (str "  stamped " changelog))
          (git "add" changelog)
          (git "commit" "-m" (str "docs: changelog for " tag))
          (println "  committed changelog")
          (git "tag" tag)
          (println (str "  tagged " tag))
          (if push?
            (do
              (println "Pushing…")
              (git "push" "origin" "HEAD")
              (if (and existed? (remote-tag-exists? tag))
                (git "push" "origin" tag "--force")
                (git "push" "origin" tag))
              (println "Pushed."))
            (let [force? (and existed? (remote-tag-exists? tag))]
              (println "\nReview, then push to trigger the release:")
              (println (str "  git push origin HEAD"
                            (if force?
                              (str " && git push origin " tag " --force")
                              (str " " tag)))))))))))
