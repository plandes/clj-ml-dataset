# Generate, Split Into Folds or Train/Test and Cache a Dataset

[![Travis CI Build Status][travis-badge]][travis-link]

  [travis-link]: https://travis-ci.org/plandes/clj-ml-dataset
  [travis-badge]: https://travis-ci.org/plandes/clj-ml-dataset.svg?branch=master

This is a small simple library that automates the parsing and caching of the
parsed utterances in [Elasticsearch].

This library can be used to create an dataset but was written with natural
language processing problems in mind.  When creating machine learning models
for use with NLP you often you need to parse a training set data over and over.
The output of the parsing become input features to your model, but the parsing
step can take a while and if the parsing pipeline changes it has to be
repeated.

This library is designed to make this process less painful.

Features:

* Create training/test split datasets.
* Store dataset in ElasticSearch with any data structure.
* Supports stratification by class label.
* Sort datasets by a split ratio, or by folds (useful for cross-fold validation).
* Provides dataset statistics, per class spreadsheet dataset creation and other
  metrics.
* Stores optional class label and ID (unique created by ElasticSearch if
desired).
* Integrates with
  the [machine learning framework](https://github.com/plandes/clj-ml-model).


<!-- markdown-toc start - Don't edit this section. Run M-x markdown-toc-refresh-toc -->
## Table of Contents

- [Obtaining](#obtaining)
- [Documentation](#documentation)
- [Setup](#setup)
- [Example](#example)
- [Usage](#usage)
    - [Write a Corpus Access Namespace](#write-a-corpus-access-namespace)
    - [Using on the REPL](#using-on-the-repl)
- [File System Based Data Store](#file-system-based-data-store)
- [Future Enhancements](#future-enhancements)
- [Known Bugs](#known-bugs)
- [Building](#building)
- [Changelog](#changelog)
- [License](#license)

<!-- markdown-toc end -->


## Obtaining

In your `project.clj` file, add:

[![Clojars Project](https://clojars.org/com.zensols.ml/dataset/latest-version.svg)](https://clojars.org/com.zensols.ml/dataset/)


## Documentation

API [documentation](https://plandes.github.io/clj-ml-dataset/codox/index.html).


## Setup

**Note**: [ElasticSearch] is no longer necessary as there's a
new [file sysetm](#file-system-based-data-store) based data store.

If you don't have an ElasticSearch instance handy I recommend you use the
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


## Example

See the [example repo](https://github.com/plandes/clj-example-nlp-ml) that
illustrates how to use this library and contains the code from where these
examples originate.  It's highly recommended to clone it and follow along as
you peruse this README.


## Usage

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

Also see the [test case](test/zensols/dataset/thaw_test.clj).


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

## File System Based Data Store

This library now has a way to unpersist data from JSON text in
the
[zensols.dataset.thaw](https://plandes.github.io/clj-ml-dataset/codox/zensols.dataset.thaw.html) namespace.
This can either be program/hand generated or you can "serialize" this to disk
using
[`freeze-dataset`](https://plandes.github.io/clj-ml-dataset/codox/zensols.dataset.db.html#freeze-dataset).

See the [test case](test/zensols/dataset/thaw_test.clj).


## Future Enhancements

What's nneded is a all
the
[zensols.dataset.db/*](https://plandes.github.io/clj-ml-dataset/codox/zensols.dataset.db.html) functions
to be multi-method so you don't need to switch between namespaces to get
different data sources (i.e. [ElasticSearch] vs file system).  It's possible to
refactor these namespaces so that the function is only written once in some
places (i.e. `instance-by-id`) and multi-methods for the rest based on
connection type.


## Building

To build from source, do the folling:

- Install [Leiningen](http://leiningen.org) (this is just a script)
- Install [GNU make](https://www.gnu.org/software/make/)
- Install [Git](https://git-scm.com)
- Download the source: `git clone --recurse-submodules https://github.com/plandes/clj-ml-dataset && cd clj-ml-dataset`


## Changelog

An extensive changelog is available [here](CHANGELOG.md).


## License

MIT License

Copyright (c) 2016 - 2018 Paul Landes

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.


<!-- links -->
[Elasticsearch]: https://www.elastic.co
