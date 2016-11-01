(defproject fake "0.1.0-SNAPSHOT"
            :plugins [[lein-modules "0.3.11"]]
            :dependencies [[org.clojure/clojure "1.8.0"]
                           [compojure "1.5.1"]
                           [ring/ring-jetty-adapter "1.5.0"]
                           [ig/havoc "0.1.1-SNAPSHOT"]]
            :main fake.core
            :aot :all)