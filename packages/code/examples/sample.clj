(ns sample.core)

(defn compute [x y]
  (let [sum (+ x y)
        squared (* sum sum)]
    (str squared)))

(defn describe-user [user]
  (when-let [name (:name user)]
    (str "user:" name)))
