(defproject kanaka/html5-css3-ebnf "0.6.4"
  :description "Generate EBNF grammars for HTML5 and CSS3 using W3C spec data."
  :url "http://example.com/FIXME"
  :license {:name "Mozilla Public License version 2"
            :url "https://www.mozilla.org/en-US/MPL/2.0/"}

  :source-paths ["src"]
  :resource-paths ["resources" "data"]

  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/tools.cli "0.4.1"]
                 [org.clojure/data.json "0.2.6"]

                 [hickory "0.7.0"]
                 [frankiesardo/linked "1.3.0"]

                 [kanaka/instacheck "0.9.1"]

                 ;; Patched version (retain comments, parsed path log)
                 [kanaka/instaparse "1.4.9.3"]]

  :profiles {:parse  {:main html5-css3-ebnf.parse}
             :html5  {:main html5-css3-ebnf.html5}
             :css3   {:main html5-css3-ebnf.css3}
             :mangle {:main html5-css3-ebnf.html-mangle}}

  :main html5-css3-ebnf.parse)
