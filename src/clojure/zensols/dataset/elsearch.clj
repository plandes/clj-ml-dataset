(ns ^{:doc "A *client simple* wrapper for an Elasticsearch wrapper.  You
probably want use the more client friendly [[zensols.dataset.db]]."
      :author "Paul Landes"}
    zensols.dataset.elsearch
  (:require [clojure.pprint :refer (pprint)]
            [clojure.tools.logging :as log]
            [clojure.set :refer (rename-keys)])
  (:require [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.index :as esi]
            [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.query :as q]
            [clojurewerkz.elastisch.rest.response :as esrsp]))

(def ^{:private true :dynamic true}
  *context* nil)

(defn create-context
  "Create a new context to be used with [[with-context]].

  Parameters
  ----------
  * **index-name** the name of the Elasticsearch index
  * **mapping-type** map type name (see ES docs)

  Keys
  ----
  * **:url** the URL to the DB (defaults to `http://localhost:9200`)
  * **:mapping-type-defs** metadata (see ES docs)"
  [index-name mapping-type
                      & {:keys [url mapping-type-defs settings]
                         :or {url "http://localhost:9200"
                              mapping-type-defs {:properties {mapping-type {}}}
                              }}]
  {:index-name index-name
   :mapping-type mapping-type
   :mapping-type-defs mapping-type-defs
   :settings settings
   :url url})

(defn- context []
  (if (nil? *context*)
    (throw (ex-info "No context bound" {})))
  *context*)

(defmacro with-context
  "Execute a body with the form (with-context [context <keys>] ...)

* **context** is created with [[create-context]]
* **keys...** option keys to override what was giving in [[create-context]]"
  {:style/indent 1}
  [exprs & forms]
  (let [[raw-context- & ckeys-] exprs]
    `(let [qkeys# (apply hash-map (quote ~ckeys-))
           context# (merge ~raw-context- qkeys#)]
       (binding [*context* context#]
         ~@forms))))

(defn context-nil? []
  (let [cnil? (nil? *context*)]
    (log/debugf "context nil?: %s" cnil?)
    cnil?))

(defn- connection []
  (esr/connect (:url (context))))

(defn create-index
  "Create a new Elasticsearch index."
  []
  (let [{:keys [properties index-name mapping-type-defs settings]} (context)]
    (log/infof "mapping: <%s>" mapping-type-defs)
    (apply esi/create (concat [(connection) index-name :mappings mapping-type-defs]
                              (if settings [:settings settings])))))

(defn delete-mapping
  "Delete an Elasticsearch mapping."
  []
  (let [{:keys [index-name mapping-type]} (context)]
    (log/debugf "delete mapping index-name: %s, mapping-type: %s"
                index-name mapping-type)
    (esi/delete-mapping (connection) index-name mapping-type)))

(defn delete-index
  "Delete an Elasticsearch index."
  []
  (let [{:keys [index-name]} (context)]
    (esi/delete (connection) index-name)))

(defn recreate-index
  "Delete an then create Elasticsearch index."
  []
  (delete-index)
  (create-index))

(defn describe
  "Get the mapping (provide info) about the index."
  []
  (let [{:keys [index-name]} (context)]  
    (esi/get-mapping (connection) index-name)))

(defn- assert-success [res]
  (if-let [err (:error res)]
    (throw (ex-info (->> err :root_cause first :reason)
                    {:error err}))
    res))

(defn put-document
  "Add a document to Elasticsearch."
  ([doc]
   (put-document nil doc))
  ([id doc]
   (let [{:keys [index-name mapping-type]} (context)]
     (log/debugf "adding %s:%s:%s <%s>" index-name mapping-type id (pr-str doc))
     (->> (if id
            (esd/put (connection) index-name mapping-type id doc)
            (esd/create (connection) index-name mapping-type doc))
          assert-success))))

(defn delete-document
  [id]
  (let [{:keys [index-name mapping-type]} (context)]
    (log/debugf "deleting: %s/%s/%s" index-name mapping-type id)
    (->> (esd/delete (connection) index-name mapping-type id)
         assert-success)))

(defn exists? []
  (let [{:keys [index-name]} (context)]
    (esi/exists? (connection) index-name)))

(defn document-by-id
  "Return a document by its ID."
  [id]
  (let [{:keys [index-name mapping-type]} (context)]
    (->> (esd/get (connection) index-name mapping-type (String/valueOf id))
         assert-success
         :_source)))

(defn search-literal
  "Return a lazy sequence of documents.
  Scanning is the underlying elastic search method here."
  [query]
  (let [{:keys [index-name mapping-type]} (context)
        conn (connection)]
    (->> (esd/search conn index-name mapping-type query)
         assert-success)))

(defn search
  "Return a lazy sequence of documents.
  Scanning is the underlying elastic search method here."
  [query]
  (->> (merge query {:search_type "query_then_fetch"
                     :scroll "1m"})
       search-literal 
       (esd/scroll-seq (connection))
       (map #(-> %
                 (select-keys [:_id :_source])
                 (rename-keys {:_id :id :_source :doc})))))

(defn document-count
  "Return the total number of documents in the DB."
  []
  (let [{:keys [index-name mapping-type]} (context)]
    (->> (esd/search (connection) index-name mapping-type :query (q/match-all))
         assert-success
         (esrsp/total-hits))))

(defn document-ids
  "Return document IDs only.  The query default to `match_all`."
  ([]
   (document-ids (q/match-all)))
  ([query]
   (->> (search {:query query
                 :fields []})
        (map :id))))

(defn documents
  "Return all documents as a lazy sequence."
  []
  (search {:query (q/match-all)}))
