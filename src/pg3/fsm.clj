(ns pg3.fsm)

(def config-map (atom
                 {:name "db1"
                  :ns "pg3"
                  :volume.size "1Gi"
                  :volume.name "db1-master-data"
                  :image "aidbox/db:passive-11.4.0.2"}))

(def ctx {:bearer "token"})

(def s (atom {:ready? false}))

(defmulti process
  (fn [state ctx params cm]
    state))

(defmethod process
  :default
  [state ctx params cm]
  (throw (ex-info (str "Unknown state " state) {})))

(defmethod process
  :init
  [state ctx params cm]
  (println "init...")
  {:state :master-volume
   :timeout 0})

(defmethod process
  :master-volume
  [state ctx params cm]
  (if (:ready? @s)
    (do
      (println "Volume is ready.")
      {:state :master-pod})
    (do
      (println "Volume is not ready")
      nil)))

(defmethod process
  :master-pod
  [state ctx params cm]
  (if (< (:max-attempts params) (:attempts cm))
    {:state :error}))

(defmethod process
  :master-init
  ;; run init script
  [state ctx params cm]
  {})

(defmethod process
  :error
  [state ctx params cm]
  (println ";((")
  {:state :error})

(defn one-or-inc [n]
  (if (nil? n) 1 (inc n)))

(defn enrich-result [cm result]
  (if (or (nil? result) (nil? (:state result)))
    (-> cm
        :metadata
        (update :attempts one-or-inc))
    (merge result {:attempts 0})))

(defn get-config-map [ctx] @config-map)

(defn update-config-map! [result]
  (swap! config-map #(merge % {:metadata result})))

(def init-cluster-fsm
  {:init          {:max-attempts 11}
   :master-volume {}
   :master-pod    {}
   :master-init   {}
   :master-done   {}
   :slave-volume  {}
   :slave-pod     {}
   :slave-init    {}
   :ready         {}
   :check         {}
   :error         {}})

(defn run [ctx sm]
  (println)
  (println "=======")

  (try
    (let [cm (get-config-map ctx)
          state (or (-> cm :metadata :state keyword) :init)
          params (get init-cluster-fsm state)
          result (process state ctx params cm)
          enriched-result (enrich-result cm result)]
      (update-config-map! enriched-result)
      (let [timeout (or (:timeout enriched-result) (:timeout ctx) 0)]
        (println "Sleep for" timeout "sec")
        (Thread/sleep timeout))
      enriched-result)

    (catch Exception e
      (prn e)
      (update-config-map! {:state :error :message "Something went wrong."})
      "Error!!!")))

(comment

  (run {} init-cluster-fsm)

  (get-config-map ctx)


  (defn toggle []
    (swap! s update :ready? not))

  (toggle)




  )
