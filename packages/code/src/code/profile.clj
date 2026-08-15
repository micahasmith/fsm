(ns code.profile
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def eof ::eof)

(def special-op-names
  #{"ns" "def" "defn" "defn-" "defmacro" "let" "let*" "letfn" "loop" "loop*"
    "if" "do" "when" "when-let" "fn" "fn*" "quote" "var" "try" "catch"
    "finally" "recur" "." "new" "set!"})

(def definition-op-names
  #{"def" "defn" "defn-" "defmacro"})

(defn core-symbol? [sym]
  (boolean (ns-resolve 'clojure.core sym)))

(defn read-forms [artifact-path]
  (with-open [reader (clojure.lang.LineNumberingPushbackReader. (io/reader artifact-path))]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (read reader false eof)]
          (if (= eof form)
            forms
            (recur (conj forms form))))))))

(defn current-ns-from-form [form]
  (when (and (seq? form)
             (= 'ns (first form))
             (symbol? (second form)))
    (str (second form))))

(defn definition-name-from-form [form]
  (when (and (seq? form)
             (symbol? (first form))
             (definition-op-names (name (first form)))
             (symbol? (second form)))
    (name (second form))))

(defn op-head [form]
  (let [head (first form)]
    (cond
      (symbol? head) {:head head
                      :op/name (name head)
                      :qualified (str head)}
      (keyword? head) {:head head
                       :op/name (name head)
                       :qualified (str head)
                       :head-kind :keyword}
      :else nil)))

(defn resolved-op-ns [current-ns op-info]
  (let [head (:head op-info)]
    (cond
      (symbol? head)
      (or (namespace head)
          (when (core-symbol? head) "clojure.core")
          current-ns)

      (keyword? head)
      "clojure.core"

      :else current-ns)))

(defn op-kind [op-info]
  (let [op-name (:op/name op-info)]
    (cond
      (nil? op-name) "unknown"
      (definition-op-names op-name) "definition"
      (special-op-names op-name) "special-form"
      (= :keyword (:head-kind op-info)) "lookup"
      :else "invoke")))

(defn form-meta [form]
  (let [m (meta form)]
    {:line (:line m)
     :column (:column m)
     :end-line (:end-line m)
     :end-column (:end-column m)}))

(defn occurrence [artifact-path ctx form op-info]
  (let [{:keys [line column end-line end-column]} (form-meta form)
        op-ns (resolved-op-ns (:current-ns ctx) op-info)
        op-name (:op/name op-info)
        qualified (if op-ns
                    (str op-ns "/" op-name)
                    (:qualified op-info))]
    {:source/file artifact-path
     :source/line line
     :source/column column
     :source/end-line end-line
     :source/end-column end-column
     :context/ns (:current-ns ctx)
     :context/def (:current-def ctx)
     :op/ns op-ns
     :op/name op-name
     :op/qualified qualified
     :arg-count (count (rest form))
     :args (mapv pr-str (rest form))
     :kind (op-kind op-info)
     :form (pr-str form)}))

(declare walk-form)

(defn walk-coll [artifact-path ctx values acc]
  (reduce (fn [[ctx* occs] value]
            (walk-form artifact-path ctx* value occs))
          [ctx acc]
          values))

(defn walk-list [artifact-path ctx form acc]
  (let [op-info (op-head form)
        occs' (cond-> acc
                op-info (conj (occurrence artifact-path ctx form op-info)))
        next-ns (or (current-ns-from-form form) (:current-ns ctx))
        next-def (or (definition-name-from-form form) (:current-def ctx))
        child-ctx (assoc ctx :current-ns next-ns :current-def next-def)
        [_ occs''] (walk-coll artifact-path child-ctx (rest form) occs')]
    [(assoc ctx :current-ns next-ns) occs'']))

(defn walk-form [artifact-path ctx form acc]
  (cond
    (seq? form) (walk-list artifact-path ctx form acc)
    (vector? form) (walk-coll artifact-path ctx form acc)
    (set? form) (walk-coll artifact-path ctx form acc)
    (map? form) (walk-coll artifact-path ctx (mapcat identity form) acc)
    :else [ctx acc]))

(defn add-adjacency [occurrences]
  (mapv (fn [idx occurrence]
          (assoc occurrence
                 :call/index idx
                 :adjacency/prev-index (when (pos? idx) (dec idx))
                 :adjacency/next-index (when (< idx (dec (count occurrences))) (inc idx))))
        (range (count occurrences))
        occurrences))

(defn source-id [artifact-path]
  (let [name (.getName (io/file artifact-path))
        base (first (str/split name #"\."))]
    (keyword "matrix" (str "code-" base))))

(defn profile-source-data [artifact-path]
  (let [forms (read-forms artifact-path)
        [_ occurrences] (reduce (fn [[ctx occs] form]
                                  (walk-form artifact-path ctx form occs))
                                [{:current-ns nil
                                  :current-def nil}
                                 []]
                                forms)
        current-ns (or (some current-ns-from-form forms)
                       "user")
        occurrences' (add-adjacency occurrences)]
    {:matrix/id (source-id artifact-path)
     :source {:artifact artifact-path}
     :code/ns current-ns
     :forms-read (count forms)
     :occurrence-count (count occurrences')
     :occurrences occurrences'}))
