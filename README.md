Generate, Split Into Folds or Train/Test and Cache a Dataset
============================================================
This is a small simple library that automates the parsing and caching of the
parsed utterances in [Elasticsearch](https://www.elastic.co).

This library can be used to create an dataset but was written with natural
language processing problems in mind.  When creating machine learning models
for use with NLP you often you need to parse a training set data over and over.
The output of the parsing become input features to your model, but the parsing
step can take a while and if the parsing pipeline changes it has to be
repeated.

This library is designed to make this process less painful.

Obtaining
---------
In your `project.clj` file, add:

[![Clojars Project](https://clojars.org/com.zensols.ml/dataset/latest-version.svg)](https://clojars.org/com.zensols.ml/dataset/)

Documentation
-------------
Additional [documentation](https://plandes.github.io/clj-ml-dataset/codox/index.html).

Setup
-----
If you don't have an Elasticsearch instance handy I recommend you use the
docker image and SSH tunnel the port so you don't have to configure that
separately.  Do do this:

1. Install [docker](https://docs.docker.com/engine/installation/)
2. Start the machine (you might need to run initial *getting started quickly*
   terminal program): ```bash docker machine start```
3. Start the Elasticsearch image (for the first time you'll have to wait for
   the image to download): ```bash docker-compose up -d```
4. Docker runs a virtual machine to host an image, so you to avoid
   (re)configuring a dynamic IP address port forward/tunnel it instead: ```base
   src/bin/docker-tunnel.sh```
5. Start the repl to your example program: ```bash lein repl```

Example
-------
See the [example repo](https://github.com/plandes/clj-example-nlp-ml) that
illustrates how to use this library and contains the code from these examples
originate.  It's highly recommended to clone it and follow along as you peruse
this README.

Usage
-----
First create a namespace to use as your *database* library.

### Write a Corpus Access Namespace
```clojure
(ns zensols.example.anon-db
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:require [zensols.actioncli.dynamic :refer (dyn-init-var) :as dyn]
            [zensols.actioncli.log4j2 :as lu]
            [zensols.nlparse.parse :as p]
            [zensols.dataset.db :as db :refer (with-connection)]))

(defn- parse-utterances [add-fn]
  (doseq [file [(io/file "answers")
                (io/file "questions")]]
    (with-open [reader (io/reader file)]
      (->> reader
           (line-seq)
           (map p/parse)
           (map #(add-fn % (:class-label (.getName file))))
           doall))))

(defn- connection []
  (swap! conn-inst #(or % (db/elasticsearch-connection
                           "example"
                           :create-instances-fn parse-utterances))))

(defn- load-corpora []
  (with-connection (connection)
    (db/instances-load)))

(defn anons []
  (with-connection (connection)
    (db/instances)))
```

### Using on the REPL
Now you only need to load the corpora once, then you can get it back and it
caches in memory on the first read access:
```clojure
user> (require '[zensols.example.anon-db])
user> (load-corpora)
user> (->> (anons) first)
=> {:class-label answer, :annotation {:text ...
user> (count (anons))
100
=> (with-connection (connection) (divide-by-set 0.75))
=> (count (anons))
75
=> (count (anons :set-type :test))
25
```

License
--------
Copyright © 2016 Paul Landes

Apache License version 2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

[http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
