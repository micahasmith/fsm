(ns code.template
  (:require [code.normalize :as normalize]
            [code.paths :as paths]
            [code.profile :as profile]
            [fsm.filesystem :as filesystem]
            [matrix.view :as matrix-view]))

(def operations paths/operations)

(defn profile-source [{:keys [request]}]
  (profile/profile-source-data (:artifact (or (:payload request) (:args request)))))

(defn build-raw-view [{:keys [request]}]
  (let [profile-artifact (:artifact (or (:payload request) (:args request)))
        profile-data (filesystem/read-json-file profile-artifact)
        matrix-view (matrix-view/json->matrix-column-view (:occurrences profile-data)
                                                          {:matrix-id (:matrix/id profile-data)})]
    (assoc matrix-view
           :source (:source profile-data)
           :profile-artifact profile-artifact
           :occurrence-count (:occurrence-count profile-data)
           :code/ns (:code/ns profile-data))))

(defn normalize-view [{:keys [request]}]
  (normalize/normalized-view-data
   (filesystem/read-json-file (:artifact (or (:payload request) (:args request))))))

(defn enumerate-paths [{:keys [request]}]
  (paths/enumerate-paths-data
   (filesystem/read-json-file (:artifact (or (:payload request) (:args request))))))
