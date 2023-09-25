(ns donut.service
  (:require
   [clojure.set :as set]
   [clojure.walk :as walk]
   [malli.core :as m]
   [malli.error :as me]))

(defrecord Service [handler substitutionss api])

(defn- validate-body
  [{{:keys [body-schema]} :fn-def
    :keys [body]
    :as request}]
  (if-let [explanation (and body-schema (m/explain body-schema body))]
    {:cognitect.anomalies/category :incorrect
     :spec-explain explanation
     :spec-explain-human (me/humanize explanation)}
    request))

(defn- transform-request
  [{:keys [fn-def] :as request}]
  (if-let [transform (:transform-request fn-def)]
    (transform request)
    request))

(defn body-client->service
  [{{:keys [client->service]} :service
    :keys [body]
    :as request}]
  (assoc request :body (if (and (map? body) client->service)
                         (set/rename-keys body client->service)
                         body)))

(defn- body-service->client
  [{{:keys [service->client]} :service
    :keys [body]
    :as request}]
  (assoc request :body (if (and (map? body) service->client)
                         (set/rename-keys body service->client)
                         body)))

(defn- response-client->service
  [{{:keys [client->service]} :service
    :keys [response]
    :as request}]
  (assoc request :response (if (and (map? response) client->service)
                             (set/rename-keys response client->service)
                             response)))

(defn- build-op [{:keys [fn-def] :as request}]
  (assoc request :op ((:op-fn fn-def) request)))

(defn- handle-op [{{:keys [handler]} :service
                   :keys [op]
                   :as request}]
  (assoc request :response (handler op)))

(defn- validate-response [response] response)

(defn handle-request
  [{:keys [service fn-name] :as request}]
  (reduce (fn [x f]
            (let [response (f x)]
              (if (:cognitect.anomalies/category response)
                (reduced response)
                response)))
          (assoc request :fn-def (get-in service [:api fn-name]))
          [body-client->service
           validate-body
           transform-request
           body-service->client
           build-op
           handle-op
           response-client->service
           validate-response
           :response]))

(defn- op-template->fn-body
  [op-template]
  (walk/postwalk (fn [x]
                   (cond
                     (= :? x)
                     'body

                     (and (vector? x)
                          (= ::term (first x)))
                     (list 'service->client (second x) (second x))

                     (and (vector? x)
                          (= ::param (first x)))
                     (list 'body (second x))

                     :else
                     x))
                 op-template))

(defn- op-form
  [op-template]
  (let [service->client-sym 'service->client
        body-sym 'body]
    `(fn [{{:keys [~service->client-sym]} :service
           :keys [~body-sym]
           :as ~(quote request)}]
       ~(op-template->fn-body op-template))))

(defn service-api
  [api]
  (reduce (fn [m [fn-name docstring-or-data data]]
            (let [data (or data docstring-or-data)
                  data (assoc data :op-fn (op-form (:op-template data)))]
              (assoc m (keyword fn-name) data)))
          {}
          api))

(defn- api-def-form
  [[fn-name docstring-or-data data]]
  (let [data      (if data data docstring-or-data)
        docstring (if data docstring-or-data "service op")
        service-sym   'service
        body-sym      'body
        fn-name-k (keyword fn-name)]
    `(defn ~fn-name
       ~docstring
       [~service-sym ~body-sym]
       (handle-request {:service ~service-sym
                        :body    ~body-sym
                        :fn-name ~fn-name-k}))))

(defmacro defservice
  [service-name & api]
  `(do
     (declare ~service-name)
     ~@(map api-def-form api)
     (def ~service-name (map->Service {:api ~(service-api api)}))))

(defmacro implement-service
  [service-name {:keys [handler substitutions]}]
  (let [implementation {:handler handler
                        :service->client substitutions
                        :client->service (set/map-invert substitutions)}]
    `(alter-var-root (var ~service-name) merge ~implementation)))

(comment
  (defn user-by-credentials
    [store {:keys [:user/password] :as creds}]
    (let [user (user-by-email db email)]
      (and user
           (not-empty password)
           (:valid (buddy-hashers/verify password (:user/password_hash user)))
           user))))
