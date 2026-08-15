(ns code.template-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [code.template :as template]
            [fsm.filesystem :as filesystem]))

(deftest profile-source-data-extracts-operation-occurrences
  (let [artifact "/Users/micahsmith/dev/fsm/packages/code/examples/sample.clj"
        profile (template/profile-source {:request {:args {:artifact artifact}}})
        first-occurrence (first (:occurrences profile))
        kinds (set (map :kind (:occurrences profile)))]
    (testing "profile stage emits occurrence rows with source context"
      (is (= artifact (get-in profile [:source :artifact])))
      (is (= "sample.core" (:code/ns profile)))
      (is (pos? (:occurrence-count profile)))
      (is (contains? kinds "invoke"))
      (is (= 0 (:call/index first-occurrence)))
      (is (string? (:op/name first-occurrence)))))
  (testing "package pipeline can produce raw, normalized, and path views"
    (let [artifact "/Users/micahsmith/dev/fsm/packages/code/examples/sample.clj"
          profile-artifact (.getAbsolutePath (java.io.File/createTempFile "code-profile" ".json"))
          raw-artifact (.getAbsolutePath (java.io.File/createTempFile "code-raw" ".json"))
          normalized-artifact (.getAbsolutePath (java.io.File/createTempFile "code-normalized" ".json"))
          profile (template/profile-source {:request {:args {:artifact artifact}}})
          _ (filesystem/write-json-file! profile-artifact profile)
          raw-view (template/build-raw-view {:request {:args {:artifact profile-artifact}}})
          _ (filesystem/write-json-file! raw-artifact raw-view)
          normalized-view (template/normalize-view {:request {:args {:artifact raw-artifact}}})
          _ (filesystem/write-json-file! normalized-artifact normalized-view)
          path-data (template/enumerate-paths {:request {:args {:artifact normalized-artifact}}})
          candidate-paths (:candidate_paths path-data)
          path-ids (set (map :path_id candidate-paths))]
      (is (pos? (:row-count raw-view)))
      (is (some #(= "op_name" (:column/name %)) (:columns raw-view)))
      (is (contains? (set (get-in normalized-view [:properties :row-kinds])) "invoke"))
      (is (seq (get-in normalized-view [:properties :invoke-op-names])))
      (is (contains? path-ids "code.path/profile-sequential-adjacency/whole-matrix"))
      (is (some #(str/starts-with? % "code.path/profile-call-frequency/str")
                path-ids)))))
