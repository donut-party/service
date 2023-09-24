(ns examples
  (:require
   [donut.service :as service]))


(service/defservice IdentityStore
  (user-signup!
   {:body-schema
    [:map
     [:user/email string?]
     [:user/password string?]]

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
    :response-schema map?

    :transform-request
    (fn [request]
      (update request :body #(assoc %
                                    :user/password_reset_token (UUID/randomUUID)
                                    :user/password_reset_token_created_at 0)))

    :op-template
    {:op-type   :update
     :container [::term :user]
     :where     {[::term :user/id] [::param :user/id]}
     :record    :?}}))


(def data-store (atom nil))
(defn reset-data-store [f] (reset! data-store nil) (f))

(defmulti identity-store-handler (fn [{:keys [op-type]}] op-type))

(defmethod identity-store-handler
  :insert
  [{:keys [records]}]
  (reset! data-store (first records)))

(defmethod identity-store-handler
  :update
  [{:keys [record]}]
  (swap! data-store merge record))

(service/implement-service IdentityStore
  {:translation
   {:user/id :my-user/id
    :user/email :my-user/email
    :user/password_hash :my-user/password_hash}
   :handler identity-store-handler})

(user-signup! IdentityStore {:user/email "test@test.com" :user/password "test"})
(create-reset-password-token! IdentityStore {:user/email "test@test.com" :user/password "test"})
