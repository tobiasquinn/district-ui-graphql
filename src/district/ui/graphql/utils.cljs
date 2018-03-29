(ns district.ui.graphql.utils
  (:require
    [camel-snake-kebab.extras :refer [transform-keys]]
    [cljs-time.coerce :as tc]
    [cljs-time.core :as t]
    [cljsjs.dataloader]
    [clojure.set :as set]
    [clojure.walk :as walk]
    [contextual.core :as contextual]
    [district.cljs-utils :as cljs-utils]
    [graphql-query.core :refer [graphql-query]]
    [print.foo :include-macros true]
    [re-frame.core :refer [dispatch dispatch-sync]]))


(def parse-graphql (aget js/GraphQL "parse"))
(def print-str-graphql (aget js/GraphQL "print"))
(def visit (aget js/GraphQL "visit"))

(set! *print-meta* true)

(defn ppm [obj]
  (let [orig-dispatch cljs.pprint/*print-pprint-dispatch*]
    (cljs.pprint/with-pprint-dispatch
      (fn [o]
        (when (meta o)
          (orig-dispatch (meta o)))
        (orig-dispatch o))
      (cljs.pprint/pprint obj))))

#_(set! cljs.pprint/*print-pprint-dispatch* ppm)


(defn create-field-node [name]
  {:kind "Field"
   :name {:kind "Name"
          :value name}})


(defn create-name-node [name]
  {:kind "Name"
   :value name})


(defn- ancestors->query-path [ancestors]
  (->> ancestors
    (remove (fn [node]
              (= (aget node "kind") "OperationDefinition")))
    (map (fn [node]
           (cond
             #_(aget node "alias")
             #_(aget node "alias" "value")

             (= (aget node "kind") "FragmentDefinition")
             {:typename (aget node "typeCondition" "name" "value")}

             (aget node "name")
             (aget node "name" "value")

             :else nil)))
    (remove nil?)
    seq))


(defn- query-path->graphql-type [schema query-path]
  (let [type-map (js-invoke schema "getTypeMap")
        top-fields (-> schema
                     (js-invoke "getQueryType")
                     (js-invoke "getFields"))]
    (loop [fields top-fields
           query-path-rest query-path]
      (let [field-name (first query-path-rest)
            gql-type (if-let [typename (:typename field-name)]
                       (aget type-map typename)
                       (aget fields field-name "type"))
            gql-type (if (instance? (aget js/GraphQL "GraphQLList") gql-type)
                       (aget gql-type "ofType")
                       gql-type)]
        (if (= 1 (count query-path-rest))
          gql-type
          (recur (js-invoke gql-type "getFields")
                 (rest query-path-rest)))))))


(defn graphql-type->id-field-names [gql-type]
  (->> (cljs-utils/js-obj->clj (js-invoke gql-type "getFields"))
    (filter (fn [[_ v]]
              (let [gql-type (aget v "type")
                    name (if (instance? (aget js/GraphQL "GraphQLNonNull") gql-type)
                           (aget gql-type "ofType" "name")
                           (aget gql-type "name"))]
                (= name "ID"))))
    (map (comp name first))
    set))


(defn- get-id-fields-names [schema ancestors]
  (when-let [query-path (ancestors->query-path ancestors)]
    (graphql-type->id-field-names (query-path->graphql-type schema query-path))))


(defn contains-typename? [node {:keys [:typename-field]}]
  (and (map? node)
       (get node typename-field)))

(defn entity? [node id-field-names]
  (and (map? node)
       (seq id-field-names)
       (every? #(not (nil? (get node %))) id-field-names)))


(defn get-ref [node id-field-names {:keys [:typename-field]}]
  {:id (if (= 1 (count id-field-names))
         (get node (first id-field-names))
         (select-keys node id-field-names))
   :type (get node typename-field)
   :graphql/entity-ref? true})


(defn entity-ref? [x]
  (boolean (:graphql/entity-ref? x)))


(defn update-entity [state {:keys [:id :type]} item]
  (update-in state [type id] cljs-utils/merge-in item))


(defn get-entity [entities {:keys [:id :type]}]
  (get-in entities [type id]))


(defn- merge-in-colls [existing-val new-val]
  (if (and (sequential? existing-val)
           (sequential? new-val))
    (mapv (partial reduce cljs-utils/merge-in)
          (partition 2 (interleave existing-val new-val)))
    (cljs-utils/merge-in existing-val new-val)))


(defn response-replace-aliases [data {:keys [:query] :as query-clj}]
  (walk/postwalk (fn [node]
                   (try
                     (let [dectx-node (contextual/decontextualize node)]

                       (if (map? dectx-node)
                         (let [path (seq (remove number? (contextual/context node)))]
                           (reduce
                             (fn [acc [key value]]
                               (let [{:keys [:name :args]} (meta (get-in query (concat path [key])))]
                                 (update-in acc (remove nil? [(or name key) args]) merge-in-colls value)))
                             {}
                             dectx-node))
                         dectx-node))
                     (catch :default _
                       node)))
                 (contextual/contextualize data)))

(defn query-clj-replace-aliases [query-clj]
  (walk/postwalk
    (fn [node]

      (if (and (map? node)
               (seq node)
               (some meta (vals node)))

        (-> (reduce (fn [acc [k v]]
                      (let [{:keys [:args :name]} (meta v)]
                        (if (or name args)
                          (assoc-in acc (remove nil? [(or name k) args]) v)
                          (assoc acc k v))))
                    {}
                    node)
          (with-meta (meta node)))
        node))
    (select-keys query-clj [:query])))


(defn replace-entities-with-refs [data {:keys [:query] :as query-clj} opts]
  (let [entities (atom {})
        graph (walk/postwalk
                (fn [node]
                  (try
                    (let [dectx-node (contextual/decontextualize node)]

                      (if (map? dectx-node)
                        (let [path (seq (remove number? (contextual/context node)))
                              id-field-names (:id-field-names (meta (get-in query path)))]
                          (cond
                            (entity? dectx-node id-field-names)
                            (let [dectx-node (update dectx-node (:typename-field opts) (:gql-name->kw opts))
                                  ref (get-ref dectx-node id-field-names opts)]
                              (swap! entities #(update-entity % ref dectx-node))
                              ref)

                            (contains-typename? dectx-node opts)
                            (let [dectx-node (update dectx-node (:typename-field opts) (:gql-name->kw opts))]
                              dectx-node)

                            :else dectx-node))
                        dectx-node))
                    (catch :default _
                      node)))
                (contextual/contextualize data))]
    {:entities @entities
     :graph graph}))


(defn normalize-response [data query-clj opts]
  #_(print.foo/look (:query query-clj))
  #_(ppm (:query query-clj))

  (-> data
    (response-replace-aliases query-clj)
    (replace-entities-with-refs (query-clj-replace-aliases query-clj) opts)))


(defn- scalar-type-of? [x scalar-type-name]
  (and (instance? (aget js/GraphQL "GraphQLScalarType") x)
       (= (aget x "name") scalar-type-name)))


(defn- scalar-arg-vals->str [v]
  (cond
    (keyword? v)
    (cljs-utils/kw->str v)

    (sequential? v)
    (mapv scalar-arg-vals->str v)

    (t/date? v)
    (tc/to-long v)

    :else v))


(defn- serialize-args [args {:keys [:gql-name->kw]}]
  (let [args (js->clj args)
        args (when (seq args) (->> args
                                (transform-keys gql-name->kw)
                                (cljs-utils/transform-vals scalar-arg-vals->str)))]
    args))


(defn- spread-query-fragments [query-clj]
  (update query-clj
          :query
          (fn [query]
            (walk/postwalk (fn [x]
                             (if (map? x)
                               (let [frag-keys (filter #(= (namespace %) "fragment") (keys x))]
                                 (reduce (fn [acc frag-key]
                                           (-> acc
                                             (cljs-utils/merge-in (get query-clj frag-key))
                                             (dissoc frag-key)))
                                         x
                                         frag-keys))
                               x))
                           query))))

(defn query->clj [query-ast schema & [{:keys [:gql-name->kw :variables]
                                       :or {gql-name->kw identity}}]]
  (let [m (partial into {})
        variables (serialize-args variables {:gql-name->kw gql-name->kw})]
    (-> query-ast
      (visit #js {:leave (fn [node key parent path ancestors]
                           (condp = (aget node "kind")
                             "Document" (m (aget node "definitions"))
                             "Name" (gql-name->kw (aget node "value"))
                             "Argument" {(aget node "name") (aget node "value")}
                             "OperationDefinition" {:query
                                                    (with-meta (aget node "selectionSet")
                                                               {:operation (keyword (aget node "operation"))})}
                             "SelectionSet" (let [selections (m (aget node "selections"))
                                                  id-field-names (get-id-fields-names schema (concat ancestors [parent]))]
                                              (if (seq id-field-names)
                                                (with-meta selections {:id-field-names (map gql-name->kw id-field-names)})
                                                selections))
                             "Field" (let [selection (aget node "selectionSet")
                                           metadata (cond-> nil
                                                      (seq (vec (aget node "arguments")))
                                                      (assoc :args (m (aget node "arguments")))

                                                      (boolean (aget node "alias"))
                                                      (assoc :name (aget node "name")))]

                                       {(or (aget node "alias")
                                            (aget node "name"))
                                        (with-meta (or selection {}) (merge (meta selection) metadata))})
                             "IntValue" (js/parseInt (aget node "value"))
                             "FloatValue" (js/parseFloat (aget node "value"))
                             "NullValue" (aget node "value")
                             "StringValue" (aget node "value")
                             "BooleanValue" (boolean (aget node "value"))
                             "ListValue" (vec (aget node "values"))
                             "ObjectValue" (aget node "value")
                             "EnumValue" (aget node "value")
                             "Variable" (get variables (aget node "name"))
                             "FragmentDefinition" {(keyword :fragment (aget node "name")) (aget node "selectionSet")}
                             "FragmentSpread" {(keyword :fragment (aget node "name")) {}}
                             js/undefined))})
      spread-query-fragments)))


(defn create-field-resolver [& [{:keys [:gql-name->kw]
                                 :or {:gql-name->kw identity}}]]
  (fn [{:keys [:graph :entities]} args _ info]
    (let [name (aget info "fieldName")
          args (serialize-args args {:gql-name->kw gql-name->kw})
          value (get-in graph (remove nil? [(gql-name->kw name) args]))]

      (cond
        (map? value)
        {:graph (if (entity-ref? value) (get-entity entities value) value)
         :entities entities}

        (and (sequential? value)
             (map? (first value)))
        (let [array (js/Array.)]
          (doseq [item value]
            (let [item (if (entity-ref? item) (get-entity entities item) item)]
              (.push array {:graph item :entities entities})))
          array)

        (scalar-type-of? (aget info "returnType") "Keyword")
        (keyword (if (boolean? value)
                   (str value)
                   value))

        (scalar-type-of? (aget info "returnType") "Date")
        (tc/from-long value)

        :else value))))


(defn- distinct-name-fn [id]
  (fn [name]
    (str name "_" id)))


(defn- rename-arg-variable [arg rename-fn]
  (if (= "Variable" (:kind (:value arg)))
    (update-in arg [:value :name :value] rename-fn)
    arg))


(defn merge-queries [query-configs]
  (let [query-configs (map #(select-keys % [:query :variables])
                           (js->clj query-configs :keywordize-keys true))]
    (-> (rest query-configs)
      (->> (reduce (fn [acc {:keys [:query :variables]}]
                     (let [query-id (str (js/Math.abs (hash [query variables])))
                           distinct-name (distinct-name-fn query-id)
                           variable-defs (->> (get-in query [:definitions 0 :variableDefinitions])
                                           (map (fn [var-def]
                                                  (update-in var-def [:variable :name :value] distinct-name))))
                           selections (->> (get-in query [:definitions 0 :selectionSet :selections])
                                        (map (fn [sel]
                                               (cond-> sel
                                                 true
                                                 (update :arguments (partial map #(rename-arg-variable % distinct-name)))

                                                 (not (:alias sel))
                                                 (assoc :alias (create-name-node (distinct-name (:value (:name sel)))))))))
                           variables (into {} (map (fn [[k v]]
                                                     [(distinct-name (name k)) v])
                                                   variables))]
                       (-> acc
                         (update-in [:query :definitions 0 :selectionSet :selections] concat selections)
                         (update-in [:query :definitions 0 :variableDefinitions] concat variable-defs)
                         (update :variables merge variables))))
                   (first query-configs)))
      (update :query clj->js))))


(defn create-dataloader [{:keys [:on-success :on-error :on-request :on-response :fetch-event] :as opts}]
  (let [dt (atom nil)]
    (reset!
      dt
      (js/DataLoader.
        (fn [query-configs]
          (let [query-configs (vec query-configs)
                {:merged-query :query
                 :merged-variables :variables} (merge-queries query-configs)
                req-opts (merge opts
                                {:query merged-query
                                 :variables merged-variables
                                 :query-configs query-configs})]
            (when on-request
              (dispatch (vec (concat on-request [req-opts]))))
            (js-invoke @dt "clearAll")
            (let [res-promise
                  (js/Promise.
                    (fn [resolve reject]
                      (dispatch (vec (concat fetch-event [req-opts])))
                      (resolve (.fill (js/Array. (count query-configs))))))]
              res-promise)))))))


(defn parse-query [query {:keys [:kw->gql-name]}]
  (cond
    (string? query)
    {:query-str query :query (parse-graphql query)}

    (map? query)
    (let [query-str (graphql-query query {:kw->gql-name kw->gql-name})]
      {:query-str query-str
       :query (parse-graphql query-str)})

    :else
    {:query-str (print-str-graphql query)
     :query query}))


(defn apply-query-middlewares [middlewares {:keys [:query :variables] :as opts}]
  (let [results (doall
                  (reduce (fn [acc middleware]
                            (let [{:keys [:query :variables :response]} ((:fn middleware) (merge opts acc))]
                              {:query (or query (:query acc))
                               :variables (or variables (:variables acc))
                               :responses (if response
                                            (conj (:responses acc) response)
                                            (:responses acc))}))
                          {:query query :variables variables :responses []}
                          middlewares))]
    results))