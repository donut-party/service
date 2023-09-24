(ns donut.service
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [clojure.string :as str]
   [clojure.set :as set]
   [clojure.walk :as walk])
  (:import [java.util UUID]))

(defrecord Service [handler translations api])

(defn term
  [k]
  [::term k])

(defn param
  [k]
  [::param k])

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

(defn- translate-request
  [{{:keys [local->api]} :service
    :keys [body]
    :as request}]
  (assoc request :body (if (and (map? body) local->api)
                         (set/rename-keys body local->api)
                         body)))

(defn- build-op [{:keys [fn-def] :as request}]
  (assoc request :op ((:op-fn fn-def) request)))

(defn- handle-op [{{:keys [handler]} :service
                   :keys [op]
                   :as _request}]
  (handler op))

(defn- validate-response [response] response)

(defn handle-request
  [{:keys [service fn-name] :as request}]
  (reduce (fn [x f]
            (let [response (f x)]
              (if (:cognitect.anomalies/category response)
                (reduced response)
                response)))
          (assoc request :fn-def (get-in service [:api fn-name]))
          [validate-body
           transform-request
           translate-request
           build-op
           handle-op
           validate-response]))

(defn- op-template->fn-body
  [op-template]
  (walk/postwalk (fn [x]
                   (cond
                     (= :? x)
                     'body

                     (and (vector? x)
                          (= ::term (first x)))
                     (list 'api->local (second x) (second x))

                     (and (vector? x)
                          (= ::param (first x)))
                     (list 'body (second x))

                     :else
                     x))
                 op-template))

(defn- op-form
  [op-template]
  (let [api->local-sym 'api->local
        body-sym 'body]
    `(fn [{{:keys [~api->local-sym]} :service
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

(defn token-expired?
  [_ _] false)

(defmacro defservice
  [service-name & api]
  `(do
     (def ~service-name (map->Service {:api ~(service-api api)}))
     ~@(map api-def-form api)))

(defservice IdentityStore
  (user-by-email
   {:body-schema []

    :op-template
    {:op-type :get-one
     :query   {:select [:*]
               :from   [::term :user]
               :where  [:= [::term :user/email] :?]}}})

  (user-by-password-reset-token
   {:body-schema []

    :op-template
    {:op-type :get-one
     :query   {:select [:*]
               :from   [::term :user]
               :where  [:= [::term :user/password_reset_token] :?]}}})

  (user-signup!
   {:body-schema []

    :transform-request
    (fn [request]
      (let [password (get-in request [:body :user/password])]
        (update request :body #(-> %
                                   (dissoc :user/password)
                                   (assoc :user/password_hash (str/upper-case password))))))

    :op-template
    {:op-type   :insert
     :container [::term :user]
     :records   [:?]}})

  (create-reset-password-token!
   {:body-schema     map?
    :response-schema []

    :transform-request
    (fn [request]
      (update request :body #(assoc %
                                    :user/password_reset_token (UUID/randomUUID)
                                    :user/password_reset_token_created_at 0)))

    :op-template
    {:op-type   :update
     :container [::term :user]
     :where     {[::term :user/id] [::param :user/id]}
     :record    :?}})

  (consume-password-reset-token!
   {:body-schema map?

    :transform-request
    (fn [{:keys [body] :as request}]
      (let [user (user-by-password-reset-token (:service request) body)]
        (if (or (not user)
                (token-expired? (:user/password_reset_token user) 0))
          {:cognitect.anomalies/category :not-found}
          (assoc request :body {:user/password_hash                   (str/upper-case (:new-password body))
                                :user/password_reset_token            nil
                                :user/password_reset_token_created_at nil
                                :user/id                              (:user/id user)}))))

    :op-template
    {:op-type   :update
     :container [::term :user]
     :where     {[::term :user/id] [::param :user/id]}
     :record    :?}}))

(defmacro extend-service
  [service-name {:keys [handler translation]}]
  (let [extension {:handler handler
                   :api->local translation
                   :local->api (set/map-invert translation)}]
    `(alter-var-root (var ~service-name) merge ~extension)))

(extend-service IdentityStore
  {:translation
   {:user/id :my-user/id
    :user/email :my-user/email
    :user/password_hash :my-user/password_hash}
   :handler identity})


(comment
  (defn user-by-credentials
    [store {:keys [:user/password] :as creds}]
    (let [user (user-by-email db email)]
      (and user
           (not-empty password)
           (:valid (buddy-hashers/verify password (:user/password_hash user)))
           user))))
