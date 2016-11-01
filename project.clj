(defproject ig/havoc "0.1.1-SNAPSHOT"
  :description "A resilience testing library for Clojure and Docker"
  :url "https://github.com/IG-Group/Havoc"
  :license {:name "Apache License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0"}
  :plugins [[lein-modules "0.3.11"]]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [byte-streams "0.2.2"]
                 [aysylu/loom "0.6.0"]
                 [clj-http "2.2.0"]
                 [selmer "1.0.7"]
                 [potemkin "0.4.3"]
                 [org.clojure/test.check "0.9.0"]
                 [com.gfredericks/test.chuck "0.2.7"]]
  :modules {:dirs       ["example"]
            :subprocess nil})
