(ns code.package
  (:refer-clojure :exclude [run!])
  (:require [fsm.dsl :refer [define-scope]]))

(def package
  {:package/id :fsm.package/code
   :version "0.1.0"
   :entry-scope :code/profile
   :input-schema :code.schema/source-file
   :emits [:code/profile-produced
           :code/raw-view-produced
           :code/normalized-view-produced
           :code/path-candidates-produced]})

(define-scope :code/profile
  :tags #{:code :profile}
  :doc "Parse a Clojure source file into operation occurrences with source-line adjacency."
  :template {:type :fn
             :fn/ref :code.template/profile-source}
  :input {:type :file :schema :code.schema/source-file}
  :output {:type :json :schema :code.schema/profile}
  :guards [[:file-exists :artifact]
           [:path-suffix :artifact ".clj"]]
  :emit :code/profile-produced)

(define-scope :code/build-raw-view
  :tags #{:code :raw-view}
  :doc "Build a code-operation matrix view from a code profile artifact."
  :template {:type :fn
             :fn/ref :code.template/build-raw-view}
  :input {:type :json :schema :code.schema/profile}
  :output {:type :json :schema :code.schema/raw-view}
  :guards [[:file-exists :artifact]
           [:path-suffix :artifact ".json"]]
  :emit :code/raw-view-produced)

(define-scope :code/normalize-view
  :tags #{:code :normalized-view}
  :doc "Normalize code-operation matrix columns into analysis-ready code properties."
  :template {:type :fn
             :fn/ref :code.template/normalize-view}
  :input {:type :json :schema :code.schema/raw-view}
  :output {:type :json :schema :code.schema/normalized-view}
  :guards [[:file-exists :artifact]
           [:path-suffix :artifact ".json"]]
  :emit :code/normalized-view-produced)

(define-scope :code/enumerate-paths
  :tags #{:code :paths}
  :doc "Enumerate deterministic code-analysis paths from a normalized code matrix."
  :template {:type :fn
             :fn/ref :code.template/enumerate-paths}
  :input {:type :json :schema :code.schema/normalized-view}
  :output {:type :json :schema :code.schema/path-candidates}
  :guards [[:file-exists :artifact]
           [:path-suffix :artifact ".json"]]
  :emit :code/path-candidates-produced)
