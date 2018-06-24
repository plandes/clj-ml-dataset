(ns zensols.dataset.thaw-test
  (:require [clojure.test :refer :all]
            [zensols.actioncli.dynamic :as dyn]
            [zensols.actioncli.util :refer (defnlock)]
            [zensols.dataset.thaw :refer :all]
            [clojure.java.io :as io]))

(defnlock connection []
  (thaw-connection "example" (io/file "test-resources/example-dataset.json")))

(defn reset-connection []
  (-> (meta #'connection) :init-resource (reset! nil)))

(dyn/register-purge-fn reset-connection)

;(reset-connection)

(defn anon-by-id
  [id]
  (with-connection (connection)
    (instance-by-id id)))

(defn anons
  "Return all annotations"
  [& opts]
  (with-connection (connection)
    (apply instances opts)))

(deftest anon-counts []
  (testing "Annotation counts"
    (is (= 3 (->> (anons) count)))
    (is (= 2 (->> (anons :set-type :train) count)))
    (is (= 1 (->> (anons :set-type :test) count)))))

(deftest anon-data []
  (testing "Annotation counts"
    (let [anon (anon-by-id "232")]
      (is (= "calendar" (-> anon :class-label)))
      (is (= "232" (-> anon :id)))
      (is (= 0 (-> anon :instance :panon :sentiment))))))
