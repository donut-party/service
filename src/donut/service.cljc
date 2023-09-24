(ns donut.service
  (:require
   [malli.core :as m]
   [malli.error :as me]))

(defrecord Service [adapter translations api])

(defn term
  [k]
  [::term k])

(defn param
  [k]
  [::param k])

(defn handle-request
  [{:keys [service body fn-name] :as request}]
  (-> (validate service request)
      (wrap)
      (translate)
      (build-op)
      (execute-op)))

(defn service-api
  [api]
  (reduce (fn [m op])
          {}
          api))

(defn api-def
  [service-name [fn-name docstring-or-data data]]
  (let [data      (if data data)
        docstring (if data docstring-or-data "service op")
        service   'service
        request   'request
        fn-name-k (keyword fn-name)]
    `(defn ~fn-name
       ~docstring
       [~service ~body]
       (handle-request {:service ~service
                        :body ~body
                        :fn-name ~fn-name-k}))))

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
             (assoc :user/password_hash (buddy-hashers/encrypt password))))))

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
                :user/password_reset_token (nano-id/nano-id 10)
                :user/password_reset_token_created_at (current-time-seconds)))))

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
                  (token-expired? (:user/password_reset_token user) token-max-age))
            {:cognitect.anomalies/category :not-found}
            (handler
             {:user/password_hash                   (budy-hashers/encrypt (:new-password x))
              :user/password_reset_token            nil
              :user/password_reset_token_created_at nil
              :user/id                              (:user/id user)})))))

    :op-template
    {:op        :update
     :container [::term :user]
     :where     {[::term :user/id] [::param :user/id]}
     :record    :?}}))

(defn user-by-credentials
  [store {:keys [:user/password] :as creds}]
  (let [user (user-by-email db email)]
    (and user
         (not-empty password)
         (:valid (buddy-hashers/verify password (:user/password_hash user)))
         user)))

(extend-service
 IdentityStore
 {:translations []
  :adapter {}})
