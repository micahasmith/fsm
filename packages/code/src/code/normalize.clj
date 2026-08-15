(ns code.normalize
  (:require [clojure.string :as str]))

(def numeric-columns
  #{"source_line" "source_column" "source_end_line" "source_end_column"
    "arg_count" "call_index" "adjacency_prev_index" "adjacency_next_index"})

(defn arg-column? [column-name]
  (str/starts-with? column-name "args_ix_"))

(defn code-kind [column-name]
  (cond
    (numeric-columns column-name) "numeric"
    (arg-column? column-name) "string"
    :else "categorical"))

(defn blankish? [value]
  (or (nil? value)
      (and (string? value)
           (str/blank? value))))

(defn normalize-value [kind value]
  (if (blankish? value)
    nil
    (case kind
      "numeric" (Double/parseDouble (str value))
      "string" (str/trim (str value))
      "categorical" (str/trim (str value))
      value)))

(defn normalization-strategy [kind]
  (case kind
    "numeric" "PARSE_DOUBLE"
    "string" "TRIM_STRING"
    "categorical" "TRIM_CATEGORY"
    "IDENTITY"))

(defn normalize-column [column]
  (let [kind (code-kind (:column/name column))
        normalized-values (mapv #(normalize-value kind %) (:values column))]
    (assoc column
           :kind kind
           :normalized-values normalized-values
           :normalization {:strategy (normalization-strategy kind)
                           :nulls-preserved true})))

(defn row-maps [columns row-count]
  (mapv (fn [idx]
          (into {}
                (map (fn [column]
                       [(:column/name column)
                        (nth (:normalized-values column) idx nil)]))
                columns))
        (range row-count)))

(defn unique-values [rows key-name]
  (->> rows
       (map #(get % key-name))
       (remove blankish?)
       distinct
       sort
       vec))

(defn normalized-view-data [raw-view]
  (let [columns (mapv normalize-column (:columns raw-view))
        rows (row-maps columns (:row-count raw-view))]
    {:matrix/id (:matrix/id raw-view)
     :source (:source raw-view)
     :profile-artifact (:profile-artifact raw-view)
     :row-count (:row-count raw-view)
     :column-count (:column-count raw-view)
     :columns columns
     :properties {:files (unique-values rows "source_file")
                  :operation-names (unique-values rows "op_name")
                  :operation-namespaces (unique-values rows "op_ns")
                  :definitions (unique-values rows "context_def")
                  :invoke-op-names (->> rows
                                        (filter #(= "invoke" (get % "kind")))
                                        (map #(get % "op_name"))
                                        (remove blankish?)
                                        distinct
                                        sort
                                        vec)
                  :adjacency-available (boolean (some #(some? (get % "adjacency_next_index")) rows))
                  :arg-columns (->> columns
                                    (map :column/name)
                                    (filter arg-column?)
                                    sort
                                    vec)
                  :row-kinds (unique-values rows "kind")}}))
