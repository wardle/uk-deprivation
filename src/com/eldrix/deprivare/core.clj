(ns com.eldrix.deprivare.core
  (:require [clojure.data.csv :as csv]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [datalevin.core :as d]
            [clojure.string :as str])
  (:import (java.io Closeable File)
           (java.time LocalDateTime)))


(def uk-composite-imd-2020-mysoc-url
  "URL to download a composite UK score for deprivation indices for 2020 -
  based on England with adjusted scores for the other nations as per Abel, Payne
  and Barclay but calculated by Alex Parsons on behalf of MySociety."
  "https://github.com/mysociety/composite_uk_imd/blob/e7a14d3317d9462890c28513866687a3a35adc8d/uk_index/UK_IMD_E.csv?raw=true")

(def schema
  {:lsoa                                                           {:db/valueType :db.type/string}
   :com.github.mysociety.composite_uk_imd.2020/UK_IMD_E_rank       {:db/valueType :db.type/double}
   :com.github.mysociety.composite_uk_imd.2020/UK_IMD_E_pop_decile {:db/valueType :db.type/long}
   })

(deftype Svc [conn]
  Closeable
  (close [_] (d/close conn)))

(defn open [dir & {:keys [read-only?] :or {read-only? true}}]
  (if (and read-only? (not (.exists (File. dir))))
    (do (println "Error: :db specified does not exist")
        (System/exit 1))
    (->Svc (d/create-conn dir schema))))

(defn close [^Svc svc]
  (d/close (.-conn svc)))

(defn parse-uk-composite-2020-mysoc [row]
  {:uk.gov.ons/lsoa                                 (get row 1)
   :uk-composite-imd-2020-mysoc/UK_IMD_E_rank       (edn/read-string (get row 8))
   :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile (edn/read-string (get row 9))})

(def headers-uk-composite-2020-mysoc
  ["nation"
   "lsoa"
   "overall_local_score"
   "income_score"
   "employment_score"
   "UK_IMD_E_score"
   "original_decile"
   "E_expanded_decile"
   "UK_IMD_E_rank"
   "UK_IMD_E_pop_decile"
   "UK_IMD_E_pop_quintile"])

(defn download-uk-composite-imd-2020
  "Downloads and installs the uk-composite-imd-2020-mysoc data."
  [^Svc svc]
  (with-open [reader (io/reader uk-composite-imd-2020-mysoc-url)]
    (let [lines (csv/read-csv reader)]
      (if-not (= headers-uk-composite-2020-mysoc (first lines))
        (throw (ex-info "invalid CSV headers" {:expected headers-uk-composite-2020-mysoc :actual (first lines)}))
        (d/transact! (.-conn svc) (map (fn [row]
                                         (parse-uk-composite-2020-mysoc row)) (rest lines)))))))

(def available-data
  {:uk-composite-imd-2020-mysoc
   {:title       "UK composite index of multiple deprivation, 2020 (MySociety)"
    :year        2020
    :description "A composite UK score for deprivation indices for 2020 - based on England
with adjusted scores for the other nations as per Abel, Payne and Barclay but
calculated by Alex Parsons on behalf of MySociety."
    :install-fn  download-uk-composite-imd-2020}})

(defn print-available [_params]
  (pprint/print-table (map (fn [[k v]] (hash-map :id (name k) :name (:title v))) (reverse (sort-by :year available-data)))))

(defn dataset-info [params]
  (if-let [dataset (get available-data (keyword (:dataset params)))]
    (do
      (println (:title dataset))
      (println (apply str (repeat (count (:title dataset)) "-")))
      (println (:description dataset)))
    (println "Invalid :dataset parameter.\nUsage: clj -X:list to see available datasets.")))

(defn register-dataset [svc k]
  (d/transact! (.-conn svc)
               [{:installed/id   k
                 :installed/date (LocalDateTime/now)}]))

(defn print-installed [{:keys [db]}]
  (if db
    (let [svc (open (str db))
          ids (d/q '[:find [?id ...]
                     :in $
                     :where
                     [?e :installed/id ?id]
                     [?e :installed/date ?date-time]]
                   (d/db (.-conn svc)))
          result (map #(hash-map :id (name %) :name (:title (get available-data %))) ids)]
      (pprint/print-table result))
    (println "Invalid :db parameter.\nUsage: clj -X:installed :db <database file>")))

(defn install [{:keys [db dataset]}]
  (if (and db dataset)
    (if-let [dataset' (get available-data (keyword dataset))]
      (with-open [svc (open (str db) :read-only? false)]
        (println "Installing dataset: " (:title dataset'))
        ((:install-fn dataset') svc)
        (register-dataset svc (keyword dataset))
        (println "Import complete"))
      (println "Invalid :dataset parameter.\nUse clj -X:list to see available datasets."))
    (println (str/join "\n"
                       ["Invalid parameters"
                        "Usage:   clj -X:install :db <database file> :dataset <dataset identifier>"
                        "  - :db      - filename of database eg. 'depriv.db'"
                        "  - :dataset - identifier of dataset eg. 'uk-composite-imd-2020-mysoc'"]))))

(defn fetch-lsoa [svc lsoa]
  (-> (apply merge
             (d/q '[:find [(pull ?e [*]) ...]
                    :in $ ?lsoa
                    :where
                    [?e :uk.gov.ons/lsoa ?lsoa]]
                  (d/db (.-conn svc))
                  lsoa))
      (dissoc :db/id)))

(comment
  (def reader (io/reader uk-composite-imd-2020-mysoc-url))
  (def lines (csv/read-csv reader))
  (first lines)
  (second lines)
  (take 5 lines)
  (take 5 (map parse-uk-composite-2020-mysoc (rest lines)))
  (d/transact! (.-conn svc) (map (fn [row]
                                   (parse-uk-composite-2020-mysoc row)) (rest lines)))
  (d/transact! (.-conn svc) [{:lsoa "E01012672" :wibble 3}])
  (def svc (open "depriv.db"))
  (download-uk-composite-imd-2020 svc)

  (d/transact! (.-conn svc)
               [{:installed/id   :uk-composite-imd-2020-mysoc2
                 :installed/date (LocalDateTime/now)}])
  (d/q '[:find ?rank ?decile
         :in $ ?lsoa
         :where
         [?e :lsoa ?lsoa]
         [?e :uk-composite-imd-2020-mysoc/UK_IMD_E_rank ?rank]
         [?e :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile ?decile]]
       (d/db (.-conn svc))
       "E01012672")
  (fetch-lsoa svc "E01012672")
  (require 'clojure.data.json)
  (clojure.data.json/write-str (fetch-lsoa svc "E01012672") :key-fn (fn [k] (str (namespace k) "-" (name k))))
  (d/q '[:find [?id ...]
         :in $
         :where
         [?e :installed/id ?id]
         [?e :installed/date ?date-time]]
       (d/db (.-conn svc)))
  )