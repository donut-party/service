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
               :from   [::service/term :user]
               :where  [:= [::service/term :user/email] ::service/body]}}})

  (user-by-password-reset-token
   {:body-schema map?

    :op-template
    {:op-type :get-one
     :query   {:select [:*]
               :from   [::service/term :user]
               :where  [:= [::service/term :user/password_reset_token] ::service/body]}}})

  (create-user
   {:body-schema
    [:map
     [:user/email string?]
     [:user/password string?]]

    :transform-request
    (fn create-user-transform-request [request]
      (let [password (get-in request [:body :user/password])]
        (update request :body #(-> %
                                   (dissoc :user/password)
                                   (assoc :user/password_hash (str/upper-case password))))))

    :op-template
    {:op-type   :insert
     :container [::service/term :user]
     :records   [::service/body]}})

  (create-reset-password-token
   {:body-schema     map?
    :response-schema map?

    :transform-request
    (fn [request]
      (update request :body #(assoc %
                                    :user/password_reset_token (UUID/randomUUID)
                                    :user/password_reset_token_created_at 0)))

    :op-template
    {:op-type   :update
     :container [::service/term :user]
     :where     {[::service/term :user/id] [::service/param :user/id]}
     :record    ::service/body}})

  (consume-password-reset-token
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
     :container [::service/term :user]
     :where     {[::service/term :user/id] [::service/param :user/id]}
     :record    ::service/body}}))


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
  {:substitutions
   {:user/id :my-user/id
    :user/email :my-user/email
    :user/password :my-user/password
    :user/password_hash :my-user/password_hash
    :user/password_reset_token :my-user/password_reset_token
    :user/password_reset_token_created_at :my-user/password_reset_token_created_at}

   :handler
   identity-store-handler})

(deftest test-user-by-email
  (reset! data-store {:my-user/id 1 :my-user/email "test@test.com"})
  (is (= {:user/id 1
          :user/email "test@test.com"}
         (user-by-email IdentityStore "test@test.com"))))

(deftest test-translates-body
  (create-user IdentityStore {:user/id 1
                              :user/email "test@test.com"
                              :user/password "password"})
  (is (= {:my-user/id 1
          :my-user/email "test@test.com"
          :my-user/password_hash "PASSWORD"}
         @data-store)))

(deftest test-translates-body-client->service
  (create-user IdentityStore {:my-user/id 1
                              :my-user/email "test@test.com"
                              :my-user/password "password"})
  (is (= {:my-user/id 1
          :my-user/email "test@test.com"
          :my-user/password_hash "PASSWORD"}
         @data-store)))

;; TODO
;; - body-schema validation
;; - an anomaly with transform
