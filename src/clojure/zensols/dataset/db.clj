(ns ^{:doc "Preemptively compute a dataset (i.e. features from natural language
utterances) and store them in Elasticsearch.  This is useful for use with
training, testing, validating and development machine learning models.

The unit of data is an instance.  An instance set (or just *instances*) makes
up the dataset.

The idea is to abstract out Elasticsearch, but that might be a future
enhancement.  At the moment functions don't carry Elassticsearch artifacts but
they are exposed.

There are three basic ways to use this data:
* Get all instances (i.e. an utterance or a feature set).  In this case all
  data returned from [[ids]] is considered training data.  This is the default
  nascent state.

* Split the data into a train and test set (see [[divide-by-set]]).

* Use the data as a cross fold validation and iterate
  folds (see [[divide-by-fold]]).

The information used to represent either fold or the test/train split is
referred to as the *dataset split* state and is stored in Elasticsearch under a
differnent mapping-type in the same index as the instances.

See [[ids]] for more information."
      :author "Paul Landes"}
    zensols.dataset.db
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.pprint :refer (pprint)])
  (:require [zensols.actioncli.dynamic :refer (defa) :as dyn]
            [zensols.actioncli.log4j2 :as lu]
            [zensols.dataset.elsearch :refer (with-context) :as es]))

(defn elasticsearch-connection
  "Create a connection to the dataset DB cache.

Parameters
----------
* **index-name** the name of the Elasticsearch index
* **mapping-type** map type name (see ES docs)

Keys
----
* **:create-instances-fn** a function that computes the instance
set (i.e. parses the utterance); this function takes a single argument, which
is also a function that is used to load utterance in the DB; this function
takes the following forms:
    * (fn [instance class-label] ...
    * (fn [id instance class-label] ...
    * (fn [id instance class-label set-type] ...
        * **id** the unique identifier of the data point
        * **instance** is the data set instance (can be an `N`-deep map)
        * **class-label** the label of the class (can be nominal, double, integer)
        * **set-type** either `:test` or `:train` used to presort the data
        with [[divide-by-preset]]
  * **:url** the URL to the DB (defaults to `http://localhost:9200`)

Example
-------
  Create a connection that produces a list of 20 instances:
```clojure
(defn- create-iter-connection []
  (letfn [(load-fn [add-fn]
            (doseq [i (range 20)]
              (add-fn (str i) (format \"inst %d\" i) (format \"class %d\" i))))]
    (elasticsearch-connection \"tmp\" :create-instances-fn load-fn)))
```"
  [index-name &
   {:keys [create-instances-fn population-use set-type url]
    :or {create-instances-fn identity
         population-use 1.0
         set-type :train
         url "http://localhost:9200"}}]
  {:ids-inst (atom nil)
   :default-set-type (atom set-type)
   :population-use (atom population-use)
   :instance-context (es/create-context
                      index-name "instance"
                      :url url
                      :settings {"index.mapping.ignore_malformed" true}
                      :mapping-type-defs
                      {:dataset {:properties {:instance {:type "nested"}}}})
   :stats-context (es/create-context
                   index-name "stats"
                   :url url
                   :settings {"index.mapping.ignore_malformed" true}
                   :mapping-type-defs
                   {:stat-info {:properties {:stats {:type "nested"}}}})
   :create-instances-fn create-instances-fn})

(def ^:private id-state-key "id-state")
(def ^{:private true :dynamic true} *connection* nil)
(defa default-connection-inst)

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
         ~'ids-inst (:ids-inst conn#)
         ~'instance-context (:instance-context conn#)
         ~'stats-context (:stats-context conn#)
         ~'population-use (:population-use conn#)
         ~'default-set-type (:default-set-type conn#)]
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

(dyn/register-purge-fn set-default-connection)

(defn set-default-set-type
  "Set the default bucket (training or testing) to get data.

  * **:set-type** is either `:train` or `:test` (`:train` if not set)

  See [[ids]]"
  [set-type]
  (use-connection
    (reset! default-set-type set-type)))

(defn- put-instance
  "Write an instance to the DB.

  The framework is designed to use [[instances-load]] instead."
  ([instance class-label]
   (put-instance nil instance class-label nil))
  ([id instance class-label]
   (put-instance id instance class-label nil))
  ([id instance class-label set-type]
   (log/debugf "loading instance (%s): %s => <%s>" id class-label instance)
   (use-connection
     (with-context [instance-context]
       (let [doc (merge {:dataset {:class-label class-label
                                   :instance instance}}
                        (if set-type
                          {:set-type set-type}))]
         (if id
           (es/put-document id doc)
           (es/put-document doc)))))))

(defn instances-load
  "Parse and load the dataset in the DB."
  []
  (use-connection
    (with-context [instance-context]
      (es/recreate-index)
      ;; if put-instance is private use a lambda form
      ((:create-instances-fn (connection)) put-instance))))

(defn instances-count
  "Return the number of datasets in the DB."
  []
  (use-connection
    (with-context [instance-context]
      (es/document-count))))

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
     (with-context [instance-context]
       (:dataset (es/document-by-id id))))))

(defn clear
  "Clear the in memory instance data.  If key `:wipe-persistent?` is `true` all
  fold and test/train split data is also cleared."
  [& {:keys [wipe-persistent?]
      :or {wipe-persistent? false}}]
  (use-connection
    (reset! ids-inst nil)
    (if wipe-persistent?
      (with-context [stats-context]
        (es/delete-document id-state-key)))))

(defn set-population-use
  "Set how much of the data from the DB to use.  This is useful for cases where
  your dataset or corpus is huge and you only want to start with a small chunk
  until you get your models debugged.

  Parameters
  ----------
  * **ratio** a number between (0-1]; by default this is 1

  **Note** This removes any stored *dataset split* state"
  [ratio]
  (use-connection
    (reset! population-use ratio)
    (clear :wipe-persistent? true)))

(defn- persist-id-state [id-state]
  (use-connection
    (with-context [stats-context]
      (es/put-document id-state-key {:stat-info id-state})))
  id-state)

(defn- unpersist-id-state []
  (use-connection
    (with-context [stats-context]
      (:stat-info (es/document-by-id id-state-key)))))

(defn- db-ids
  "Get IDs straight from the DB."
  []
  (use-connection
    (with-context [instance-context]
      (->> (es/document-ids)
           doall))))

(defn instance-count
  "Get the number of total instances in the database.  This result is
  independent of the *dataset split* state."
  []
  (use-connection
    (with-context [instance-context]
      (es/document-count))))

(defn- create-id-list []
  (use-connection
    (with-context [instance-context]
      (when (es/exists?)
        (let [all-ids (db-ids)
              keep-ids (* @population-use (count all-ids))]
          (take keep-ids all-ids))))))

(defn- compute-folds [id-list-data]
  (let [{:keys [current-fold folds]} id-list-data
        [train-key test-key] (if (= 1 (count folds))
                               [:test :train]
                               [:train :test])]
    (->> (concat (take current-fold folds)
                 (drop (+ current-fold 1) folds))
         (apply concat)
         (hash-map test-key (nth folds current-fold) train-key))))

(defn- create-id-data [ids folds]
  (let [fold-size (/ (count ids) folds)]
    (->> (reduce (fn [m fold]
                   (merge m
                          {:left (drop fold-size (:left m))
                           :folds (concat (:folds m)
                                          (list (take fold-size (:left m))))}))
                 {:left ids}
                 (range folds))
         :folds
         (remove empty?)
         vec
         ((fn [folds]
            (let [data {:current-fold 0
                        :folds folds}]
              (merge data
                     {:train-test (compute-folds data)})))))))

(defn- id-list-data []
  (use-connection
    (swap! ids-inst
           #(or %
                (unpersist-id-state)
                (create-id-data (create-id-list) 1)))
    @ids-inst))

(defn ids
  "Return all IDs based on the *dataset split* (see class docs).

  Keys
  ----
  * **:set-type** is either `:train` or `:test` and defaults
  to [[set-default-set-type]] or `:train` if not set"
  [& {:keys [set-type] :or {set-type nil}}]
  (use-connection
    (with-context [instance-context]
      (when (es/exists?)
        (let [set-type (or set-type @default-set-type)
              {:keys [train-test]} (id-list-data)]
          (get train-test set-type))))))

(defn instances
  "Return all instance data based on the *dataset split* (see class docs).

  See [[instance-by-id]] for the data in each map sequence returned.

  Keys
  ----
  * **:set-type** is either `:train` or `:test` and defaults
    to [[set-default-set-type]] or `:train` if not set"
  [& keys]
  (let [conn (connection)]
    (->> (apply ids keys)
         (map #(instance-by-id conn %)))))

(defn stats
  "Get training vs testing *dataset split* statistics."
  []
  (ids)
  (use-connection
    (if @ids-inst
      (let [train (count (:train (:train-test @ids-inst)))
            test (count (:test (:train-test @ids-inst)))]
        {:train train
         :test test
         :split (double (/ train (+ train test)))}))))

(defn- id-info []
  {:train (ids :set-type :train)
   :test (ids :set-type :test)})

(defn set-fold
  "Set the current fold in the *dataset split* state.

  You must call [[divide-by-fold]] before calling this.

  See the namespace docs for more information."
  [fold]
  (use-connection
    (swap! ids-inst
           (fn [data]
             (let [data (or data (id-list-data))
                   folds (:folds data)]
               (if-not folds
                 (throw (ex-info "No folds defined--call `divide-by-fold'" {})))
               (if (>= fold (count folds))
                 (throw (ex-info (str "Not enough folds to set current to " fold)
                                 {:fold fold})))
               (let [data (assoc data :current-fold fold)]
                 (-> data
                     (merge {:train-test (compute-folds data)})
                     (persist-id-state))))))
    (stats)))

(defn divide-by-set
  "Divide the dataset into a test and training *buckets*.

  * **train-ratio** this is the percentage of data in the train bucket, which
  defaults to `0.5`

  Keys
  ----
  * **:shuffle?** if `true` then shuffle the set before partitioning, otherwise
  just update the *demarcation* boundary"
  ([]
   (divide-by-set 0.5))
  ([train-ratio & {:keys [shuffle?] :or {shuffle? true}}]
   (use-connection
     (let [ids (->> (db-ids) ((if shuffle? shuffle identity)))
           sz (count ids)
           train-count (* train-ratio sz)
           id-data {:train-test {:train (take train-count ids)
                                 :test (drop train-count ids)}}]
       (reset! ids-inst id-data)
       (log/infof "shuffled: %s" (stats))
       (persist-id-state id-data)
       (stats)))))

(defn divide-by-preset
  "Divide the data into test and training *buckets*.  The respective train/test
  buckets are dictated by the `:set-type` label given in parameter given to the
  **:create-instances-fn** as documented in [[elasticsearch-connection]]."
  []
  (use-connection
    (with-context [instance-context]
      (->> (es/documents)
           (reduce (fn [{:keys [test train]} {:keys [id doc]}]
                     (let [set-type (:set-type doc)
                           [test train] (if (= "train" set-type)
                                          [test (cons id train)]
                                          [(cons id test) train])]
                       {:train train
                        :test test}))
                   {})
           (hash-map :train-test)
           (reset! ids-inst))
      (log/infof "divided by preset: %s" (stats))
      (persist-id-state @ids-inst)
      (stats))))

(defn divide-by-fold
  "Divide the data into folds and initialize the current fold in the *dataset
  split* state.  Using this kind of dataset split is useful for cross fold
  validation.

  * **folds** number of folds to use, which defaults to 10

  See [[set-fold]]"
  ([]
   (divide-by-fold 10))
  ([folds & {:keys [shuffle?] :or {shuffle? true}}]
   (use-connection
     (let [id-data (-> (create-id-list)
                       ((if shuffle? shuffle identity))
                       (create-id-data folds))]
       (reset! ids-inst id-data)
       (log/infof "shuffled: %s" (stats))
       (persist-id-state id-data)))))

(defn- main
  "In REPL testing"
  [& actions]
  (use-connection
    (->> actions
         (map (fn [action]
                (case action
                  -2 (set-default-connection)
                  -1 (clear :wipe-persistent? true)
                  0 (instances-load)
                  4 (create-id-list)
                  7 (id-info)
                  8 (with-context [stats-context]
                      (pprint (es/describe)))
                  9 (set-population-use 0.7)
                  12 (stats))))
         doall)))
