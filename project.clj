(defproject com.zensols.ml/dataset "0.1.0-SNAPSHOT"
  :description "Generate, split into folds or train/test and cache a dataset"
  :url "https://github.com/plandes/clj-nlp-annotation"
  :license {:name "Apache License version 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :distribution :repo}
  :plugins [[lein-codox "0.10.1"]
            [org.clojars.cvillecsteele/lein-git-version "1.0.3"]]
  :codox {:metadata {:doc/format :markdown}
          :project {:name "Generate, split into folds or train/test and cache a dataset"}
          :output-path "target/doc/codox"
          :source-uri "https://github.com/plandes/clj-ml-dataset/blob/v{version}/{filepath}#L{line}"}
  :source-paths ["src/clojure"]
  :javac-options ["-Xlint:unchecked"]
  :dependencies [[org.clojure/clojure "1.8.0"]

                 ;; command line
                 [com.zensols.tools/actioncli "0.0.12"]

                 ;; write dataset report
                 [com.zensols.tools/misc "0.0.4"]

                 ;; elastic search
                 [clojurewerkz/elastisch "2.2.2"]]
  :profiles {:appassem {:aot :all}
             :dev
             {:jvm-opts
              ["-Dlog4j.configurationFile=test-resources/log4j2.xml" "-Xms4g" "-Xmx12g" "-XX:+UseConcMarkSweepGC"]
              :dependencies [[org.apache.logging.log4j/log4j-core "2.7"]
                             [org.apache.logging.log4j/log4j-slf4j-impl "2.7"]
                             [com.zensols/clj-append "1.0.5"]]}})
