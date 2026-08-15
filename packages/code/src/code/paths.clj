(ns code.paths
  (:require [clojure.string :as str]))

(def operations
  [{:op/id "code.op/profile-call-frequency"
    :path/id "code.path/profile-call-frequency"
    :description "Profile repeated invocation frequency for each concrete operation."
    :requires ["invoke-operations"]
    :expansion {:mode "per-item" :source "invoke-op-names"}}
   {:op/id "code.op/profile-namespace-usage"
    :path/id "code.path/profile-namespace-usage"
    :description "Profile how operations distribute across resolved namespaces."
    :requires ["operation-namespaces"]
    :expansion {:mode "per-item" :source "operation-namespaces"}}
   {:op/id "code.op/profile-definition-adjacency"
    :path/id "code.path/profile-definition-adjacency"
    :description "Profile within-definition sequencing and local adjacency."
    :requires ["definitions"]
    :expansion {:mode "per-item" :source "definitions"}}
   {:op/id "code.op/profile-arg-shapes"
    :path/id "code.path/profile-arg-shapes"
    :description "Profile argument-shape patterns across operation occurrences."
    :requires ["arg-columns"]
    :expansion {:mode "whole-matrix" :source "rows"}}
   {:op/id "code.op/profile-sequential-adjacency"
    :path/id "code.path/profile-sequential-adjacency"
    :description "Profile adjacency edges between consecutive operation occurrences."
    :requires ["adjacency-metadata"]
    :expansion {:mode "whole-matrix" :source "rows"}}])

(defn normalize-requirements [requirements]
  (cond
    (string? requirements) [requirements]
    (keyword? requirements) [(name requirements)]
    (vector? requirements) (mapv #(if (keyword? %) (name %) (str %)) requirements)
    (seq? requirements) (mapv #(if (keyword? %) (name %) (str %)) requirements)
    :else []))

(def symbolic-slugs
  {"+" "plus"
   "*" "star"
   "/" "slash"
   "-" "dash"
   "=" "equals"
   ">" "gt"
   "<" "lt"
   ">=" "gte"
   "<=" "lte"
   "!" "bang"
   "?" "question"})

(defn slug [value]
  (let [value' (get symbolic-slugs (str value) (str value))
        slugged (-> value'
                    (.toLowerCase)
                    (.replaceAll "[^a-z0-9]+" "-")
                    (.replaceAll "^-|-$" ""))]
    (if (str/blank? slugged)
      (str "sym-" (Math/abs (hash value')))
      slugged)))

(defn requirement-satisfied? [requirement normalized-view]
  (let [properties (:properties normalized-view)]
    (case requirement
      "invoke-operations" (seq (:invoke-op-names properties))
      "operation-namespaces" (seq (:operation-namespaces properties))
      "definitions" (seq (:definitions properties))
      "arg-columns" (seq (:arg-columns properties))
      "adjacency-metadata" (true? (:adjacency-available properties))
      false)))

(defn missing-requirements [requirements normalized-view]
  (filterv #(not (requirement-satisfied? % normalized-view))
           requirements))

(defn source-items [source normalized-view]
  (let [properties (:properties normalized-view)]
    (case source
      "invoke-op-names" (:invoke-op-names properties)
      "operation-namespaces" (:operation-namespaces properties)
      "definitions" (:definitions properties)
      "rows" (range (:row-count normalized-view))
      [])))

(defn expansion-bindings [expansion normalized-view]
  (let [mode (:mode expansion)
        source (:source expansion)]
    (case mode
      "per-item" (mapv (fn [item]
                         {:item item
                          :source source})
                       (source-items source normalized-view))
      "whole-matrix" [{:scope :whole-matrix
                       :source source
                       :row-count (:row-count normalized-view)}]
      [{:scope :unbound
        :source source}])))

(defn binding-suffix [binding]
  (cond
    (:item binding) (slug (:item binding))
    (:scope binding) (slug (:scope binding))
    :else "matrix"))

(defn path-id [operation binding]
  (str (:path/id operation) "/" (binding-suffix binding)))

(defn operation-evidence [operation-id normalized-view]
  (let [properties (:properties normalized-view)]
    (case operation-id
      "code.op/profile-call-frequency" {:invoke_op_names (:invoke-op-names properties)}
      "code.op/profile-namespace-usage" {:operation_namespaces (:operation-namespaces properties)}
      "code.op/profile-definition-adjacency" {:definitions (:definitions properties)}
      "code.op/profile-arg-shapes" {:arg_columns (:arg-columns properties)}
      "code.op/profile-sequential-adjacency" {:row_count (:row-count normalized-view)}
      {})))

(defn operation-reason [operation-id normalized-view]
  (let [properties (:properties normalized-view)]
    (case operation-id
      "code.op/profile-call-frequency" (format "Detected %d invoke operations" (count (:invoke-op-names properties)))
      "code.op/profile-namespace-usage" (format "Detected %d operation namespaces" (count (:operation-namespaces properties)))
      "code.op/profile-definition-adjacency" (format "Detected %d enclosing definitions" (count (:definitions properties)))
      "code.op/profile-arg-shapes" (format "Detected %d argument columns" (count (:arg-columns properties)))
      "code.op/profile-sequential-adjacency" (format "Detected %d operation rows with adjacency ordering" (:row-count normalized-view))
      "Code path requirements satisfied")))

(defn option-row [operation requirements normalized-view binding]
  (let [missing (missing-requirements requirements normalized-view)]
    {:path_id (path-id operation binding)
     :path_template (:path/id operation)
     :operation (:op/id operation)
     :eligible (empty? missing)
     :missing_requirements missing
     :requirements requirements
     :binding binding
     :description (:description operation)
     :expansion (:expansion operation)
     :evidence (operation-evidence (:op/id operation) normalized-view)}))

(defn candidate-path [operation requirements normalized-view binding]
  {:path_id (path-id operation binding)
   :path_template (:path/id operation)
   :operation (:op/id operation)
   :requirements requirements
   :binding binding
   :description (:description operation)
   :expansion (:expansion operation)
   :reason (operation-reason (:op/id operation) normalized-view)
   :evidence (operation-evidence (:op/id operation) normalized-view)})

(defn history-entry [normalized-view option-matrix candidate-paths]
  {:matrix_id (:matrix/id normalized-view)
   :available_option_count (count option-matrix)
   :created_path_count (count candidate-paths)
   :created_paths (mapv :path_id candidate-paths)
   :created_operations (mapv :operation candidate-paths)})

(defn enumerate-paths-data [normalized-view]
  (let [option-matrix (->> operations
                           (mapcat (fn [operation]
                                     (let [requirements (normalize-requirements (:requires operation))]
                                       (map (fn [binding]
                                              (option-row operation requirements normalized-view binding))
                                            (expansion-bindings (:expansion operation) normalized-view)))))
                           vec)
        candidate-paths (->> option-matrix
                             (filter :eligible)
                             (mapv (fn [option]
                                     (candidate-path {:op/id (:operation option)
                                                      :path/id (:path_template option)
                                                      :description (:description option)
                                                      :expansion (:expansion option)}
                                                     (:requirements option)
                                                     normalized-view
                                                     (:binding option)))))]
    {:matrix_id (:matrix/id normalized-view)
     :option_matrix option-matrix
     :candidate_paths candidate-paths
     :history_entry (history-entry normalized-view option-matrix candidate-paths)}))
