(ns pogonos.render
  (:require [clojure.string :as str]
            [pogonos.partials-resolver :as pres]
            [pogonos.nodes :as nodes]
            [pogonos.parse :as parse]
            [pogonos.protocols :as proto]
            [pogonos.read :as read]
            [pogonos.stringify :as stringify])
  #?(:clj
     (:import [pogonos.nodes
               Inverted Partial Root Section UnescapedVariable Variable])))

(def ^:dynamic *partials-resolver*
  #?(:clj (pres/file-partials-resolver)))

(defn escape [s]
  (str/replace s #"[&<>\"']"
               #({"&" "&amp;", "<" "&lt;", ">" "&gt;"
                  "\"" "&quot;", "'" "&#39;"}
                 (str %))))

(defn lookup [ctx keys]
  (if (seq keys)
    (let [k (first keys)]
      (when-let [x (->> ctx
                        (filter #(and (map? %) (contains? % k)))
                        first)]
        (get-in x keys)))
    (first ctx)))

(defn- render-variable [ctx out var unescaped?]
  (let [val (lookup ctx (:keys var))
        escape-fn (if unescaped? identity escape)]
    (if (fn? val)
      (parse/parse (read/make-string-reader (str (val)))
                   #(proto/render % ctx (comp out escape-fn)))
      (out (escape-fn (str val))))))

(defn render [ctx out x {:keys [partials]}]
  (binding [*partials-resolver* (or partials *partials-resolver*)]
    (proto/render x ctx out)))

(extend-protocol proto/IRenderable
  #?(:clj Object :cljs object)
  (render [this ctx out])

  #?(:clj String :cljs string)
  (render [this ctx out]
    (out this))

  #?(:clj Root :cljs nodes/Root)
  (render [this ctx out]
    (doseq [node (:body this)]
      (proto/render node ctx out)))

  #?(:clj Variable :cljs nodes/Variable)
  (render [this ctx out]
    (render-variable ctx out this (:unescaped? this)))

  #?(:clj UnescapedVariable :cljs nodes/UnescapedVariable)
  (render [this ctx out]
    (render-variable ctx out this true))

  #?(:clj Section :cljs nodes/Section)
  (render [this ctx out]
    (let [val (lookup ctx (:keys this))]
      (cond (not val) nil

            (map? val)
            (doseq [node (:nodes this)]
              (proto/render node (cons val ctx) out))

            (and (coll? val) (sequential? val))
            (when (seq val)
              (doseq [e val, node (:nodes this)]
                (proto/render node (cons e ctx) out)))

            (fn? val)
            (let [body (val (stringify/stringify (:nodes this)))]
              (binding [parse/*open-delim* (or (:open (meta this))
                                               parse/default-open-delim)
                        parse/*close-delim* (or (:close (meta this))
                                                parse/default-close-delim)]
                (parse/parse* (read/make-string-reader body)
                              #(proto/render % ctx out))))

            :else
            (doseq [node (:nodes this)]
              (proto/render node ctx out)))))

  #?(:clj Inverted :cljs nodes/Inverted)
  (render [this ctx out]
    (let [val (lookup ctx (:keys this))]
      (when (or (not val)
                (and (coll? val) (sequential? val) (empty? val)))
        (doseq [node (:nodes this)]
          (proto/render node ctx out)))))

  #?(:clj Partial :cljs nodes/Partial)
  (render [this ctx out]
    (if-let [r (pres/resolve *partials-resolver* (:name this))]
      (parse/parse r
                   (fn [node]
                     (proto/render node ctx out)
                     ;; FIXME: Should interrupt during reading or parsing time
                     (when (and (string? node) (str/ends-with? node "\n"))
                       (proto/render (:indent this) ctx out))))
      (assert false (str "Partial named \"" (:name this) "\" not found")))))
