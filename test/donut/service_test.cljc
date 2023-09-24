(ns donut.service-test
  (:require
   #?(:clj [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer [deftest is use-fixtures] :include-macros true])
   [clojure.string :as str]
   [donut.service :as service])
  (:import
   [java.util UUID]))

(defn token-expired?
  [_ _] false)

(service/defservice IdentityStore
  (user-by-email
   {:body-schema string?

    :op-template
    {:op-type :get-one
     :query   {:select [:*]
               :from   [::term :user]
               :where  [:= [::term :user/email] :?]}}})

  (user-by-password-reset-token
   {:body-schema map?

    :op-template
    {:op-type :get-one
     :query   {:select [:*]
               :from   [::term :user]
               :where  [:= [::term :user/password_reset_token] :?]}}})

  (user-signup!
   {:body-schema map?

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
     :record    :?}})

  (consume-password-reset-token!
   {:body-schema
    [:map
     [:new-password string?]
     [:token string?]]

    :transform-request
    (fn [{:keys [body] :as request}]
      (let [user (user-by-password-reset-token (:service request) (:token body))]
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


(def data-store (atom nil))
(defn reset-data-store [f] (reset! data-store nil) (f))

(use-fixtures :each reset-data-store)

(defmulti identity-store-handler (fn [{:keys [op-type]}] op-type))

(defmethod identity-store-handler
  :get-one
  [_]
  @data-store)

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

(deftest test-user-by-email
  (reset! data-store {:my-user/id 1 :my-user/email "test@test.com"})
  (is (= {:user/id 1
          :user/email "test@test.com"}
         (user-by-email IdentityStore "test@test.com"))))
