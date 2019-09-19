(ns k8s
  (:require [cheshire.core]
            [clj-yaml.core]
            [org.httpkit.client]
            [clojure.string :as str])
  (:import
   io.kubernetes.client.ApiClient
   io.kubernetes.client.ApiException
   io.kubernetes.client.Configuration
   io.kubernetes.client.apis.CoreV1Api
   io.kubernetes.client.models.V1Pod
   io.kubernetes.client.models.V1PodList
   io.kubernetes.client.util.Config

   java.io.BufferedReader
   java.io.InputStreamReader

   [java.util.concurrent TimeUnit]
   io.kubernetes.client.Exec))

(defn string->stream
  ([s] (string->stream s "UTF-8"))
  ([s encoding]
   (-> s
       (.getBytes encoding)
       (java.io.ByteArrayInputStream.))))

(defn default-ctx []
  (let [cl     (io.kubernetes.client.util.Config/defaultClient)
        token  (-> (.get (.getAuthentications cl) "BearerToken")
                   (.getApiKey))
        base   (.getBasePath cl)]
    {:req-ops {:headers {"Authorization" (str "Bearer " token)}}
     :base base
     :client cl}))

(defn ctx-for [cfg]
  (let [cl     (io.kubernetes.client.util.Config/fromConfig (string->stream cfg))
        token  (-> (.get (.getAuthentications cl) "BearerToken")
                   (.getApiKey))
        base   (.getBasePath cl)]
    {:req-ops {:headers {"Authorization" (str "Bearer " token)}}
     :base base
     :client cl}))

(defn api-req [ctx opts]
  (let [url (str (:base ctx) (:url opts))
        _ (println (str/upper-case (name (or (:method opts) :get))) url)
        resp @(org.httpkit.client/request
               (merge
                opts
                (:req-ops ctx)
                {:url url
                 :headers (merge (:headers (:req-ops ctx))
                                 (:headers opts))}))]
    (cond-> (select-keys resp [:status :body])
      (:body resp)
      (assoc :body (cheshire.core/parse-string (:body resp) keyword)))))

(defn api-get [ctx url]
  (api-req ctx {:url url}))

(defn api-delete [ctx url]
  (api-req ctx {:url url :method :delete}))

(defn api-post [ctx url res]
  (api-req ctx {:url url
                :method :post
                :headers {"Content-Type" "application/json"}
                :body (cheshire.core/generate-string res)}))

(defn api-put [ctx url res]
  (api-req ctx {:url url
                :method :put
                :headers {"Content-Type" "application/json"}
                :body (cheshire.core/generate-string res)}))

(defn api-patch [ctx url res]
  (api-req ctx {:url url
                :method :patch
                :headers {"Content-Type" "application/json-patch+json"}
                :body (cheshire.core/generate-string res)}))

(defn exec [{cl :client :as ctx} ns pod cnt cmd]
  (let [exec (io.kubernetes.client.Exec. cl)
        _ (-> cl
              (.getHttpClient)
              (.setReadTimeout 600 TimeUnit/SECONDS))
        proc (.exec exec ns pod (into-array cmd) cnt true true)
        out (-> proc
                .getInputStream
                java.io.InputStreamReader.
                java.io.BufferedReader.)]
    (loop [res []]
      (if-let [l (.readLine out)]
        (do
          (print ">> ")
          (println (pr-str l))
          (recur (conj res l)))
        res))))


(defn get-resource [ctx {api :apiVersion kind :kind {ns :namespace nm :name} :metadata :as res}]
  (let [{st :status res :body}
        (api-get ctx (str (if (= "v1" api)
                            (str "/api/" api "/")
                            (str "/apis/" api "/"))
                          "namespaces/"
                          ns "/" (str (str/lower-case kind) "s") "/" nm))]
    (when (= 200 st)
      res)))

(defn get-resources [ctx {api :apiVersion kind :kind {ns :namespace} :metadata :as res}]
  (let [{st :status res :body}
        (api-get ctx (str (if (= "v1" api)
                            (str "/api/" api "/")
                            (str "/apis/" api "/"))
                          "namespaces/"
                          ns "/" (str (str/lower-case kind) "s")))]
    (when (= 200 st)
      res)))

(defn update-resource [ctx {api :apiVersion kind :kind {ns :namespace nm :name} :metadata :as res}]
  (let [{st :status res :body :as resp}
        (api-put ctx (str (if (= "v1" api)
                            (str "/api/" api "/")
                            (str "/apis/" api "/"))
                          "namespaces/"
                          ns "/" (str (str/lower-case kind) "s")
                          "/" nm)
                 res)]
    (if (> 300 st)
      res
      (println resp))))

(defn create-resource [ctx {api :apiVersion kind :kind {ns :namespace} :metadata :as res}]
  (let [{st :status body :body :as resp}
        (api-post ctx (str (if (= "v1" api)
                             (str "/api/" api "/")
                             (str "/apis/" api "/"))
                           "namespaces/"
                           ns "/" (str (str/lower-case kind) "s"))
                  res)]
    (if (> 300 st)
      body
      (println "UPS: " resp))))

(comment

  (def ctx (default-ctx))

  ctx

  (api-get ctx "/api/v1/namespaces/default/pods")

  (spit  "/tmp/res.yaml"
         (clj-yaml.core/generate-string
          (api-get ctx "/api/v1/namespaces/cloud/pods")))

  (api-post ctx "/api/v1/namespaces/default/pods" {:pod "def"})

  (api-delete ctx "/api/v1/namespaces/default/pods/buildit")

  (exec ctx "cloud" "cloud-postgres-0" "postgres" ["bash" "-c" "df -h"])

  (->>
   (:items (api-get ctx "/api/v1/namespaces/pg3/persistentvolumeclaims"))
   (mapv #(get-in % [:metadata :name])))

  (->>
   (:items (api-get ctx "/api/v1/namespaces/pg3/secrets"))
   (mapv #(get-in % [:metadata :name])))

  (->>
   (:items (api-get ctx "/api/v1/namespaces/pg3/configmaps"))
   (mapv #(get-in % [:metadata :name])))

  (api-post ctx "/api/v1/namespaces/pg3/configmaps"
            {:kind "ConfigMap"
             :apiVersion "v1"
             :metadata {:name "db1"
                        :namespace "pg3"}
             :data {:cluster "db1"
                    :volume "1Gi"
                    :image "aidbox/db:walg-11.4.0.2"}})

  (:data (api-get ctx "/api/v1/namespaces/pg3/configmaps/db1"))

  (api-patch
   ctx  "/api/v1/namespaces/pg3/configmaps/db1"
   [{:op "replace" :path "/data/status" :value "ok"}])

  (->>
   (:items (api-get ctx "/api/v1/namespaces"))
   (mapv #(get-in % [:metadata :name])))

  (api-post ctx "/api/v1/namespaces"
            {:apiVersion "v1"
             :kind "Namespace"
             :metadata {:name "pg3"}})

  ;; create stateful set for master with sleep infinite and label "init"
  ;; init cluster and configure it with exec
  ;; update stateful set for postgres and label ready

  ;; run check

  (def ctx (default-ctx))

  (def ex (io.kubernetes.client.Exec. (:client ctx)))

  (def proc (.exec ex "pg3" "db1-0" (into-array ["sleep" "30"]) "postgres" true true))

  (def proc (.exec ex "pg3" "db1-0" (into-array ["ls"]) "postgres" true true))

  (exec ctx "pg3" "db1-0" "postgres" ["sleep" "30"])

  (->
   (.getHttpClient (:client ctx))
   (.setReadTimeout 200 TimeUnit/SECONDS))

  (type proc)

  _)
