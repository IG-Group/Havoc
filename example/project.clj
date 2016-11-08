(defproject example "0.1.0-SNAPSHOT"
  :plugins [[lein-modules "0.3.11"]]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [ig/havoc "0.1.1-SNAPSHOT"]
                 [ch.qos.logback/logback-classic "1.1.7"]
                 [org.slf4j/log4j-over-slf4j "1.7.14"]
                 [org.slf4j/jul-to-slf4j "1.7.14"]
                 [org.slf4j/jcl-over-slf4j "1.7.14"]]
  :aliases {"go" ["do" ["modules" "uberjar"] "test"]})