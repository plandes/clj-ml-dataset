(ns ^{:doc "Exactly like [[zensols.dataset.db]] but use the file system.

Instead of using ElasticSearch, use a rows of a JSON file created
with [[zensols.dataset.db/freeze-dataset]].  The file can be created
by any program since it's just a text file with the following keys:

* **:instance**: the (i.e. parsed) data instance (see [[zensols.dataset.db]])
* **:class-label**: label of the class for the data instance
* **:id**: the string unique ID of the instance
* **:set-type**: either `train` or `test` depending on the set type."
      :author "Paul Landes"}
    zensols.dataset.thaw
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [zensols.dataset.db :as edb]))

(def ^{:private true :dynamic true} *connection* nil)

(defonce default-connection-inst (atom nil))

(defmacro with-connection
  "Execute a body with the form (with-connection connection ...)

  * **connection** is created with [[elasticsearch-connection]]"
  {:style/indent 1}
  [connection & body]
  `(let [def-conn# @default-connection-inst]
     (binding [*connection* (or ~connection def-conn#)]
       ~@body)))

(defn- connection []
  (let [conn (or *connection* @default-connection-inst)]
    (if (nil? conn)
      (throw (ex-info "Connection not bound: use `with-connection`" {})))
    conn))

(defmacro use-connection
  {:style/indent 0
   :private true}
  [& body]
  `(let [conn# (connection)
         ~'by-id (:by-id conn#)
         ~'by-set-type (:by-set-type conn#)]
     ~@body))

(defn set-default-connection
  "Set the default connection.

  Parameter **conn** is used in place of what is set with [[with-connection]].
  This is very convenient and saves typing, but will get clobbered if
  a [[with-connection]] is used further down in the stack frame.

  If the parameter is missing, it's unset."
  ([]
   (set-default-connection nil))
  ([conn]
   (reset! default-connection-inst conn)))

(defn thaw-connection
  "Create a connection with **name** analogous
  to [[zensols.dataset.db/elasticsearch-connection]] but read from **resource**
  (any type usable by `clojure.java.io/reader` as the backing store.  The
  results are cached in memory."
  [name resource
   & {:keys [set-type-key]
      :or {set-type-key :set-type}}]
  (with-open [reader (io/reader resource)]
    (->> (line-seq reader)
         (map #(json/read-str % :key-fn keyword))
         (reduce (fn [res {:keys [id set-type] :as m}]
                   (let [m (dissoc m set-type-key)
                         set-type-kw (keyword set-type)
                         set-type-map (:by-set-type res)
                         set-type-data (get set-type-map set-type-kw)
                         set-type-data (conj set-type-data m)]
                     (assoc res
                            :by-id (assoc (:by-id res) id m)
                            :by-set-type (assoc set-type-map set-type-kw set-type-data))))
                 {:by-id {}
                  :by-set-type {}})
         (merge {:name name})
         doall)))

(defn instances-count
  "Return the number of datasets in the DB."
  []
  (use-connection
    (count by-id)))

(defn ids
  "Return all IDs based on the *dataset split* (see class docs).

  Keys
  ----
  * **:set-type** is either `:train`, `:test`, `:train-test` (all) and defaults
  to [[set-default-set-type]] or `:train` if not set"
  [& {:keys [set-type]
      :or {set-type :train-test}}]
  (use-connection
    (cond (or (= set-type :test) (= set-type :train))
          (->> by-set-type set-type (map :id))
          (= set-type :train-test) (keys by-id)
          true (-> (format "No defined set type: %s" set-type)
                   (ex-info {:set-type set-type})
                   throw))))

(defn instance-by-id
  "Get a specific instance by its ID.

  This returns a map that has the following keys:

  * **:instance** the instance data, which was set with
  **:create-instances-fn** in [[elasticsearch-connection]]"
  ([conn id]
   (with-connection conn
     (instance-by-id id)))
  ([id]
   (use-connection
     (get by-id id))))

(defn instances
  "Return all instance data based on the *dataset split* (see class docs).

  See [[instance-by-id]] for the data in each map sequence returned.

  Keys
  ----
  * **:set-type** is either `:train`, `:test`, `:train-test` (all) and defaults
  to [[set-default-set-type]] or `:train` if not set
  * **:include-ids?** if non-`nil` return keys in the map as well"
  [& {:keys [set-type id-set]
      :or {set-type :train-test}}]
  (use-connection
    (cond id-set (->> id-set (map instance-by-id) doall)
          (or (= set-type :test) (= set-type :train))
          (->> by-set-type set-type)
          (= set-type :train-test) by-id
          true (-> (format "No defined set type: %s" set-type)
                   (ex-info {:set-type set-type})
                   throw))))
