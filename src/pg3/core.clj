(ns pg3.core
  (:require [k8s])
  (:gen-class))


(defn watch []
  (try 
    (let [ctx (k8s/default-ctx)]

      (println "SEC"
               (->>
                (:items (k8s/api-get ctx "/api/v1/namespaces/pg3/secrets"))
                (mapv #(get-in % [:metadata :name]))))

      (println "CFG"
               (->>
                (:items (k8s/api-get ctx "/api/v1/namespaces/pg3/configmaps"))
                (mapv #(get-in % [:metadata :name]))))

      (println "DB" (k8s/api-get ctx "/api/v1/namespaces/pg3/configmaps/db1"))
      )

    (catch Exception e
      (println e))))

(defn -main [& [args]]
  (loop []
    (println "Wake up")
    (Thread/sleep 600000)
    (watch)
    (recur)))


(comment
  (watch)
  )
