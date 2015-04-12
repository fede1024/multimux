(defproject multimux "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.apache.avro/avro "1.7.7"]
                 [com.taoensso/timbre "3.4.0"]
                 [log4j "1.2.16"]
                 [com.google.guava/guava "14.0.1"]]
  :resource-paths  ["lib/jediterm-pty-2.0.jar" "lib/guava-14.0.1.jar"]
  :java-source-paths ["src/java/"]
  :jvm-opts ["-Dsun.java2d.pmoffscreen=false"]
  :main ^:skip-aot multimux.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
