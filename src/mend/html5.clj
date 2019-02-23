(ns mend.html5
  (:require [clojure.string :as string]
            [com.rpl.specter :refer [nthpath]]

            [clojure.tools.cli :refer [parse-opts]]

            [instacheck.core :as ebnf]
            [instacheck.cli :as ebnf-cli]

            [hickory.core :as hick]
            [hickory.select :as s]

            ;; Not actually used here, but convenient for testing
            [clojure.pprint :refer [pprint]]
            [clojure.test.check.generators :as gen]
            [com.gfredericks.test.chuck :as chuck]))

;; https://developer.mozilla.org/en-US/docs/Web/HTML/Reference
;; https://developer.mozilla.org/en-US/docs/Web/HTML/Element
;; https://developer.mozilla.org/en-US/docs/Web/HTML/Attributes
;; https://developer.mozilla.org/en-US/docs/Web/HTML/Global_attributes
;; - XML ebnf:
;;   http://bottlecaps.de/rr/ui
;;   https://www.w3.org/TR/2000/REC-xml-20001006
;; - HTML ebnf:
;;   https://github.com/lisp/de.setf.xml/blob/master/bnf/html-grammar.bnf
;; http://w3c.github.io/html/syntax.html#void-elements

(def HTML5-EBNF-PREFIX "data/html5-prefix.ebnf")
(def HTML5-EBNF-BASE "data/html5-base.ebnf")

(def HTML5-ELEMENTS "w3c_html/sections/elements.include")
(def HTML5-ATTRIBUTES "w3c_html/sections/attributes.include")


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parsing W3C standards data

(defn deref-content
  "Recursively pull text content out of hickory data
  (remove internal tags)"
  [h]
  (cond
    (map? h)    (recur (-> h :content))
    (vector? h) (apply str (apply concat (for [c h] (deref-content c))))
    :else       h))

(defn id->name [id]
  (string/replace id #"[^\s]+/" ""))

(defn id->terminal [id]
  (string/replace id #"/" "__"))

(defn item-names
  "Extract items names of the form <{name}>..."
  [s]
  (map second (re-seq #"<[{]([\S]+)[}]>" s)))

(defn header-mangle
  "Translate W3C section table header into map keyword."
  [h]
  (-> h
      deref-content
      (string/replace #"[^A-Za-z0-9_.]" "")
      string/lower-case
      keyword))

(defn field-mangle
  "Translate the W3C section table (hickory) data into map values. The
  specific translation depends on the field name/keyword."
  [k v]
  (condp = k
    ;; Elements (section/attributes.include)
    :element    (item-names (deref-content v))
    :categories (string/split (deref-content v) #"\s*;\s*")
    :attributes (item-names (deref-content v))

    ;; Attributes (section/attributes.include)
    ;; Unlike :element there aren't currently any :attribute fields
    ;; with multiples but make it a sequence to make processing
    ;; symetric (and it could change in the future)
    :attribute  [(string/replace (deref-content v) #"[<{}>]*" "")]
    :elements   (string/split
                  (string/replace (deref-content v) #"[<{}>]*" "")
                  #"\s*[\n;]\s*")
    ;; Extract literals (surrounded by <code>) and
    ;; metadata/descriptive values.
    :value      (let [literals (s/select (s/descendant
                                           (s/tag :code)) v)
                      metas (s/select (s/descendant
                                        (s/and
                                          (s/tag :a)
                                          (s/not (s/has-descendant
                                                   (s/tag :code))))) v)]
                  {:literals (set (map deref-content literals))
                   :metas (set (map deref-content metas))})
    (deref-content v)))

(defn parse-w3c-include
  "Given the raw W3C section HTML for elements on attributes, return
  a list of maps with the parsed data.
 
  Parsing elements.include returns a list of maps each of this form:
  {:element \"a\",
   :description \"Hyperlink\",
   :categories [\"flow\" \"phrasing*\" \"interactive\"],
   :parents \"phrasing\",
   :children \"transparent*\",
   :attributes (\"links/href\" \"links/target\" \"links/download\" ...),
   :interface \"{{HTMLAnchorElement}}\"}

  Parsing attributes.include returns a list of maps each of this form:
  {:attribute \"abbr\",
   :elements [\"th\"],
   :description \"Alternative label to use for the header cell ...\",
   :value {:literals #{},
           :metas #{\"Text\"}}}
  "
  [include-data]
  (let [hickory-data (hick/as-hickory (hick/parse include-data))
        ;; Pull the tables out of the hickory data
        tables (s/select (s/descendant (s/tag :table)) hickory-data)
        ;; Pull out the rows from the first table
        chunks (s/select
                 (s/descendant
                   (s/tag :tr))
                 (first tables))
        ;; Pull out the header and cell data
        rows (for [c chunks] (s/select
                               (s/descendant
                                 (s/or (s/tag :th)
                                       (s/tag :td)))
                               c))
        ;; Mangle the headers and zipmap with the cell data
        headers (map header-mangle (first rows))
        zipped (for [v (rest rows)]
                    (zipmap headers v))
        ;; Mangle the field/cell data
        mangled (for [row zipped]
                  (with-meta
                    (into {} (map (fn [[k v]]
                                    [k (field-mangle k v)]) row))
                    row))
        ;; Split multiple items in element/attribute field (currently
        ;; just elements h1-h6)
        kind (first headers)
        full (for [item mangled
                   iname (get item kind)]
               (assoc item kind iname))]
    full))

;; Void tags do not have a closing tag.
;; http://w3c.github.io/html/syntax.html#void-elements
(defn void-element [elem] (= "empty" (:children elem)))

;; Global attributes apply to "HTML elements"
(defn global-attribute [attr] (some #(= "HTML elements" %) (:elements attr)))

;; Boolean only tags can appear without an assignment value
(defn boolean-attribute [attr] (contains? (-> attr :value :metas) "Boolean attribute"))

;; Special elements appear once and in specific order
(defn special-element [elem] (#{"html" "head" "title" "body"} (:element elem)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EBNF generation

(defn ebnf-tag-rhs
  [element]
  (let [tag-name (:element element)]
    (if (void-element element)
      [;; void tag (no end tag)
       (str "'<" tag-name "' "
            "(<space> " tag-name "-attribute)* "
            "'>'")
;; For now don't emit self-closing void elements
;;     ;; void tag (self-closing mark is allowed)
;;     (str "'<" tag-name "' "
;;          "(<space> " tag-name "-attribute)* "
;;          "'/>'")
       ]
      [;; normal tag (i.e. <tag ...> ... </tag>)
       (str "'<" tag-name "' "
            "(<space> " tag-name "-attribute)* "
            "'>' (element | content)* "
            "'</" tag-name ">'")])))

(defn ebnf-element
  [element]
  (let [lhs "element "
        pre "        "]
    (str
      lhs "= "
      (string/join
        (str "\n" pre "| ")
        (mapcat ebnf-tag-rhs element)))))

(defn ebnf-tag-attrs
  [tag-name attrs & append]
  (let [lhs (str tag-name "-attribute ")
        pre (apply str (repeat (count lhs) " "))
        a-ids (fn [pred? as]
                (sort-by id->name (set (map :attribute (filter pred? as)))))
        bool-attrs (a-ids boolean-attribute attrs)
        other-attrs (a-ids #(not (boolean-attribute %)) attrs)]
    (str
      lhs "= "
      (string/join
        (str "\n" pre "| ")
        (concat
          (for [a-id bool-attrs]
            (str "'" (id->name a-id) "'"))
          (for [a-id other-attrs]
            (str "'" (id->name a-id)
                 "=\"' attr-val-" (id->terminal a-id) " '\"'"))
          append)))))

(defn ebnf-attr-vals
  [attr]
  (let [lhs (str "attr-val-" (id->terminal (:attribute attr)) " ")
        pre (apply str (repeat (count lhs) " "))
        outputs (filter
                  #(not (nil? %))
                  (concat
                    (for [value (sort (-> attr :value :literals))]
                      (str "'" value "'"))
                    (for [value (sort (-> attr :value :metas))]
                      (condp = value
                        "Valid integer" (str "integer")
                        "Valid non-negative integer" (str "non-negative-integer")
                        "Valid floating-point number" (str "floating-point-number")
                        (str "'STUB " value "'")))))]
    (str
      ;; Always have an empty value
      lhs "= ''"
      ;;lhs "= '' | '\"\"'"
      ;; If there are other values then output separator
      (when (> (count outputs) 0)
        (str "\n" pre "| "))
      ;; Output values
      (string/join
        (str "\n" pre "| ")
        outputs))))

(defn ebnf-elements-attributes
  "Takes an element map and an attribute map and returns an EBNF
  grammar for HTML5 elements and attributes (global and non-global)."
  [elements attributes]
  (let [elems (filter (complement special-element) elements)
        unique-attrs (map (comp first val)
                          (group-by :attribute attributes))]
    (str
      (ebnf-element (sort-by :element elems))
      "\n\n"
      (ebnf-tag-attrs "global" (filter global-attribute attributes))
      "\n\n"
      (string/join
        "\n"
        (for [e (sort-by :element elems)
              :let [elem (:element e)
                    attrs (filter #((set (:elements %)) elem)
                                  attributes)]]
          (ebnf-tag-attrs elem attrs "global-attribute")))
      "\n\n"
      (string/join
        "\n"
        (for [a (sort-by :attribute unique-attrs)]
          (ebnf-attr-vals a))))))


(defn ebnf-combined
  "Takes an element map and an attribute map and return an EBNF
  grammar as a string that represents the full HTML5 EBNF syntax."
  [elements attributes]
  (string/join
    "\n\n"
    ["(* This was generated by src/mend/html5.clj *)"
     (slurp HTML5-EBNF-PREFIX)
     (ebnf-elements-attributes elements attributes)
     (slurp HTML5-EBNF-BASE)]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLI

(defn pr-err
  [& args]
  (binding [*out* *err*]
    (apply println args)
    (flush)))

;; Find each :path in a grammar and replace it with :value
;; This allows us to replace stub generators in the grammar with
;; references to generators in a different namespace as just one
;; example.
(def grammar-updates
  [;; Replace the stub CSS value generator with real one
   {:path [:attr-val-style]
    :value {:tag :nt :keyword :css-assignments}}
   ;; Replace the image generator
   {:path [:img-attribute :parsers (nthpath 6) :parsers (nthpath 1)]
    :value {:tag :nt :keyword :misc-gen/image-path}}])

(defn ns-prefix [ctx]
  (str
"(ns " (:namespace ctx) "
   (:require [clojure.test.check.generators :as gen]
             [com.gfredericks.test.chuck.generators :as chuck]
             [rend.misc-generators :as misc-gen]))

;; Generated by mend.html5

"))


(defn grammar->ns
  [ctx grammar]
  (str (ns-prefix ctx)
       (if (:function ctx)
         (ebnf/grammar->generator-func-source ctx grammar)
         (ebnf/grammar->generator-defs-source ctx grammar))))

(def cli-options
  (vec
    (concat
      ebnf-cli/general-cli-options
      [[nil "--namespace NAMESPACE"
        "Name of namespace to generate"]
       [nil "--ebnf-output EBNF-OUTPUT"
        "Write intermediate EBNF to file"]
       [nil "--function FUNCTION"
        "Emit a function named FUNCTION which returns a generator rather than a defn for every generator"]])))

(defn opt-errors [opts]
  (when (:errors opts)
    (doall (map pr-err (:errors opts)))
    (System/exit 2))
  opts)

(defn html5-ns [opts]
  (let [ctx (merge {:weights-res (atom {})
                    :grammar-updates grammar-updates}
                   (select-keys opts [:namespace :css-namespace
                                      :weights :function]))

        _ (pr-err "Generating HTML5 EBNF from files:"
                  HTML5-ELEMENTS
                  HTML5-ATTRIBUTES)
        html5-elements (parse-w3c-include (slurp HTML5-ELEMENTS))
        html5-attributes (parse-w3c-include (slurp HTML5-ATTRIBUTES))
        html5-ebnf-str (ebnf-combined html5-elements html5-attributes)
        _ (when-let [efile (:ebnf-output opts)]
            (pr-err "Saving EBNF to" efile)
            (spit efile html5-ebnf-str))

        ;; The following each take 4-6 seconds
        _ (pr-err "Loading HTML5 grammar from EBNF")
        html5-grammar (ebnf/load-grammar html5-ebnf-str)
        _ (pr-err "Converting HTML5 grammar to generators")
        ns-str (grammar->ns ctx html5-grammar)]

    (when-let [wfile (:weights-output opts)]
      (pr-err "Saving weights to" wfile)
      (ebnf-cli/save-weights ctx (:weights-output opts)))

    ns-str))

(defn -main [& args]
  (let [opts (:options (opt-errors (parse-opts args cli-options)))]
    (when (not (:namespace opts))
      (pr-err "--namespace NAMESPACE required")
      (System/exit 2))

    (println (html5-ns opts))))

