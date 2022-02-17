(ns pogonos.api
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pogonos.core :as pg]
            [pogonos.error :as error]
            [pogonos.output :as out]
            [pogonos.reader :as reader])
  (:import [java.io File PushbackReader]
           [java.util.regex Pattern]))

(defn render [{:keys [string file resource data data-file output] :as opts}]
  (let [data (or (when data-file
                   (with-open [r (-> (io/reader (str data-file))
                                     PushbackReader.)]
                     (edn/read r)))
                 data)
        out (if output
              (out/to-file (str output))
              (out/to-stdout))
        opts' (-> opts
                  (assoc :output out)
                  (dissoc :string :file :resource :data :data-file))]
    (cond string (pg/render-string string data opts')
          file (pg/render-file (str file) data opts')
          resource (pg/render-resource (str resource) data opts')
          :else (pg/render-input (reader/->reader *in*) data opts'))))

(def ^:private ^:const path-separator
  (re-pattern (Pattern/quote (System/getProperty "path.separator"))))

(defn- str->matcher [s]
  (comp boolean (partial re-find (re-pattern s))))

(defn- check-inputs [inputs include? exclude? opts]
  (let [include? (or include? (constantly true))
        exclude? (or exclude? (constantly false))]
    (doseq [input inputs
            :when (and (include? input) (not (exclude? input)))]
      (with-open [r (reader/->reader input)]
        (pg/check-input r opts)))))

(defn- check-files [files {:keys [file-regex file-exclude-regex] :as opts}]
  (let [include? (when file-regex
                   (comp (str->matcher file-regex) #(.getPath ^File %)))
        exclude? (when file-exclude-regex
                   (comp (str->matcher file-exclude-regex) #(.getPath ^File %)))]
    (check-inputs (map io/file files) include? exclude? opts)))

(defn- check-dirs [dirs opts]
  (-> (for [dir dirs
            ^File file (file-seq (io/file dir))
            :when (.isFile file)]
        file)
      (check-files opts)))

(defn- split-path [path]
  (if (sequential? path)
    (mapv str path)
    (str/split (str path) path-separator)))

(defn check [{:keys [string file dir resource] :as opts}]
  (try
    (cond string (pg/check-string string opts)
          file (check-files (split-path file) opts)
          dir (check-dirs (split-path dir) opts)
          resource (pg/check-resource (str resource) opts)
          :else (pg/check-input (reader/->reader *in*) opts))
    (catch Exception e
      (if (::error/type (ex-data e))
        (throw (ex-info (str "Template parsing failed: " (ex-message e))
                        (ex-data e)))
        (throw e)))))
