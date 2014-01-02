(ns ptanglogs.core
  (:use [clojure.string :only [replace-first]])
  (:use [incanter.core :only [$ $data $rollup col-names conj-cols save]])
  (:use [incanter.stats :only [mean sd quantile]])
  (:require [clojure.java.io :as io]
	    [incanter.core :as incanter]
	    [incanter.io :as incanterio]))

;; chemin de readlines
;;http://blog.magpiebrain.com/tag/incanter/

(def time-pattern #"^([0-9- :,]*) INFO.*TIME MONITOR.*;(.*);(.*) ms$")
(def error-pattern #"^([0-9- :,]*).* ERROR (.*)$")

(defn str-to-long [x] (Long/parseLong (apply str (filter #(Character/isDigit %) x))))

(defn time-to-reading
  "filter out line and convert duration into a long"
  [[s t l d]]
  [t l (str-to-long d)] )

(defn error-to-reading
  "filter out line"
  [[s t l]]
  (cond
   (re-matches #".*\w*at .*" l) nil
   (re-matches #".*WorkerThread#.*" l) [t (replace-first l #"\(WorkerThread#.*\)" "")]
   (re-matches #".*HealthDetailsServiceWeb - unable.*" l) [t (replace-first l #"\[.*\]" "")]
   (re-matches #".*LifeSummaryServiceWeb - unable.*" l) [t (replace-first l #"\[.*\]" "")]
   (re-matches #".*SLF4J: Found binding in.*" l) [t (replace-first l #"\[.*\]" "")]
   :else [t l] ))

(defn parse-line
  "returns the reading as a vector"
  [pattern mapper source]
  (first (map mapper (re-seq pattern source))))

(defn filter-matching
  [readings]
  "filter out non matching lines"
   (remove nil? readings))
 
(defn parse-file 
  "returns a list of readings"
  [filename parser]
  (filter-matching
   (with-open [rdr (io/reader filename)]
       (doall (map parser (line-seq rdr))))))

(defn response-time-summary
  "build the summary of a dataset"
  [ds] 
   (zipmap [ :count :mean :sd :min :q95 :max]
      (flatten (incanter/with-data ($ :duration ds)
	   [ (count $data) (mean $data) (sd $data) (quantile $data :probs[0 0.95 1]) ] ))))

(defn readings-to-dataset
  "build a dataset from a list of readings"
  [readings]
  (incanter/dataset [:timestamp :label :duration] readings))

(defn compute-metric
  "compute a metric grouped by label"
  [ds [metric f]]
  (col-names ($rollup f :duration :label ds) [:label metric])) 

(defn q [p ds] (quantile ds :probs [p])) 

(defn compute-stats
  "compute stats grouped by label"
  [ds]
  (let [metrics {:count count :mean mean :sd sd
		 :min (partial q 0) :q95 (partial q 0.95) :max (partial q 1)}]
	 (map (partial compute-metric ds) metrics)))

(defn stats
   [ds]
   (let [result (compute-stats ds)]
     (col-names 
      (conj-cols
       ($ :label (nth result 0)) 
       ($ :count (nth result 0)) 
       ($ :mean (nth result 1)) 
       ($ :sd (nth result 2)) 
       ($ :min (nth result 3)) 
       ($ :q95 (nth result 4)) 
       ($ :max (nth result 5)) )
      [:label :count :mean :sd :min :q95 :max])))

(defn counters
   [ds]
   (let [result (compute-stats ds)]
     (col-names 
      (conj-cols
       ($ :label (nth result 0)) 
       ($ :count (nth result 0)) )
      [:label :count])))

(defn -main
  [& args]
  (let [mode (first args)
	filename (first (rest args))]
    (cond
     (= "time" mode) (let [parser (partial (partial parse-line time-pattern) time-to-reading) ]
			   (save (stats
			     (readings-to-dataset (parse-file  filename parser))) "stats.csv"))
     (= "errors" mode) (let [parser (partial (partial parse-line error-pattern) error-to-reading)]
			 (save (counters
			     (readings-to-dataset (parse-file  filename parser))) "errors.csv"))
     :else (println (str "invalid mode " mode)) ))) 
 
