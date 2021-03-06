(ns metabase.test.data.bigquery
  (:require [clj-time
             [coerce :as tcoerce]
             [format :as tformat]]
            [clojure.string :as str]
            [medley.core :as m]
            [metabase
             [config :as config]
             [driver :as driver]
             [util :as u]]
            [metabase.driver
             [bigquery :as bigquery]
             [google :as google]]
            [metabase.driver.bigquery.query-processor :as bigquery.qp]
            [metabase.test.data :as data]
            [metabase.test.data
             [interface :as tx]
             [sql :as sql.tx]]
            [metabase.util
             [date :as du]
             [schema :as su]]
            [schema.core :as s])
  (:import com.google.api.client.util.DateTime
           com.google.api.services.bigquery.Bigquery
           [com.google.api.services.bigquery.model Dataset DatasetReference QueryRequest QueryResponse Table
            TableDataInsertAllRequest TableDataInsertAllRequest$Rows TableFieldSchema TableReference TableRow
            TableSchema]
           java.sql.Time))

(sql.tx/add-test-extensions! :bigquery)

;; Don't enable foreign keys when testing because BigQuery *doesn't* have a notion of foreign keys. Joins are still
;; allowed, which puts us in a weird position, however; people can manually specifiy "foreign key" relationships in
;; admin and everything should work correctly. Since we can't infer any "FK" relationships during sync our normal FK
;; tests are not appropriate for BigQuery, so they're disabled for the time being.
;;
;; TODO - either write BigQuery-speciifc tests for FK functionality or add additional code to manually set up these FK
;; relationships for FK tables
(defmethod driver/supports? [:bigquery :foreign-keys] [_ _] (not config/is-test?))


;;; ----------------------------------------------- Connection Details -----------------------------------------------

(def ^:private ^String normalize-name (comp #(str/replace % #"-" "_") name))

(def ^:private details
  (delay
    (reduce
     (fn [acc env-var]
       (assoc acc env-var (tx/db-test-env-var-or-throw :bigquery env-var)))
     {}
     [:project-id :client-id :client-secret :access-token :refresh-token])))

(defn project-id
  "BigQuery project ID that we're using for tests, from the env var `MB_BIGQUERY_TEST_PROJECT_ID`."
  ^String []
  (:project-id @details))

(let [bigquery* (delay (#'bigquery/database->client {:details @details}))]
  (defn- bigquery ^Bigquery []
    @bigquery*))

(defmethod tx/dbdef->connection-details :bigquery [_ _ {:keys [database-name]}]
  (assoc @details :dataset-id (normalize-name database-name)))


;;; -------------------------------------------------- Loading Data --------------------------------------------------

(defn- create-dataset! [^String dataset-id]
  {:pre [(seq dataset-id)]}
  (google/execute
   (.insert
    (.datasets (bigquery))
    (project-id)
    (doto (Dataset.)
      (.setLocation "US")
      (.setDatasetReference (doto (DatasetReference.)
                              (.setDatasetId dataset-id))))))
  (println (u/format-color 'blue "Created BigQuery dataset '%s'." dataset-id)))

(defn- destroy-dataset! [^String dataset-id]
  {:pre [(seq dataset-id)]}
  (google/execute-no-auto-retry (doto (.delete (.datasets (bigquery)) (project-id) dataset-id)
                                  (.setDeleteContents true)))
  (println (u/format-color 'red "Deleted BigQuery dataset '%s'." dataset-id)))

(defn execute!
  "Execute arbitrary (presumably DDL) SQL statements against the test project. Waits for statement to complete, throwing
  an Exception if it fails."
  ^QueryResponse [format-string & args]
  (driver/with-driver :bigquery
    (let [sql (apply format format-string args)]
      (printf "[BigQuery] %s\n" sql)
      (flush)
      (bigquery/with-finished-response [response (#'bigquery/execute-bigquery (data/db) sql)]
        response))))

(def ^:private valid-field-types
  #{:BOOLEAN :FLOAT :INTEGER :RECORD :STRING :TIMESTAMP :TIME})

(s/defn ^:private create-table!
  [dataset-id       :- su/NonBlankString
   table-id         :- su/NonBlankString,
   field-name->type :- {su/KeywordOrString (apply s/enum valid-field-types)}]
  (google/execute
   (.insert
    (.tables (bigquery))
    (project-id)
    dataset-id
    (doto (Table.)
      (.setTableReference (doto (TableReference.)
                            (.setProjectId (project-id))
                            (.setDatasetId dataset-id)
                            (.setTableId table-id)))
      (.setSchema (doto (TableSchema.)
                    (.setFields (for [[field-name field-type] field-name->type]
                                  (doto (TableFieldSchema.)
                                    (.setMode "REQUIRED")
                                    (.setName (name field-name))
                                    (.setType (name field-type))))))))))
  (println (u/format-color 'blue "Created BigQuery table '%s.%s'." dataset-id table-id)))

(defn- table-row-count ^Integer [^String dataset-id, ^String table-id]
  (ffirst (:rows (#'bigquery/post-process-native
                  (google/execute
                   (.query (.jobs (bigquery)) (project-id)
                           (doto (QueryRequest.)
                             (.setQuery (format "SELECT COUNT(*) FROM [%s.%s]" dataset-id table-id)))))))))

;; This is a dirty HACK
(defn- ^DateTime timestamp-honeysql-form->GoogleDateTime
  "Convert the HoneySQL form we normally use to wrap a `Timestamp` to a Google `DateTime`."
  [{[{s :literal}] :args}]
  {:pre [(string? s) (seq s)]}
  (DateTime. (du/->Timestamp (str/replace s #"'" ""))))


(defn- insert-data! [^String dataset-id, ^String table-id, row-maps]
  {:pre [(seq dataset-id) (seq table-id) (sequential? row-maps) (seq row-maps) (every? map? row-maps)]}
  (google/execute
   (.insertAll
    (.tabledata (bigquery)) (project-id) dataset-id table-id
    (doto (TableDataInsertAllRequest.)
      (.setRows (for [row-map row-maps]
                  (let [data (TableRow.)]
                    (doseq [[k v] row-map
                            :let [v (cond
                                      (instance? honeysql.types.SqlCall v)
                                      (timestamp-honeysql-form->GoogleDateTime v)
                                      :else v)]]
                      (.set data (name k) v))
                    (doto (TableDataInsertAllRequest$Rows.)
                      (.setJson data))))))))
  ;; Wait up to 30 seconds for all the rows to be loaded and become available by BigQuery
  (let [expected-row-count (count row-maps)]
    (loop [seconds-to-wait-for-load 30]
      (let [actual-row-count (table-row-count dataset-id table-id)]
        (cond
          (= expected-row-count actual-row-count)
          :ok

          (> seconds-to-wait-for-load 0)
          (do (Thread/sleep 1000)
              (recur (dec seconds-to-wait-for-load)))

          :else
          (throw (Exception. (format "Failed to load table data for %s.%s: expected %d rows, loaded %d"
                                     dataset-id table-id expected-row-count actual-row-count))))))))


(def ^:private base-type->bigquery-type
  {:type/BigInteger     :INTEGER
   :type/Boolean        :BOOLEAN
   :type/Date           :TIMESTAMP
   :type/DateTime       :TIMESTAMP
   :type/DateTimeWithTZ :TIMESTAMP ; is this correct ???
   :type/Decimal        :NUMERIC
   :type/Dictionary     :RECORD
   :type/Float          :FLOAT
   :type/Integer        :INTEGER
   :type/Text           :STRING
   :type/Time           :TIME})

(defn- fielddefs->field-name->base-type
  "Convert `field-definitions` to a format appropriate for passing to `create-table!`."
  [field-definitions]
  (into
   {"id" :INTEGER}
   (for [{:keys [field-name base-type]} field-definitions]
     {field-name (or (base-type->bigquery-type base-type)
                     (println (u/format-color 'red "Don't know what BigQuery type to use for base type: %s" base-type))
                     (throw (Exception. (format "Don't know what BigQuery type to use for base type: %s" base-type))))})))

(defn- time->string
  "Coerces `t` to a Joda DateTime object and returns it's String representation."
  [t]
  (->> t
       tcoerce/to-date-time
       (tformat/unparse #'bigquery.qp/bigquery-time-format)))

(defn- tabledef->prepared-rows
  "Convert `table-definition` to a format approprate for passing to `insert-data!`."
  [{:keys [field-definitions rows]}]
  {:pre [(every? map? field-definitions) (sequential? rows) (seq rows)]}
  (let [field-names (map :field-name field-definitions)]
    (for [[i row] (m/indexed rows)
          :let    [vs (for [v row]
                        (u/prog1 (cond
                                   (instance? Time v)
                                   (time->string v)

                                   (instance? java.util.Date v)
                                   ;; convert to Google version of DateTime, otherwise it doesn't work (!)
                                   (DateTime. ^java.util.Date v)

                                   :else v)
                          ;; make sure v is non-nil
                          (assert (not (nil? <>)))))]]
      (assoc (zipmap field-names vs)
             :id (inc i)))))

(defn- load-tabledef! [dataset-name {:keys [table-name field-definitions], :as tabledef}]
  (let [table-name (normalize-name table-name)]
    (create-table! dataset-name table-name (fielddefs->field-name->base-type field-definitions))
    (insert-data!  dataset-name table-name (tabledef->prepared-rows tabledef))))


(defn- existing-dataset-names
  "Fetch a list of *all* dataset names that currently exist in the BQ test project."
  []
  (for [dataset (get (google/execute (doto (.list (.datasets (bigquery)) (project-id))
                                       ;; Long/MAX_VALUE barfs but it has to be a Long
                                       (.setMaxResults (long Integer/MAX_VALUE))))
                     "datasets")]
    (get-in dataset ["datasetReference" "datasetId"])))

;; keep track of databases we haven't created yet
(def ^:private existing-datasets
  (atom #{}))

(defmethod tx/create-db! :bigquery [_ {:keys [database-name table-definitions]} & _]
  {:pre [(seq database-name) (sequential? table-definitions)]}
  ;; fetch existing datasets if we haven't done so yet
  (when-not (seq @existing-datasets)
    (reset! existing-datasets (set (existing-dataset-names)))
    (println "These BigQuery datasets have already been loaded:\n" (u/pprint-to-str (sort @existing-datasets))))
  ;; now check and see if we need to create the requested one
  (let [database-name (normalize-name database-name)]
    (when-not (contains? @existing-datasets database-name)
      (try
        (u/auto-retry 10
          ;; if the dataset failed to load successfully last time around, destroy whatever was loaded so we start
          ;; again from a blank slate
          (u/ignore-exceptions
            (destroy-dataset! database-name))
          (create-dataset! database-name)
          ;; do this in parallel because otherwise it can literally take an hour to load something like
          ;; fifty_one_different_tables
          (u/pdoseq [tabledef table-definitions]
            (load-tabledef! database-name tabledef))
          (swap! existing-datasets conj database-name)
          (println (u/format-color 'green "[OK]")))
        ;; if creating the dataset ultimately fails to complete, then delete it so it will hopefully work next time
        ;; around
        (catch Throwable e
          (u/ignore-exceptions
            (println (u/format-color 'red "Failed to load BigQuery dataset '%s'." database-name))
            (destroy-dataset! database-name))
          (throw e))))))

(defmethod tx/aggregate-column-info :bigquery
  ([driver aggregation-type]
   ((get-method tx/aggregate-column-info :sql-jdbc/test-extensions) driver aggregation-type))

  ([driver aggregation-type field]
   (merge
    ((get-method tx/aggregate-column-info :sql-jdbc/test-extensions) driver aggregation-type field)
    ;; BigQuery averages, standard deviations come back as Floats. This might apply to some other ag types as well;
    ;; add them as we come across them.
    (when (#{:avg :stddev} aggregation-type)
      {:base_type :type/Float}))))
