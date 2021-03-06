;;
;; Copyright (c) Two Sigma Open Source, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;  http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
(ns waiter.scheduler.kubernetes
  (:require [cheshire.core :as cheshire]
            [clj-http.client :as clj-http]
            [clj-time.core :as t]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [plumbing.core :as pc]
            [slingshot.slingshot :as ss]
            [waiter.authorization :as authz]
            [waiter.scheduler :as scheduler]
            [waiter.schema :as schema]
            [waiter.service-description :as sd]
            [waiter.util.async-utils :as au]
            [waiter.util.date-utils :as du]
            [waiter.util.http-utils :as http-utils]
            [waiter.util.utils :as utils])
  (:import (java.io InputStreamReader)
           (org.joda.time.format DateTimeFormat)))

(defn authorization-from-environment []
  "Sample implementation of the authentication string refresh function.
   Returns a string to be used as the value for the Authorization HTTP header,
   reading the string from the WAITER_K8S_AUTH_STRING environment variable."
  (log/info "called waiter.scheduler.kubernetes/authorization-from-environment")
  (System/getenv "WAITER_K8S_AUTH_STRING"))

(def k8s-api-auth-str
  "Atom containing authentication string for the Kubernetes API server.
   This value may be periodically refreshed asynchronously."
  (atom nil))

(def k8s-timestamp-format
  "Kubernetes reports dates in ISO8061 format, sans the milliseconds component."
  (DateTimeFormat/forPattern "yyyy-MM-dd'T'HH:mm:ss'Z'"))

(defn- timestamp-str->datetime
  "Parse a Kubernetes API timestamp string."
  [k8s-timestamp-str]
  (du/str-to-date k8s-timestamp-str k8s-timestamp-format))

(defn- use-short-service-hash? [k8s-max-name-length]
  ;; This is fairly arbitrary, but if we have at least 48 characters for the app name,
  ;; then we can fit the full 32 character service-id hash, plus a hyphen as a separator,
  ;; and still have 25 characters left for some prefix of the app name.
  ;; If we have fewer than 48 characters, then we'll probably want to shorten the hash.
  (< k8s-max-name-length 48))

(defn service-id->k8s-app-name [{:keys [max-name-length pod-suffix-length] :as scheduler} service-id]
  "Shorten a full Waiter service-id to a Kubernetes-compatible application name.
   May return the service-id unmodified if it doesn't violate the
   configured name-length restrictions for this Kubernetes cluster.

   Example:

   (service-id->k8s-app-name
     {:max-name-length 32}
     \"waiter-myapp-e8b625cc83c411e8974c38d5474b213d\")
   ==> \"myapp-e8b625cc474b213d\""
  (let [[_ app-prefix x y z] (re-find #"([^-]+)-(\w{8})(\w+)(\w{8})$" service-id)
        k8s-max-name-length (- max-name-length pod-suffix-length 1)
        suffix (if (use-short-service-hash? k8s-max-name-length)
                 (str \- x z)
                 (str \- x y z))
        prefix-max-length (- k8s-max-name-length (count suffix))
        app-prefix' (cond-> app-prefix
                      (< prefix-max-length (count app-prefix))
                      (subs 0 prefix-max-length))]
    (str app-prefix' suffix)))

(defn replicaset->Service
  "Convert a Kubernetes ReplicaSet JSON response into a Waiter Service record."
  [replicaset-json]
  (try
    (pc/letk
      [[spec
        [:metadata name namespace [:annotations waiter/service-id]]
        [:status {replicas 0} {availableReplicas 0} {readyReplicas 0} {unavailableReplicas 0}]]
       replicaset-json
       requested (get spec :replicas 0)
       staged (- replicas (+ availableReplicas unavailableReplicas))]
        (scheduler/make-Service
          {:id service-id
           :instances requested
           :k8s/app-name name
           :k8s/namespace namespace
           :task-count replicas
           :task-stats {:healthy readyReplicas
                        :running (- replicas staged)
                        :staged staged
                        :unhealthy (- replicas readyReplicas staged)}}))
    (catch Throwable t
      (log/error t "error converting ReplicaSet to Waiter Service"))))

(defn k8s-object->id
  "Get the id (name) from a ReplicaSet or Pod's metadata"
  [k8s-obj]
  (get-in k8s-obj [:metadata :name]))

(defn k8s-object->resource-version
  "Get the resource version from a Kubernetes API response object.
   Valid on ReplicaSets, Pods, and watch-update objects."
  [k8s-obj]
  (some-> k8s-obj
          (get-in [:metadata :resourceVersion])
          (Long/parseLong)))

(defn k8s-object->service-id
  "Get the Waiter service-id from a ReplicaSet or Pod's annotations"
  [k8s-obj]
  (get-in k8s-obj [:metadata :annotations :waiter/service-id]))

(defn- pod->instance-id
  "Construct the Waiter instance-id for the given Kubernetes pod incarnation.
   Note that a new Waiter Service Instance is created each time a pod restarts,
   and that we generate a unique instance-id by including the pod's restartCount value."
  ([scheduler pod] (pod->instance-id scheduler pod (get-in pod [:status :containerStatuses 0 :restartCount])))
  ([{:keys [pod-suffix-length] :as scheduler} pod restart-count]
   (let [pod-name (k8s-object->id pod)
         service-id (k8s-object->service-id pod)]
     (str service-id \. pod-name \- restart-count))))

(defn- killed-by-k8s?
  "Determine whether a pod was killed (restarted) by its corresponding Kubernetes liveness checks."
  [{:keys [exitCode reason] :as pod-terminated-info}]
  ;; TODO (#351) - Look at events for messages about liveness probe failures.
  ;; Currently, we assume any SIGKILL (137) with the default "Error" reason was a livenessProbe kill.
  (and (= 137 exitCode)
       (= "Error" reason)))

(defn- track-failed-instances!
  "Update this KubernetesScheduler's service-id->failed-instances-transient-store
   when a new pod failure is listed in the given pod's lastState container status.
   Note that unique instance-ids are deterministically generated each time the pod is restarted
   by passing the pod's restartCount value to the pod->instance-id function."
  [{:keys [service-id] :as live-instance} {:keys [service-id->failed-instances-transient-store] :as scheduler} pod]
  (when-let [newest-failure (get-in pod [:status :containerStatuses 0 :lastState :terminated])]
    (let [failure-flags (if (= "OOMKilled" (:reason newest-failure)) #{:memory-limit-exceeded} #{})
          newest-failure-start-time (-> newest-failure :startedAt timestamp-str->datetime)
          restart-count (get-in pod [:status :containerStatuses 0 :restartCount])
          newest-failure-id (pod->instance-id scheduler pod (dec restart-count))
          failures (-> service-id->failed-instances-transient-store deref (get service-id))]
      (when-not (contains? failures newest-failure-id)
        (let [newest-failure-instance (cond-> (assoc live-instance
                                                :flags failure-flags
                                                :healthy? false
                                                :id newest-failure-id
                                                :started-at newest-failure-start-time)
                                        ;; To match the behavior of the marathon scheduler,
                                        ;; we don't include the exit code in failed instances that were killed by k8s.
                                        (not (killed-by-k8s? newest-failure))
                                        (assoc :exit-code (:exitCode newest-failure)))]
          (swap! service-id->failed-instances-transient-store
                 update-in [service-id] assoc newest-failure-id newest-failure-instance))))))

(defn pod->ServiceInstance
  "Convert a Kubernetes Pod JSON response into a Waiter Service Instance record."
  [scheduler pod]
  (try
    (let [port0 (get-in pod [:spec :containers 0 :ports 0 :containerPort])]
      (scheduler/make-ServiceInstance
        {:extra-ports (->> (get-in pod [:metadata :annotations :waiter/port-count])
                           Integer/parseInt range next (mapv #(+ port0 %)))
         :healthy? (get-in pod [:status :containerStatuses 0 :ready] false)
         :host (get-in pod [:status :podIP])
         :id (pod->instance-id scheduler pod)
         :k8s/app-name (get-in pod [:metadata :labels :app])
         :k8s/namespace (get-in pod [:metadata :namespace])
         :k8s/pod-name (k8s-object->id pod)
         :k8s/restart-count (get-in pod [:status :containerStatuses 0 :restartCount])
         :log-directory (str "/home/" (get-in pod [:metadata :namespace]))
         :port port0
         :protocol (get-in pod [:metadata :annotations :waiter/protocol])
         :service-id (k8s-object->service-id pod)
         :started-at (-> pod
                         (get-in [:status :startTime])
                         (timestamp-str->datetime))}))
    (catch Throwable e
      (log/error e "error converting pod to waiter service instance" pod)
      (comment "Returning nil on failure."))))

(defn streaming-api-request
  "Make a long-lived HTTP request to the Kubernetes API server using the configured authentication.
   If data is provided via :body, the application/json content type is added automatically.
   The response payload (if any) is returned as a lazy seq of parsed JSON objects."
  ([url] (streaming-api-request url {}))
  ([url {:keys [keyword-keys?] :or {keyword-keys? true}}]
   (let [auth-str @k8s-api-auth-str
         request-options (cond-> {:as :stream}
                           auth-str (assoc :headers {"Authorization" auth-str}))]
     (-> url
         (clj-http/get request-options)
         :body
         InputStreamReader.
         (cheshire/parsed-seq keyword-keys?)))))

(defn api-request
  "Make an HTTP request to the Kubernetes API server using the configured authentication.
   If data is provided via :body, the application/json content type is added automatically.
   The response payload (if any) is automatically parsed to JSON."
  [client url & {:keys [body content-type request-method] :as options}]
  (scheduler/log "making request to K8s API server:" url request-method body)
  (ss/try+
    (let [auth-str @k8s-api-auth-str
          result (pc/mapply http-utils/http-request client url
                            :accept "application/json"
                            (cond-> options
                              auth-str (assoc-in [:headers "Authorization"] auth-str)
                              (and (not content-type) body) (assoc :content-type "application/json")))]
      (scheduler/log "response from K8s API server:" result)
      result)
    (catch [:status 400] _
      (log/error "malformed K8s API request: " url options))
    (catch [:client client] response
      (log/error "request to K8s API server failed: " url options body response)
      (ss/throw+ response))))

(defn- service-description->namespace
  [{:strs [run-as-user]}]
  run-as-user)

(defn- get-services
  "Get all Waiter Services (reified as ReplicaSets) running in this Kubernetes cluster."
  [{:keys [watch-state] :as scheduler}]
  (-> watch-state deref :service-id->service vals))

(defn- get-replicaset-pods
  "Get all Kubernetes pods associated with the given Waiter Service's corresponding ReplicaSet."
  [{:keys [watch-state] :as scheduler} {service-id :id}]
  (-> watch-state deref :service-id->pod-id->pod (get service-id) vals))

(defn- live-pod?
  "Returns true if the pod has started, but has not yet been deleted."
  [pod]
  (and (some? (get-in pod [:status :podIP]))
       (nil? (get-in pod [:metadata :deletionTimestamp]))))

(defn- get-service-instances!
  "Get all active Waiter Service Instances associated with the given Waiter Service.
   Also updates the service-id->failed-instances-transient-store as a side-effect."
  [{:keys [api-server-url http-client] :as scheduler} basic-service-info]
  (vec (for [pod (get-replicaset-pods scheduler basic-service-info)
             :when (live-pod? pod)]
         (let [service-instance (pod->ServiceInstance scheduler pod)]
           (track-failed-instances! service-instance scheduler pod)
           service-instance))))

(defn instances-breakdown!
  "Get all Waiter Service Instances associated with the given Waiter Service.
   Grouped by liveness status, i.e.: {:active-instances [...] :failed-instances [...] :killed-instances [...]}"
  [{:keys [service-id->failed-instances-transient-store] :as scheduler} {service-id :id :as basic-service-info}]
  {:active-instances (get-service-instances! scheduler basic-service-info)
   :failed-instances (-> @service-id->failed-instances-transient-store (get service-id []) vals vec)})

(defn- patch-object-json
  "Make a JSON-patch request on a given Kubernetes object."
  [http-client k8s-object-uri ops]
  (api-request http-client k8s-object-uri
               :body (utils/clj->json ops)
               :content-type "application/json-patch+json"
               :request-method :patch))

(defn- patch-object-replicas
  "Update the replica count in the given Kubernetes object's spec."
  [http-client k8s-object-uri replicas replicas']
  (patch-object-json http-client k8s-object-uri
                     [{:op :test :path "/spec/replicas" :value replicas}
                      {:op :replace :path "/spec/replicas" :value replicas'}]))

(defn- get-replica-count
  "Query the current requested replica count for the given Kubernetes object."
  [{:keys [watch-state] :as scheduler} service-id]
  (-> watch-state deref :service-id->service (get service-id) :instances))

(defmacro k8s-patch-with-retries
  "Query the current replica count for the given Kubernetes object,
   retrying a limited number of times in the event of an HTTP 409 conflict error."
  [patch-cmd retry-condition retry-cmd]
  `(let [patch-result# (ss/try+
                         ~patch-cmd
                         (catch [:status 409] _#
                           (with-meta
                             `conflict
                             {:throw-context ~'&throw-context})))]
     (if (not= `conflict patch-result#)
       patch-result#
       (if ~retry-condition
         ~retry-cmd
         (throw (-> patch-result# meta :throw-context :throwable))))))

(defn- build-replicaset-url
  "Build the URL for the given Waiter Service's ReplicaSet."
  [{:keys [api-server-url replicaset-api-version]} {:keys [k8s/app-name k8s/namespace]}]
  (str api-server-url "/apis/" replicaset-api-version
       "/namespaces/" namespace "/replicasets/" app-name))

(defn- scale-service-up-to
  "Scale the number of instances for a given service to a specific number.
   Only used for upward scaling. No-op if it would result in downward scaling."
  [{:keys [http-client max-patch-retries] :as scheduler} {service-id :id :as service} instances']
  (let [replicaset-url (build-replicaset-url scheduler service)]
    (loop [attempt 1
           instances (:instances service)]
      (if (<= instances' instances)
        (log/warn "skipping non-upward scale-up request on" service-id
                  "from" instances "to" instances')
        (k8s-patch-with-retries
          (patch-object-replicas http-client replicaset-url instances instances')
          (<= attempt max-patch-retries)
          (recur (inc attempt) (get-replica-count scheduler service-id)))))))

(defn- scale-service-by-delta
  "Scale the number of instances for a given service by a given delta.
   Can scale either upward (positive delta) or downward (negative delta)."
  [{:keys [http-client max-patch-retries] :as scheduler} {service-id :id :as service} instances-delta]
  (let [replicaset-url (build-replicaset-url scheduler service)]
    (loop [attempt 1
           instances (:instances service)]
      (let [instances' (+ instances instances-delta)]
        (k8s-patch-with-retries
          (patch-object-replicas http-client replicaset-url instances instances')
          (<= attempt max-patch-retries)
          (recur (inc attempt) (get-replica-count scheduler service-id)))))))

(defn- kill-service-instance
  "Safely kill the Kubernetes pod corresponding to the given Waiter Service Instance.
   Returns nil on success, but throws on failure."
  [{:keys [api-server-url http-client] :as scheduler} {:keys [id k8s/namespace k8s/pod-name service-id] :as instance} service]
  ;; SAFE DELETION STRATEGY:
  ;; 1) Delete the target pod with a grace period of 5 minutes
  ;;    Since the target pod is currently in the "Terminating" state,
  ;;    the owner ReplicaSet will not immediately create a replacement pod.
  ;; 2) Scale down the owner ReplicaSet by 1 pod.
  ;;    Since the target pod is still in the "Terminating" state (assuming delay < 5min),
  ;;    the owner ReplicaSet will not immediately delete a different victim pod.
  ;; 3) Force-delete the target pod. This immediately removes the pod from Kubernetes.
  ;;    The state of the ReplicaSet (desired vs actual pods) should now be consistent.
  ;;    We want to eagerly delete the pod to short-circuit the 5-minute delay from above.
  ;; Note that if it takes more than 5 minutes to get from step 1 to step 2,
  ;; we assume we're already so far out of sync that the possibility of non-atomic scaling
  ;; doesn't hurt us significantly. If it takes more than 5 minutes to get from step 1
  ;; to step 3, then the pod was already deleted, and the force-delete is no longer needed.
  ;; The force-delete can fail with a 404 (object not found), but this operation still succeeds.
  (let [pod-url (str api-server-url
                     "/api/v1/namespaces/"
                     namespace
                     "/pods/"
                     pod-name)
        base-body {:kind "DeleteOptions" :apiVersion "v1"}
        ;; we use a 5-minute (300s) grace period on pods to enable manual victim selection on scale-down
        term-json (-> base-body (assoc :gracePeriodSeconds 300) utils/clj->json)
        ;; setting the grace period to 0 seconds results in an immediate SIGKILL to the pod
        kill-json (-> base-body (assoc :gracePeriodSeconds 0) utils/clj->json)
        make-kill-response (fn [killed? message status]
                             {:instance-id id :killed? killed?
                              :message message :service-id service-id :status status})]
    ; request termination of the instance
    (api-request http-client pod-url :request-method :delete :body term-json)
    ; scale down the replicaset to reflect removal of this instance
    (try
      (scale-service-by-delta scheduler service -1)
      (catch Throwable t
        (log/error t "Error while scaling down ReplicaSet after pod termination")))
    ; force-kill the instance (should still be terminating)
    (try
      (api-request http-client pod-url :request-method :delete :body kill-json)
      (catch Throwable t
        (log/error t "Error force-killing pod")))
    (comment "Success! Even if the scale-down or force-kill operation failed,
              the pod will be force-killed after the grace period is up.")))

(defn create-service
  "Reify a Waiter Service as a Kubernetes ReplicaSet."
  [{:keys [service-description service-id]}
   {:keys [api-server-url http-client replicaset-api-version replicaset-spec-builder-fn] :as scheduler}]
  (let [{:strs [cmd-type]} service-description]
    (when (= "docker" cmd-type)
      (throw (ex-info "Unsupported command type on service"
                      {:cmd-type cmd-type
                       :service-description service-description
                       :service-id service-id}))))
  (let [spec-json (replicaset-spec-builder-fn scheduler service-id service-description)
        request-url (str api-server-url "/apis/" replicaset-api-version "/namespaces/"
                         (service-description->namespace service-description) "/replicasets")
        response-json (api-request http-client request-url
                                   :body (utils/clj->json spec-json)
                                   :request-method :post)]
    (some-> response-json replicaset->Service)))

(defn- delete-service
  "Delete the Kubernetes ReplicaSet corresponding to a Waiter Service.
   Owned Pods will be removed asynchronously by the Kubernetes garbage collector."
  [{:keys [api-server-url http-client] :as scheduler} {:keys [id] :as service}]
  (let [replicaset-url (build-replicaset-url scheduler service)
        kill-json (utils/clj->json
                    {:kind "DeleteOptions" :apiVersion "v1"
                     :propagationPolicy "Background"})]
    (api-request http-client replicaset-url :request-method :delete :body kill-json)
    {:message (str "Kubernetes deleted ReplicaSet for " id)
     :result :deleted}))

(defn service-id->service
  "Look up a Waiter Service record via its service-id."
  [{:keys [watch-state] :as scheduler} service-id]
  (-> watch-state deref :service-id->service (get service-id)))

(defn get-service->instances
  "Returns a map of scheduler/Service records -> map of scheduler/ServiceInstance records."
  [scheduler-config]
  (pc/map-from-keys #(instances-breakdown! scheduler-config %)
                    (get-services scheduler-config)))

; The Waiter Scheduler protocol implementation for Kubernetes
(defrecord KubernetesScheduler [api-server-url
                                authorizer
                                daemon-state
                                fileserver
                                http-client
                                max-patch-retries
                                max-name-length
                                orchestrator-name
                                pod-base-port
                                pod-suffix-length
                                replicaset-api-version
                                replicaset-spec-builder-fn
                                retrieve-syncer-state-fn
                                service-id->failed-instances-transient-store
                                service-id->password-fn
                                service-id->service-description-fn
                                watch-state]
  scheduler/ServiceScheduler

  (get-services [this]
    (get-services this))

  (kill-instance [this {:keys [id service-id] :as instance}]
    (ss/try+
      (let [service (service-id->service this service-id)]
        (kill-service-instance this instance service)
        {:instance-id id
         :killed? true
         :message "Successfully killed instance"
         :service-id service-id
         :status 200})
      (catch [:status 404] e
        {:instance-id id
         :killed? false
         :message "Instance not found"
         :service-id service-id
         :status 404})
      (catch Throwable e
        {:instance-id id
         :killed? false
         :message "Error while killing instance"
         :service-id service-id
         :status 500})))

  (service-exists? [this service-id]
    (ss/try+
      (some? (service-id->service this service-id))
      (catch [:status 404] _
        (comment "App does not exist."))))

  (create-service-if-new [this {:keys [service-id] :as descriptor}]
    (when-not (scheduler/service-exists? this service-id)
      (ss/try+
        (create-service descriptor this)
        (catch [:status 409] _
          (log/error "conflict status when trying to start app. Is app starting up?"
                     descriptor))
        (catch Throwable e
          (log/error e "Error starting new app." descriptor)))))

  (delete-service [this service-id]
    (ss/try+
      (let [service (service-id->service this service-id)
            delete-result (delete-service this service)]
        (swap! service-id->failed-instances-transient-store dissoc service-id)
        delete-result)
      (catch [:status 404] _
        (log/warn "service does not exist:" service-id)
        {:result :no-such-service-exists
         :message "Kubernetes reports service does not exist"})
      (catch Throwable e
        (log/warn "internal error while deleting service"
                  {:service-id service-id})
        {:result :error
         :message "Internal error while deleting service"})))

  (scale-service [this service-id scale-to-instances _]
    (ss/try+
      (if-let [service (service-id->service this service-id)]
        (do
          (scale-service-up-to this service scale-to-instances)
          {:success true
           :status 200
           :result :scaled
           :message (str "Scaled to " scale-to-instances)})
        (do
          (log/error "cannot scale missing service" service-id)
          {:success false
           :status 404
           :result :no-such-service-exists
           :message "Failed to scale missing service"}))
      (catch [:status 409] _
        {:success false
         :status 409
         :result :conflict
         :message "Scaling failed due to repeated patch conflicts"})
      (catch Throwable e
        (log/error e "Error while scaling waiter service" service-id)
        {:success false
         :status 500
         :result :failed
         :message "Error while scaling waiter service"})))

  (retrieve-directory-content
    [{:keys [http-client] {:keys [port scheme]} :fileserver}
     _ _ host browse-path]
    (let [auth-str @k8s-api-auth-str
          headers (when auth-str {"Authorization" auth-str})
          browse-path (if (string/blank? browse-path) "/" browse-path)
          browse-path (cond->
                        browse-path
                        (not (string/ends-with? browse-path "/"))
                        (str "/")
                        (not (string/starts-with? browse-path "/"))
                        (->> (str "/")))
          target-url (str scheme "://" host ":" port browse-path)]
      (when port
        (ss/try+
          (let [result (http-utils/http-request
                         http-client
                         target-url
                         :accept "application/json"
                         :content-type "application/json"
                         :headers headers)]
            (for [{entry-name :name entry-type :type :as entry} result]
              (if (= "file" entry-type)
                (assoc entry :url (str target-url entry-name))
                (assoc entry :path (str browse-path entry-name)))))
          (catch [:client http-client] response
            (log/error "request to fileserver failed: " target-url response))
          (catch Throwable t
            (log/error t "request to fileserver failed"))))))

  (service-id->state [_ service-id]
    {:failed-instances (vals (get @service-id->failed-instances-transient-store service-id))
     :syncer (retrieve-syncer-state-fn service-id)})

  (state [{:keys [watch-state]}]
    {:watch-state @watch-state
     :service-id->failed-instances @service-id->failed-instances-transient-store
     :syncer (retrieve-syncer-state-fn)})

  (validate-service [_ service-id]
    (let [{:strs [run-as-user]} (service-id->service-description-fn service-id)]
      (authz/check-user authorizer run-as-user service-id))))

(defn default-replicaset-builder
  "Factory function which creates a Kubernetes ReplicaSet spec for the given Waiter Service."
  [{:keys [fileserver orchestrator-name pod-base-port replicaset-api-version
           service-id->password-fn] :as scheduler}
   service-id
   {:strs [backend-proto cmd cpus grace-period-secs health-check-interval-secs
           health-check-max-consecutive-failures mem min-instances ports
           run-as-user] :as service-description}
   {:keys [default-container-image] :as context}]
  (let [home-path (str "/home/" run-as-user)
        base-env (scheduler/environment service-id service-description
                                        service-id->password-fn home-path)
        ;; Make $PORT0 value pseudo-random to ensure clients can't hardcode it.
        ;; Helps maintain compatibility with Marathon, where port assignment is dynamic.
        port0 (-> service-id hash (mod 100) (* 10) (+ pod-base-port))
        env (into [;; We set these two "MESOS_*" variables to improve interoperability.
                   ;; New clients should prefer using WAITER_SANDBOX.
                   {:name "MESOS_DIRECTORY" :value home-path}
                   {:name "MESOS_SANDBOX" :value home-path}]
                  (concat
                    (for [[k v] base-env]
                      {:name k :value v})
                    (for [i (range ports)]
                      {:name (str "PORT" i) :value (str (+ port0 i))})))
        k8s-name (service-id->k8s-app-name scheduler service-id)
        backend-protocol-lower (string/lower-case backend-proto)
        backend-protocol-upper (string/upper-case backend-proto)
        health-check-url (sd/service-description->health-check-url service-description)
        memory (str mem "Mi")
        ssl? (= "https" backend-protocol-lower)]
    (cond->
      {:kind "ReplicaSet"
       :apiVersion replicaset-api-version
       :metadata {:annotations {:waiter/service-id service-id}
                  :labels {:app k8s-name
                           :managed-by orchestrator-name}
                  :name k8s-name}
       :spec {:replicas min-instances
              :selector {:matchLabels {:app k8s-name
                                       :managed-by orchestrator-name}}
              :template {:metadata {:annotations {:waiter/port-count (str ports)
                                                  :waiter/protocol backend-protocol-lower
                                                  :waiter/service-id service-id}
                                    :labels {:app k8s-name
                                             :managed-by orchestrator-name}}
                         :spec {:containers [{:command ["/usr/bin/waiter-init" cmd]
                                              :env env
                                              :image default-container-image
                                              :imagePullPolicy "IfNotPresent"
                                              :livenessProbe {:httpGet {:path health-check-url
                                                                        :port port0
                                                                        :scheme backend-protocol-upper}
                                                              :failureThreshold health-check-max-consecutive-failures
                                                              :initialDelaySeconds grace-period-secs
                                                              :periodSeconds health-check-interval-secs
                                                              :timeoutSeconds 1}
                                              :name "waiter-app"
                                              :ports [{:containerPort port0}]
                                              :readinessProbe {:httpGet {:path health-check-url
                                                                         :port port0
                                                                         :scheme backend-protocol-upper}
                                                               :failureThreshold 1
                                                               :periodSeconds health-check-interval-secs
                                                               :timeoutSeconds 1}
                                              :resources {:limits {:cpu cpus
                                                                   :memory memory}
                                                          :requests {:cpu cpus
                                                                     :memory memory}}
                                              :volumeMounts [{:mountPath home-path
                                                              :name "user-home"}]
                                              :workingDir home-path}]
                                :volumes [{:name "user-home"
                                           :emptyDir {}}]
                                :terminationGracePeriodSeconds 0}}}}
      ;; Optional fileserver sidecar container
      (integer? (:port fileserver))
      (update-in
        [:spec :template :spec :containers]
        conj
        (let [{:keys [cmd image port] {:keys [cpu mem]} :resources} fileserver
              memory (str mem "Mi")]
          {:command cmd
           :env [{:name "WAITER_FILESERVER_PORT"
                  :value (str port)}]
           :image image
           :imagePullPolicy "IfNotPresent"
           :name "waiter-fileserver"
           :ports [{:containerPort port}]
           :resources {:limits {:cpu cpu :memory memory}
                       :requests {:cpu cpu :memory memory}}
           :volumeMounts [{:mountPath "/srv/www"
                           :name "user-home"}]
           :workingDir home-path})))))

(defn start-auth-renewer
  "Initialize the k8s-api-auth-str atom,
   and optionally start a chime to periodically refresh the value."
  [{:keys [action-fn refresh-delay-mins] :as context}]
  {:pre [(or (nil? refresh-delay-mins)
             (utils/pos-int? refresh-delay-mins))
         (symbol? action-fn)]}
  (let [refresh! (-> action-fn utils/resolve-symbol deref)
        auth-update-task (fn auth-update-task []
                           (if-let [auth-str' (refresh! context)]
                             (reset! k8s-api-auth-str auth-str')))]
    (assert (fn? refresh!) "Refresh function must be a Clojure fn")
    (auth-update-task)
    (when refresh-delay-mins
      (du/start-timer-task
        (t/minutes refresh-delay-mins)
        auth-update-task
        :delay-ms (* 60000 refresh-delay-mins)))))


(defn- reset-watch-state!
  "Reset the global state that is used as the basis for applying incremental watch updates."
  [{:keys [watch-state] :as scheduler}
   {:keys [query-fn resource-key resource-url metadata-key] :as options}]
  (let [{:keys [version] :as initial-state} (query-fn scheduler options resource-url)]
    (swap! watch-state assoc
           resource-key (get initial-state resource-key)
           metadata-key {:timestamp {:snapshot (t/now)}
                         :version {:snapshot version}})
    version))

(def default-watch-options
  "Default options for start-k8s-watch! daemon threads."
  {:api-request-fn api-request
   :exit-on-error? true
   :streaming-api-request-fn streaming-api-request})

(defn- start-k8s-watch!
  "Start a thread to continuously update the watch-state atom based on watched K8s events."
  [{:keys [api-server-url watch-state] :as scheduler}
   {:keys [exit-on-error? resource-key resource-name resource-url streaming-api-request-fn update-fn] :as options}]
  (doto
    (Thread.
      (fn k8s-watch []
        (try
          ;; retry getting state updates forever
          (while true
            (try
              (let [version (reset-watch-state! scheduler options)
                    watch-url (str resource-url "&watch=true&resourceVersion=" version)]
                ;; process updates forever (unless there's an exception)
                (doseq [json-object (streaming-api-request-fn watch-url)]
                  (when json-object
                    (update-fn json-object))))
              (catch Exception e
                (log/error e "error in" resource-key "state watch thread"))))
          (catch Throwable t
            (when exit-on-error?
              (log/error t "unrecoverable error in" resource-name "state watch thread, terminating waiter.")))
          (finally
            (when exit-on-error?
              (System/exit 1))))))
    (.setDaemon true)
    (.start)))

(defn- global-state-query
  [{:keys [api-request-fn http-client] :as scheduler} {:keys [api-request-fn]} objects-url]
  (let [{:keys [items] :as response} (api-request-fn http-client objects-url)
        resource-version (k8s-object->resource-version response)]
    {:items items
     :version resource-version}))

(defn global-pods-state-query
  "Query K8s for all Waiter-managed Pods"
  [scheduler options pods-url]
  (let [{:keys [items version]} (global-state-query scheduler options pods-url)
        service-id->pod-id->pod (->> items
                                      (group-by k8s-object->service-id)
                                      (pc/map-vals (partial pc/map-from-vals k8s-object->id)))]
    {:service-id->pod-id->pod service-id->pod-id->pod
     :version version}))

(defn start-pods-watch!
  "Start a thread to continuously update the watch-state atom based on watched Pod events."
  ([scheduler] (start-pods-watch! scheduler default-watch-options))
  ([{:keys [api-server-url watch-state orchestrator-name] :as scheduler} options]
   (start-k8s-watch!
     scheduler
     (->
       {:query-fn global-pods-state-query
        :resource-key :service-id->pod-id->pod
        :resource-name "Pods"
        :resource-url (str api-server-url "/api/v1/pods?labelSelector=managed-by=" orchestrator-name)
        :metadata-key :pods-metadata
        :update-fn (fn pods-watch-update [{pod :object update-type :type}]
                     (let [now (t/now)
                           pod-id (k8s-object->id pod)
                           service-id (k8s-object->service-id pod)
                           version (k8s-object->resource-version pod)]
                       (scheduler/log "pod state update:" update-type version pod)
                       (swap! watch-state
                              #(as-> % state
                                 (case update-type
                                   "ADDED" (assoc-in state [:service-id->pod-id->pod service-id pod-id] pod)
                                   "MODIFIED" (assoc-in state [:service-id->pod-id->pod service-id pod-id] pod)
                                   "DELETED" (utils/dissoc-in state [:service-id->pod-id->pod service-id pod-id]))
                                 (assoc-in state [:pods-metadata :timestamp :watch] now)
                                 (assoc-in state [:pods-metadata :version :watch] version)))))}
       (merge options)))))

(defn global-rs-state-query
  "Query K8s for all Waiter-managed ReplicaSets"
  [scheduler options rs-url]
  (let [{:keys [items version]} (global-state-query scheduler options rs-url)
        service-id->service (->> items
                                 (map replicaset->Service)
                                 (filter some?)
                                 (pc/map-from-vals :id))]
    {:service-id->service service-id->service
     :version version}))

(defn start-replicasets-watch!
  "Start a thread to continuously update the watch-state atom based on watched ReplicaSet events."
  ([scheduler] (start-replicasets-watch! scheduler default-watch-options))
  ([{:keys [api-server-url watch-state orchestrator-name replicaset-api-version] :as scheduler} options]
   (start-k8s-watch!
     scheduler
     (->
       {:query-fn global-rs-state-query
        :resource-key :service-id->service
        :resource-name "ReplicaSets"
        :resource-url (str api-server-url "/apis/" replicaset-api-version
                           "/replicasets?labelSelector=managed-by="
                           orchestrator-name)
        :metadata-key :rs-metadata
        :update-fn (fn rs-watch-update [{rs :object update-type :type}]
                     (let [now (t/now)
                           {service-id :id :as service} (replicaset->Service rs)
                           version (k8s-object->resource-version rs)]
                       (when service
                         (scheduler/log "rs state update:" update-type version service)
                         (swap! watch-state
                                #(as-> % state
                                   (case update-type
                                     "ADDED" (assoc-in state [:service-id->service service-id] service)
                                     "MODIFIED" (assoc-in state [:service-id->service service-id] service)
                                     "DELETED" (utils/dissoc-in state [:service-id->service service-id]))
                                   (assoc-in state [:rs-metadata :timestamp :watch] now)
                                   (assoc-in state [:rs-metadata :version :watch] version))))))}
       (merge options)))))

(defn kubernetes-scheduler
  "Returns a new KubernetesScheduler with the provided configuration. Validates the
   configuration against kubernetes-scheduler-schema and throws if it's not valid."
  [{:keys [authentication authorizer http-options max-patch-retries max-name-length orchestrator-name
           pod-base-port pod-suffix-length replicaset-api-version replicaset-spec-builder
           scheduler-name scheduler-state-chan scheduler-syncer-interval-secs service-id->service-description-fn
           service-id->password-fn url start-scheduler-syncer-fn]
    {fileserver-port :port fileserver-scheme :scheme :as fileserver} :fileserver}]
  {:pre [(schema/contains-kind-sub-map? authorizer)
         (or (nil? fileserver-port)
             (and (integer? fileserver-port)
                  (< 0 fileserver-port 65535)))
         (re-matches #"https?" fileserver-scheme)
         (utils/pos-int? (:socket-timeout http-options))
         (utils/pos-int? (:conn-timeout http-options))
         (utils/non-neg-int? max-patch-retries)
         (utils/pos-int? max-name-length)
         (not (string/blank? orchestrator-name))
         (integer? pod-base-port)
         (< 0 pod-base-port 65527) ; max port is 65535, and we need to reserve up to 10 ports
         (utils/pos-int? pod-suffix-length)
         (not (string/blank? replicaset-api-version))
         (symbol? (:factory-fn replicaset-spec-builder))
         (some? (io/as-url url))
         (not (string/blank? scheduler-name))
         (au/chan? scheduler-state-chan)
         (utils/pos-int? scheduler-syncer-interval-secs)
         (fn? service-id->password-fn)
         (fn? service-id->service-description-fn)
         (fn? start-scheduler-syncer-fn)]}
  (let [authorizer (utils/create-component authorizer)
        http-client (http-utils/http-client-factory http-options)
        service-id->failed-instances-transient-store (atom {})
        replicaset-spec-builder-fn (let [f (-> replicaset-spec-builder
                                               :factory-fn
                                               utils/resolve-symbol
                                               deref)]
                                     (assert (fn? f) "ReplicaSet spec function must be a Clojure fn")
                                     (fn [scheduler service-id service-description]
                                       (f scheduler service-id service-description replicaset-spec-builder)))
        watch-state (atom nil)
        scheduler-config {:api-server-url url
                          :http-client http-client
                          :orchestrator-name orchestrator-name
                          :replicaset-api-version replicaset-api-version
                          :service-id->failed-instances-transient-store service-id->failed-instances-transient-store
                          :watch-state watch-state}
        get-service->instances-fn #(get-service->instances scheduler-config)
        {:keys [retrieve-syncer-state-fn]} (start-scheduler-syncer-fn
                                             scheduler-name
                                             get-service->instances-fn
                                             scheduler-state-chan
                                             scheduler-syncer-interval-secs)]
    (when authentication
      (start-auth-renewer authentication))
    (let [daemon-state (atom nil)
          scheduler (->KubernetesScheduler url
                                           authorizer
                                           daemon-state
                                           fileserver
                                           http-client
                                           max-patch-retries
                                           max-name-length
                                           orchestrator-name
                                           pod-base-port
                                           pod-suffix-length
                                           replicaset-api-version
                                           replicaset-spec-builder-fn
                                           retrieve-syncer-state-fn
                                           service-id->failed-instances-transient-store
                                           service-id->password-fn
                                           service-id->service-description-fn
                                           watch-state)
          pod-watch-thread (start-pods-watch! scheduler)
          rs-watch-thread (start-replicasets-watch! scheduler)]
      (reset! daemon-state {:pod-watch-daemon pod-watch-thread
                            :rs-watch-daemon rs-watch-thread})
      scheduler)))
