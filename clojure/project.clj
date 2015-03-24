(defproject multimux "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]]
  :resource-paths  ["lib/jediterm-pty-2.0.jar" "lib/guava-14.0.1.jar" "lib/pty4j-0.3.jar"
                    "lib/log4j-1.2.14.jar" "lib/jna.jar" "lib/jna-platform.jar"
                    "lib/purejavacomm-0.0.17.jar"
                    ]
  :jvm-opts ["-Dsun.java2d.pmoffscreen=false"]
  :main ^:skip-aot multimux.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
