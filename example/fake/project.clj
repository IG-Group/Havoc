(defproject fake "0.1.0-SNAPSHOT"
  :plugins [[lein-modules "0.3.11"]]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [compojure "1.5.1"]
                 [ring/ring-jetty-adapter "1.5.0"]
                 [ig/havoc "0.1.1"]
                 [ymilky/franzy "0.0.1"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [ch.qos.logback/logback-classic "1.1.7"]
                 [org.slf4j/log4j-over-slf4j "1.7.14"]
                 [org.slf4j/jul-to-slf4j "1.7.14"]
                 [org.slf4j/jcl-over-slf4j "1.7.14"]]
  :main fake.core
  :aot :all)