(ns cr-ocs.literals
  (:require [clojure.walk :as walk]
            [themis.core  :as themis]
            [themis.predicates :as preds]
            [themis.validators :as validators]
            [cr-ocs.util  :as util]
            [cr-ocs.db    :as cdb]
            [io.pedestal.http.route.definition :as definition]
            [io.pedestal.log :as log])
  (:import (java.net URLDecoder)))

;; TODO: All these literals should be Types/Records that support print-method
;;       to enable full serialization (right now only reading works)

(defn decode-map
  "URL Decode the values of a Map
  This opens up the potential for non-sanitized input to be rendered."
  [m]
  (walk/postwalk (fn [x] (if (string? x) (URLDecoder/decode x) x)) m))

;; Data literals
;; -------------
(defn regex
  "A non-auto-escaping regex literal.
  This is like Regex strings in Clojure pre-1.0.
  If we want the short form, we'll need to come up with a different convention"
  [s]
  {:pre (string? s)}
  (re-pattern s))

;; Schema literals
;; ---------------
(def accepted-schema-toggles #{:unique :index :fulltext :identity nil})

(defn parse-short-schema-vec [s-vec]
  (let [doc-string (last s-vec)
        [ident card kind opt-toggle] (butlast s-vec)]
    (if (contains? accepted-schema-toggles opt-toggle)
      (merge {:db/id (cdb/temp-id :db.part/db)
              :db/ident ident
              :db/valueType (keyword "db.type" (name kind))
              :db/cardinality (keyword "db.cardinality" (name card))
              :db/doc (str doc-string)
              :db.install/_attribute :db.part/db}
             (condp = opt-toggle
               :unique   {:db/unique :db.unique/value}
               :identity {:db/unique :db.unique/identity}
               :index    {:db/index true}
               :fulltext {:db/fulltext true
                          :db/index true}
               nil))
      (throw (ex-info (str "Short schema toggles must be one of: " accepted-schema-toggles)
                      {:found-toggle opt-toggle})))))

(defn short-schema-tx [form]
  {:pre [(vector? form)]}
  (mapv parse-short-schema-vec form))

;; Routing/Action literals
;; -----------------------

(defrecord RespondAction [name params edn-coerce body status headers enforce-format doc]
  definition/ExpandableVerbAction
  (expand-verb-action [_]
    (let [params (or params [])
          edn-coerce (or edn-coerce [])
          response-attrs (cond-> {}
                                 status (assoc :status status)
                                 headers (assoc :headers headers))]
      {:route-name name
       :handler `(fn [req#]
                   (let [{:keys ~params :as params#} (merge
                                                      (decode-map (:path-params req#))
                                                      (:params req#)
                                                      (:json-params req#)
                                                      (:edn-params req#))
                         {:keys ~edn-coerce :as coercions#} (reduce (fn [cmap# k#]
                                                                      (if (params# k#)
                                                                        (assoc cmap# k# (try
                                                                                          (util/read-edn (params# k#))
                                                                                          (catch Exception e#
                                                                                            (params# k#))))
                                                                        cmap#))
                                                                    {}
                                                                    ~(mapv keyword edn-coerce))
                         resp# (if ~enforce-format
                                 (util/response (util/payload req# (or ~doc "") ~body))
                                 {:status 200 :headers {} :body ~(or body "")})]
                     (util/deep-merge resp# ~response-attrs)))
       :interceptors []})))

(defn respond [form]
  {:pre [(map? form)
         (:name form)
         (-> form :name keyword?)]}
  (map->RespondAction form))

(defmethod print-method RespondAction [t ^java.io.Writer w]
  (.write w (str "#cr-ocs/respond" (into {} t))))

(defrecord RedirectAction [name params body status headers url]
  definition/ExpandableVerbAction
  (expand-verb-action [_]
    (let [params (or params [])
          url (or url "")
          response-attrs (cond-> {}
                                 status (assoc :status status)
                                 headers (assoc :headers headers)
                                 body (assoc :body body))]
      {:route-name name
       :handler `(fn [req#]
                   (let [{:keys ~params :as params#} (merge
                                                       (decode-map (:path-params req#))
                                                       (:params req#)
                                                       (:json-params req#)
                                                       (:edn-params req#))]
                     (util/deep-merge {:status 302
                                       :headers {"Location" ~url}
                                       :body ""}
                                      ~response-attrs)))
       :interceptors []})))

(defn redirect [form]
  {:pre [(map? form)
         (:url form)
         (:name form)
         (-> form :name keyword?)]}
  (map->RedirectAction form))

(defmethod print-method RedirectAction [t ^java.io.Writer w]
  (.write w (str "#cr-ocs/redirect" (into {} t))))

(defrecord ValidateAction [name params properties doc]
  definition/ExpandableVerbAction
  (expand-verb-action [_]
    (let [params (or params [])
          rule-vec (walk/postwalk
                    (fn [form] (if (symbol? form)
                                 (util/fully-qualify-symbol (the-ns 'cr-ocs.literals)
                                                            form)
                                 form))
                    (or properties []))]
      {:route-name name
       :handler `(fn [req#]
                   (let [{:keys ~params :as params#} (merge
                                                      (decode-map (:path-params req#))
                                                      (:params req#)
                                                      (:json-params req#)
                                                      (:edn-params req#))]
                     (util/response
                      (util/payload req#
                                    (or ~doc "")
                                    (themis/unfold-result (themis/validation params# ~rule-vec))))))})))

(defn validate [form]
  {:pre [(map? form)
         (:name form)
         (-> form :name keyword?)]}
  (map->ValidateAction form))

(defmethod print-method ValidateAction [t ^java.io.Writer w]
  (.write w (str "#cr-ocs/validate" (into {} t))))

(defrecord QueryAction [name params query edn-coerce constants doc]
  definition/ExpandableVerbAction
  (expand-verb-action [_]
    (let [variables (vec (map #(-> % clojure.core/name keyword) (or params [])))
          coercions (set (map #(-> % clojure.core/name keyword) (or edn-coerce [])))]
      {:route-name name
       :handler `(fn [req#]
                   (let [args# (merge
                                (:path-params req#)
                                (:params req#)
                                (:json-params req#)
                                (:edn-params req#))
                         vals# (map (fn [k#]
                                      (let [in-val# (get args# k#)]
                                        (if (contains? ~coercions k#)
                                          (try
                                            (util/read-edn in-val#)
                                            (catch Exception e#
                                              in-val#))
                                          in-val#)))
                                    ~variables)
                         packet# (cdb/q '~query (concat vals# ~constants))]
                     (util/response
                      (util/payload req# (or ~doc "")
                                    {:response packet#}))))})))

(defn query [form]
  {:pre [(map? form)
         (:query form)
         (-> form :query vector?)
         (:name form)
         (-> form :name keyword?)]}
  (map->QueryAction form))

(defmethod print-method QueryAction [t ^java.io.Writer w]
  (.write w (str "#cr-ocs/query" (into {} t))))

(defn process-lookup-ref [[str-attr val]]
  [(keyword str-attr) val])

(defn process-id [entity-data]
  (let [id (:db/id entity-data)]
    (cond (vector? id) (assoc entity-data :db/id (process-lookup-ref id))
          (nil? id) (assoc entity-data :db/id (cdb/temp-id))
          :default entity-data)))

(defn massage-data [data]
  (vec (map #(-> % process-id)
            data)))

(defn apply-whitelist [data wl-keys]
  (map #(select-keys % wl-keys) data))

(defrecord TransactAction [name properties doc]
  definition/ExpandableVerbAction
  (expand-verb-action [_]
    {:route-name name
     :handler `(fn [req#]
                 (let [payload# (get-in req# [:json-params :payload])
                       whitelist# (apply-whitelist payload# ~properties)]
                   (util/response
                    (util/payload req# (or ~doc "")
                                  {:response {:transaction (map #(vector (:e %) (:a %) (:v %))
                                                                (:tx-data @(cdb/transact! (massage-data whitelist#))))
                                              :whitelist whitelist#}}))))}))

(defn transact [form]
  {:pre [(map? form)
         (:name form)
         (-> form :name keyword?)]}
  (map->TransactAction form))

(defmethod print-method TransactAction [t ^java.io.Writer w]
  (.write w (str "#cr-ocs/transact" (into {} t))))

