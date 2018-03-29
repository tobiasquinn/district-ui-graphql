(ns district.ui.graphql.queries
  (:require
    [district.cljs-utils :as cljs-utils]
    [district.ui.graphql.utils :as utils]
    [district.graphql-utils :as graphql-utils]))

(def gql-sync (aget js/GraphQL "graphqlSync"))
(def db-key :district.ui.graphql)

(defn config
  ([db]
   (get-in db [db-key :config]))
  ([db key]
   (get-in db [db-key :config key])))

(defn merge-config [db config]
  (update-in db [db-key :config] merge config))

(defn typename-field->gql-name [db]
  ((config db :kw->gql-name) (config db :typename-field)))

(defn results [db]
  (get-in db [db-key :results]))

(defn merge-results [db results]
  (update-in db [db-key :results] cljs-utils/merge-in results))

(defn query->merged-query [db query-str variables]
  (get-in db [db-key :query->merged-query query-str variables]))

(defn query-info [db query-str]
  (get-in db [db-key :query-info query-str]))

(defn query->merged-query-info [db query variables]
  (query-info db (query->merged-query db query variables)))

(defn assoc-queries-with-merged-query [db query-configs merged-query-str]
  (reduce (fn [db {:keys [:query-str :variables]}]
            (assoc-in db [db-key :query->merged-query query-str variables] merged-query-str))
          db
          query-configs))

(defn assoc-query-loading [db query-str loading?]
  (assoc-in db [db-key :query-info query-str :loading?] loading?))

(defn assoc-query-errors [db query-str errors]
  (assoc-in db [db-key :query-info query-str :errors] errors))

(defn query [db query-str variables]
  (let [gql-name->kw (config db :gql-name->kw)
        {:keys [:data :errors]}
        (-> (gql-sync (config db :schema)
                      query-str
                      (results db)
                      {}
                      (clj->js variables)
                      nil
                      (utils/create-field-resolver {:gql-name->kw gql-name->kw}))
          (graphql-utils/js->clj-response {:gql-name->kw gql-name->kw}))
        query-info (query->merged-query-info db query-str variables)]
    (merge data
           (when errors
             {:graphql/errors (map #(aget % "message") (vec errors))})
           (when-let [errors (:errors query-info)]
             {:graphql/errors errors})
           (when-let [loading? (:loading? query-info)]
             {:graphql/loading? loading?}))))

(defn entities
  ([db]
   (get-in db [db-key :results :entities]))
  ([db type]
   (get-in db [db-key :results :entities type])))

(defn entity [db type id]
  (get-in db [db-key :results :entities type (if (map? id)
                                               (cljs-utils/transform-vals str id)
                                               (str id))]))

(defn graph [db]
  (get-in db [db-key :results :graph]))