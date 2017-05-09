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
            [clojure.string :as s]
            [clojure.set :refer (rename-keys)]
            [clojure.data.csv :as csv])
  (:require [clj-excel.core :as excel])
  (:require [zensols.actioncli.dynamic :as dyn]
            [zensols.actioncli.resource :as res]
            [zensols.util.string :as zs]
            [zensols.util.spreadsheet :as ss]
            [zensols.dataset.elsearch :refer (with-context) :as es]))

(def instance-key :instance)

(def class-label-key :class-label)

(def ^:private dataset-index-name "dataset")

(def ^:private stat-index-name "stat-info")

(def ^:private es-class-label-key class-label-key)

(def ^:private id-state-key "id-state")

(def ^{:private true :dynamic true} *connection* nil)

(def ^{:private true :dynamic true}
  *load-set-types*
  "Used to temporarily store key stat data for [[instances-load]].")

(defonce default-connection-inst (atom nil))

(defn elasticsearch-connection
  "Create a connection to the dataset DB cache.

Parameters
----------
* **index-name** the name of the Elasticsearch index

Keys
----
* **:create-instances-fn** a function that computes the instance
set (i.e. parses the utterance) and invoked by [[instances-load]]; this
function takes a single argument, which is also a function that is used to
load utterance in the DB; this function takes the following forms:
    * (fn [instance class-label] ...
    * (fn [id instance class-label] ...
    * (fn [id instance class-label set-type] ...
        * **id** the unique identifier of the data point
        * **instance** is the data set instance (can be an `N`-deep map)
        * **class-label** the label of the class (can be nominal, double, integer)
        * **set-type** either `:test`, `:train`, `:train-test` (all) used to presort the data
        with [[divide-by-preset]]; note that it isn't necessary to
        call [[divide-by-preset]] for the first invocation of [[instances-load]]

  * **:url** the URL to the DB (defaults to `http://localhost:9200`)
  * **mapping-type** map type name (see ES docs)

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
   {:keys [create-instances-fn population-use set-type url mapping-type-def]
    :or {create-instances-fn identity
         population-use 1.0
         set-type :train
         mapping-type-def {instance-key {:type "nested"}
                           class-label-key {:type "string"
                                            :index "not_analyzed"}}
         url "http://localhost:9200"}}]
  {:index-name index-name
   :ids-inst (atom nil)
   :default-set-type (atom set-type)
   :population-use (atom population-use)
   :instance-context (es/create-context
                      index-name dataset-index-name
                      :url url
                      :settings {"index.mapping.ignore_malformed" true}
                      :mapping-type-defs
                      {dataset-index-name {:properties mapping-type-def}})
   :stats-context (es/create-context
                   index-name "stats"
                   :url url
                   :settings {"index.mapping.ignore_malformed" true}
                   :mapping-type-defs
                   {stat-index-name {:properties {:stats {:type "nested"}}}})
   :create-instances-fn create-instances-fn})

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
         ~'index-name (:index-name conn#)
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

  * **:set-type** is either `:train` (default) or `:test`;
  see [[elasticsearch-connection]]

  See [[ids]]"
  [set-type]
  (use-connection
    (reset! default-set-type set-type)))

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
       (->> (es/document-by-id id))))))

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
      (es/put-document id-state-key id-state)))
  id-state)

(defn- unpersist-id-state []
  (use-connection
    (with-context [stats-context]
      (es/document-by-id id-state-key))))

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
  * **:set-type** is either `:train`, `:test`, `:train-test` (all) and defaults
  to [[set-default-set-type]] or `:train` if not set"
  [& {:keys [set-type]}]
  (use-connection
    (with-context [instance-context]
      (when (es/exists?)
        (let [set-type (or set-type @default-set-type)
              {:keys [train-test]} (id-list-data)]
          (if (= set-type :train-test)
            (apply concat (vals train-test))
            (get train-test set-type)))))))

(defn instances
  "Return all instance data based on the *dataset split* (see class docs).

  See [[instance-by-id]] for the data in each map sequence returned.

  Keys
  ----
  * **:set-type** is either `:train`, `:test`, `:train-test` (all) and defaults
  to [[set-default-set-type]] or `:train` if not set
  * **:include-ids?** if non-`nil` return keys in the map as well"
  [& {:keys [set-type include-ids? id-set]}]
  (let [conn (connection)]
    (->> (or id-set (ids :set-type set-type))
         (map (if include-ids?
                #(assoc (instance-by-id conn %) :id %)
                #(instance-by-id conn %))))))

(defn stats
  "Get training vs testing *dataset split* statistics."
  []
  (ids)
  (use-connection
    (if @ids-inst
      (let [train (count (:train (:train-test @ids-inst)))
            test (count (:test (:train-test @ids-inst)))
            total (+ train test)]
        {:train train
         :test test
         :split (if (= 0 total)
                  0.0
                  (double (/ train total)))}))))

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
                       (log/debugf "%s: %s" set-type id)
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
       (persist-id-state id-data)
       (stats)))))

(defn- put-instance
  "Write an instance to the DB.

  The framework is designed to use [[instances-load]] instead."
  ([instance class-label]
   (put-instance nil instance class-label nil))
  ([id instance class-label]
   (put-instance id instance class-label nil))
  ([id instance class-label set-type]
   (log/debugf "loading instance (%s): %s => <%s>"
               id class-label (zs/trunc instance))
   (log/tracef "instance: %s" instance)
   (use-connection
     (with-context [instance-context]
       (let [doc (merge {class-label-key class-label instance-key instance}
                        (if set-type {:set-type set-type}))
             res (if id
                   (es/put-document id doc)
                   (es/put-document doc))]
         (when set-type
           (.add (get *load-set-types* set-type) (:_id res))))))))

(defn instances-load
  "Parse and load the dataset in the DB."
  [& {:keys [recreate-index]
      :or {recreate-index true}}]
  (use-connection
    (with-context [instance-context]
      (if recreate-index
        (es/recreate-index))
      ;; Elasticsearch queues inserts so avoid the user having to invoke
      ;; `divide-by-preset` since all records might not clear by the time
      ;; they're indexed and added to the stats index
      (binding [*load-set-types* {:train (java.util.LinkedList.)
                                  :test (java.util.LinkedList.)}]
        ;; if put-instance is private use a lambda form
        (log/infof "loading instances...")
        ((:create-instances-fn (connection)) put-instance)
        (let [{:keys [test train]} *load-set-types*]
          (when (or (not (empty? train)) (not (empty? test)))
            ;; almost never get to hear since ES is still loading and will
            ;; return no results
            (log/infof "divide: %d: train: %d, test: %d"
                       (+ (count train) (count test)))
            (reset! ids-inst {:train-test {:train (lazy-seq train)
                                           :test (lazy-seq test)}})
            (persist-id-state @ids-inst)))))))

(defn distribution
  "Return maps representing the data set distribution by class label.  Each
  element of the returned sequence has the following keys:

  * **:class-label** the class-label fo the instances
  * **:count** the number of instances for **:class-label**"
  []
  (use-connection
    (with-context [instance-context]
      (->> (es/buckets es-class-label-key)
           (map #(rename-keys % {:name class-label-key}))))))

(defn instances-by-class-label
  "Return a map with class-labels for keys and corresponding instances for that
  class-label.

  Keys
  ----
  * **:max-instances** the maximum number of instances per class
  * **:type* the data to grab, which is one of the following symbols:
    * **ids** returns only IDs (not the document data)
    * **document** returns the document data (`:class-label` and `:instance` keys)
  * **:seed** if given, seed the random number generator, otherwise don't
  return random documents"
  [& {:keys [max-instances type seed]
      :or {max-instances Integer/MAX_VALUE
           type 'document}}]
  (use-connection
    (with-context [instance-context]
      (letfn ([query [class-label]
               (merge {:query
                       (if seed
                         {:function_score
                          {:filter {:term {es-class-label-key class-label}}
                           :functions [{:random_score
                                        {:seed seed}}]}}
                         {:term {es-class-label-key class-label}})}
                      (if (= type 'ids) {:fields []}))])
        (->> (distribution)
             (map class-label-key)
             (map (fn [class-label]
                    (->> (query class-label)
                         es/search
                         (take max-instances)
                         (map (case type
                                document #(-> % :doc)
                                ids #(-> % :id Integer/parseInt)))
                         (array-map class-label))))
             (into {})
             doall)))))

(defn- divide-by-uneven-distribution-set
  "Divide the dataset into a test and training *buckets*.

  * **train-ratio** this is the percentage of data in the train bucket.

  Keys
  ----
  * **:shuffle?** if `true` then shuffle the set before partitioning, otherwise
  just update the *demarcation* boundary"
  [train-ratio & {:keys [shuffle? ids
                         max-instances]
                  :or {shuffle? true
                       max-instances Integer/MAX_VALUE}}]
  (use-connection
    (let [ids (->> (or ids (db-ids))
                   ((if shuffle? shuffle identity))
                   (take max-instances))
          sz (count ids)
          train-count (* train-ratio sz)
          id-data {:train-test {:train (take train-count ids)
                                :test (drop train-count ids)}}]
      (reset! ids-inst id-data)
      (log/infof "divided by set: %s" (stats))
      (persist-id-state id-data)
      (stats))))

(defn- divide-by-class-distribution-set
  "Just like [[divide-by-uneven-distribution-set]] but each test and training set
  has an even distribution by class label.

  The **keys** parameter(s) are described in [[divide-by-set]]
  and [[instances-by-class-label]]."
  [train-ratio & opts]
  (use-connection
    (with-context [instance-context]
      (let [opts (concat opts [:type 'ids])
            {:keys [shuffle?] :or {shuffle? true}} (apply hash-map opts)]
        (->> opts
             (apply instances-by-class-label)
             vals
             (reduce (fn [{:keys [train test]} ids]
                       (let [sz (count ids)
                             train-count (* train-ratio sz)]
                         {:train (concat train (take train-count ids))
                          :test (concat test (drop train-count ids))}))
                     {})
             ((if shuffle?
                (fn [{:keys [test train]}]
                  {:test (shuffle test)
                   :train (shuffle train)})
                identity))
             (array-map :train-test)
             (reset! ids-inst)
             persist-id-state))
      (let [stats (stats)]
        (log/infof "divided by set with even distribution by class: %s" stats)
        stats))))

(defn divide-by-set
  "Divide the dataset into a test and training *buckets*.

  * **train-ratio** this is the percentage of data in the train bucket, which
  defaults to `0.5`

  Keys
  ----
  * **:dist-type** one of the following symbols:
      *even* each test/training set has an even distribution by class label
      *uneven* each test/training set has an uneven distribution by class label
  * **:shuffle?** if `true` then shuffle the set before partitioning, otherwise
  just update the *demarcation* boundary
  * **:max-instances** the maximum number of instances per class
  * **:seed** if given, seed the random number generator, otherwise don't
  return random documents"
  ([]
   (divide-by-set 0.5))
  ([train-ratio & {:keys [dist-type shuffle? max-instances seed]
                    :as opts
                    :or {shuffle? true
                         dist-type 'uneven}}]
   (-> (case dist-type
         even divide-by-class-distribution-set
         uneven divide-by-uneven-distribution-set)
       (apply (apply concat (list train-ratio) opts)))))

(defn dataset-file []
  (use-connection
    (->> (format "%s-dataset.xls" index-name)
         (res/resource-path :analysis-report))))

(defn- rows-counts-by-class-label []
  (->> (instances-by-class-label)
       (reduce (fn [{:keys [total rows]} [name insts]]
                 (let [cnt (count insts)]
                   {:rows (conj rows [name cnt])
                    :total (+ total cnt)}))
               {:rows [] :total 0})
       ((fn [{:keys [rows total]}]
          (->> (sort-by second rows)
               reverse
               (#(concat % [["Total" total]])))))
       (cons ["Label" "Count"])))

(defn write-dataset
  "Write the data set to a spreadsheet.  If the file name ends with a `.csv` a
  CSV file is written, otherwise an Excel file is written.

  Keys
  ----
  * **:output-file** where to write the file and defaults to
  [[res/resource-path]] `:analysis-report`
  * **:single?** if `true` then create a single sheet, otherwise the training
  and testing *buckets* are split between sheets"
  [& {:keys [output-file single? instance-fn columns-fn]
      :or {instance-fn identity
           columns-fn (constantly ["Instance"])}}]
  (use-connection
    (let [output-file (or output-file (dataset-file))
          csv? (re-matches #".*\.csv$" (.toString output-file))]
      (letfn [(data-set [set-type fields col-names header? headerize?]
                (let [insts (instances :set-type set-type :include-ids? true)
                      header-inst (first insts)]
                  (->> insts
                       (map (fn [{:keys [class-label id instance]}]
                              (->> (instance-fn instance)
                                   (concat fields [class-label id]))))
                       ((if header?
                          #(cons (concat col-names
                                         ["Label" "Id"]
                                         (columns-fn header-inst))
                                 %)
                          identity))
                       ((if headerize? ss/headerize identity)))))]
        (if csv?
          (with-open [writer (io/writer output-file)]
            (->> (concat (data-set :train ["train"] ["Set Type"] true false)
                         (data-set :test ["test"] nil false false))
                 (csv/write-csv writer)))
          (-> (excel/build-workbook
               (excel/workbook-hssf)
               (if single?
                 {"Train and Test"
                  (concat (data-set :train ["train"] ["Set Type"] true true)
                          (data-set :train ["test"] ["Test"] false false))}
                 {"Train" (data-set :train nil nil true true)
                  "Test" (data-set :test nil nil true true)
                  "Counts By Label" (-> (rows-counts-by-class-label)
                                        ss/headerize)}))
              (ss/autosize-columns)
              (excel/save output-file)))))))
