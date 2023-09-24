(ns donut.service
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [clojure.string :as str]))

(defrecord Service [adapter translations api])

(defn term
  [k]
  [::term k])

(defn param
  [k]
  [::param k])

(defn- validate-body
  [{:keys [fn-def] :as request}]
  request)

(defn- wrap [request] request)
(defn- translate [request] request)
(defn- build-op [request] request)
(defn- execute-op [request] request)
(defn- validate-response [request] request)

(defn handle-request
  [{:keys [service body fn-name] :as request}]
  (reduce (fn [x f]
            (let [response (f x)]
              (if (:cognitect.anomalies/category response)
                (reduced response)
                response)))
          (assoc request :fn-def (get-in service [:api fn-name]))
          [validate-body
           wrap
           translate
           build-op
           execute-op
           validate-response]))

(defn service-api
  [api]
  (reduce (fn [m [fn-name docstring-or-data data]]
            (assoc m (keyword fn-name) (or data docstring-or-data)))
          {}
          api))

(defn api-def
  [service-name [fn-name docstring-or-data data]]
  (let [data      (if data data)
        docstring (if data docstring-or-data "service op")
        service   'service
        body      'body
        fn-name-k (keyword fn-name)]
    `(defn ~fn-name
       ~docstring
       [~service ~body]
       (handle-request {:service ~service
                        :body    ~body
                        :fn-name ~fn-name-k}))))

(defn token-expired?
  [x y] false)

(defmacro defservice
  [service-name & api]
  `(do
     (def ~service-name (map->Service {:api ~(service-api api)}))
     ~@(map #(api-def service-name %) api)))

(defservice IdentityStore
  (user-by-email
   {:body-schema []

    :op-template
    {:op    :get-one
     :query {:select [:*]
             :from   [::term :user]
             :where  [:= [::term :user/email] :?]}}})

  (user-by-password-reset-token
   {:body-schema []

    :op-template
    {:op    :get-one
     :query {:select [:*]
             :from   [::term :user]
             :where  [:= [::term :user/password_reset_token] :?]}}})

  (user-signup!
   {:body-schema []

    :wrap
    (fn [handler]
      (fn [{:keys [:user/password] :as user}]
        (handler
         (-> user
             (dissoc :user/password)
             (assoc :user/password_hash (str/upper-case password))))))

    :op-template
    {:op        :insert
     :container [::term :user]
     :records   [:?]}})

  (create-reset-password-token!
   {:body-schema  []
    :response-schema []

    :wrap
    (fn [handler]
      (fn [u]
        (handler
         (assoc u
                :user/password_reset_token (java.util.UUID/randomUUID)
                :user/password_reset_token_created_at 0))))

    :op-template
    {:op        :update
     :container [::term :user]
     :where     {[::term :user/id] [::param :user/id]}
     :record    :?}})

  (consume-password-reset-token!
   {:body-schema []

    :wrap
    (fn [handler]
      (fn [u]
        (let [user (user-by-password-reset-token u)]
          (if (or (not user)
                  (token-expired? (:user/password_reset_token user) 0))
            {:cognitect.anomalies/category :not-found}
            (handler
             {:user/password_hash                   (str/upper-case (:new-password u))
              :user/password_reset_token            nil
              :user/password_reset_token_created_at nil
              :user/id                              (:user/id user)})))))

    :op-template
    {:op        :update
     :container [::term :user]
     :where     {[::term :user/id] [::param :user/id]}
     :record    :?}}))

#_
(defn user-by-credentials
  [store {:keys [:user/password] :as creds}]
  (let [user (user-by-email db email)]
    (and user
         (not-empty password)
         (:valid (buddy-hashers/verify password (:user/password_hash user)))
         user)))

#_
(extend-service
 IdentityStore
 {:translations []
  :adapter {}})
